import { useEffect, useId, useMemo, useState } from "react";
import { StudyChart, type StudyPane, type StudySeries } from "../../components/StudyChart";
import { analyzeChart, CHART_RANGES, failure, isAbort, type ChartAnalysis, type ChartRange, type IndicatorSpec, type IndicatorType } from "../../lib/research";
import { serverTimestamp } from "../../market/format";
import { AnimatedNav } from "../AnimatedNav";

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

/* 같은 설정은 잠깐 재사용해 칸을 오갈 때 다시 계산하지 않는다. */
const cache = new Map<string, { at: number; chart: ChartAnalysis }>();

function seriesFor(spec: IndicatorSpec, values: Record<string, Array<number | null>>, tone: number): StudySeries[] {
  const name = `${spec.type} ${spec.period}`;
  if (spec.type === "BBANDS") return [
    { key: `${spec.id}.upper`, label: `BB 상단`, values: values.upper ?? [], tone, style: "dash" },
    { key: `${spec.id}.middle`, label: `BB ${spec.period}`, values: values.middle ?? [], tone },
    { key: `${spec.id}.lower`, label: `BB 하단`, values: values.lower ?? [], tone, style: "dash" },
  ];
  if (spec.type === "MACD") return [
    { key: `${spec.id}.histogram`, label: "히스토그램", values: values.histogram ?? [], tone: 6, style: "bar" },
    { key: `${spec.id}.macd`, label: "MACD", values: values.macd ?? [], tone: 1 },
    { key: `${spec.id}.signal`, label: "시그널", values: values.signal ?? [], tone: 2 },
  ];
  return [{ key: `${spec.id}.value`, label: name, values: values.value ?? [], tone }];
}

/** 종목 상세의 지표 차트. 서버가 계산한 지표를 캔들과 같은 칸 수로 받아 그린다. */
export function IndicatorChart({ code, name }: { code: string; name: string }) {
  const formId = useId();
  const [settings, setSettings] = useState(readSaved);
  const [chart, setChart] = useState<ChartAnalysis | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [draftType, setDraftType] = useState<IndicatorType>("SMA");
  const [draftPeriod, setDraftPeriod] = useState("20");
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
        if (!isAbort(reason)) setError(failure(reason, "지표를 계산하지 못했어요."));
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    };
    const debounce = window.setTimeout(() => void load(false), 250);
    const timer = window.setInterval(() => { if (!document.hidden) void load(true); }, REFRESH_MS);
    return () => { window.clearTimeout(debounce); window.clearInterval(timer); controller.abort(); };
    // key가 code·range·indicators를 모두 담는다.
  }, [key]);

  const panes = useMemo<StudyPane[]>(() => {
    if (!chart) return [];
    const specs = new Map(indicators.map((item) => [item.id, item]));
    const overlay: StudySeries[] = [];
    const oscillators: StudyPane[] = [];
    let tone = 1;
    for (const series of chart.indicators) {
      const spec = specs.get(series.id);
      if (!spec) continue;
      if (series.pane === "overlay") {
        overlay.push(...seriesFor(spec, series.values, tone));
        tone = tone % 5 + 1;
      } else {
        oscillators.push({
          id: spec.id,
          title: `${LABEL[spec.type]}${spec.type === "MACD" ? " 12·26·9" : ` ${spec.period}`}`,
          height: 120,
          series: seriesFor(spec, series.values, 3),
          guides: spec.type === "RSI" ? [30, 50, 70] : spec.type === "MACD" ? [0] : undefined,
          domain: spec.type === "RSI" ? [0, 100] : undefined,
          format: spec.type === "ATR" ? (value) => priceFormat.format(value) : (value) => decimalFormat.format(value),
        });
      }
    }
    return [
      { id: "price", title: name, height: 280, candles: chart.candles, series: overlay, format: (value) => priceFormat.format(value) },
      { id: "count", title: "체결 건수", height: 72, series: [{ key: "count", label: "건수", values: chart.candles.map((candle) => candle.volume), style: "bar", tone: 6 }], format: (value) => countFormat.format(value) },
      ...oscillators,
    ];
  }, [chart, indicators, name]);
  const times = useMemo(() => chart?.candles.map((candle) => serverTimestamp(candle.recordedAt)) ?? [], [chart]);

  const add = () => {
    const period = Number(draftPeriod);
    if (!Number.isInteger(period) || period < 2 || period > 500 || indicators.length >= MAX_INDICATORS) return;
    setSettings((current) => ({ ...current, indicators: [...current.indicators, { id: nextId(draftType, period, current.indicators), type: draftType, period }] }));
  };
  const remove = (id: string) => setSettings((current) => ({ ...current, indicators: current.indicators.filter((item) => item.id !== id) }));
  const periodValue = Number(draftPeriod);
  const periodError = draftPeriod !== "" && (!Number.isInteger(periodValue) || periodValue < 2 || periodValue > 500) ? "기간은 2~500 사이 정수예요." : "";
  const longest = Math.max(0, ...indicators.map((item) => item.type === "MACD" ? 35 : item.period));

  return (
    <section className="nx-indicators" aria-labelledby={`${formId}-title`}>
      <header>
        <h2 id={`${formId}-title`}>지표 분석</h2>
        {loading && <span className="nx-muted" role="status">계산 중…</span>}
      </header>
      <AnimatedNav as="div" fill activeKey={range} className="nx-seg nx-indicator-ranges" role="group" aria-label="캔들 간격">
        {CHART_RANGES.map((item) => (
          <button key={item.value} type="button" className={range === item.value ? "is-active" : undefined} aria-pressed={range === item.value}
            onClick={() => setSettings((current) => ({ ...current, range: item.value }))}>{item.label}</button>
        ))}
      </AnimatedNav>
      <ul className="nx-indicator-chips" aria-label="켜 둔 지표">
        {indicators.map((item) => (
          <li key={item.id}>
            <span>{LABEL[item.type]}{item.type === "MACD" ? "" : <> <span className="nx-num">{item.period}</span></>}</span>
            <button type="button" aria-label={`${LABEL[item.type]} ${item.period} 끄기`} onClick={() => remove(item.id)}>×</button>
          </li>
        ))}
        {indicators.length === 0 && <li className="nx-muted">지표 없이 캔들만 보여요.</li>}
      </ul>
      <form className="nx-indicator-add" onSubmit={(event) => { event.preventDefault(); add(); }} noValidate>
        <label className="vh" htmlFor={`${formId}-type`}>지표 종류</label>
        <select id={`${formId}-type`} className="nx-select" value={draftType} onChange={(event) => {
          const type = event.target.value as IndicatorType;
          setDraftType(type);
          setDraftPeriod(String(TYPES.find((item) => item.type === type)?.period ?? 20));
        }}>
          {TYPES.map((item) => <option key={item.type} value={item.type}>{item.label} · {item.note}</option>)}
        </select>
        <label className="vh" htmlFor={`${formId}-period`}>기간</label>
        <input id={`${formId}-period`} className="nx-input nx-num" inputMode="numeric" value={draftPeriod} disabled={draftType === "MACD"}
          aria-invalid={Boolean(periodError)} onChange={(event) => setDraftPeriod(event.target.value.replace(/[^0-9]/g, ""))} />
        <button type="submit" className="nx-btn is-small" disabled={!!periodError || draftPeriod === "" || indicators.length >= MAX_INDICATORS}>지표 추가</button>
      </form>
      {periodError && <p className="nx-field-hint is-error">{periodError}</p>}
      {error && <p className="nx-note is-error" role="alert">{error}</p>}
      <div className="nx-indicator-chart">
        {!chart ? <div className="nx-skeleton" role="status" aria-label="차트를 불러오는 중"><span /><span /><span /></div>
          : chart.candles.length === 0 ? <p className="nx-empty-state">이 간격으로 쌓인 캔들이 아직 없어요.</p>
          : <StudyChart times={times} panes={panes} label={`${name} ${CHART_RANGES.find((item) => item.value === chart.range)?.label ?? chart.range} 캔들과 지표`} />}
      </div>
      <p className="nx-field-note">
        캔들 {chart?.candles.length.toLocaleString("ko-KR") ?? 0}개 · 마지막 캔들은 아직 진행 중일 수 있어요. 막대는 거래량(주)이 아니라 체결 건수예요.
        {chart && longest > chart.candles.length && " 캔들 수가 지표 기간보다 적으면 값이 비어 있어요."}
      </p>
    </section>
  );
}
