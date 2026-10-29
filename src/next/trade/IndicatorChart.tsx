import { useEffect, useId, useMemo, useRef, useState } from "react";
import { StudyChart, type StudyLegendRow, type StudyPane, type StudySeries } from "../../components/StudyChart";
import { analyzeChart, CHART_RANGES, failure, isAbort, type ChartAnalysis, type ChartRange, type IndicatorSpec, type IndicatorType } from "../../lib/research";
import { serverTimestamp } from "../../market/format";

const TYPES: Array<{ type: IndicatorType; label: string; period: number; note: string }> = [
  { type: "SMA", label: "단순 이동평균", period: 20, note: "종가 평균" },
  { type: "EMA", label: "지수 이동평균", period: 20, note: "최근 값에 가중" },
  { type: "BBANDS", label: "볼린저 밴드", period: 20, note: "평균 ± 2표준편차" },
  { type: "RSI", label: "RSI", period: 14, note: "0~100, 70 과열 · 30 침체" },
  { type: "MACD", label: "MACD", period: 26, note: "12/26/9 고정" },
  { type: "ATR", label: "ATR", period: 14, note: "평균 변동폭" },
];
const LABEL = Object.fromEntries(TYPES.map((item) => [item.type, item.label])) as Record<IndicatorType, string>;
const DEFAULTS: IndicatorSpec[] = [
  { id: "sma5", type: "SMA", period: 5 },
  { id: "ema20", type: "EMA", period: 20 },
  { id: "rsi14", type: "RSI", period: 14 },
];
const MAX_INDICATORS = 12;
const REFRESH_MS = 15_000;
const STORAGE_KEY = "gamestock.indicators";

const priceFormat = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });
const decimalFormat = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 2 });
const countFormat = new Intl.NumberFormat("ko-KR", { notation: "compact", maximumFractionDigits: 1 });

function readSaved(): { range: ChartRange; indicators: IndicatorSpec[] } {
  try {
    const saved = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? "null") as { range?: ChartRange; indicators?: IndicatorSpec[] } | null;
    if (saved && CHART_RANGES.some((item) => item.value === saved.range) && Array.isArray(saved.indicators)) {
      return { range: saved.range!, indicators: saved.indicators.filter((item) => LABEL[item.type] && Number.isInteger(item.period)).slice(0, MAX_INDICATORS) };
    }
  } catch { /* 저장된 설정이 깨졌으면 기본값을 쓴다. */ }
  return { range: "1h", indicators: DEFAULTS };
}

/** id는 [A-Za-z][A-Za-z0-9_]{0,31}이고 겹치면 안 된다. */
function nextId(type: IndicatorType, period: number, taken: IndicatorSpec[]) {
  const base = `${type.toLowerCase()}${period}`;
  let id = base;
  for (let suffix = 2; taken.some((item) => item.id === id); suffix += 1) id = `${base}_${suffix}`;
  return id;
}

const nameOf = (spec: IndicatorSpec) => (spec.type === "MACD" ? "MACD 12 26 9" : `${spec.type === "BBANDS" ? "BB" : spec.type} ${spec.period}`);

/* 같은 설정은 잠깐 재사용해 칸을 오갈 때 다시 계산하지 않는다. */
const cache = new Map<string, { at: number; chart: ChartAnalysis }>();

function seriesFor(spec: IndicatorSpec, values: Record<string, Array<number | null>>, tone: number): StudySeries[] {
  if (spec.type === "BBANDS") return [
    { key: `${spec.id}.upper`, label: "상단", values: values.upper ?? [], tone, style: "dash" },
    { key: `${spec.id}.middle`, label: "중심", values: values.middle ?? [], tone },
    { key: `${spec.id}.lower`, label: "하단", values: values.lower ?? [], tone, style: "dash" },
  ];
  if (spec.type === "MACD") return [
    { key: `${spec.id}.macd`, label: "MACD", values: values.macd ?? [], tone: 1 },
    { key: `${spec.id}.signal`, label: "시그널", values: values.signal ?? [], tone: 2 },
    { key: `${spec.id}.histogram`, label: "히스토그램", values: values.histogram ?? [], tone: 6, style: "bar" },
  ];
  return [{ key: `${spec.id}.value`, label: nameOf(spec), values: values.value ?? [], tone }];
}

/** 종목 상세의 지표 차트. 서버가 계산한 지표를 캔들과 같은 칸 수로 받아 그린다. */
export function IndicatorChart({ code, name }: { code: string; name: string }) {
  const formId = useId();
  const [settings, setSettings] = useState(readSaved);
  const [chart, setChart] = useState<ChartAnalysis | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const { range, indicators } = settings;
  const key = `${code}|${range}|${indicators.map((item) => `${item.id}:${item.type}:${item.period}`).join(",")}`;

  useEffect(() => {
    try { window.localStorage.setItem(STORAGE_KEY, JSON.stringify(settings)); } catch { /* 저장하지 못해도 화면은 그대로 쓴다. */ }
  }, [settings]);

  /* 설정이 바뀌면 이전 요청을 취소하고 잠깐 기다렸다 부른다. 진행 중인 캔들 때문에 열려 있는 동안 주기적으로 다시 받는다. */
  useEffect(() => {
    let controller = new AbortController();
    const load = async (force: boolean) => {
      const hit = cache.get(key);
      if (!force && hit && Date.now() - hit.at < REFRESH_MS) { setChart(hit.chart); setError(""); setLoading(false); return; }
      controller.abort();
      controller = new AbortController();
      setLoading(true);
      try {
        const next = await analyzeChart(code, range, indicators, controller.signal);
        cache.set(key, { at: Date.now(), chart: next });
        setChart(next);
        setError("");
      } catch (reason) {
        if (!isAbort(reason)) setError(failure(reason, "지표를 계산하지 못했어요. 15초 뒤 다시 계산해요."));
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    };
    const debounce = window.setTimeout(() => void load(false), 250);
    const timer = window.setInterval(() => { if (!document.hidden) void load(true); }, REFRESH_MS);
    return () => { window.clearTimeout(debounce); window.clearInterval(timer); controller.abort(); };
    // key가 code·range·indicators를 모두 담는다.
  }, [key]);

  const remove = (id: string) => setSettings((current) => ({ ...current, indicators: current.indicators.filter((item) => item.id !== id) }));
  const add = (type: IndicatorType, period: number) => setSettings((current) => (
    current.indicators.length >= MAX_INDICATORS ? current
      : { ...current, indicators: [...current.indicators, { id: nextId(type, period, current.indicators), type, period }] }
  ));

  const panes = useMemo<StudyPane[]>(() => {
    if (!chart) return [];
    const specs = new Map(indicators.map((item) => [item.id, item]));
    const overlay: StudySeries[] = [];
    const overlayRows: StudyLegendRow[] = [];
    const oscillators: StudyPane[] = [];
    let tone = 1;
    for (const series of chart.indicators) {
      const spec = specs.get(series.id);
      if (!spec) continue;
      const lines = seriesFor(spec, series.values, series.pane === "overlay" ? tone : 3);
      const row: StudyLegendRow = { id: spec.id, label: nameOf(spec), keys: lines.map((line) => line.key), onRemove: () => remove(spec.id) };
      if (series.pane === "overlay") {
        overlay.push(...lines);
        overlayRows.push(row);
        tone = tone % 5 + 1;
      } else {
        oscillators.push({
          id: spec.id,
          title: LABEL[spec.type],
          height: 132,
          series: lines,
          legendRows: [row],
          guides: spec.type === "RSI" ? [30, 50, 70] : spec.type === "MACD" ? [0] : undefined,
          domain: spec.type === "RSI" ? [0, 100] : undefined,
          format: spec.type === "ATR" ? (value) => priceFormat.format(value) : (value) => decimalFormat.format(value),
        });
      }
    }
    return [
      { id: "price", title: name, height: 300 + overlayRows.length * 20, candles: chart.candles, series: overlay, legendRows: overlayRows, lastPrice: true, format: (value) => priceFormat.format(value) },
      { id: "count", title: "체결 건수", height: 80, series: [{ key: "count", label: "건수", values: chart.candles.map((candle) => candle.volume), style: "bar", tone: 6 }], format: (value) => countFormat.format(value) },
      ...oscillators,
    ];
    // remove는 setSettings만 부르므로 매번 새로 만들어도 결과가 같다.
  }, [chart, indicators, name]); // eslint-disable-line react-hooks/exhaustive-deps
  const times = useMemo(() => chart?.candles.map((candle) => serverTimestamp(candle.recordedAt)) ?? [], [chart]);
  const longest = Math.max(0, ...indicators.map((item) => item.type === "MACD" ? 35 : item.period));

  return (
    <section className="nx-indicators" aria-labelledby={`${formId}-title`}>
      <header>
        <h2 id={`${formId}-title`}>지표 분석</h2>
        {loading && <span className="nx-muted" role="status">계산 중</span>}
      </header>
      <div className="nx-study-toolbar">
        <div className="nx-study-ranges" role="group" aria-label="캔들 간격">
          {CHART_RANGES.map((item) => (
            <button key={item.value} type="button" data-label={item.label} className={range === item.value ? "is-active" : undefined} aria-pressed={range === item.value}
              onClick={() => setSettings((current) => ({ ...current, range: item.value }))}>{item.label}</button>
          ))}
        </div>
        <IndicatorMenu open={menuOpen} onOpenChange={setMenuOpen} count={indicators.length} onAdd={add} />
      </div>
      {error && <p className="nx-note is-error" role="alert">{error}</p>}
      <div className="nx-indicator-chart">
        {!chart ? (error ? null : <div className="nx-skeleton" role="status" aria-label="지표를 계산하는 중"><span /><span /><span /></div>)
          : chart.candles.length === 0 ? <p className="nx-empty-state">이 간격으로 쌓인 캔들이 아직 없어요. 더 긴 간격을 골라 보세요.</p>
          : <StudyChart times={times} panes={panes} label={`${name} ${CHART_RANGES.find((item) => item.value === chart.range)?.label ?? chart.range} 캔들과 지표`} />}
      </div>
      {chart && chart.candles.length > 0 && <p className="nx-field-note">
        {indicators.length === 0 ? "지표를 모두 껐어요. 오른쪽 위 지표 버튼으로 다시 켤 수 있어요. " : ""}
        캔들 {chart.candles.length.toLocaleString("ko-KR")}개. 마지막 캔들은 아직 진행 중일 수 있어요. 막대는 거래량(주)이 아니라 체결 건수예요.
        {longest > chart.candles.length && " 캔들 수가 지표 기간보다 적으면 값이 비어 있어요."}
      </p>}
    </section>
  );
}

/** 지표 고르기. 종류를 고르고 기간을 정해 추가한다. 차트 위에 한 단계 밝은 면으로 뜬다. */
function IndicatorMenu({ open, onOpenChange, count, onAdd }: {
  open: boolean; onOpenChange: (open: boolean) => void; count: number; onAdd: (type: IndicatorType, period: number) => void;
}) {
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const firstOption = useRef<HTMLButtonElement>(null);
  const [type, setType] = useState<IndicatorType>("SMA");
  const [period, setPeriod] = useState("20");
  const fixed = type === "MACD";
  const value = fixed ? 26 : Number(period);
  const periodError = !fixed && period !== "" && (!Number.isInteger(value) || value < 2 || value > 500) ? "기간은 2에서 500 사이 정수로 넣어 주세요." : "";
  const full = count >= MAX_INDICATORS;
  const blocked = full || Boolean(periodError) || (!fixed && period === "");

  const close = (refocus = true) => { onOpenChange(false); if (refocus) trigger.current?.focus(); };

  useEffect(() => {
    if (!open) return;
    firstOption.current?.focus();
    const onPointer = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) onOpenChange(false); };
    document.addEventListener("pointerdown", onPointer);
    return () => document.removeEventListener("pointerdown", onPointer);
  }, [open, onOpenChange]);

  const pick = (next: IndicatorType) => {
    setType(next);
    setPeriod(String(TYPES.find((item) => item.type === next)?.period ?? 20));
  };

  return (
    <div className="nx-study-menu-root" ref={root} onKeyDown={(event) => { if (open && event.key === "Escape") { event.stopPropagation(); close(); } }}>
      <button ref={trigger} type="button" className="nx-study-menu-trigger" aria-expanded={open} aria-controls={id} onClick={() => onOpenChange(!open)}>
        <span className="nx-study-fx" aria-hidden="true">fx</span>지표 <span className="nx-num nx-muted">{count}</span>
      </button>
      {open && (
        <div id={id} className="nx-study-menu" role="dialog" aria-label="지표 추가">
          <div className="nx-study-types" role="radiogroup" aria-label="지표 종류">
            {TYPES.map((item, index) => (
              <button key={item.type} ref={index === 0 ? firstOption : undefined} type="button" role="radio" aria-checked={type === item.type}
                className={type === item.type ? "is-active" : undefined} onClick={() => pick(item.type)}>
                <span>{item.label}</span>
                <span className="nx-muted">{item.note}</span>
              </button>
            ))}
          </div>
          <form className="nx-study-add" noValidate onSubmit={(event) => {
            event.preventDefault();
            if (blocked) return;
            onAdd(type, value);
            close();
          }}>
            <label htmlFor={`${id}-period`}>기간</label>
            <input id={`${id}-period`} className="nx-input nx-num" inputMode="numeric" value={fixed ? "12·26·9" : period} disabled={fixed}
              aria-invalid={Boolean(periodError)} aria-describedby={`${id}-hint`} onChange={(event) => setPeriod(event.target.value.replace(/[^0-9]/g, ""))} />
            <span className="nx-muted">캔들</span>
            <button type="submit" className="nx-btn is-primary is-small" disabled={blocked}>추가</button>
            <p id={`${id}-hint`} className={`nx-field-hint${periodError ? " is-error" : ""}`}>
              {periodError || (full ? `지표는 ${MAX_INDICATORS}개까지 켤 수 있어요. 차트 범례의 ×로 하나를 끈 뒤 추가해 주세요.` : `${count}/${MAX_INDICATORS}개 켜짐`)}
            </p>
          </form>
        </div>
      )}
    </div>
  );
}
