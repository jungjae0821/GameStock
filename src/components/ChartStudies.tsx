import { useCallback, useEffect, useMemo, useState } from "react";
import { computeIndicator, type IndicatorSpec, type IndicatorType, type Series } from "../lib/indicators";
import type { ChartGeometry, ChartSample } from "../market/chartGeometry";
import { INDICATOR_TYPES, MAX_INDICATORS } from "../next/trade/IndicatorMenu";

/*
 * 종목 차트에 겹쳐 그리는 지표. 이동평균·볼린저 밴드는 가격 칸에, RSI·MACD·ATR과 체결 건수는 가격 칸 아래 칸에 그린다.
 * 값은 차트가 이미 가진 봉(실시간 봉 포함)으로 계산해 마지막 봉과 함께 움직인다.
 */

const STORAGE_KEY = "gamestock.indicators";
const DEFAULTS: IndicatorSpec[] = [
  { id: "sma5", type: "SMA", period: 5 },
  { id: "ema20", type: "EMA", period: 20 },
  { id: "rsi14", type: "RSI", period: 14 },
];
const PANE_OF = Object.fromEntries(INDICATOR_TYPES.map((item) => [item.type, item.pane])) as Record<IndicatorType, "overlay" | "oscillator">;

function readSaved(): IndicatorSpec[] {
  try {
    const saved = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? "null") as { indicators?: IndicatorSpec[] } | null;
    if (saved && Array.isArray(saved.indicators)) {
      return saved.indicators.filter((item) => PANE_OF[item.type] && Number.isInteger(item.period)).slice(0, MAX_INDICATORS);
    }
  } catch { /* 저장된 설정이 깨졌으면 기본값을 쓴다. */ }
  return DEFAULTS;
}

/** id는 [A-Za-z][A-Za-z0-9_]{0,31}이고 겹치면 안 된다. 퀀트 화면과 같은 규칙이다. */
function nextId(type: IndicatorType, period: number, taken: IndicatorSpec[]) {
  const base = `${type.toLowerCase()}${period}`;
  let id = base;
  for (let suffix = 2; taken.some((item) => item.id === id); suffix += 1) id = `${base}_${suffix}`;
  return id;
}

export function useIndicatorSettings() {
  const [indicators, setIndicators] = useState(readSaved);
  useEffect(() => {
    try { window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ indicators })); } catch { /* 저장하지 못해도 화면은 그대로 쓴다. */ }
  }, [indicators]);
  const add = useCallback((type: IndicatorType, period: number) => setIndicators((current) => (
    current.length >= MAX_INDICATORS ? current : [...current, { id: nextId(type, period, current), type, period }]
  )), []);
  const remove = useCallback((id: string) => setIndicators((current) => current.filter((item) => item.id !== id)), []);
  const oscillators = indicators.filter((item) => PANE_OF[item.type] === "oscillator").length;
  return { indicators, add, remove, oscillators };
}

export type StudyLine = { key: string; values: Series; tone: number; style?: "line" | "dash" | "bar" };
export type StudyRow = { id: string; label: string; lines: StudyLine[] };
export type StudyPane = { id: string; row: StudyRow; height: number; guides: number[]; domain?: [number, number]; format: (value: number) => string };
export type Studies = { overlay: StudyRow[]; panes: StudyPane[] };

const nameOf = (spec: IndicatorSpec) => (spec.type === "MACD" ? "MACD 12 26 9" : `${spec.type === "BBANDS" ? "BB" : spec.type} ${spec.period}`);
const priceFormat = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });
const decimalFormat = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 2 });
const countFormat = new Intl.NumberFormat("ko-KR", { notation: "compact", maximumFractionDigits: 1 });

/** 캔들 모드의 표본으로 지표를 계산한다. 체결 건수 칸은 지표가 없어도 늘 둔다. */
export function useStudies(samples: ChartSample[], indicators: IndicatorSpec[], enabled: boolean): Studies | null {
  return useMemo(() => {
    if (!enabled || samples.length === 0) return null;
    const bars = samples.map((sample) => ({ high: sample.candle?.highPrice ?? sample.price, low: sample.candle?.lowPrice ?? sample.price, close: sample.candle?.closePrice ?? sample.price }));
    const overlay: StudyRow[] = [];
    const panes: StudyPane[] = [{
      id: "count", height: 64, guides: [], format: (value) => countFormat.format(value),
      row: { id: "count", label: "체결 건수", lines: [{ key: "count", values: samples.map((sample) => sample.candle?.volume ?? null), tone: 6, style: "bar" }] },
    }];
    let tone = 1;
    for (const spec of indicators) {
      const result = computeIndicator(spec, bars);
      const keys = Object.keys(result.values);
      const lines: StudyLine[] = keys.map((key) => ({
        key: `${spec.id}.${key}`,
        values: result.values[key],
        tone: spec.type === "MACD" ? (key === "macd" ? 1 : key === "signal" ? 2 : 6) : result.pane === "overlay" ? tone : 3,
        style: key === "histogram" ? "bar" : key === "upper" || key === "lower" ? "dash" : "line",
      }));
      const row = { id: spec.id, label: nameOf(spec), lines };
      if (result.pane === "overlay") {
        overlay.push(row);
        tone = tone % 5 + 1;
      } else {
        panes.push({
          id: spec.id, row, height: 96,
          guides: spec.type === "RSI" ? [30, 70] : spec.type === "MACD" ? [0] : [],
          domain: spec.type === "RSI" ? [0, 100] : undefined,
          format: (value) => (spec.type === "ATR" ? priceFormat : decimalFormat).format(value),
        });
      }
    }
    return { overlay, panes };
  }, [samples, indicators, enabled]);
}

function path(values: Series, x: (index: number) => number, y: (value: number) => number) {
  let d = "";
  let open = false;
  values.forEach((value, index) => {
    if (value === null || !Number.isFinite(value)) { open = false; return; }
    d += `${open ? "L" : "M"}${x(index).toFixed(1)},${y(value).toFixed(1)}`;
    open = true;
  });
  return d;
}

const valueAt = (values: Series, index: number) => {
  const value = values[index];
  return value === null || value === undefined || !Number.isFinite(value) ? null : value;
};

/** 가격 칸 위에 겹치는 지표 선. 가격 칸과 같은 눈금을 쓴다. */
export function StudyOverlay({ rows, geometry: g, width, height }: { rows: StudyRow[]; geometry: ChartGeometry; width: number; height: number }) {
  if (rows.length === 0) return null;
  return (
    <svg className="market-study-overlay" viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
      <defs><clipPath id="market-study-clip"><rect width={g.plotWidth} height={g.plotHeight} /></clipPath></defs>
      <g clipPath="url(#market-study-clip)">
        {rows.flatMap((row) => row.lines).map((line) => (
          <path key={line.key} className={`market-study-line is-tone-${line.tone}${line.style === "dash" ? " is-dash" : ""}`} d={path(line.values, g.xAtIndex, g.y)} />
        ))}
      </g>
    </svg>
  );
}

function LegendRow({ row, index, format, onRemove }: { row: StudyRow; index: number; format: (value: number) => string; onRemove?: (id: string) => void }) {
  return (
    <div className="market-study-row">
      <span className="market-study-name">{row.label}</span>
      {row.lines.map((line) => {
        const value = valueAt(line.values, index);
        return <span key={line.key} className="market-study-value num">
          <i className={`study-swatch is-tone-${line.tone}`} aria-hidden="true" />{value === null ? "–" : format(value)}
        </span>;
      })}
      {onRemove && <button type="button" className="study-legend-remove" aria-label={`${row.label} 끄기`} onClick={() => onRemove(row.id)}>×</button>}
    </div>
  );
}

/** 가격 칸 왼쪽 위 범례. 지표마다 한 줄, 고른 봉(없으면 마지막 봉)의 값을 보인다. */
export function StudyLegend({ rows, index, onRemove }: { rows: StudyRow[]; index: number; onRemove: (id: string) => void }) {
  if (rows.length === 0) return null;
  return <div className="market-study-legend">{rows.map((row) => <LegendRow key={row.id} row={row} index={index} format={(value) => priceFormat.format(value)} onRemove={onRemove} />)}</div>;
}

/** 가격 칸 아래 칸들. 가로 위치는 가격 칸과 같아 같은 봉이 같은 세로줄에 선다. */
export function StudyPanes({ panes, geometry: g, width, index, crossX, onRemove }: {
  panes: StudyPane[]; geometry: ChartGeometry; width: number; index: number; crossX: number | null; onRemove: (id: string) => void;
}) {
  return (
    <div className="market-study-panes">
      {panes.map((pane) => {
        let low = Number.POSITIVE_INFINITY;
        let high = Number.NEGATIVE_INFINITY;
        for (const line of pane.row.lines) for (const value of line.values) if (value !== null && Number.isFinite(value)) { low = Math.min(low, value); high = Math.max(high, value); }
        if (pane.row.lines.some((line) => line.style === "bar")) { low = Math.min(low, 0); high = Math.max(high, 0); }
        if (pane.domain) [low, high] = pane.domain;
        if (!Number.isFinite(low)) { low = 0; high = 1; }
        if (low === high) high = low + 1;
        const top = 22;
        const inner = pane.height - top - 4;
        const y = (value: number) => top + (1 - (value - low) / (high - low)) * inner;
        const zero = y(Math.min(Math.max(0, low), high));
        return (
          <section key={pane.id} className="market-study-pane" aria-label={pane.row.label}>
            <div className="market-study-pane-legend">
              <LegendRow row={pane.row} index={index} format={pane.format} onRemove={pane.id === "count" ? undefined : onRemove} />
            </div>
            <svg viewBox={`0 0 ${width} ${pane.height}`} height={pane.height} aria-hidden="true">
              {pane.guides.map((guide) => <line key={guide} className="market-study-guide" x1={0} x2={g.plotWidth} y1={y(guide)} y2={y(guide)} />)}
              {pane.row.lines.map((line) => line.style === "bar" ? (
                <g key={line.key} className={`market-study-bars is-tone-${line.tone}`}>
                  {line.values.map((value, at) => value === null || !Number.isFinite(value) ? null : (
                    <rect key={at} x={g.xAtIndex(at) - g.bodyWidth / 2} width={g.bodyWidth} y={Math.min(zero, y(value))} height={Math.max(1, Math.abs(y(value) - zero))} />
                  ))}
                </g>
              ) : <path key={line.key} className={`market-study-line is-tone-${line.tone}`} d={path(line.values, g.xAtIndex, y)} />)}
              <line className="market-study-axis" x1={g.plotWidth + 0.5} x2={g.plotWidth + 0.5} y1={0} y2={pane.height} />
              {pane.id !== "count" && <>
                <text className="market-study-tick" x={g.plotWidth + 8} y={top + 4} dominantBaseline="central">{pane.format(high)}</text>
                <text className="market-study-tick" x={g.plotWidth + 8} y={top + inner - 4} dominantBaseline="central">{pane.format(low)}</text>
              </>}
              {crossX !== null && <line className="market-study-cross" x1={crossX} x2={crossX} y1={0} y2={pane.height} />}
            </svg>
          </section>
        );
      })}
    </div>
  );
}
