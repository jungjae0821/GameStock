import { useId, useMemo, useState, type PointerEvent } from "react";
import { useNarrow, useWidth } from "../../../lib/useWidth";
import { indexValue, rate, signedWon, won } from "../../../market/format";
import {
  CONTRACT_MULTIPLIER, legBreakeven, legExitValue, optionDelta, optionPoints, probabilityBeyond, type OptionLeg,
} from "../../../market/optionMath";
import { AnimatedNav } from "../../AnimatedNav";

interface Props {
  leg: OptionLeg;
  spot: number;
  /** 스냅샷의 서버 시각(초). 열 시각을 1초마다 흔들지 않도록 시세가 올 때만 바뀐다. */
  nowEpoch: number;
  volatility: number;
  /** 시간가치 감소를 읽는 단위(초). 회차마다 다르다. */
  decayUnit: number;
}

type View = "table" | "chart" | "breakeven";
type Mode = "rate" | "pnl" | "value";

const VIEWS: Array<{ value: View; label: string }> = [
  { value: "table", label: "표" }, { value: "chart", label: "차트" }, { value: "breakeven", label: "손익분기" },
];
const MODES: Array<{ value: Mode; label: string }> = [
  { value: "rate", label: "수익률" }, { value: "pnl", label: "손익" }, { value: "value", label: "평가액" },
];
const YEAR_SECONDS = 365 * 24 * 60 * 60;
const KST_OFFSET = 9 * 3600;
const COLUMN_STEPS = [60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200, 86400];
const ROW_HALF = 6;
const CHART_SAMPLES = 120;
const clock = new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Seoul", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
const day = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "numeric", day: "numeric", weekday: "short" });
const plain = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });

/** 1, 2, 2.5, 5 × 10ⁿ 중 raw 이상인 가장 작은 간격. */
function niceStep(raw: number): number {
  const power = 10 ** Math.floor(Math.log10(raw));
  return ([1, 2, 2.5, 5, 10].find((unit) => unit * power >= raw) ?? 10) * power;
}

/** 지금부터 만기까지를 한국시간 정각 경계로 나눈 열. 첫 열은 지금, 마지막 열은 만기다. */
function timeColumns(now: number, expiry: number, max: number): number[] {
  const span = expiry - now;
  if (span <= 0) return [expiry];
  const interval = COLUMN_STEPS.find((step) => Math.ceil(span / step) <= max - 1) ?? 86400 * Math.ceil(span / 86400 / (max - 1));
  const columns = [now];
  for (let at = Math.ceil((now + KST_OFFSET) / interval) * interval - KST_OFFSET; at < expiry; at += interval) {
    if (at - now >= interval * 0.35 && expiry - at >= interval * 0.35) columns.push(at);
  }
  columns.push(expiry);
  return columns;
}

function untilLabel(seconds: number): string {
  if (seconds < 3600) return `${Math.max(1, Math.round(seconds / 60))}분 후`;
  if (seconds < 86400) {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.round((seconds % 3600) / 60);
    return minutes ? `${hours}시간 ${minutes}분 후` : `${hours}시간 후`;
  }
  const days = Math.floor(seconds / 86400);
  const hours = Math.round((seconds % 86400) / 3600);
  return hours ? `${days}일 ${hours}시간 후` : `${days}일 후`;
}

function decayLabel(seconds: number): string {
  if (seconds >= 86400) return "하루";
  if (seconds >= 3600) return `${seconds / 3600}시간`;
  return `${seconds / 60}분`;
}

/** 지금 지수를 가운데 둔 행. 위아래 6칸은 깔끔한 간격의 지수, 현재 지수는 따로 한 줄 끼운다. */
function priceRows(spot: number, step: number): Array<{ price: number; spot: boolean }> {
  const rows: Array<{ price: number; spot: boolean }> = [];
  const above = Math.floor(spot / step) * step + step * ROW_HALF;
  for (let index = 0; index <= ROW_HALF * 2; index += 1) {
    const price = above - step * index;
    if (Math.abs(price - spot) < step * 0.15) continue;
    if (price > 0) rows.push({ price, spot: false });
  }
  rows.push({ price: spot, spot: true });
  return rows.sort((left, right) => right.price - left.price);
}

/** 손익률 절댓값으로 나눈 칸 색 단계. 1% 미만은 무채색. */
function heat(ratio: number): string {
  const size = Math.abs(ratio);
  if (size < 0.01) return "is-flat";
  const level = size < 0.25 ? 1 : size < 0.5 ? 2 : size < 0.9 ? 3 : 4;
  return `is-${ratio > 0 ? "up" : "down"} is-l${level}`;
}

/** 칸이 좁아 원 단위는 기호 없이 자릿수만 쓴다. 100만 이상은 만 단위로 줄인다. */
function compact(value: number, signed: boolean): string {
  const sign = signed && value > 0 ? "+" : value < 0 ? "-" : "";
  const size = Math.abs(Math.round(value));
  return `${sign}${size >= 1_000_000 ? `${plain.format(Math.round(size / 10_000))}만` : plain.format(size)}`;
}

function ratioText(ratio: number): string {
  const percent = ratio * 100;
  if (Math.abs(percent) < 0.05) return "0%";
  const digits = Math.abs(percent) >= 100 ? 0 : 1;
  return `${percent > 0 ? "+" : "-"}${Math.abs(percent).toFixed(digits)}%`;
}

/**
 * Unusual Whales 옵션 수익 계산기처럼 지수(행) × 시각(열)마다 정리 손익을 칠한다.
 * 같은 식으로 손익 곡선(차트)과 시각별 손익분기도 보여 준다.
 */
export function OptionProfitCalculator({ leg, spot, nowEpoch, volatility, decayUnit }: Props) {
  const [view, setView] = useState<View>("table");
  const [mode, setMode] = useState<Mode>("rate");
  const narrow = useNarrow();
  const secondsLeft = Math.max(0, leg.expiryEpoch - nowEpoch);
  const expiryBreakeven = legBreakeven(leg, leg.expiryEpoch, volatility);

  const { columns, rows } = useMemo(() => {
    const move = spot * volatility * Math.sqrt(Math.max(1, secondsLeft) / YEAR_SECONDS);
    const reach = Math.max(move * 2.2, Math.abs((expiryBreakeven ?? leg.strike) - spot) * 1.35, Math.abs(leg.strike - spot) * 1.35, spot * 0.003);
    return { columns: timeColumns(nowEpoch, leg.expiryEpoch, narrow ? 6 : 9), rows: priceRows(spot, niceStep(reach / ROW_HALF)) };
  }, [spot, volatility, secondsLeft, expiryBreakeven, leg.strike, leg.expiryEpoch, nowEpoch, narrow]);

  const delta = optionDelta(spot, leg.strike, secondsLeft, volatility, leg.call) * CONTRACT_MULTIPLIER * leg.quantity;
  const decayAt = Math.min(leg.expiryEpoch, nowEpoch + decayUnit);
  const decay = (optionPoints(spot, leg.strike, secondsLeft, volatility, leg.call)
    - optionPoints(spot, leg.strike, leg.expiryEpoch - decayAt, volatility, leg.call)) * CONTRACT_MULTIPLIER * leg.quantity;
  const chance = expiryBreakeven === null ? 0 : probabilityBeyond(spot, expiryBreakeven, secondsLeft, volatility, leg.call);
  const maxProfit = leg.call ? null : leg.strike * CONTRACT_MULTIPLIER * leg.quantity - leg.cost;

  const cell = (price: number, at: number) => {
    const value = legExitValue(leg, price, at, volatility);
    const pnl = value - leg.cost;
    const ratio = leg.cost > 0 ? pnl / leg.cost : 0;
    const text = mode === "rate" ? ratioText(ratio) : mode === "pnl" ? compact(pnl, true) : compact(value, false);
    return { ratio, text, title: `지수 ${indexValue(price)} · 평가액 ${won(value)} · 손익 ${signedWon(pnl)} (${ratioText(ratio)})` };
  };

  const head = (at: number, index: number) => {
    const last = index === columns.length - 1;
    const multiDay = leg.expiryEpoch - nowEpoch > 86400;
    return (
      <th key={at} scope="col" className={last ? "is-expiry" : undefined}>
        <span className="opt-calc-col-main">{index === 0 ? "지금" : last ? "만기" : untilLabel(at - nowEpoch)}</span>
        <span className="opt-calc-col-sub num">{multiDay ? `${day.format(at * 1000)} ` : ""}{clock.format(at * 1000)}</span>
      </th>
    );
  };

  return (
    <section className="derivative-card opt-calc" aria-labelledby="opt-calc-title">
      <header className="opt-calc-head">
        <div>
          <h2 id="opt-calc-title">수익 계산기</h2>
          <p>지수와 시각마다 지금 정리하면 받을 손익을 미리 봅니다.</p>
        </div>
        <div className="opt-calc-controls">
          <AnimatedNav as="div" fill activeKey={view} className="nx-seg" role="group" aria-label="계산기 보기">
            {VIEWS.map((item) => (
              <button type="button" key={item.value} className={view === item.value ? "is-active" : undefined}
                aria-pressed={view === item.value} onClick={() => setView(item.value)}>{item.label}</button>
            ))}
          </AnimatedNav>
          {view === "table" && (
            <AnimatedNav as="div" fill activeKey={mode} className="nx-seg opt-calc-modes" role="group" aria-label="표시 값">
              {MODES.map((item) => (
                <button type="button" key={item.value} className={mode === item.value ? "is-active" : undefined}
                  aria-pressed={mode === item.value} onClick={() => setMode(item.value)}>{item.label}</button>
              ))}
            </AnimatedNav>
          )}
        </div>
      </header>

      <dl className="opt-calc-stats">
        <div><dt>최대 이익</dt><dd className="num is-up">{maxProfit === null ? "제한 없음" : signedWon(maxProfit)}</dd></div>
        <div>
          <dt>만기 손익분기</dt>
          <dd className="num">{expiryBreakeven === null ? "–" : indexValue(expiryBreakeven)}
            {expiryBreakeven !== null && <small>{rate(expiryBreakeven / spot - 1)}</small>}</dd>
        </div>
        <div><dt>델타 <small>지수 1pt당</small></dt><dd className="num">{signedWon(delta)}</dd></div>
        <div><dt>시간가치 감소 <small>{decayLabel(decayUnit)}당</small></dt><dd className="num">{signedWon(-decay)}</dd></div>
        <div><dt>이론 수익 확률</dt><dd className="num">{(chance * 100).toFixed(1)}%</dd></div>
        <div><dt>모형 변동성</dt><dd className="num">{(volatility * 100).toFixed(1)}%</dd></div>
      </dl>

      {view === "table" && (
        <div className="opt-calc-scroll" tabIndex={0} role="region" aria-label="지수·시각별 손익 표">
          <table className="opt-calc-table">
            <caption className="vh">행은 게임주 지수, 열은 정리 시각. 칸은 {MODES.find((item) => item.value === mode)?.label}</caption>
            <thead>
              <tr>
                <th scope="col" className="opt-calc-corner">지수</th>
                {columns.map(head)}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.price} className={row.spot ? "is-spot" : undefined}>
                  <th scope="row">
                    {row.spot && <span className="opt-calc-spot-tag">현재</span>}
                    <span className="num">{indexValue(row.price)}</span>
                    {!row.spot && <small className="num">{ratioText(row.price / spot - 1)}</small>}
                  </th>
                  {columns.map((at) => {
                    const { ratio, text, title } = cell(row.price, at);
                    return <td key={at} className={`num ${heat(ratio)}`} title={title}>{text}</td>;
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {view === "chart" && (
        <PayoffChart leg={leg} spot={spot} nowEpoch={nowEpoch} volatility={volatility}
          low={rows.at(-1)?.price ?? spot * 0.98} high={rows[0]?.price ?? spot * 1.02} breakeven={expiryBreakeven} />
      )}

      {view === "breakeven" && (
        <div className="table-scroll" tabIndex={0} role="region" aria-label="시각별 손익분기 지수">
          <table className="table opt-calc-breakeven">
            <caption className="vh">정리 시각별 손익분기 지수</caption>
            <thead>
              <tr><th scope="col">정리 시각</th><th scope="col" className="cell-num">손익분기 지수</th><th scope="col" className="cell-num">현재 대비</th></tr>
            </thead>
            <tbody>
              {columns.map((at, index) => {
                const target = legBreakeven(leg, at, volatility);
                return (
                  <tr key={at}>
                    <td>{index === 0 ? "지금" : index === columns.length - 1 ? "만기" : untilLabel(at - nowEpoch)}
                      <span className="opt-calc-time num">{clock.format(at * 1000)}</span></td>
                    <td className="num">{target === null ? "–" : indexValue(target)}</td>
                    <td className={`num ${target !== null && target > spot ? "is-up" : "is-down"}`}>{target === null ? "–" : rate(target / spot - 1)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <p className="derivative-note">
        현재 지수와 모형 변동성으로 계산한 블랙-숄즈 추정입니다. 만기 전 정리는 최소 프리미엄 10원과 수수료 0.3%,
        만기 결제는 수수료 없이 내재가치로 계산했어요. 실제 지수 움직임과 결과를 보장하지 않습니다.
      </p>
    </section>
  );
}

/** 지수(가로)에 따른 손익(세로). 실선은 만기, 점선은 지금 정리했을 때. 0 위는 적색, 아래는 청색으로 칠한다. */
function PayoffChart({ leg, spot, nowEpoch, volatility, low, high, breakeven }: {
  leg: OptionLeg; spot: number; nowEpoch: number; volatility: number; low: number; high: number; breakeven: number | null;
}) {
  const clipUp = useId();
  const clipDown = useId();
  const [frame, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const height = useNarrow() ? 220 : 300;
  const left = 12;
  const right = 84;
  const top = 16;
  const bottom = 30;
  const plotWidth = Math.max(0, width - left - right);
  const plotHeight = height - top - bottom;

  const prices = Array.from({ length: CHART_SAMPLES + 1 }, (_, index) => low + ((high - low) * index) / CHART_SAMPLES);
  const atExpiry = prices.map((price) => legExitValue(leg, price, leg.expiryEpoch, volatility) - leg.cost);
  const atNow = prices.map((price) => legExitValue(leg, price, nowEpoch, volatility) - leg.cost);
  const values = [...atExpiry, ...atNow, 0];
  const step = niceStep((Math.max(...values) - Math.min(...values)) / 4 || 1);
  const yTop = Math.ceil(Math.max(...values) / step) * step;
  const yBottom = Math.floor(Math.min(...values) / step) * step;
  const x = (price: number) => left + ((price - low) / (high - low)) * plotWidth;
  const y = (value: number) => top + plotHeight - ((value - yBottom) / (yTop - yBottom || 1)) * plotHeight;
  const line = (series: number[]) => series.map((value, index) => `${index ? "L" : "M"}${x(prices[index]).toFixed(1)},${y(value).toFixed(1)}`).join(" ");
  const area = `${line(atExpiry)} L${x(high).toFixed(1)},${y(0).toFixed(1)} L${x(low).toFixed(1)},${y(0).toFixed(1)} Z`;
  const ticks: number[] = [];
  for (let value = yBottom; value <= yTop + step / 2; value += step) ticks.push(value);
  const priceStep = niceStep((high - low) / (plotWidth > 520 ? 6 : 3));
  const priceTicks: number[] = [];
  for (let price = Math.ceil(low / priceStep) * priceStep; price <= high; price += priceStep) priceTicks.push(price);
  const inspected = hover === null ? null : Math.max(0, Math.min(CHART_SAMPLES, hover));

  const onMove = (event: PointerEvent<HTMLDivElement>) => {
    if (plotWidth <= 0) return;
    const offset = event.clientX - event.currentTarget.getBoundingClientRect().left - left;
    setHover(offset < 0 || offset > plotWidth ? null : Math.round((offset / plotWidth) * CHART_SAMPLES));
  };

  return (
    <figure className="opt-payoff">
      <div className="opt-payoff-frame" ref={frame} style={{ height }} onPointerMove={onMove} onPointerLeave={() => setHover(null)}>
        {plotWidth > 0 && (
          <svg width={width} height={height} role="img"
            aria-label={`지수에 따른 손익 곡선. 만기 손익분기 ${breakeven === null ? "없음" : indexValue(breakeven)}`}>
            <defs>
              <clipPath id={clipUp}><rect x={left} y={0} width={plotWidth} height={Math.max(0, y(0))} /></clipPath>
              <clipPath id={clipDown}><rect x={left} y={y(0)} width={plotWidth} height={Math.max(0, height - y(0))} /></clipPath>
            </defs>
            <g className="opt-payoff-grid" aria-hidden="true">
              {ticks.map((value) => (
                <g key={value}>
                  <line x1={left} x2={left + plotWidth} y1={Math.round(y(value)) + 0.5} y2={Math.round(y(value)) + 0.5} />
                  <text x={left + plotWidth + 10} y={y(value)} dy="0.32em">{compact(value, true)}</text>
                </g>
              ))}
              {priceTicks.map((price) => (
                <text key={price} x={x(price)} y={height - 8} textAnchor="middle">{indexValue(price)}</text>
              ))}
            </g>
            <path className="opt-payoff-area is-up" d={area} clipPath={`url(#${clipUp})`} />
            <path className="opt-payoff-area is-down" d={area} clipPath={`url(#${clipDown})`} />
            <line className="opt-payoff-zero" x1={left} x2={left + plotWidth} y1={y(0)} y2={y(0)} />
            <g className="opt-payoff-marker is-spot">
              <line x1={x(spot)} x2={x(spot)} y1={top} y2={top + plotHeight} />
              <text x={x(spot)} y={top - 4} textAnchor="middle">현재</text>
            </g>
            {breakeven !== null && breakeven > low && breakeven < high && (
              <g className="opt-payoff-marker is-breakeven">
                <line x1={x(breakeven)} x2={x(breakeven)} y1={top} y2={top + plotHeight} />
                <text x={x(breakeven)} y={top + plotHeight - 6} textAnchor={leg.call ? "start" : "end"} dx={leg.call ? 6 : -6}>손익분기</text>
              </g>
            )}
            <path className="opt-payoff-line is-now" d={line(atNow)} />
            <path className="opt-payoff-line is-expiry" d={line(atExpiry)} />
            {inspected !== null && (
              <g className="opt-payoff-inspect" aria-hidden="true">
                <line x1={x(prices[inspected])} x2={x(prices[inspected])} y1={top} y2={top + plotHeight} />
                <circle cx={x(prices[inspected])} cy={y(atExpiry[inspected])} r={3.5} />
                <circle className="is-now" cx={x(prices[inspected])} cy={y(atNow[inspected])} r={3} />
              </g>
            )}
          </svg>
        )}
      </div>
      <figcaption>
        {inspected === null ? (
          <>
            <span className="opt-payoff-key is-expiry">만기</span>
            <span className="opt-payoff-key is-now">지금 정리</span>
            <span>그래프 위에 올리면 지수별 손익을 읽어 줍니다.</span>
          </>
        ) : (
          <>
            <span className="num">지수 {indexValue(prices[inspected])}</span>
            <span className="opt-payoff-key is-expiry">만기 <b className={`num ${atExpiry[inspected] >= 0 ? "is-up" : "is-down"}`}>{signedWon(atExpiry[inspected])}</b></span>
            <span className="opt-payoff-key is-now">지금 정리 <b className={`num ${atNow[inspected] >= 0 ? "is-up" : "is-down"}`}>{signedWon(atNow[inspected])}</b></span>
          </>
        )}
      </figcaption>
    </figure>
  );
}
