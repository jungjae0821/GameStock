import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from "react";
import { BrandLogo, BrandMark } from "../components/BrandMark";
import { Delta } from "../components/Delta";
import { GameIcon } from "../components/GameIcon";
import { Link } from "../components/Link";
import { PriceCell } from "../components/PriceCell";
import { ProfileMenu } from "../components/ProfileMenu";
import { RestrictionBadge } from "../components/RestrictionBadge";
import { SeriesChart, type Marker } from "../components/SeriesChart";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { rate, shares, since, won } from "../market/format";
import { marketOpinion, newsEffect, sessionRate, strengthRatio, type OpinionTone } from "../market/selectors";
import type { Quote, TradePrint } from "../market/types";
import { LISTING_BY_CODE } from "../market/universe";
import { API_DOCS_PATH, HEATMAP_PATH, MARKET_PATH, NEWS_PATH, navigate, type Route } from "../router";
import { toggleNxTheme, useNxTheme } from "./uiMode";
import { NxAccountPanel, type NxAccountTab } from "./trade/NxAccountPanel";
import { NxOrderTicket } from "./trade/NxOrderTicket";
import { OrderDock } from "./trade/OrderDock";
import { PriceDrivers } from "./trade/PriceDrivers";
import { StockTalk } from "./trade/StockTalk";
import { StockChat } from "./trade/StockChat";
import { IndicatorChart } from "./trade/IndicatorChart";
import { MarketTabs } from "./MarketTabs";
import { AnimatedNav } from "./AnimatedNav";

const NAV = [
  { label: "홈", to: "/", match: "home" },
  { label: "거래", to: "/market", match: "market" },
  { label: "히트맵", to: HEATMAP_PATH, match: "heatmap" },
  { label: "뉴스", to: "/news", match: "news" },
  { label: "랭킹", to: "/ranking", match: "ranking" },
  { label: "퀀트", to: "/quant", match: "quant" },
  { label: "API", to: API_DOCS_PATH, match: "apiDocs" },
] as const;

/** 테마와 새 화면 표시는 html에 단다. 포털로 뜨는 팝오버도 같은 색을 받아야 한다. */
export function useNxRoot(): void {
  const theme = useNxTheme();
  useEffect(() => {
    const root = document.documentElement;
    root.dataset.nx = "on";
    root.dataset.nxTheme = theme;
    root.style.colorScheme = theme;
    return () => {
      delete root.dataset.nx;
      delete root.dataset.nxTheme;
      root.style.colorScheme = "light";
    };
  }, [theme]);
}

function navActive(route: Route, match: (typeof NAV)[number]["match"]): boolean {
  return route.name === match || (match === "market" && (route.name === "trade" || route.name === "etf" || route.name === "derivatives"));
}

export function NxTopbar({ route }: { route: Route }) {
  const theme = useNxTheme();
  return (
    <header className="nx-top">
      <Link className="nx-brand" to="/" aria-label="슈엔증권 홈">
        <BrandMark size={26} />
        <BrandLogo />
      </Link>
      <nav className="nx-nav" aria-label="주요 화면">
        {NAV.map((item) => {
          const active = navActive(route, item.match);
          return (
            <Link key={item.to} to={item.to} className={`nx-nav-link${active ? " is-active" : ""}`} aria-current={active ? "page" : undefined}>
              {item.label}
            </Link>
          );
        })}
      </nav>
      <button type="button" className="nx-icon-button" onClick={toggleNxTheme} aria-label={theme === "dark" ? "밝은 화면으로 바꾸기" : "어두운 화면으로 바꾸기"}>
        {theme === "dark" ? (
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4.5" /><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8" /></svg>
        ) : (
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z" /></svg>
        )}
      </button>
      <ProfileMenu route={route} />
    </header>
  );
}

/**
 * 휴대폰 브라우저의 아래 탭바. 본문 뒤에 두어 Tab 이동도 보이는 순서(위 → 본문 → 아래)를 따른다.
 * 다른 화면에서는 CSS로 숨기고, 그때는 상단 바의 메뉴가 같은 일을 한다.
 */
export function NxTabbar({ route }: { route: Route }) {
  return (
    <AnimatedNav animationId="main-bottom" activeKey={NAV.find(item => navActive(route, item.match))?.match ?? ""} className="nx-tabbar" aria-label="주요 화면" inset={.28}>
      {NAV.map((item) => {
        const active = navActive(route, item.match);
        return (
          <Link key={item.to} to={item.to} className={`nx-tabbar-link${active ? " is-active" : ""}`} aria-current={active ? "page" : undefined}>
            {item.label}
          </Link>
        );
      })}
    </AnimatedNav>
  );
}

function StockSelector({ current }: { current: string }) {
  const snapshot = useMarket();
  return (
    <label className="nx-stock-selector">
      <span>종목 선택</span>
      <select value={current} onChange={(event) => navigate(MARKET_PATH(event.target.value))}>
        {snapshot.codes.map((code) => {
          const listing = LISTING_BY_CODE[code];
          return listing ? <option key={code} value={code}>{listing.name} · {code}{snapshot.watch.includes(code) ? " · 관심" : ""}</option> : null;
        })}
      </select>
    </label>
  );
}

function StockHead({ code, quote }: { code: string; quote: Quote }) {
  const snapshot = useMarket();
  const api = useMarketApi();
  const listing = LISTING_BY_CODE[code];
  const watched = snapshot.watch.includes(code);
  return (
    <header className="nx-head">
      <div className="nx-head-id">
        <GameIcon code={code} name={listing.name} size="large" />
        <div>
          <p className="nx-head-sub"><span className="nx-code">{code}</span>{listing.publisher} · {listing.genre}</p>
          <h1 className="nx-head-name">{listing.name}</h1>
        </div>
        <button type="button" className={`nx-watch-toggle${watched ? " is-on" : ""}`} aria-label={`${listing.name} 관심 ${watched ? "해제" : "등록"}`} aria-pressed={watched} onClick={() => api.toggleWatch(code)}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.3-4.1 5.9-.9z" /></svg>
          {watched ? "관심 종목" : "관심 등록"}
        </button>
      </div>
      <div className="nx-head-price">
        <PriceCell className="nx-num nx-price" value={quote.price} />
        <Delta change={quote.price - quote.prevClose} ratio={sessionRate(quote)} className="nx-head-delta" />
        <RestrictionBadge code={code} />
      </div>
    </header>
  );
}

function StockStats({ quote }: { quote: Quote }) {
  return (
    <section className="nx-trade-facts" aria-labelledby="nx-stock-info-title">
      <h2 id="nx-stock-info-title">종목 정보</h2>
      <dl className="nx-stats">
        <div><dt>전일</dt><dd className="nx-num">{won(quote.prevClose)}</dd></div>
        <div><dt>시가</dt><dd className="nx-num">{won(quote.open)}</dd></div>
        <OpinionStat quote={quote} />
        <div><dt>고가</dt><dd className="nx-num is-up">{won(quote.high)}</dd></div>
        <div><dt>저가</dt><dd className="nx-num is-down">{won(quote.low)}</dd></div>
        <div><dt>상한가</dt><dd className="nx-num">{won(quote.limitUp)}</dd></div>
        <div><dt>하한가</dt><dd className="nx-num">{won(quote.limitDown)}</dd></div>
        <div><dt>거래량</dt><dd className="nx-num">{shares(quote.volume)}</dd></div>
      </dl>
    </section>
  );
}

const OPINION_LABEL: Record<OpinionTone, string> = {
  buy: "매수 우위",
  sell: "매도 우위",
  neutral: "중립",
  caution: "변동성 주의",
};

const signalScore = (score: number) => (score > 0 ? "+1" : score < 0 ? "-1" : "0");

/** 시세 칸의 시장 의견. 누르거나 마우스를 올리면 어떤 신호로 나온 값인지 펼친다. */
function OpinionStat({ quote }: { quote: Quote }) {
  const snapshot = useMarket();
  const id = useId();
  const opinion = marketOpinion(quote, snapshot.news, snapshot.updatedAt || Date.now());
  const goodNews = (opinion.newsCount + opinion.newsBalance) / 2;
  const badNews = opinion.newsCount - goodNews;
  return (
    <div className="nx-opinion">
      <dt>의견</dt>
      <dd>
        <button type="button" className={`nx-opinion-value is-${opinion.tone}`} aria-describedby={id}
          onKeyDown={(event) => { if (event.key === "Escape") event.currentTarget.blur(); }}>
          {OPINION_LABEL[opinion.tone]}
        </button>
        <div className="nx-opinion-panel" id={id} role="tooltip">
          {opinion.caution && <p className="nx-opinion-caution">{opinion.caution}: 가격이 크게 흔들릴 수 있어 의견을 내지 않아요.</p>}
          <table>
            <tbody>
              <tr><th scope="row">최근 체결 <span className="nx-num">{opinion.printCount}</span>건</th><td>매수 <span className="nx-num">{Math.round(opinion.buyShare * 100)}%</span></td><td className="nx-num">{signalScore(opinion.strengthScore)}</td></tr>
              <tr><th scope="row">시가 대비</th><td className="nx-num">{rate(opinion.fromOpen)}</td><td className="nx-num">{signalScore(opinion.openScore)}</td></tr>
              <tr><th scope="row">24시간 뉴스</th><td>호재 <span className="nx-num">{goodNews}</span> · 악재 <span className="nx-num">{badNews}</span></td><td className="nx-num">{signalScore(opinion.newsScore)}</td></tr>
            </tbody>
          </table>
          <p className="nx-opinion-rule">합이 +2 이상이면 매수 우위, -2 이하면 매도 우위예요. 지금 거래 흐름을 요약한 값이고, 앞으로의 가격을 알려 주지는 않아요.</p>
        </div>
      </dd>
    </div>
  );
}

function StockNews({ code, quote }: { code: string; quote: Quote }) {
  const snapshot = useMarket();
  const items = useMemo(() => snapshot.news.filter((item) => item.code === code).sort((a, b) => b.at - a.at).slice(0, 4), [snapshot.news, code]);
  return (
    <section className="nx-stock-news" aria-labelledby="nx-stock-news-title">
      <header><h2 id="nx-stock-news-title">관련 뉴스</h2><Link to="/news">전체 뉴스</Link></header>
      {items.length === 0 ? <p className="nx-muted">이 종목의 최근 뉴스가 아직 없어요.</p> : items.map((item) => {
        const effect = item.priceChangeRatio ?? newsEffect(item.priceAtPublish, quote);
        return <Link key={item.id} to={NEWS_PATH(item.id)} className="nx-stock-article">
          <span>{item.source} · {since(item.at, snapshot.updatedAt || Date.now())}</span>
          <h3>{item.title}</h3>
          <span>발행 후 <strong className={`nx-num ${effect > 0 ? "is-up" : effect < 0 ? "is-down" : ""}`}>{rate(effect)}</strong></span>
        </Link>;
      })}
    </section>
  );
}

/** 체결 테이프에 남기는 최근 체결 수. 칸 폭이 고정이라 넘치는 것은 오른쪽 끝에서 잘린다. */
const TAPE_LIMIT = 12;
/** 테이프 칸 폭과 간격(px). CSS의 .nx-tape-chip 폭·.nx-tape-strip 간격과 같아야 밀려나는 거리가 맞는다. */
const TAPE_CHIP = 72;
const TAPE_GAP = 6;
const SLIDE_MS = 320;
const SLIDE_EASING = "cubic-bezier(0.16, 1, 0.3, 1)";

/** 같은 방향으로 이어진 최근 체결 수. 흐름이 한쪽으로 쏠리는 정도를 센다. */
function combo(prints: TradePrint[]): { side: "buy" | "sell"; count: number } | null {
  const first = prints[0];
  if (!first) return null;
  let count = 0;
  for (const print of prints) {
    if (print.side !== first.side) break;
    count += 1;
  }
  return { side: first.side, count };
}

/**
 * 같은 시각·가격·수량의 체결이 연달아 올 수 있다. 뒤(오래된 쪽)부터 번호를 매겨
 * 새 체결이 앞에 붙어도 기존 체결의 키는 그대로 두고, 새 체결만 새 키를 받게 한다.
 */
function printKeys(prints: TradePrint[]): string[] {
  const seen = new Map<string, number>();
  const keys = new Array<string>(prints.length);
  for (let index = prints.length - 1; index >= 0; index -= 1) {
    const print = prints[index];
    const base = `${print.at}-${print.price}-${print.qty}-${print.side}`;
    const count = seen.get(base) ?? 0;
    seen.set(base, count + 1);
    keys[index] = `${base}#${count}`;
  }
  return keys;
}

type Arrival = { code: string; signature: string; fresh: string[] };

/**
 * 직전 렌더와 비교해 이번에 새로 들어온 체결 키를 고른다. 체결 목록이 그대로인 다시 그리기에서는
 * 앞서 고른 묶음을 유지해 진행 중인 움직임을 끊지 않는다. 종목을 바꾼 첫 렌더는 새 체결이 없다.
 */
function useArrival(code: string, keys: string[]): Arrival {
  const arrival = useRef<Arrival | null>(null);
  const signature = keys.join(",");
  const previous = arrival.current;
  if (!previous || previous.code !== code) {
    arrival.current = { code, signature, fresh: [] };
  } else if (previous.signature !== signature) {
    const known = new Set(previous.signature.split(","));
    arrival.current = { code, signature, fresh: keys.filter((key) => !known.has(key)) };
  }
  return arrival.current!;
}

/**
 * 호가 아래의 체결 테이프. 새 체결 묶음은 왼쪽 끝에 붙고, 앞선 체결은 그만큼 오른쪽으로 밀려나며 옅어진다.
 * 칸마다 따로 움직이지 않고 띠 전체를 한 번에 밀어 칸끼리 겹치지 않게 한다.
 */
function PrintTape({ quote, keys, arrival }: { quote: Quote; keys: string[]; arrival: Arrival }) {
  const strip = useRef<HTMLDivElement>(null);
  const prints = quote.prints.slice(0, TAPE_LIMIT);
  const streak = combo(quote.prints);
  const fresh = new Set(arrival.fresh);
  const shift = arrival.fresh.length * (TAPE_CHIP + TAPE_GAP);

  useLayoutEffect(() => {
    const element = strip.current;
    if (!element) return;
    for (const running of element.getAnimations()) running.cancel();
    if (shift <= 0 || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    element.animate([{ transform: `translateX(${-shift}px)` }, { transform: "translateX(0)" }], { duration: SLIDE_MS, easing: SLIDE_EASING });
  }, [arrival, shift]);

  return (
    <div className="nx-tape">
      <p className="nx-tape-streak" aria-live="off">
        {streak && streak.count > 1 ? <>
          <strong className={`nx-num is-${streak.side === "buy" ? "up" : "down"}`}>{streak.count}</strong>
          <span>연속 {streak.side === "buy" ? "매수" : "매도"}</span>
        </> : <span>{prints[0] ? `${prints[0].side === "buy" ? "매수" : "매도"} 체결` : "체결 대기"}</span>}
      </p>
      <div className="nx-tape-track" role="img" aria-label={`최근 체결 ${prints.length}건`}>
        <div className="nx-tape-strip" ref={strip} aria-hidden="true">
          {prints.map((print, index) => (
            <span key={keys[index]} className={`nx-tape-chip nx-num is-${print.side}${fresh.has(keys[index]) ? " is-fresh" : ""}`}
              style={{ opacity: Math.max(0.35, 1 - index * 0.07) }}>
              {print.qty.toLocaleString("ko-KR")}<small>주</small>
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

function Book({ quote, onPriceSelect }: { quote: Quote; onPriceSelect: (price: number) => void }) {
  const asks = [...quote.asks].sort((a, b) => a.price - b.price).slice(0, 5).reverse();
  const bids = [...quote.bids].sort((a, b) => b.price - a.price).slice(0, 5);
  const depth = Math.max(1, ...asks.map((level) => level.qty), ...bids.map((level) => level.qty));
  const strength = strengthRatio(quote);
  const keys = printKeys(quote.prints.slice(0, TAPE_LIMIT));
  const arrival = useArrival(quote.code, keys);
  /* 이번 묶음의 체결. 현재가 줄을 그 방향 색으로 한 번 쓸고, 묶음 수량을 잠깐 띄운다. */
  const hits = quote.prints.slice(0, TAPE_LIMIT).filter((_, index) => arrival.fresh.includes(keys[index]));
  const hitSide = hits[0]?.side;
  const hitQty = hits.filter((print) => print.side === hitSide).reduce((sum, print) => sum + print.qty, 0);

  /*
   * 가격별 잔량과 마지막 변화 방향. 잔량이 늘거나 줄면 그 숫자만 한 번 반짝인다(숫자 칸의 key가 바뀌어 다시 붙는다).
   * 잔량이 그대로인 다시 그리기에서도 방향 클래스를 유지해야 진행 중인 반짝임이 끊기지 않는다.
   */
  const lastQty = useRef(new Map<string, { qty: number; change: string }>());
  const changeOf = (side: "ask" | "bid", level: { price: number; qty: number }) => {
    const id = `${side}:${level.price}`;
    const before = lastQty.current.get(id);
    const change = !before ? "" : before.qty === level.qty ? before.change : level.qty > before.qty ? " is-more" : " is-less";
    lastQty.current.set(id, { qty: level.qty, change });
    return change;
  };

  const row = (side: "ask" | "bid", level: { price: number; qty: number } | undefined, gridRow: number) => {
    const change = level ? changeOf(side, level) : "";
    return (
      <div className={`nx-book-row is-${side}`} style={{ gridRow }} key={`${side}-${gridRow}`} role="row">
        {level ? <>
          <button type="button" className="nx-book-price nx-num" onClick={() => onPriceSelect(level.price)} aria-label={`${side === "ask" ? "매도" : "매수"} 호가 ${won(level.price)}을 주문 가격으로`}>
            {won(level.price).replace("₩", "")}
          </button>
          <span className="nx-book-qty nx-num" role="cell">
            <span className="nx-book-bar" style={{ width: `${(level.qty / depth) * 100}%` }} aria-hidden="true" />
            <span key={`${level.price}:${level.qty}`} className={`nx-book-count${change}`}>{level.qty.toLocaleString("ko-KR")}</span>
            <span className="nx-book-unit">주</span>
          </span>
        </> : <span className="nx-book-empty" aria-hidden="true">-</span>}
      </div>
    );
  };

  return (
    <section className="nx-panel nx-book" aria-labelledby="nx-book-title">
      <header className="nx-panel-head">
        <h2 id="nx-book-title" className="nx-panel-title">호가 · 체결</h2>
        <span className="nx-muted" title={`최근 체결 ${quote.prints.length}건 기준`}>체결 강도 <span className="nx-num">{strength === null ? "-" : `${Math.round(strength * 100)}%`}</span></span>
      </header>
      <div className="nx-ladder" role="table" aria-label="매도 5단계, 현재가, 매수 5단계">
        {Array.from({ length: 5 }, (_, index) => row("ask", asks[index - (5 - asks.length)], index + 1))}
        <div className="nx-judge" role="row" aria-label={`현재가 ${won(quote.price)}`}>
          {hitSide && <span key={`sweep-${arrival.signature}`} className={`nx-judge-sweep is-${hitSide}`} aria-hidden="true" />}
          <PriceCell className="nx-num nx-judge-price" value={quote.price} />
          <Delta change={quote.price - quote.prevClose} ratio={sessionRate(quote)} showAmount={false} />
          {hitSide && (
            <span key={`hit-${arrival.signature}`} className={`nx-judge-hit nx-num is-${hitSide}`} aria-hidden="true">
              {hitSide === "buy" ? "매수" : "매도"} {hitQty.toLocaleString("ko-KR")}주
            </span>
          )}
        </div>
        {Array.from({ length: 5 }, (_, index) => row("bid", bids[index], index + 7))}
      </div>
      <PrintTape quote={quote} keys={keys} arrival={arrival} />
    </section>
  );
}

export function TradeTerminal({ ticker }: { ticker: string | null }) {
  const snapshot = useMarket();
  const { setActiveDetailCode } = useMarketApi();
  const [accountTab, setAccountTab] = useState<NxAccountTab>("holdings");
  const [selected, setSelected] = useState<{ code: string; price: number; revision: number } | null>(null);
  const [orderOpen, setOrderOpen] = useState(false);
  const accountFocusPending = useRef(false);
  const code = ticker?.toUpperCase() ?? snapshot.codes[0] ?? null;
  const listing = code ? LISTING_BY_CODE[code] : undefined;
  const quote = code ? snapshot.quotes[code] : undefined;
  const fills = snapshot.portfolio.fills;
  const markers = useMemo<Marker[]>(() => [
    ...snapshot.news.filter((item) => item.code === code).map((item) => ({ at: item.at, label: item.title })),
    ...fills.filter((fill) => fill.code === code).map((fill) => ({
      at: fill.at, price: fill.price, kind: fill.side,
      label: `내 ${fill.side === "buy" ? "매수" : "매도"} ${fill.qty.toLocaleString("ko-KR")}주 · ${won(fill.price)}`,
    })),
  ], [snapshot.news, fills, code]);

  useEffect(() => { setOrderOpen(false); }, [code]);

  useEffect(() => {
    if (!code) return undefined;
    setActiveDetailCode(code);
    return () => setActiveDetailCode(null);
  }, [setActiveDetailCode, code]);

  if (!code || !listing) {
    return <p className="nx-empty">거래할 종목을 찾을 수 없어요. <Link to="/market">시장으로 돌아가기</Link></p>;
  }
  if (!quote) {
    return <p className="nx-empty" role="status">시세를 불러오는 중이에요.</p>;
  }

  const focusTicket = () => {
    if (window.matchMedia("(max-width: 960px)").matches) setOrderOpen(true);
    else {
      const ticket = document.querySelector<HTMLFormElement>(".nx-ticket");
      ticket?.focus({ preventScroll: true });
      ticket?.scrollIntoView({ block: "nearest" });
    }
  };
  const focusAccount = () => {
    const account = document.querySelector<HTMLElement>(".nx-account");
    account?.focus({ preventScroll: true });
    account?.scrollIntoView({ block: "nearest" });
    accountFocusPending.current = false;
  };

  return (
    <div className="nx-trade-page">
      <MarketTabs active="stocks" />
      <div className="nx-trade-toolbar">
        <Link to="/market" className="nx-back-link"><span aria-hidden="true">←</span> 주식 목록</Link>
        <StockSelector current={code} />
      </div>
      <div className="nx-workspace">
        {snapshot.portfolioError && <p role="status" className="nx-alert">{snapshot.portfolioError}</p>}
        <div className="nx-trade-main">
        <section className="nx-trade-chart" aria-label={`${listing.name} 시세와 차트`}>
          <StockHead code={code} quote={quote} />
          <div className="nx-chart integration-tools">
            <SeriesChart key={code} code={code} markers={markers} initialMode="candle" />
          </div>
        </section>
        <StockStats quote={quote} />
        <IndicatorChart key={code} code={code} name={listing.name} />
        <PriceDrivers code={code} />
        <section className="nx-panel nx-account" aria-label="내 잔고와 주문 내역" tabIndex={-1}>
          <NxAccountPanel code={code} tab={accountTab} onTabChange={setAccountTab}
            onFocusTicket={focusTicket} />
        </section>
        <div className="nx-trade-details">
          <Book quote={quote} onPriceSelect={(price) => { setSelected((previous) => ({ code, price, revision: (previous?.revision ?? 0) + 1 })); focusTicket(); }} />
          <StockNews code={code} quote={quote} />
        </div>
        <div className="nx-trade-community">
          <StockTalk key={code} code={code} name={listing.name} />
          <StockChat key={`chat-${code}`} code={code} name={listing.name} />
        </div>
        <p className="nx-trade-disclaimer">게임 뉴스와 모의 주문으로 형성되는 시세입니다. 실제 금융상품이나 투자 수익과 관계없습니다.</p>
        </div>
        <OrderDock title={listing.name} open={orderOpen} onOpenChange={setOrderOpen}
          onAfterClose={() => { if (accountFocusPending.current) focusAccount(); }}>
          <NxOrderTicket
            key={code}
            code={code}
            onShowOrders={() => {
              setAccountTab("orders");
              if (orderOpen) accountFocusPending.current = true;
              else window.requestAnimationFrame(focusAccount);
              setOrderOpen(false);
            }}
            selectedLimitPrice={selected?.code === code ? selected.price : null}
            selectedLimitPriceRevision={selected?.code === code ? selected.revision : undefined}
          />
        </OrderDock>
      </div>
    </div>
  );
}
