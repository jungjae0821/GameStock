import { useEffect, useState, type KeyboardEvent, type PointerEvent } from "react";
import { GameIcon } from "../components/GameIcon";
import { HoldingsTable } from "../components/HoldingsTable";
import { Link } from "../components/Link";
import { NewsFeed } from "../components/NewsFeed";
import { PriceCell } from "../components/PriceCell";
import { apiFetch } from "../lib/api";
import { useNarrow, useWidth } from "../lib/useWidth";
import { BUCKET_MS, bucketPrices, kstMidnight, type PricePoint } from "../market/dayHistory";
import { compactWon, hm, indexValue, rate, signedNumber } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { holdings, sessionRate, totals } from "../market/selectors";
import { VISIBLE_NEWS_SOURCES, type MarketSnapshot, type NewsSource } from "../market/types";
import { LISTING_BY_CODE } from "../market/universe";
import { useSteadySnapshot } from "../market/useSteadySnapshot";
import { MARKET_PATH } from "../router";
import { AnimatedNav } from "./AnimatedNav";
import { HomeBillboard } from "./BillboardStrip";

type IndexCandle = { recordedAt: string; closePrice: number };

/*
 * 오늘 0시부터의 게임주 지수 10분 봉(/api/derivatives/index/today)을 10분 칸 값으로 펼친다.
 * 지수는 날이 바뀌어도 이어지므로 첫 칸은 어제 종가 근처다. 마지막 점은 실시간 지수로 바꿔 끼운다.
 */
function useIndexHistory(): { points: number[]; status: "loading" | "ready" | "error" } {
  const [state, setState] = useState<{ points: number[]; status: "loading" | "ready" | "error" }>({ points: [], status: "loading" });

  useEffect(() => {
    let active = true;
    const load = async () => {
      try {
        const candles = await apiFetch<IndexCandle[]>("/api/derivatives/index/today", { auth: false });
        if (!active) return;
        const points = bucketPrices(candles.map((candle): PricePoint => ({ recordedAt: candle.recordedAt, price: candle.closePrice })), Date.now());
        setState({ points, status: points.length ? "ready" : "error" });
      } catch {
        if (active) setState((current) => ({ ...current, status: current.points.length ? "ready" : "error" }));
      }
    };
    void load();
    const timer = window.setInterval(() => void load(), 60_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  return state;
}

const DAY_MS = 24 * 3_600_000;
const TIME_TICKS = [0, 6, 12, 18, 24];
/** 오른쪽 값 눈금 글자 자리와 아래 시각 글자 자리. */
const AXIS_RIGHT = 64;
const AXIS_BOTTOM = 26;
/** 눈금 간격이 1 이상이면 소수점 아래는 늘 0이라 뺀다. */
const AXIS_NUMBER = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });

/** 1, 2, 2.5, 5 × 10ⁿ 중 눈금이 4개 안팎이 되는 간격. */
function niceStep(span: number): number {
  const raw = span / 4;
  const power = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].find((unit) => unit * power >= raw) ?? 10;
  return step * power;
}

/**
 * 오늘 0시부터 24시까지를 가로 전체로 잡는다. 선이 오른쪽 끝에 닿지 않은 만큼이 남은 하루다.
 * 점선은 어제 종가. 마우스를 올리면 그 10분 칸의 지수를 읽어 준다.
 */
function IndexChart({ points, live, prevClose, status }: { points: number[]; live: number; prevClose: number; status: "loading" | "ready" | "error" }) {
  const [frame, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const height = useNarrow() ? 200 : 280;

  const series = points.length ? [...points.slice(0, -1), live] : [];
  const plotWidth = Math.max(0, width - AXIS_RIGHT);
  const plotHeight = height - AXIS_BOTTOM;
  const empty = series.length < 2 || plotWidth === 0;

  const values = [...series, prevClose];
  const step = niceStep(Math.max(...values) - Math.min(...values) || 2);
  const top = Math.ceil(Math.max(...values) / step) * step;
  const bottom = Math.floor(Math.min(...values) / step) * step;
  const ticks: number[] = [];
  for (let value = bottom; value <= top + step / 2; value += step) ticks.push(value);
  const y = (value: number) => plotHeight - ((value - bottom) / (top - bottom || 1)) * plotHeight;
  const bucketsPerDay = DAY_MS / BUCKET_MS;
  const x = (index: number) => (index / bucketsPerDay) * plotWidth;
  const path = series.map((value, index) => `${index === 0 ? "M" : "L"}${x(index).toFixed(1)},${y(value).toFixed(1)}`).join(" ");
  const tone = live >= prevClose ? "is-up" : "is-down";
  const midnight = kstMidnight(Date.now());
  const last = series.length - 1;
  const inspected = hover === null ? null : Math.min(hover, last);

  const onMove = (event: PointerEvent<HTMLDivElement>) => {
    if (empty) return;
    const left = event.currentTarget.getBoundingClientRect().left;
    const index = Math.round(((event.clientX - left) / plotWidth) * bucketsPerDay);
    setHover(index < 0 ? null : Math.min(index, last));
  };

  return (
    <div className={`nx-index-chart ${tone}`} ref={frame} style={{ height }} onPointerMove={onMove} onPointerLeave={() => setHover(null)}>
      {empty ? (
        <p className="nx-index-chart-note">{status === "error" ? "오늘 지수 기록을 불러오지 못했어요. 1분 뒤 다시 받아요." : "오늘 지수 기록을 불러오고 있어요."}</p>
      ) : (
        <svg width={width} height={height} role="img" aria-label={`오늘 게임주 지수 흐름. 0시 ${indexValue(series[0])}에서 지금 ${indexValue(live)}`}>
          <g className="nx-index-grid" aria-hidden="true">
            {ticks.map((value) => (
              <g key={value}>
                <line x1={0} x2={plotWidth} y1={Math.round(y(value)) + 0.5} y2={Math.round(y(value)) + 0.5} />
                <text x={plotWidth + 10} y={y(value)} dy="0.32em">{step >= 1 ? AXIS_NUMBER.format(value) : indexValue(value)}</text>
              </g>
            ))}
            {TIME_TICKS.map((hour) => (
              <text key={hour} x={(hour / 24) * plotWidth} y={height - 6} textAnchor={hour === 0 ? "start" : hour === 24 ? "end" : "middle"}>
                {String(hour).padStart(2, "0")}:00
              </text>
            ))}
          </g>
          <line className="nx-index-base" x1={0} x2={plotWidth} y1={y(prevClose)} y2={y(prevClose)} />
          <path className="nx-index-line" d={path} />
          <circle className="nx-index-dot" cx={x(last)} cy={y(live)} r={4} />
          {inspected !== null && (
            <g className="nx-index-inspect" aria-hidden="true">
              <line x1={x(inspected)} x2={x(inspected)} y1={0} y2={plotHeight} />
              <circle cx={x(inspected)} cy={y(series[inspected])} r={4} />
            </g>
          )}
        </svg>
      )}
      {inspected !== null && !empty && (
        <p className="nx-index-readout" style={{ left: Math.min(Math.max(x(inspected), 56), plotWidth - 56) }} aria-hidden="true">
          <span>{inspected === last ? "지금" : hm(midnight + inspected * BUCKET_MS)}</span>
          <span className="nx-num">{indexValue(series[inspected])}</span>
        </p>
      )}
    </div>
  );
}

const RANK_LIMIT = 10;

type RankTab = "movers" | "cap" | "gainers" | "losers";
// 화면을 오가며 다시 마운트돼도 현재 접속 중 선택한 순위를 유지한다.
let lastRankTab: RankTab = "movers";
const RANK_TABS: ReadonlyArray<{ id: RankTab; label: string; empty: string }> = [
  { id: "movers", label: "많이 움직인 종목", empty: "아직 체결된 종목이 없어요." },
  { id: "cap", label: "시가총액 순위", empty: "시가총액이 잡힌 종목이 아직 없어요." },
  { id: "gainers", label: "급등", empty: "오늘 오른 종목이 아직 없어요." },
  { id: "losers", label: "급락", empty: "오늘 내린 종목이 아직 없어요." },
];

function rankCodes(snapshot: MarketSnapshot, tab: RankTab): string[] {
  const codes = snapshot.codes.filter((code) => snapshot.quotes[code] && LISTING_BY_CODE[code]);
  const ratio = (code: string) => sessionRate(snapshot.quotes[code]);
  const sorted =
    tab === "cap" ? codes.sort((a, b) => (snapshot.quotes[b].marketCap ?? 0) - (snapshot.quotes[a].marketCap ?? 0))
    : tab === "gainers" ? codes.filter((code) => ratio(code) > 0).sort((a, b) => ratio(b) - ratio(a))
    : tab === "losers" ? codes.filter((code) => ratio(code) < 0).sort((a, b) => ratio(a) - ratio(b))
    : codes.sort((a, b) => Math.abs(ratio(b)) - Math.abs(ratio(a)));
  return sorted.slice(0, RANK_LIMIT);
}

/** 순위 표는 5초마다 한꺼번에 바뀐다. 체결마다 줄이 자리를 바꾸면 누르려던 종목이 도망간다. */
function RankBoard() {
  const snapshot = useSteadySnapshot();
  const [tab, updateTab] = useState<RankTab>(() => lastRankTab);
  const setTab = (next: RankTab) => {
    lastRankTab = next;
    updateTab(next);
  };
  const current = RANK_TABS.find((item) => item.id === tab)!;
  const codes = rankCodes(snapshot, tab);

  /* 화살표, Home, End로 탭 사이를 옮긴다(WAI-ARIA 탭 패턴). */
  const onKey = (event: KeyboardEvent<HTMLDivElement>) => {
    const index = RANK_TABS.findIndex((item) => item.id === tab);
    const step = { ArrowRight: 1, ArrowLeft: RANK_TABS.length - 1 }[event.key];
    const next = event.key === "Home" ? RANK_TABS[0] : event.key === "End" ? RANK_TABS.at(-1)! : step ? RANK_TABS[(index + step) % RANK_TABS.length] : null;
    if (!next) return;
    event.preventDefault();
    setTab(next.id);
    document.getElementById(`nx-board-tab-${next.id}`)?.focus();
  };

  return (
    <section className="nx-panel nx-home-board" aria-labelledby="nx-board-title">
      <h2 id="nx-board-title" className="nx-panel-title">오늘의 종목 순위</h2>
      <AnimatedNav as="div" activeKey={tab} className="nx-tabs nx-board-tabs" role="tablist" aria-label="순위 종류" onKeyDown={onKey}>
        {RANK_TABS.map((item) => (
          <button key={item.id} type="button" role="tab" id={`nx-board-tab-${item.id}`} aria-selected={tab === item.id} aria-controls="nx-board-panel"
            tabIndex={tab === item.id ? 0 : -1} className={tab === item.id ? "is-active" : undefined} onClick={() => setTab(item.id)}>
            {item.label}
          </button>
        ))}
      </AnimatedNav>
      <div id="nx-board-panel" role="tabpanel" aria-labelledby={`nx-board-tab-${tab}`}>
        {snapshot.codes.length === 0 ? <p className="nx-board-empty" role="status">시세를 불러오고 있어요.</p>
          : codes.length === 0 ? <p className="nx-board-empty">{current.empty}</p> : (
          <ol className={`nx-board-list is-${tab}`}>
            {codes.map((code, index) => {
              const quote = snapshot.quotes[code];
              const ratio = sessionRate(quote);
              return (
                <li key={code}>
                  <Link to={MARKET_PATH(code)} className="nx-board-row">
                    <span className="nx-num nx-board-no">{index + 1}</span>
                    <GameIcon code={code} size="small" />
                    <span className="nx-board-name">{LISTING_BY_CODE[code].name}</span>
                    {tab === "cap" && <span className="nx-num nx-board-cap">{compactWon(quote.marketCap ?? 0)}</span>}
                    <PriceCell className="nx-num nx-board-price" value={quote.price} />
                    <span className={`nx-num nx-board-rate ${ratio > 0 ? "is-up" : ratio < 0 ? "is-down" : ""}`}>{rate(ratio)}</span>
                  </Link>
                </li>
              );
            })}
          </ol>
        )}
      </div>
    </section>
  );
}

export function HomeBoard() {
  const snapshot = useMarket();
  const history = useIndexHistory();
  const [newsSource, setNewsSource] = useState<NewsSource>();
  const { value, prevClose } = snapshot.index;
  const change = value - prevClose;
  const sum = totals(snapshot);
  const held = holdings(snapshot, sum.total);
  const day = history.points.length ? [...history.points.slice(0, -1), value] : [];

  return (
    <div className="nx-home">
      <h1 className="vh">슈엔증권 홈</h1>
      <HomeBillboard />
      <section className="nx-panel nx-home-index" aria-labelledby="nx-index-title">
        <header className="nx-index-head">
          <div>
            <h2 id="nx-index-title" className="nx-panel-title">게임주 지수</h2>
            <p className="nx-index-now">
              {/* 지수는 매 틱 바뀌어서 번쩍임이 꺼지지 않는다. 큰 숫자는 그대로 둔다. */}
              <span className="nx-num nx-hero-value">{indexValue(value)}</span>
              <span className={`nx-num nx-hero-change ${change > 0 ? "is-up" : change < 0 ? "is-down" : ""}`}>
                {signedNumber(change)} ({rate(prevClose ? change / prevClose : 0)})
              </span>
            </p>
          </div>
          {/* 최고·최저는 10분 칸 값과 지금 값 중에서 고른다. */}
          {day.length > 0 && (
            <dl className="nx-index-range">
              <div><dt>오늘 최고</dt><dd className="nx-num">{indexValue(Math.max(...day))}</dd></div>
              <div><dt>오늘 최저</dt><dd className="nx-num">{indexValue(Math.min(...day))}</dd></div>
              <div><dt>어제 종가</dt><dd className="nx-num">{indexValue(prevClose)}</dd></div>
            </dl>
          )}
        </header>
        <IndexChart points={history.points} live={value} prevClose={prevClose} status={history.status} />
      </section>

      {/* 넓은 화면에서는 순위를 왼쪽, 뉴스를 오른쪽에 나란히 둔다. 무엇이 움직였고 왜 움직였는지를 한눈에. */}
      <div className="nx-home-split">
        <RankBoard />
        <section className="nx-panel nx-home-news" aria-labelledby="nx-news-title">
          <header className="nx-panel-head">
            <h2 id="nx-news-title" className="nx-panel-title">최근 게임 뉴스</h2>
            <Link to="/news" className="nx-more">전체 보기</Link>
          </header>
          <div className="nx-filters nx-home-news-filters" role="group" aria-label="뉴스 출처">
            {[undefined, ...VISIBLE_NEWS_SOURCES].map((source) => (
              <button key={source ?? "all"} type="button"
                className={`nx-chip nx-chip--small${newsSource === source ? " is-active" : ""}`}
                aria-pressed={newsSource === source} onClick={() => setNewsSource(source)}>
                {source ?? "전체"}
              </button>
            ))}
          </div>
          <NewsFeed limit={8} source={newsSource} emptyMessage={newsSource ? `${newsSource}가 아직 없어요.` : undefined} />
        </section>
      </div>

      {held.length > 0 && (
        <section className="nx-panel nx-home-holdings" aria-labelledby="nx-holdings-title">
          <header className="nx-panel-head">
            <h2 id="nx-holdings-title" className="nx-panel-title">내 보유</h2>
            <span className="nx-muted">평가액 <span className="nx-num">{compactWon(sum.stockValue)}</span></span>
          </header>
          <HoldingsTable caption="내 보유 종목과 평가 손익" />
        </section>
      )}
    </div>
  );
}
