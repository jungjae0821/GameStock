import { useState, type PointerEvent } from "react";
import { Delta } from "../../../components/Delta";
import { useNarrow, useWidth } from "../../../lib/useWidth";
import { useOptionFlow, type OptionFlowBucket, type OptionFlowRange } from "../../../market/derivatives";
import { compactWon, indexValue, signedWon, won } from "../../../market/format";
import { AnimatedNav } from "../../AnimatedNav";

interface Props {
  spot: number;
  prevClose: number;
  changePercent: number;
  /** 스냅샷에 딸려 오는 최근 지수 틱(시각 없음). 거래 흐름을 못 받았을 때 지수 선만이라도 그리는 데 쓴다. */
  recent: number[];
  /** 고른 계약. 지수 축 범위 안에 들어오면 행사가·손익분기 선을 얇게 겹친다. */
  strike: number | null;
  breakeven: number | null;
}

type Metric = "volume" | "net";

const METRICS: Array<{ value: Metric; label: string }> = [{ value: "volume", label: "옵션 거래량" }, { value: "net", label: "순 프리미엄" }];
const RANGES: Array<{ value: OptionFlowRange; label: string; interval: string }> = [
  { value: "1h", label: "1시간", interval: "1분봉" }, { value: "6h", label: "6시간", interval: "5분봉" },
  { value: "1d", label: "1일", interval: "5분봉" }, { value: "1w", label: "1주", interval: "1시간봉" },
];
/** 아래 시각 눈금 간격(초). 한국시간 정각에 맞춘다. */
const TICK_SECONDS: Record<OptionFlowRange, number> = { "1h": 600, "6h": 3600, "1d": 10800, "1w": 86400 };
const KST_OFFSET = 9 * 3600;
const LEFT = 56;
const RIGHT = 76;
const TOP = 14;
const BOTTOM = 28;
const count = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });
const clock = new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Seoul", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
const day = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "numeric", day: "numeric" });

/** 1, 2, 2.5, 5 × 10ⁿ 중 눈금이 4개 안팎이 되는 간격. */
function niceStep(span: number): number {
  const raw = span / 4;
  const power = 10 ** Math.floor(Math.log10(raw));
  return ([1, 2, 2.5, 5, 10].find((unit) => unit * power >= raw) ?? 10) * power;
}

/** 축 눈금. 1만 이상은 만 단위로 줄이되 2.5만 같은 눈금이 반올림되지 않게 소수 한 자리까지 둔다. */
function axisNumber(value: number): string {
  if (Math.abs(value) < 10_000) return count.format(value);
  return `${Number((value / 10_000).toFixed(1)).toLocaleString("ko-KR")}만`;
}

/**
 * Unusual Whales 옵션 흐름 차트처럼 봉마다 콜·풋 체결 수량을 쌓고(왼쪽 축), 그 위에 게임주 지수를 선으로 그린다(오른쪽 축).
 * 순 프리미엄 보기에서는 0을 기준으로 상승 쪽 돈은 위, 하락 쪽 돈은 아래로 세운다.
 * 콜은 오르면 이익이라 적색, 풋은 청색이다(한국 관행. 체인·계산기와 같은 색).
 */
export function OptionFlowChart({ spot, prevClose, changePercent, recent, strike, breakeven }: Props) {
  const [metric, setMetric] = useState<Metric>("volume");
  const [range, setRange] = useState<OptionFlowRange>("1d");
  const { flow, pending, error } = useOptionFlow(range);
  const [frame, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const height = useNarrow() ? 260 : 340;
  const plotWidth = Math.max(0, width - LEFT - RIGHT);
  const plotHeight = height - TOP - BOTTOM;
  const buckets = flow?.buckets ?? [];
  // 거래 흐름을 아직 못 받았으면(서버가 엔드포인트를 모르거나 응답 전) 최근 지수 틱으로 선만 그린다. 막대와 시각 축은 뺀다.
  const lineOnly = !flow;
  const slots = Math.max(1, lineOnly ? recent.length : buckets.length);
  const slotWidth = plotWidth / slots;
  const barWidth = Math.max(1, Math.min(14, slotWidth * 0.64));
  const x = (slot: number) => LEFT + slotWidth * (slot + 0.5);

  // 진행 중인 마지막 봉의 지수는 실시간 값으로 바꿔 끼운다.
  const index: Array<number | null> = lineOnly
    ? (recent.length ? [...recent.slice(0, -1), spot] : [spot])
    : buckets.map((bucket, slot) => slot === buckets.length - 1 ? spot : bucket.index);
  const known = index.filter((value): value is number => value !== null);
  const refs = [strike, breakeven].filter((value): value is number => value !== null);
  const indexLow = Math.min(...known, spot);
  const indexHigh = Math.max(...known, spot);
  const indexPad = Math.max((indexHigh - indexLow) * 0.12, spot * 0.0005);
  const inView = (value: number) => value >= indexLow - indexPad && value <= indexHigh + indexPad;
  const indexStep = niceStep(indexHigh - indexLow + indexPad * 2);
  const indexTop = indexHigh + indexPad;
  const indexBottom = indexLow - indexPad;
  const yIndex = (value: number) => TOP + plotHeight - ((value - indexBottom) / (indexTop - indexBottom || 1)) * plotHeight;
  const indexTicks: number[] = [];
  for (let value = Math.ceil(indexBottom / indexStep) * indexStep; value <= indexTop; value += indexStep) indexTicks.push(value);
  let indexPath = "";
  index.forEach((value, slot) => {
    if (value === null) return;
    indexPath += `${slot && index[slot - 1] !== null ? "L" : "M"}${x(slot).toFixed(1)},${yIndex(value).toFixed(1)}`;
  });

  // 왼쪽 축: 거래량은 0부터 위로, 순 프리미엄은 0을 가운데 두고 위아래로.
  const values = buckets.map((bucket) => metric === "volume" ? bucket.callVolume + bucket.putVolume : bucket.netPremium);
  const hasFlow = buckets.some((bucket) => bucket.callVolume + bucket.putVolume > 0);
  const valueHigh = Math.max(0, ...values);
  const valueLow = metric === "volume" ? 0 : Math.min(0, ...values);
  const valueStep = niceStep(Math.max(valueHigh - valueLow, metric === "volume" ? 4 : 4000));
  const valueTop = Math.max(valueStep, Math.ceil(valueHigh / valueStep) * valueStep);
  const valueBottom = metric === "volume" ? 0 : Math.min(-valueStep, Math.floor(valueLow / valueStep) * valueStep);
  const yValue = (value: number) => TOP + plotHeight - ((value - valueBottom) / (valueTop - valueBottom)) * plotHeight;
  const valueTicks: number[] = [];
  for (let value = valueBottom; value <= valueTop + valueStep / 2; value += valueStep) valueTicks.push(value);

  // 좁은 화면에서는 글자가 겹치지 않게 눈금 간격을 기본값의 배수로 넓힌다(글자 하나에 64px).
  const span = flow ? flow.to - flow.from : 1;
  const tick = TICK_SECONDS[range] * Math.max(1, Math.ceil(64 / ((TICK_SECONDS[range] / span) * plotWidth || 64)));
  const timeTicks: number[] = [];
  if (flow) for (let at = Math.ceil((flow.from + KST_OFFSET) / tick) * tick - KST_OFFSET; at < flow.to; at += tick) timeTicks.push(at);
  const xAt = (epoch: number) => flow ? LEFT + ((epoch - flow.from) / (flow.to - flow.from)) * plotWidth : LEFT;
  const timeLabel = (at: number) => range === "1w" || (at + KST_OFFSET) % 86400 === 0 ? day.format(at * 1000) : clock.format(at * 1000);

  const inspected = hover === null || hover >= buckets.length ? null : buckets[hover];
  const onMove = (event: PointerEvent<HTMLDivElement>) => {
    if (plotWidth <= 0) return;
    const offset = event.clientX - event.currentTarget.getBoundingClientRect().left - LEFT;
    setHover(offset < 0 || offset > plotWidth ? null : Math.min(index.length - 1, Math.floor(offset / slotWidth)));
  };
  const intervalLabel = RANGES.find((item) => item.value === range)?.interval;
  const rangeLabel = RANGES.find((item) => item.value === range)?.label;

  return (
    <section className="opt-flow" aria-labelledby="opt-flow-title">
      <header className="opt-flow-head">
        <div className="opt-flow-quote">
          <h2 id="opt-flow-title">게임주 지수</h2>
          <strong className="num">{indexValue(spot)}</strong>
          <Delta change={spot - prevClose} ratio={changePercent / 100} showAmount={false} />
        </div>
        <div className="opt-flow-controls">
          <AnimatedNav as="div" fill activeKey={metric} className="nx-seg" role="group" aria-label="차트 값">
            {METRICS.map((item) => (
              <button type="button" key={item.value} className={metric === item.value ? "is-active" : undefined}
                aria-pressed={metric === item.value} onClick={() => setMetric(item.value)}>{item.label}</button>
            ))}
          </AnimatedNav>
          <AnimatedNav as="div" fill activeKey={range} className="nx-seg" role="group" aria-label="기간">
            {RANGES.map((item) => (
              <button type="button" key={item.value} className={range === item.value ? "is-active" : undefined}
                aria-pressed={range === item.value} onClick={() => { setRange(item.value); setHover(null); }}>{item.label}</button>
            ))}
          </AnimatedNav>
        </div>
      </header>

      <dl className="opt-flow-summary" aria-busy={pending || !flow}>
        <div><dt>최근 {rangeLabel}</dt><dd className="num">{intervalLabel}</dd></div>
        <div><dt>거래량</dt><dd className="num">{flow ? `${count.format(flow.callVolume + flow.putVolume)}계약` : "—"}</dd></div>
        <div><dt>프리미엄</dt><dd className="num">{flow ? compactWon(flow.callPremium + flow.putPremium) : "—"}</dd></div>
        <div><dt>순 프리미엄</dt><dd className={`num ${flow && flow.netPremium > 0 ? "is-up" : flow && flow.netPremium < 0 ? "is-down" : ""}`}>{flow ? signedWon(flow.netPremium) : "—"}</dd></div>
      </dl>

      <ul className="opt-flow-legend">
        <li className="is-put"><b>풋</b> <span className="num">{flow ? `${count.format(flow.putVolume)}계약 | ${compactWon(flow.putPremium)}` : "—"}</span></li>
        <li className="is-call"><b>콜</b> <span className="num">{flow ? `${count.format(flow.callVolume)}계약 | ${compactWon(flow.callPremium)}` : "—"}</span></li>
        <li className="is-index"><b>게임주 지수</b></li>
        {refs.some(inView) && <li className="is-ref"><b>고른 계약</b> 행사가·손익분기</li>}
      </ul>

      <div className={`opt-flow-frame${pending ? " is-pending" : ""}`} ref={frame} style={{ height }} onPointerMove={onMove} onPointerLeave={() => setHover(null)}>
        {plotWidth > 0 && (
          <svg width={width} height={height} role="img"
            aria-label={flow
              ? `최근 ${rangeLabel} 옵션 ${metric === "volume" ? "거래량" : "순 프리미엄"}과 게임주 지수. 콜 ${flow.callVolume}계약, 풋 ${flow.putVolume}계약.`
              : `게임주 지수 최근 흐름. 현재 ${indexValue(spot)}`}>
            <g className="opt-flow-grid" aria-hidden="true">
              {valueTicks.map((value) => (
                <g key={value}>
                  <line x1={LEFT} x2={LEFT + plotWidth} y1={Math.round(yValue(value)) + 0.5} y2={Math.round(yValue(value)) + 0.5}
                    className={value === 0 ? "is-zero" : undefined} />
                  {hasFlow && <text x={LEFT - 10} y={yValue(value)} dy="0.32em" textAnchor="end">{axisNumber(value)}</text>}
                </g>
              ))}
              {known.length > 0 && indexTicks.map((value) => (
                <text key={value} x={LEFT + plotWidth + 10} y={yIndex(value)} dy="0.32em">{indexValue(value)}</text>
              ))}
              {timeTicks.map((at) => (
                <text key={at} x={xAt(at)} y={height - 8} textAnchor="middle">{timeLabel(at)}</text>
              ))}
            </g>
            {hover !== null && <rect className="opt-flow-hover" x={x(hover) - slotWidth / 2} y={TOP} width={slotWidth} height={plotHeight} />}
            <g className="opt-flow-bars">
              {buckets.map((bucket, slot) => {
                const left = x(slot) - barWidth / 2;
                if (metric === "net") {
                  if (!bucket.netPremium) return null;
                  const top = Math.min(yValue(0), yValue(bucket.netPremium));
                  return <rect key={bucket.at} className={bucket.netPremium > 0 ? "is-call" : "is-put"} x={left} y={top}
                    width={barWidth} height={Math.max(1, Math.abs(yValue(bucket.netPremium) - yValue(0)))} />;
                }
                if (!bucket.callVolume && !bucket.putVolume) return null;
                const callTop = yValue(bucket.callVolume);
                const putTop = yValue(bucket.callVolume + bucket.putVolume);
                return (
                  <g key={bucket.at}>
                    {bucket.callVolume > 0 && <rect className="is-call" x={left} y={callTop} width={barWidth} height={Math.max(1, yValue(0) - callTop)} />}
                    {bucket.putVolume > 0 && <rect className="is-put" x={left} y={putTop} width={barWidth} height={Math.max(1, callTop - putTop)} />}
                  </g>
                );
              })}
            </g>
            {strike !== null && inView(strike) && <line className="opt-flow-ref" x1={LEFT} x2={LEFT + plotWidth} y1={yIndex(strike)} y2={yIndex(strike)} />}
            {breakeven !== null && inView(breakeven) && <line className="opt-flow-ref is-breakeven" x1={LEFT} x2={LEFT + plotWidth} y1={yIndex(breakeven)} y2={yIndex(breakeven)} />}
            <path className="opt-flow-index" d={indexPath} />
            {index.length > 0 && (
              <g className="opt-flow-live">
                <circle cx={x(index.length - 1)} cy={yIndex(spot)} r={3.5} />
                <rect x={LEFT + plotWidth + 2} y={yIndex(spot) - 11} width={RIGHT - 4} height={22} rx={4} />
                <text x={LEFT + plotWidth + 10} y={yIndex(spot)} dy="0.34em">{indexValue(spot)}</text>
              </g>
            )}
            {hover !== null && index[hover] !== null && <circle className="opt-flow-dot" cx={x(hover)} cy={yIndex(index[hover]!)} r={4.5} />}
          </svg>
        )}
        {flow && !hasFlow && !pending && <p className="opt-flow-empty">최근 {rangeLabel} 동안 옵션 체결이 없어요.</p>}
        {lineOnly && <p className="opt-flow-empty is-top">{error ? "옵션 거래 기록을 받지 못해 최근 지수 흐름만 그렸어요. 15초마다 다시 받아요." : "옵션 거래 기록을 불러오는 중이에요."}</p>}
        {lineOnly && hover !== null && index[hover] !== null && (
          <div className={`opt-flow-tip${x(hover) > LEFT + plotWidth * 0.55 ? " is-flip" : ""}`} style={{ left: x(hover) }} role="status">
            <p><span className="opt-flow-swatch is-index" />지수 <b className="num">{indexValue(index[hover]!)}</b></p>
          </div>
        )}
        {inspected && hover !== null && (
          <FlowTooltip bucket={inspected} index={index[hover]} interval={flow!.intervalSeconds}
            left={x(hover)} flip={x(hover) > LEFT + plotWidth * 0.55} />
        )}
      </div>
      <p className="opt-flow-foot">모의 시장 사용자 체결만 셉니다. 순 프리미엄은 (콜 매수 − 콜 청산) − (풋 매수 − 풋 청산)이며 만기 결제는 빠집니다.</p>
    </section>
  );
}

function FlowTooltip({ bucket, index, interval, left, flip }: {
  bucket: OptionFlowBucket; index: number | null; interval: number; left: number; flip: boolean;
}) {
  const volume = bucket.callVolume + bucket.putVolume;
  const ratio = bucket.callVolume > 0 ? (bucket.putVolume / bucket.callVolume).toFixed(2) : bucket.putVolume > 0 ? "∞" : "—";
  return (
    <div className={`opt-flow-tip${flip ? " is-flip" : ""}`} style={{ left }} role="status">
      <p className="num">{day.format(bucket.at * 1000)} {clock.format(bucket.at * 1000)} – {clock.format((bucket.at + interval) * 1000)}</p>
      <p><span className="opt-flow-swatch is-put" />풋 <b className="num">{count.format(bucket.putVolume)}</b>
        <span className="opt-flow-swatch is-call" />콜 <b className="num">{count.format(bucket.callVolume)}</b></p>
      <p><span className="opt-flow-swatch is-index" />지수 <b className="num">{index === null ? "기록 없음" : indexValue(index)}</b></p>
      <dl>
        <div><dt>풋/콜 비율</dt><dd className="num">{ratio}</dd></div>
        <div><dt>봉 거래량</dt><dd className="num">{count.format(volume)}계약</dd></div>
        <div><dt>봉 프리미엄</dt><dd className="num">{won(bucket.callPremium + bucket.putPremium)}</dd></div>
        <div><dt>순 프리미엄</dt><dd className={`num ${bucket.netPremium > 0 ? "is-up" : bucket.netPremium < 0 ? "is-down" : ""}`}>{signedWon(bucket.netPremium)}</dd></div>
      </dl>
    </div>
  );
}
