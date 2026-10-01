import { useEffect, useId, useState } from "react";
import type { PointerEvent } from "react";
import { apiFetch } from "../lib/api";
import { indexValue, serverTimestamp, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";

type ChartMode = "line" | "candle";
type ChartRange = "realtime" | "1h" | "6h" | "12h" | "1d" | "1w" | "1m" | "1y";
type ChartCandle = { recordedAt: string; openPrice: number; highPrice: number; lowPrice: number; closePrice: number; volume: number };
type PricePoint = { recordedAt: string; price: number };
type Sample = { at: number; price: number; candle?: ChartCandle };

const RANGE_OPTIONS: Array<{ value: ChartRange; label: string }> = [
  { value: "realtime", label: "실시간" }, { value: "1h", label: "1시간" },
  { value: "6h", label: "6시간" }, { value: "12h", label: "12시간" },
  { value: "1d", label: "1일" }, { value: "1w", label: "1주" },
  { value: "1m", label: "1개월" }, { value: "1y", label: "1년" },
];
const timeFormatter = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false,
});
const axisFormatter = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul", month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false,
});

/** Hover and touch read the server's recorded price/time pair, never an estimated tick time. */
export function SeriesChart({ code, tone }: { code: string; tone?: "up" | "down" | "flat" }) {
  const snapshot = useMarket();
  const quote = snapshot.quotes[code];
  const [mode, setMode] = useState<ChartMode>("line");
  const [range, setRange] = useState<ChartRange>("realtime");
  const [data, setData] = useState<{ key: string; samples: Sample[] }>({ key: "", samples: [] });
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [hoveredAt, setHoveredAt] = useState<number | null>(null);
  const gradientId = useId().replace(/:/g, "");
  const dataKey = `${code}:${mode}:${range}`;

  useEffect(() => {
    let active = true;
    let pending = false;
    setLoading(true);
    setFailed(false);
    const load = async () => {
      if (pending) return;
      pending = true;
      try {
        let samples: Sample[];
        if (mode === "line" && range !== "1m" && range !== "1y") {
          const historyRange = range === "realtime" ? "30m" : range === "1d" ? "24h" : range;
          const points = await apiFetch<PricePoint[]>(`/api/stocks/${code}/history?range=${historyRange}`);
          samples = points.map((point) => ({ at: serverTimestamp(point.recordedAt), price: point.price }));
        } else {
          const candles = await apiFetch<ChartCandle[]>(`/api/stocks/${code}/chart?range=${range === "realtime" ? "1h" : range}`);
          samples = candles.map((candle) => ({ at: serverTimestamp(candle.recordedAt), price: candle.closePrice, candle }));
        }
        if (!active) return;
        // A timestamp can occur twice in the same transaction; retain its final recorded price.
        const unique = new Map(samples.filter((sample) => Number.isFinite(sample.at) && Number.isFinite(sample.price) && sample.price > 0).map((sample) => [sample.at, sample]));
        setData({ key: dataKey, samples: [...unique.values()].sort((left, right) => left.at - right.at) });
        setFailed(false);
      } catch {
        if (active) setFailed(true);
      } finally {
        pending = false;
        if (active) setLoading(false);
      }
    };
    void load();
    const timer = window.setInterval(() => void load(), range === "realtime" ? 2000 : 10_000);
    return () => { active = false; window.clearInterval(timer); };
  }, [code, mode, range, dataKey]);

  if (!quote) return null;
  const samples = data.key === dataKey ? data.samples : [];
  const values = mode === "candle"
    ? samples.flatMap((sample) => sample.candle ? [sample.candle.openPrice, sample.candle.highPrice, sample.candle.lowPrice, sample.candle.closePrice] : [sample.price])
    : samples.map((sample) => sample.price);
  const max = Math.max(...(values.length ? values : [quote.price]));
  const min = Math.min(...(values.length ? values : [quote.price]));
  const span = max - min || Math.max(1, max * 0.002);
  const hi = max + span * 0.18;
  const lo = min - span * 0.18;
  const y = (price: number) => ((hi - price) / (hi - lo)) * 100;
  const firstAt = samples[0]?.at ?? 0;
  const lastAt = samples.at(-1)?.at ?? firstAt;
  /* 캔들은 봉 간격을 균등하게(시간 불균형에 따른 뭉침 방지), 선형은 실제 시간 비율로 배치한다. */
  const slot = samples.length > 0 ? 96 / samples.length : 96;
  const x = (at: number) => {
    if (mode === "candle") {
      const index = samples.findIndex((sample) => sample.at === at);
      return 2 + slot * (index + 0.5);
    }
    return lastAt > firstAt ? 2 + ((at - firstAt) / (lastAt - firstAt)) * 96 : 50;
  };
  const points = samples.map((sample) => ({ x: x(sample.at), y: y(sample.price) }));
  const path = points.map((point, index) => `${index === 0 ? "M" : "L"} ${point.x.toFixed(3)} ${point.y.toFixed(3)}`).join(" ");
  const area = points.length > 1 ? `${path} L ${points.at(-1)!.x} 100 L ${points[0].x} 100 Z` : "";
  const hoveredIndex = samples.findIndex((sample) => sample.at === hoveredAt);
  const hovered = samples[hoveredIndex];
  const currentValue = samples.at(-1)?.price ?? quote.price;
  const prevY = y(quote.prevClose);
  const ratio = sessionRate(quote);
  const chartTone = tone ?? (ratio > 0 ? "up" : ratio < 0 ? "down" : "flat");
  const rangeLabel = RANGE_OPTIONS.find((option) => option.value === range)?.label;

  const pointAtPointer = (event: PointerEvent<HTMLDivElement>) => {
    if (!samples.length) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    const fraction = Math.max(0, Math.min(1, ((event.clientX - bounds.left) / bounds.width * 100 - 2) / 96));
    if (mode === "candle") {
      const index = Math.max(0, Math.min(samples.length - 1, Math.floor(fraction / slot)));
      setHoveredAt(samples[index].at);
      return;
    }
    const target = firstAt + fraction * (lastAt - firstAt);
    let nearest = samples[0];
    for (const sample of samples) {
      if (Math.abs(sample.at - target) < Math.abs(nearest.at - target)) nearest = sample;
    }
    setHoveredAt(nearest.at);
  };

  return (
    <figure className={`chart stock-series-chart is-${chartTone}`}>
      <div className="chart-toolbar">
        <div className="chart-control chart-range-control" role="group" aria-label="차트 기간">
          {RANGE_OPTIONS.map((option) => (
            <button key={option.value} type="button" className={`chart-control-button${range === option.value ? " is-active" : ""}`} aria-pressed={range === option.value}
              onClick={() => { setRange(option.value); setHoveredAt(null); }}>{option.label}</button>
          ))}
        </div>
        <div className="chart-control chart-mode-control" role="group" aria-label="차트 표현 방식">
          {([ ["line", "선"], ["candle", "캔들"] ] as const).map(([value, label]) => (
            <button key={value} type="button" className={`chart-control-button${mode === value ? " is-active" : ""}`} aria-pressed={mode === value}
              onClick={() => { setMode(value); setHoveredAt(null); }}>{label}</button>
          ))}
        </div>
      </div>
      <div className="chart-readout" aria-live="off">
        {hovered ? (
          <div className="chart-hover-readout" role="tooltip">
            <time className="num" dateTime={new Date(hovered.at).toISOString()}>{timeFormatter.format(hovered.at)}</time>
            <strong className="num">{won(hovered.price)}</strong>
            {mode === "candle" && hovered.candle && <span className="chart-hover-ohlc num">시가 {won(hovered.candle.openPrice)} · 고가 {won(hovered.candle.highPrice)} · 저가 {won(hovered.candle.lowPrice)}</span>}
          </div>
        ) : <p className="chart-status" role="status">{loading ? "차트 불러오는 중…" : failed ? "가격 기록을 불러오지 못했습니다." : samples.length ? "차트에 마우스를 올리거나 터치하면 당시 가격을 볼 수 있어요." : "이 기간에 저장된 가격 기록이 없습니다."}</p>}
      </div>
      <div className="chart-plot">
        <div className="chart-canvas" role="group" aria-label={`${code} 가격 차트 탐색`} tabIndex={samples.length ? 0 : -1}
          onPointerMove={pointAtPointer} onPointerDown={pointAtPointer}
          onPointerLeave={(event) => { if (event.pointerType === "mouse") setHoveredAt(null); }}
          onFocus={() => setHoveredAt((current) => current ?? samples.at(-1)?.at ?? null)} onBlur={() => setHoveredAt(null)}
          onKeyDown={(event) => {
            if (!samples.length) return;
            const current = hoveredIndex < 0 ? samples.length - 1 : hoveredIndex;
            let next = current;
            if (event.key === "ArrowLeft") next = Math.max(0, current - 1);
            else if (event.key === "ArrowRight") next = Math.min(samples.length - 1, current + 1);
            else if (event.key === "Home") next = 0;
            else if (event.key === "End") next = samples.length - 1;
            else if (event.key === "Escape") { setHoveredAt(null); return; }
            else return;
            event.preventDefault(); setHoveredAt(samples[next].at);
          }}>
          <svg viewBox="0 0 100 100" preserveAspectRatio="none" role="img" aria-label={`${code} ${mode === "line" ? "선형" : "캔들"} 차트. ${rangeLabel} 기준 마지막 기록 ${won(currentValue)}`} focusable="false">
            <defs><linearGradient id={gradientId} x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stopColor="currentColor" stopOpacity="0.12" /><stop offset="100%" stopColor="currentColor" stopOpacity="0" /></linearGradient></defs>
            {prevY >= 0 && prevY <= 100 && <line className="chart-prevclose" x1="0" x2="100" y1={prevY} y2={prevY} vectorEffect="non-scaling-stroke" />}
            {mode === "line" ? <>
              {area && <path d={area} fill={`url(#${gradientId})`} />}
              <path className="chart-line" d={path} vectorEffect="non-scaling-stroke" />
            </> : samples.map((sample) => {
              const candle = sample.candle;
              if (!candle) return null;
              const bodyTop = Math.min(y(candle.openPrice), y(candle.closePrice));
              const width = Math.max(0.3, Math.min(2.4, slot * 0.55));
              return <g key={sample.at} className={`chart-candle ${candle.closePrice >= candle.openPrice ? "is-up" : "is-down"}`}>
                <line className="chart-candle-wick" x1={x(sample.at)} x2={x(sample.at)} y1={y(candle.highPrice)} y2={y(candle.lowPrice)} vectorEffect="non-scaling-stroke" />
                <rect className="chart-candle-body" x={x(sample.at) - width / 2} y={bodyTop} width={width} height={Math.max(0.35, Math.abs(y(candle.closePrice) - y(candle.openPrice)))} />
              </g>;
            })}
          </svg>
          {samples.length > 0 && <>
            <span className="chart-extreme is-high" style={{top:`${y(max)}%`}}>최고 {won(max)}</span>
            {max !== min && <span className="chart-extreme is-low" style={{top:`${y(min)}%`}}>최저 {won(min)}</span>}
            <span className="chart-last-dot" style={{left:`${x(lastAt)}%`,top:`${y(currentValue)}%`}} />
          </>}
          {hovered && <>
            <span className="chart-crosshair" style={{left:`${x(hovered.at)}%`}} />
            <span className="chart-hover-dot" style={{left:`${x(hovered.at)}%`,top:`${y(hovered.price)}%`}} />
          </>}
        </div>
        {samples.length > 0 && <span className="chart-current" style={{top:`${y(currentValue)}%`}}>{won(currentValue)}</span>}
      </div>
      <div className="chart-timeline num" aria-hidden="true"><span>{samples.length ? axisFormatter.format(firstAt) : ""}</span><span>{samples.length > 1 ? axisFormatter.format(lastAt) : ""}</span></div>
      <table className="vh">
        <caption>{`${code} ${rangeLabel} ${mode === "line" ? "선형" : "캔들"} 차트 요약`}</caption>
        <tbody>
          <tr><th scope="row">기간 최고</th><td>{won(max)}</td></tr>
          <tr><th scope="row">기간 최저</th><td>{won(min)}</td></tr>
          <tr><th scope="row">전일 종가</th><td>{won(quote.prevClose)}</td></tr>
          <tr><th scope="row">마지막 기록</th><td>{won(currentValue)}</td></tr>
          <tr><th scope="row">세션 등락률</th><td>{indexValue(ratio * 100)}%</td></tr>
        </tbody>
      </table>
    </figure>
  );
}
