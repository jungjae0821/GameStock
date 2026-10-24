import { memo, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { PointerEvent } from "react";
import { apiFetch } from "../lib/api";
import { indexValue, serverTimestamp, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";
import { chartGeometry, clamp, nearestSampleIndex, normalizeChartSamples } from "../market/chartGeometry";
import type { ChartCandle, ChartGeometry, ChartMode, ChartSample } from "../market/chartGeometry";
import { CANDLE_INTERVAL_MS, collectLiveCandles, mergeLiveCandles } from "../market/liveCandles";
import type { LiveCandle } from "../market/liveCandles";

export type ChartRange = "realtime" | "1h" | "6h" | "12h" | "1d" | "1w" | "1m" | "1y";
type PricePoint = { recordedAt: string; price: number };
type Marker = { at: number; label: string };
type Inspection = { at: number; y: number };
const NO_MARKERS: Marker[] = [];
const NO_SAMPLES: ChartSample[] = [];
const NO_LIVE_CANDLES: LiveCandle[] = [];
const RANGE_OPTIONS: Array<{ value: ChartRange; label: string; interval: string }> = [
  { value: "realtime", label: "실시간", interval: "1분봉" }, { value: "1h", label: "1시간", interval: "1분봉" },
  { value: "6h", label: "6시간", interval: "5분봉" }, { value: "12h", label: "12시간", interval: "10분봉" },
  { value: "1d", label: "1일", interval: "30분봉" }, { value: "1w", label: "1주", interval: "2시간봉" },
  { value: "1m", label: "1개월", interval: "일봉" }, { value: "1y", label: "1년", interval: "주봉" },
];
const priceFormatter = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });
const timeFormatter = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
});
const minuteFormatter = new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Seoul", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
const secondFormatter = new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Seoul", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" });
const dateFormatter = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "numeric", day: "numeric" });
const monthFormatter = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", year: "2-digit", month: "numeric" });

/** Pointer inspection only updates the crosshair and readout; the recorded bars stay memoized. */
const ChartDrawing = memo(function ChartDrawing({ samples, geometry: g, mode, markers, currentPrice, prevClose, code, rangeLabel, tone, width, height }: {
  samples: ChartSample[]; geometry: ChartGeometry; mode: ChartMode; markers: Marker[];
  currentPrice: number; prevClose: number; code: string; rangeLabel: string; tone: string; width: number; height: number;
}) {
  const clipId = useId();
  const firstAt = samples[0]?.at ?? 0;
  const lastAt = samples.at(-1)?.at ?? firstAt;
  const duration = lastAt - firstAt;
  const formatter = duration < 120_000 ? secondFormatter : duration <= 86_400_000 ? minuteFormatter : duration <= 40 * 86_400_000 ? dateFormatter : monthFormatter;
  const tickIndices: number[] = [];
  const stride = Math.max(1, Math.ceil(samples.length / Math.max(2, Math.floor(g.plotWidth / 110))));
  for (let index = 0; index < samples.length; index += stride) {
    const previous = tickIndices.at(-1);
    if (previous === undefined || g.xAtIndex(index) - g.xAtIndex(previous) >= 80) tickIndices.push(index);
  }
  if (samples.length > 1 && (tickIndices.length === 0 || g.xAtIndex(samples.length - 1) - g.xAtIndex(tickIndices.at(-1)!) >= 80)) tickIndices.push(samples.length - 1);
  const linePath = mode === "line" ? samples.map((sample, index) => `${index ? "L" : "M"}${g.xAtIndex(index).toFixed(2)},${g.y(sample.price).toFixed(2)}`).join(" ") : "";
  const prevY = g.y(prevClose);
  return <svg className="market-chart-drawing" viewBox={`0 0 ${width} ${height}`} role="img"
    aria-label={`${code} ${rangeLabel} ${mode === "candle" ? "캔들" : "선형"} 차트. 현재가 ${won(currentPrice)}`}>
    <defs><clipPath id={clipId}><rect width={g.plotWidth} height={g.plotHeight} /></clipPath></defs>
    <g className="market-chart-grid" aria-hidden="true">
      {g.scale.ticks.map(price => <line key={price} x1={0} x2={g.plotWidth} y1={Math.round(g.y(price)) + 0.5} y2={Math.round(g.y(price)) + 0.5} />)}
      {tickIndices.map(index => <line key={index} x1={Math.round(g.xAtIndex(index)) + 0.5} x2={Math.round(g.xAtIndex(index)) + 0.5} y1={0} y2={g.plotHeight} />)}
    </g>
    <g clipPath={`url(#${clipId})`}>
      {prevY > 0 && prevY < g.plotHeight && <line className="market-chart-reference" x1={0} x2={g.plotWidth} y1={prevY} y2={prevY} />}
      {mode === "line" ? <path className={`market-chart-line is-${tone}`} d={linePath} /> : samples.map((sample, index) => {
        const bar = sample.candle;
        if (!bar) return null;
        const center = Math.round(g.xAtIndex(index)) + 0.5;
        const openY = Math.round(g.y(bar.openPrice));
        const closeY = Math.round(g.y(bar.closePrice));
        return <g key={sample.at} className={`market-chart-candle is-${bar.closePrice >= bar.openPrice ? "up" : "down"}`} data-at={sample.at}>
          <line x1={center} x2={center} y1={Math.round(g.y(bar.highPrice))} y2={Math.round(g.y(bar.lowPrice))} />
          <rect x={Math.round(center - g.bodyWidth / 2)} y={Math.min(openY, closeY)} width={g.bodyWidth} height={Math.max(1, Math.abs(closeY - openY))} />
        </g>;
      })}
      {samples.length > 0 && <line className={`market-chart-live-line is-${tone}`} x1={0} x2={g.plotWidth} y1={g.y(currentPrice)} y2={g.y(currentPrice)} />}
      {samples.length > 0 && markers.filter(marker => marker.at >= firstAt).map(marker => {
        const index = nearestSampleIndex(samples, marker.at);
        return <circle key={`${marker.at}-${marker.label}`} className="market-chart-news" cx={g.xAtIndex(index)}
          cy={g.y(marker.at > lastAt ? currentPrice : samples[index].price)} r={3.5}><title>{marker.label}</title></circle>;
      })}
    </g>
    <g className="market-chart-axis" aria-hidden="true">
      <line x1={g.plotWidth + 0.5} x2={g.plotWidth + 0.5} y1={0} y2={height} />
      <line x1={0} x2={width} y1={g.plotHeight + 0.5} y2={g.plotHeight + 0.5} />
      {g.scale.ticks.map(price => <text key={price} x={g.plotWidth + 8} y={clamp(g.y(price), 9, g.plotHeight - 8)} dominantBaseline="central">{priceFormatter.format(price)}</text>)}
      {tickIndices.map(index => <text key={index} x={clamp(g.xAtIndex(index), 30, g.plotWidth - 30)} y={g.plotHeight + 18} textAnchor="middle">{formatter.format(samples[index].at)}</text>)}
    </g>
  </svg>;
});

export function SeriesChart({ code, tone, markers = NO_MARKERS, fitMarkers = false, initialMode = "line" }: {
  code: string; tone?: "up" | "down" | "flat"; markers?: Marker[]; fitMarkers?: boolean; initialMode?: ChartMode;
}) {
  const snapshot = useMarket();
  const quote = snapshot.quotes[code];
  const helpId = useId();
  const [mode, setMode] = useState<ChartMode>(initialMode);
  const [range, setRange] = useState<ChartRange>(initialMode === "candle" ? "1h" : "6h");
  const [data, setData] = useState<{ key: string; samples: ChartSample[] }>({ key: "", samples: [] });
  const [live, setLive] = useState<{ key: string; bars: LiveCandle[] }>({ key: "", bars: [] });
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [inspection, setInspection] = useState<Inspection | null>(null);
  const [size, setSize] = useState({ width: 720, height: 360 });
  const plot = useRef<HTMLDivElement>(null);
  const pointerFrame = useRef(0);
  const pendingPointer = useRef<{ x: number; y: number } | null>(null);
  const autoFit = useRef(fitMarkers);
  const earliestMarker = markers.length ? Math.min(...markers.map(marker => marker.at)) : null;
  const dataKey = `${code}:${mode}:${range}`;
  const recordedSamples = data.key === dataKey ? data.samples : NO_SAMPLES;
  // Apply this message during the same render as the quote; the effect only retains the accumulated extrema.
  const liveBars = useMemo(() => {
    if (mode !== "candle") return NO_LIVE_CANDLES;
    const previous = live.key === dataKey ? live.bars : NO_LIVE_CANDLES;
    return quote?.prints.length ? collectLiveCandles(previous, quote.prints, CANDLE_INTERVAL_MS[range]) : previous;
  }, [mode, live, dataKey, quote?.prints, range]);
  const samples = useMemo(() => mode === "candle"
    ? mergeLiveCandles(recordedSamples, liveBars) : recordedSamples, [recordedSamples, mode, liveBars]);
  const currentPrice = quote?.price ?? 1;
  const geometry = useMemo(() => chartGeometry(samples, mode, size.width, size.height, currentPrice), [samples, mode, size.width, size.height, currentPrice]);
  const pointerModel = useRef({ samples, geometry });
  pointerModel.current = { samples, geometry };
  const available = Boolean(quote);

  useLayoutEffect(() => {
    if (mode !== "candle") return;
    setLive(previous => previous.key === dataKey && previous.bars === liveBars ? previous : { key: dataKey, bars: liveBars });
  }, [mode, dataKey, liveBars]);

  useLayoutEffect(() => {
    const node = plot.current;
    if (!node) return;
    const measure = ({ width, height }: { width: number; height: number }) => {
      // Keep the SVG viewBox equal to its CSS size, including fractional pixels.
      // clientWidth/clientHeight round these and can rescale all text and strokes.
      if (width > 0 && height > 0) setSize(previous => previous.width === width && previous.height === height ? previous : { width, height });
    };
    measure(node.getBoundingClientRect());
    const observer = new ResizeObserver(entries => {
      const entry = entries[0];
      if (entry) measure(entry.contentRect);
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, [available]);

  useEffect(() => () => window.cancelAnimationFrame(pointerFrame.current), []);
  useEffect(() => {
    let active = true;
    let pending = false;
    setLoading(true);
    setFailed(false);
    const load = async () => {
      if (pending) return;
      pending = true;
      try {
        let next: ChartSample[];
        if (mode === "line" && range !== "1m" && range !== "1y") {
          const historyRange = range === "realtime" ? "30m" : range === "1d" ? "24h" : range;
          const points = await apiFetch<PricePoint[]>(`/api/stocks/${code}/history?range=${historyRange}`, { auth: false });
          next = points.map(point => ({ at: serverTimestamp(point.recordedAt), price: point.price }));
        } else {
          const candles = await apiFetch<ChartCandle[]>(`/api/stocks/${code}/chart?range=${range === "realtime" ? "1h" : range}`, { auth: false });
          next = candles.map(candle => ({ at: serverTimestamp(candle.recordedAt), price: candle.closePrice, candle }));
        }
        if (!active) return;
        const sorted = normalizeChartSamples(next);
        const wider = RANGE_OPTIONS[RANGE_OPTIONS.findIndex(option => option.value === range) + 1]?.value;
        if (autoFit.current && earliestMarker !== null && sorted.length > 1 && earliestMarker < sorted[0].at && wider) {
          setRange(wider);
          return;
        }
        setData({ key: dataKey, samples: sorted });
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
  }, [code, mode, range, dataKey, earliestMarker]);

  const clearInspection = () => {
    window.cancelAnimationFrame(pointerFrame.current);
    pointerFrame.current = 0;
    pendingPointer.current = null;
    setInspection(null);
  };
  const pointAtPointer = (event: PointerEvent<HTMLDivElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    pendingPointer.current = { x: event.clientX - bounds.left, y: event.clientY - bounds.top };
    if (pointerFrame.current) return;
    pointerFrame.current = window.requestAnimationFrame(() => {
      pointerFrame.current = 0;
      const point = pendingPointer.current;
      const { samples: currentSamples, geometry: g } = pointerModel.current;
      if (!point || !currentSamples.length) return;
      if (point.x < 0 || point.x > g.plotWidth || point.y < 0 || point.y > g.plotHeight) { setInspection(null); return; }
      const index = g.indexAtX(point.x);
      const y = point.y / g.plotHeight;
      const at = currentSamples[index].at;
      setInspection(previous => previous?.at === at && previous.y === y ? previous : { at, y });
    });
  };

  if (!quote) return null;
  const hoveredIndex = inspection ? nearestSampleIndex(samples, inspection.at) : -1;
  const hovered = hoveredIndex >= 0 && samples[hoveredIndex].at === inspection?.at ? samples[hoveredIndex] : undefined;
  const selected = hovered ?? samples.at(-1);
  const selectedBar = mode === "candle" ? selected?.candle : undefined;
  const ratio = sessionRate(quote);
  const chartTone = tone ?? (ratio > 0 ? "up" : ratio < 0 ? "down" : "flat");
  const selectedTone = selectedBar ? (selectedBar.closePrice >= selectedBar.openPrice ? "up" : "down") : chartTone;
  const rangeOption = RANGE_OPTIONS.find(option => option.value === range)!;
  const markerOutside = markers.length > 0 && samples.length > 1 && markers.every(marker => marker.at < samples[0].at);
  const crossY = inspection ? clamp(inspection.y * geometry.plotHeight, 0, geometry.plotHeight) : 0;
  const crossX = hovered ? geometry.xAtIndex(hoveredIndex) : 0;
  const status = loading ? "차트 불러오는 중…" : failed ? (samples.length ? "갱신 지연 · 마지막 기록을 표시합니다." : "가격 기록을 불러오지 못했습니다.")
    : !samples.length ? "이 기간에 저장된 가격 기록이 없습니다." : markerOutside ? "이전 뉴스 시점은 더 긴 기간에서 볼 수 있어요." : "";

  return <figure className={`stock-series-chart market-chart mode-${mode}`}>
    <div className="market-chart-toolbar">
      <div className="market-chart-ranges" role="group" aria-label="차트 기간">
        {RANGE_OPTIONS.map(option => <button key={option.value} type="button" className={range === option.value ? "is-active" : undefined}
          aria-pressed={range === option.value} onClick={() => { clearInspection(); setRange(option.value); autoFit.current = false; }}>{option.label}</button>)}
      </div>
      <div className="market-chart-modes" role="group" aria-label="차트 표현 방식">
        {([["line", "선"], ["candle", "캔들"]] as const).map(([value, label]) => <button key={value} type="button"
          className={mode === value ? "is-active" : undefined} aria-pressed={mode === value}
          onClick={() => { clearInspection(); setMode(value); }}>{label}</button>)}
      </div>
    </div>
    <div className="market-chart-readout" aria-live="off">
      {/* 종목 코드는 화면 머리에, 통화는 차트 아래 줄에 이미 있다. 여기에는 봉 간격만 둔다. */}
      <div className="market-chart-symbol"><span>{mode === "candle" ? rangeOption.interval : "가격"}</span>
        {selected && <time className="num" dateTime={new Date(selected.at).toISOString()}>{timeFormatter.format(selected.at)}</time>}
      </div>
      <dl className={`market-chart-ohlc num is-${selectedTone}`}>
        {selectedBar ? <>
          <div><dt><abbr title="시가">시</abbr></dt><dd>{priceFormatter.format(selectedBar.openPrice)}</dd></div>
          <div><dt><abbr title="고가">고</abbr></dt><dd>{priceFormatter.format(selectedBar.highPrice)}</dd></div>
          <div><dt><abbr title="저가">저</abbr></dt><dd>{priceFormatter.format(selectedBar.lowPrice)}</dd></div>
          <div><dt><abbr title="종가">종</abbr></dt><dd>{priceFormatter.format(selectedBar.closePrice)}</dd></div>
        </> : <div><dt>{hovered ? "선택 가격" : "마지막 기록"}</dt><dd>{selected ? priceFormatter.format(selected.price) : "—"}</dd></div>}
      </dl>
    </div>
    <div className="market-chart-plot" ref={plot} role="group" aria-label={`${code} 가격 차트 탐색`} aria-describedby={helpId}
      tabIndex={samples.length ? 0 : -1} onPointerMove={pointAtPointer} onPointerDown={pointAtPointer}
      onPointerLeave={event => { if (event.pointerType === "mouse") clearInspection(); }} onPointerCancel={clearInspection}
      onFocus={() => { const last = samples.at(-1); if (last) setInspection(previous => previous ?? { at: last.at, y: geometry.y(last.price) / geometry.plotHeight }); }}
      onBlur={clearInspection} onKeyDown={event => {
        if (!samples.length) return;
        const current = hovered ? hoveredIndex : samples.length - 1;
        let next = current;
        if (event.key === "ArrowLeft") next = Math.max(0, current - 1);
        else if (event.key === "ArrowRight") next = Math.min(samples.length - 1, current + 1);
        else if (event.key === "Home") next = 0;
        else if (event.key === "End") next = samples.length - 1;
        else if (event.key === "Escape") { event.preventDefault(); clearInspection(); return; }
        else return;
        event.preventDefault();
        window.cancelAnimationFrame(pointerFrame.current); pointerFrame.current = 0;
        setInspection({ at: samples[next].at, y: geometry.y(samples[next].price) / geometry.plotHeight });
      }}>
      <ChartDrawing samples={samples} geometry={geometry} mode={mode} markers={markers} currentPrice={currentPrice} prevClose={quote.prevClose}
        code={code} rangeLabel={rangeOption.label} tone={chartTone} width={size.width} height={size.height} />
      {samples.length > 0 && <span className={`market-chart-price-tag num is-${chartTone}`} aria-hidden="true"
        style={{ top: clamp(geometry.y(currentPrice), 10, geometry.plotHeight - 10), width: geometry.axisWidth }}>{priceFormatter.format(currentPrice)}</span>}
      {hovered && <div className="market-chart-crosshair" aria-hidden="true">
        <span className="market-chart-cross-v" style={{ left: crossX, height: geometry.plotHeight }} />
        <span className="market-chart-cross-h" style={{ top: crossY, width: geometry.plotWidth }} />
        <span className="market-chart-cross-price num" style={{ top: clamp(crossY, 10, geometry.plotHeight - 10), width: geometry.axisWidth }}>{priceFormatter.format(geometry.priceAtY(crossY))}</span>
        <span className="market-chart-cross-time num" style={{ top: geometry.plotHeight, left: clamp(crossX, 76, Math.max(76, geometry.plotWidth - 76)) }}>{timeFormatter.format(hovered.at)}</span>
        {mode === "line" && <span className="market-chart-cross-dot" style={{ left: crossX, top: geometry.y(hovered.price) }} />}
      </div>}
      {!samples.length && <p className="market-chart-empty" role="status">{status}</p>}
    </div>
    <figcaption className="market-chart-footer"><span role="status">{samples.length ? status : ""}</span><span className="num">UTC+9 · KRW</span></figcaption>
    <p id={helpId} className="vh">마우스나 터치로 가격과 시각을 확인합니다. 키보드 좌우 방향키로 기록을 이동하고 Home·End로 처음과 끝을 선택합니다.</p>
    <table className="vh"><caption>{`${code} ${rangeOption.label} 차트 요약`}</caption><tbody>
      <tr><th scope="row">기간 최고</th><td>{samples.length ? won(geometry.max) : "기록 없음"}</td></tr>
      <tr><th scope="row">기간 최저</th><td>{samples.length ? won(geometry.min) : "기록 없음"}</td></tr>
      <tr><th scope="row">전일 종가</th><td>{won(quote.prevClose)}</td></tr>
      <tr><th scope="row">현재가</th><td>{won(currentPrice)}</td></tr>
      <tr><th scope="row">마지막 저장 가격</th><td>{recordedSamples.length ? won(recordedSamples.at(-1)!.price) : "기록 없음"}</td></tr>
      <tr><th scope="row">세션 등락률</th><td>{indexValue(ratio * 100)}%</td></tr>
    </tbody></table>
  </figure>;
}
