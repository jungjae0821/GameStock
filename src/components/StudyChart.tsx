import { memo, useMemo, useState, type PointerEvent } from "react";
import { useWidth } from "../lib/useWidth";

/*
 * 지표 분석·백테스트용 여러 칸 차트. 칸마다 세로 눈금이 따로 있고 가로(시간)는 모든 칸이 같이 쓴다.
 * 값이 null인 곳(지표 준비 기간, 결측)은 선을 끊는다. 0으로 채우지 않는다.
 */

export type StudyCandle = { openPrice: number; highPrice: number; lowPrice: number; closePrice: number };
export type StudySeries = {
  key: string;
  label: string;
  values: Array<number | null>;
  /** line: 실선, dash: 점선(밴드 상·하단), bar: 0 기준 막대(히스토그램·건수) */
  style?: "line" | "dash" | "bar";
  /** 1~6 계열 색. CSS의 .is-tone-N과 맞춘다. */
  tone?: number;
};
export type StudyMarker = { index: number; side: "BUY" | "SELL"; label: string };
export type StudyPane = {
  id: string;
  title: string;
  height: number;
  candles?: StudyCandle[];
  series: StudySeries[];
  /** RSI 30/70처럼 고정 기준선. */
  guides?: number[];
  /** 고정 세로 범위. 없으면 값에 맞춘다. */
  domain?: [number, number];
  format: (value: number) => string;
  markers?: StudyMarker[];
};

const AXIS = 64;
const PAD_TOP = 22;
const PAD_BOTTOM = 6;
const TIME_AXIS = 22;

function niceTicks(low: number, high: number, count: number) {
  const span = high - low || Math.abs(high) * 0.01 || 1;
  const raw = span / Math.max(2, count);
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  const unit = raw / magnitude;
  const step = (unit <= 1 ? 1 : unit <= 2 ? 2 : unit <= 5 ? 5 : 10) * magnitude;
  const ticks: number[] = [];
  for (let value = Math.ceil(low / step) * step; value <= high + step * 1e-9; value += step) ticks.push(Number(value.toPrecision(12)));
  return ticks;
}

function paneScale(pane: StudyPane, from: number, to: number) {
  if (pane.domain) return { low: pane.domain[0], high: pane.domain[1] };
  let low = Number.POSITIVE_INFINITY;
  let high = Number.NEGATIVE_INFINITY;
  const take = (value: number | null | undefined) => {
    if (value === null || value === undefined || !Number.isFinite(value)) return;
    if (value < low) low = value;
    if (value > high) high = value;
  };
  for (let index = from; index < to; index += 1) {
    const candle = pane.candles?.[index];
    if (candle) { take(candle.lowPrice); take(candle.highPrice); }
    for (const series of pane.series) take(series.values[index]);
  }
  for (const guide of pane.guides ?? []) take(guide);
  if (pane.series.some((series) => series.style === "bar")) take(0);
  if (!Number.isFinite(low)) return { low: 0, high: 1 };
  const span = high - low || Math.abs(high) * 0.02 || 1;
  return { low: low - span * 0.08, high: high + span * 0.08 };
}

/** null을 만나면 선을 끊는 SVG 경로. */
function linePath(values: Array<number | null>, x: (index: number) => number, y: (value: number) => number) {
  let path = "";
  let open = false;
  values.forEach((value, index) => {
    if (value === null || !Number.isFinite(value)) { open = false; return; }
    path += `${open ? "L" : "M"}${x(index).toFixed(1)},${y(value).toFixed(1)}`;
    open = true;
  });
  return path;
}

const timeFormat = (span: number) => new Intl.DateTimeFormat("ko-KR", span > 2 * 86_400_000
  ? { timeZone: "Asia/Seoul", month: "numeric", day: "numeric" }
  : { timeZone: "Asia/Seoul", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
const fullTime = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });

type Layout = { width: number; plotWidth: number; slot: number; x: (index: number) => number; tops: number[]; total: number };

function layoutFor(width: number, count: number, panes: StudyPane[]): Layout {
  const plotWidth = Math.max(1, width - AXIS);
  const slot = plotWidth / Math.max(24, count);
  const offset = plotWidth - slot * count;
  const tops: number[] = [];
  let top = 0;
  for (const pane of panes) { tops.push(top); top += pane.height; }
  return { width, plotWidth, slot, x: (index) => offset + slot * (index + 0.5), tops, total: top + TIME_AXIS };
}

const Drawing = memo(function Drawing({ times, panes, layout, label }: { times: number[]; panes: StudyPane[]; layout: Layout; label: string }) {
  const { plotWidth, slot, x, tops, total, width } = layout;
  const span = (times.at(-1) ?? 0) - (times[0] ?? 0);
  const formatter = timeFormat(span);
  const stride = Math.max(1, Math.ceil(times.length / Math.max(2, Math.floor(plotWidth / 96))));
  const timeTicks = times.map((_, index) => index).filter((index) => (times.length - 1 - index) % stride === 0);
  const body = Math.max(1, Math.min(14, slot * 0.66));

  return (
    <svg className="study-chart-drawing" viewBox={`0 0 ${width} ${total}`} role="img" aria-label={label}>
      {panes.map((pane, paneIndex) => {
        const top = tops[paneIndex];
        const scale = paneScale(pane, 0, times.length);
        const innerTop = top + PAD_TOP;
        const innerHeight = Math.max(1, pane.height - PAD_TOP - PAD_BOTTOM);
        const y = (value: number) => innerTop + (1 - (value - scale.low) / (scale.high - scale.low || 1)) * innerHeight;
        const ticks = niceTicks(scale.low, scale.high, Math.max(2, Math.floor(innerHeight / 36)));
        const zero = y(Math.min(Math.max(0, scale.low), scale.high));
        return (
          <g key={pane.id} className="study-pane">
            {paneIndex > 0 && <line className="study-pane-rule" x1={0} x2={width} y1={top + 0.5} y2={top + 0.5} />}
            <g className="study-grid" aria-hidden="true">
              {ticks.map((tick) => {
                const ty = Math.round(y(tick)) + 0.5;
                if (ty < innerTop - 1 || ty > innerTop + innerHeight + 1) return null;
                return <g key={tick}>
                  <line x1={0} x2={plotWidth} y1={ty} y2={ty} />
                  <text x={plotWidth + 6} y={ty} dominantBaseline="middle">{pane.format(tick)}</text>
                </g>;
              })}
              {pane.guides?.map((guide) => <line key={`g${guide}`} className="study-guide" x1={0} x2={plotWidth} y1={y(guide)} y2={y(guide)} />)}
            </g>
            {pane.candles && pane.candles.map((candle, index) => {
              const cx = Math.round(x(index)) + 0.5;
              const openY = Math.round(y(candle.openPrice));
              const closeY = Math.round(y(candle.closePrice));
              return <g key={index} className={`study-candle is-${candle.closePrice >= candle.openPrice ? "up" : "down"}`}>
                <line x1={cx} x2={cx} y1={Math.round(y(candle.highPrice))} y2={Math.round(y(candle.lowPrice))} />
                <rect x={Math.round(cx - body / 2)} y={Math.min(openY, closeY)} width={body} height={Math.max(1, Math.abs(closeY - openY))} />
              </g>;
            })}
            {pane.series.map((series) => series.style === "bar" ? (
              <g key={series.key} className={`study-bars is-tone-${series.tone ?? 1}`}>
                {series.values.map((value, index) => value === null || !Number.isFinite(value) ? null : (
                  <rect key={index} className={value < 0 ? "is-negative" : undefined} x={x(index) - body / 2} width={body}
                    y={Math.min(zero, y(value))} height={Math.max(1, Math.abs(y(value) - zero))} />
                ))}
              </g>
            ) : (
              <path key={series.key} className={`study-line is-tone-${series.tone ?? 1}${series.style === "dash" ? " is-dash" : ""}`} d={linePath(series.values, x, y)} />
            ))}
            {pane.markers?.map((marker, index) => {
              const value = pane.series[0]?.values[marker.index];
              if (value === null || value === undefined) return null;
              const mx = x(marker.index);
              const my = y(value);
              const up = marker.side === "BUY";
              return <path key={index} className={`study-marker is-${up ? "buy" : "sell"}`}
                d={up ? `M${mx},${my + 4} l-5,9 h10 z` : `M${mx},${my - 4} l-5,-9 h10 z`}><title>{marker.label}</title></path>;
            })}
          </g>
        );
      })}
      <g className="study-time" aria-hidden="true">
        {timeTicks.map((index) => <text key={index} x={x(index)} y={total - 6} textAnchor="middle">{formatter.format(times[index])}</text>)}
      </g>
    </svg>
  );
});

/** times와 각 칸의 values는 같은 길이로 1:1 대응해야 한다. */
export function StudyChart({ times, panes, label }: { times: number[]; panes: StudyPane[]; label: string }) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const layout = useMemo(() => layoutFor(width, times.length, panes), [width, times.length, panes]);
  const index = hover ?? times.length - 1;

  const onMove = (event: PointerEvent<HTMLDivElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    const px = event.clientX - box.left;
    if (px > layout.plotWidth || times.length === 0) { setHover(null); return; }
    const first = layout.x(0) - layout.slot / 2;
    setHover(Math.max(0, Math.min(times.length - 1, Math.floor((px - first) / layout.slot))));
  };

  return (
    <div className="study-chart" ref={ref} onPointerMove={onMove} onPointerLeave={() => setHover(null)}>
      {width > 0 && times.length > 0 && <>
        <Drawing times={times} panes={panes} layout={layout} label={label} />
        {hover !== null && <span className="study-crosshair" style={{ left: layout.x(hover) }} aria-hidden="true" />}
        {panes.map((pane, paneIndex) => {
          const candle = pane.candles?.[index];
          return (
            <div key={pane.id} className="study-legend" style={{ top: layout.tops[paneIndex] + 2 }}>
              <strong>{pane.title}</strong>
              {paneIndex === 0 && <span className="nx-num">{fullTime.format(times[index])}</span>}
              {candle && <span className="nx-num">시 {pane.format(candle.openPrice)} 고 {pane.format(candle.highPrice)} 저 {pane.format(candle.lowPrice)} 종 {pane.format(candle.closePrice)}</span>}
              {pane.series.map((series) => {
                const value = series.values[index];
                return <span key={series.key} className={`study-legend-item is-tone-${series.tone ?? 1}`}>
                  {series.label} <span className="nx-num">{value === null || value === undefined || !Number.isFinite(value) ? "–" : pane.format(value)}</span>
                </span>;
              })}
            </div>
          );
        })}
      </>}
    </div>
  );
}
