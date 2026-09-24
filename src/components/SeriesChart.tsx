import { useEffect, useState } from "react";
import { apiFetch } from "../lib/api";
import { indexValue, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";

type ChartMode = "line" | "candle";
type ChartRange = "realtime" | "1h" | "6h" | "12h" | "1d" | "1w" | "1m" | "1y";

type ChartCandle = {
  recordedAt: string;
  openPrice: number;
  highPrice: number;
  lowPrice: number;
  closePrice: number;
  volume: number;
};

const MODE_OPTIONS: Array<{ value: ChartMode; label: string }> = [
  { value: "line", label: "선형" },
  { value: "candle", label: "캔들" },
];

const RANGE_OPTIONS: Array<{ value: ChartRange; label: string }> = [
  { value: "realtime", label: "실시간" },
  { value: "1h", label: "1시간" },
  { value: "6h", label: "6시간" },
  { value: "12h", label: "12시간" },
  { value: "1d", label: "1일" },
  { value: "1w", label: "1주" },
  { value: "1m", label: "1개월" },
  { value: "1y", label: "1년" },
];

interface Point {
  x: number;
  y: number;
}

function smoothPath(points: Point[]): string {
  if (points.length === 0) return "";
  if (points.length === 1) return `M ${points[0].x.toFixed(2)} ${points[0].y.toFixed(2)}`;

  let path = `M ${points[0].x.toFixed(2)} ${points[0].y.toFixed(2)}`;
  for (let index = 0; index < points.length - 1; index += 1) {
    const previous = points[index - 1] ?? points[index];
    const current = points[index];
    const next = points[index + 1];
    const following = points[index + 2] ?? next;
    const controlOne = {
      x: current.x + (next.x - previous.x) / 6,
      y: current.y + (next.y - previous.y) / 6,
    };
    const controlTwo = {
      x: next.x - (following.x - current.x) / 6,
      y: next.y - (following.y - current.y) / 6,
    };
    path += ` C ${controlOne.x.toFixed(2)} ${controlOne.y.toFixed(2)}, ${controlTwo.x.toFixed(2)} ${controlTwo.y.toFixed(2)}, ${next.x.toFixed(2)} ${next.y.toFixed(2)}`;
  }
  return path;
}

/** 종목 가격을 실시간 선형 또는 구간별 OHLC 캔들로 보여준다. */
export function SeriesChart({ code, tone }: { code: string; tone?: "up" | "down" | "flat" }) {
  const snapshot = useMarket();
  const quote = snapshot.quotes[code];
  const [mode, setMode] = useState<ChartMode>("line");
  const [range, setRange] = useState<ChartRange>("realtime");
  const [history, setHistory] = useState<ChartCandle[]>([]);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (range === "realtime") {
      setHistory([]);
      setLoading(false);
      setFailed(false);
      return undefined;
    }

    let active = true;
    const load = async () => {
      try {
        setLoading(true);
        const next = await apiFetch<ChartCandle[]>(`/api/stocks/${code}/chart?range=${range}`);
        if (!active) return;
        setHistory(next);
        setFailed(false);
      } catch {
        if (active) setFailed(true);
      } finally {
        if (active) setLoading(false);
      }
    };

    void load();
    const timer = window.setInterval(() => void load(), 10_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [code, range]);

  if (!quote) return null;

  const realtimeCandle: ChartCandle = {
    recordedAt: new Date().toISOString(),
    openPrice: quote.open,
    highPrice: quote.high,
    lowPrice: quote.low,
    closePrice: quote.price,
    volume: quote.volume,
  };
  const candles = range === "realtime" ? [realtimeCandle] : history;
  const lineValues = range === "realtime" ? quote.series : history.map((item) => item.closePrice);
  const chartValues = mode === "candle"
    ? candles.flatMap((item) => [item.openPrice, item.highPrice, item.lowPrice, item.closePrice])
    : lineValues;
  const values = chartValues.length > 0 ? chartValues : [quote.price];
  const max = Math.max(...values);
  const min = Math.min(...values);
  const span = max - min || Math.max(1, max * 0.001);
  const hi = max + span * 0.08;
  const lo = min - span * 0.08;
  const rangeSize = hi - lo;
  const y = (value: number) => ((hi - value) / rangeSize) * 100;
  const points = lineValues.map((value, index) => ({
    x: lineValues.length > 1 ? (index / (lineValues.length - 1)) * 100 : 50,
    y: y(value),
  }));
  const currentValue = mode === "candle" ? candles.at(-1)?.closePrice ?? quote.price : lineValues.at(-1) ?? quote.price;
  const prevY = y(quote.prevClose);
  const showPrev = Math.abs(prevY - y(max)) > 9 && Math.abs(prevY - y(min)) > 9;
  const rate = sessionRate(quote);
  const fallbackTone = rate > 0 ? "up" : rate < 0 ? "down" : "flat";

  return (
    <figure className={`chart is-${tone ?? fallbackTone}`}>
      <div className="chart-toolbar">
        <div className="chart-control" role="group" aria-label="차트 표현 방식">
          <span className="chart-control-label">표현</span>
          {MODE_OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              className={`chart-control-button${mode === option.value ? " is-active" : ""}`}
              aria-pressed={mode === option.value}
              onClick={() => setMode(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
        <div className="chart-control chart-range-control" role="group" aria-label="차트 기간">
          <span className="chart-control-label">기간</span>
          {RANGE_OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              className={`chart-control-button${range === option.value ? " is-active" : ""}`}
              aria-pressed={range === option.value}
              onClick={() => setRange(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>
      {loading && <p className="chart-status" role="status">차트 불러오는 중…</p>}
      {failed && <p className="chart-status is-error" role="status">해당 기간의 차트를 불러오지 못했습니다.</p>}
      <div className="chart-plot">
        <svg
          viewBox="0 0 100 100"
          preserveAspectRatio="none"
          role="img"
          aria-label={`${code} ${mode === "line" ? "선형" : "캔들"} 차트. ${RANGE_OPTIONS.find((item) => item.value === range)?.label} 기준 현재가 ${won(currentValue)}`}
          focusable="false"
        >
          <line className="chart-grid" x1="0" x2="100" y1={y(max)} y2={y(max)} vectorEffect="non-scaling-stroke" />
          <line className="chart-grid" x1="0" x2="100" y1={y(min)} y2={y(min)} vectorEffect="non-scaling-stroke" />
          <line className="chart-prevclose" x1="0" x2="100" y1={prevY} y2={prevY} vectorEffect="non-scaling-stroke" />
          {mode === "line" ? (
            <>
              <path className="chart-line" d={smoothPath(points)} vectorEffect="non-scaling-stroke" />
              {points.length === 1 && <circle className="chart-point" cx={points[0].x} cy={points[0].y} r="1.8" vectorEffect="non-scaling-stroke" />}
              {points.length > 1 && <circle className="chart-point" cx={points.at(-1)?.x} cy={points.at(-1)?.y} r="1.8" vectorEffect="non-scaling-stroke" />}
            </>
          ) : (
            candles.map((item, index) => {
              const x = candles.length > 1 ? (index / (candles.length - 1)) * 100 : 50;
              const openY = y(item.openPrice);
              const closeY = y(item.closePrice);
              const bodyTop = Math.min(openY, closeY);
              const bodyHeight = Math.max(1.2, Math.abs(closeY - openY));
              const width = Math.max(0.7, Math.min(3.2, 72 / Math.max(1, candles.length)));
              const rising = item.closePrice >= item.openPrice;
              return (
                <g key={`${item.recordedAt}-${index}`} className={`chart-candle ${rising ? "is-up" : "is-down"}`}>
                  <line className="chart-candle-wick" x1={x} x2={x} y1={y(item.highPrice)} y2={y(item.lowPrice)} vectorEffect="non-scaling-stroke" />
                  <rect className="chart-candle-body" x={x - width / 2} y={bodyTop} width={width} height={bodyHeight} />
                </g>
              );
            })
          )}
        </svg>
        <span className="chart-tick" style={{ top: `${y(max)}%` }}>{won(max)}</span>
        {showPrev && <span className="chart-tick is-prev" style={{ top: `${prevY}%` }}>전일 {won(quote.prevClose)}</span>}
        <span className="chart-tick is-bottom" style={{ top: `${y(min)}%` }}>{won(min)}</span>
        <span className="chart-current" style={{ top: `${y(currentValue)}%` }}>{won(currentValue)}</span>
      </div>
      <table className="vh">
        <caption>{`${code} ${RANGE_OPTIONS.find((item) => item.value === range)?.label} ${mode === "line" ? "선형" : "캔들"} 차트 요약`}</caption>
        <tbody>
          <tr><th scope="row">기간 최고</th><td>{won(max)}</td></tr>
          <tr><th scope="row">기간 최저</th><td>{won(min)}</td></tr>
          <tr><th scope="row">전일 종가</th><td>{won(quote.prevClose)}</td></tr>
          <tr><th scope="row">현재가</th><td>{won(currentValue)}</td></tr>
          <tr><th scope="row">세션 등락률</th><td>{indexValue(rate * 100)}%</td></tr>
        </tbody>
      </table>
    </figure>
  );
}
