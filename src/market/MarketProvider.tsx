import { createContext, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { onAuthStateChanged } from "firebase/auth";
import { firebaseAuth } from "../lib/firebase";
import { apiFetch } from "../lib/api";
import { classifyNewsSource } from "./newsSource";
import { openMarketStream } from "./stream";
import { requireSignIn } from "../lib/auth";
import { announceMissionReward } from "../components/MissionRewardToast";
import { INITIAL_CASH, MarketEngine } from "./engine";
import { stockMarketProfile } from "./marketCap";
import { serverTimestamp } from "./format";
import { LISTING_BY_CODE, dailyPriceBand, roundToTick } from "./universe";
import type { DailyMissionStatus, LoanStatus, MarketSnapshot, OrderRequest, OrderResult, Portfolio, Position, Quote, TradingRestriction } from "./types";
import { BackendLoadingScreen } from "../components/BackendLoadingScreen";

export interface MarketApi {
  placeOrder: (request: OrderRequest) => Promise<OrderResult>;
  cancelOpenOrder: (code: string) => Promise<CancelOrdersResult>;
  cancelAllOpenOrders: () => Promise<CancelOrdersResult>;
  orderable: (code: string, side: "buy" | "sell") => number;
  toggleWatch: (code: string) => void;
  setActiveDetailCode: (code: string | null) => void;
  /** 파생 거래 뒤 주식 계좌 현금을 즉시 맞춘다. */
  refreshPortfolio: () => Promise<void>;
  claimMissionReward: (missionId: string) => Promise<{ rewardCash: number; awarded: boolean; missions: DailyMissionStatus }>;
  /** 대출을 받거나 갚고, 바뀐 계좌를 바로 화면에 반영한다. */
  loanAction: (kind: "borrow" | "repay", amount: number) => Promise<{ message: string; loan: LoanStatus }>;
}

export type CancelOrdersResult = {
  ok: boolean;
  cancelled: number;
  message: string;
};

type BackendStock = {
  code: string;
  name: string;
  genre: string;
  price: number;
  changePercent: number;
  volume: number;
  activeUsers?: number;
  marketCap?: number;
  movementWeight?: number;
  referencePrice?: number;
  limitUp?: number;
  limitDown?: number;
  restriction?: TradingRestriction | null;
};
/** indexValue는 날이 바뀌어도 이어지는 게임주 지수, indexPrevClose는 그 지수의 전날 종가다. */
type BackendMarketStatus = { indexValue: number; indexPrevClose?: number; tradingDate: string; restriction?: TradingRestriction | null };
type BackendOrderBookLevel = { price: number; quantity: number; orderCount: number };
type BackendOrderBook = { stockCode: string; bids: BackendOrderBookLevel[]; asks: BackendOrderBookLevel[] };
type BackendTrade = { side: string; quantity: number; price: number; orderType?: string; createdAt: string };

const toPrint = (trade: BackendTrade) => ({
  at: serverTimestamp(trade.createdAt) || Date.now(),
  price: trade.price,
  qty: trade.quantity,
  side: trade.side.toUpperCase() === "BUY" ? "buy" as const : "sell" as const,
});
const topLevels = (value: BackendOrderBookLevel[]) => value.slice(0, 5).map((level) => ({ price: level.price, qty: level.quantity }));
type BackendEvent = {
  stockCode: string;
  title: string;
  description?: string;
  impact?: number;
  sentiment?: string;
  source?: string;
  publishedAt?: string;
  priceAtPublish?: number;
  priceChangePercent?: number;
  priceDirection?: string;
  excerpt?: string[];
  articleUrl?: string | null;
};
type BackendPosition = { stockCode: string; quantity: number; averagePrice: number; availableQuantity?: number };
type BackendPortfolio = { cash: number; positions: BackendPosition[]; realizedProfitLoss?: number; attendanceRewardCash?: number; missionRewardCash?: number; loanBalance?: number; derivativeAssetValue?: number; derivativeRealizedProfitLoss?: number };
type BackendSettlement = { id: number; stockCode: string; side: string; quantity: number; grossAmount: number; createdAt: string };
type BackendActiveOrder = { id: number; stockCode: string; status: string };
type BackendMissionReward = { rewardCash: number; awarded: boolean; portfolio: BackendPortfolio; missions: DailyMissionStatus };
type BackendLoanAction = { message: string; loan: LoanStatus; portfolio: BackendPortfolio };

const SnapshotContext = createContext<MarketSnapshot | null>(null);
const ApiContext = createContext<MarketApi | null>(null);

const fallbackEngine = new MarketEngine(20260910);

function fallbackSnapshot(): MarketSnapshot {
  return fallbackEngine.getSnapshot();
}

function toFills(settlements: BackendSettlement[]): Portfolio["fills"] {
  return settlements.map((trade) => ({
    id: trade.id,
    code: trade.stockCode,
    side: trade.side.toUpperCase() === "BUY" ? "buy" : "sell",
    qty: trade.quantity,
    price: trade.quantity > 0 ? trade.grossAmount / trade.quantity : 0,
    at: serverTimestamp(trade.createdAt),
  }));
}

function toPortfolio(value?: BackendPortfolio): Portfolio {
  if (!value) return { cash: INITIAL_CASH, positions: {}, fills: [], realized: 0, attendanceRewardCash: 0, missionRewardCash: 0, loanBalance: 0, derivativeAssetValue: 0, derivativeRealizedProfitLoss: 0 };
  const positions: Record<string, Position> = {};
  for (const item of value.positions ?? []) {
    if (!LISTING_BY_CODE[item.stockCode] || item.quantity < 1) continue;
    positions[item.stockCode] = { code: item.stockCode, qty: item.quantity, avgCost: item.averagePrice, availableQty: item.availableQuantity };
  }
  return {
    cash: value.cash,
    positions,
    fills: [],
    realized: value.realizedProfitLoss ?? 0,
    attendanceRewardCash: value.attendanceRewardCash ?? 0,
    missionRewardCash: value.missionRewardCash ?? 0,
    loanBalance: value.loanBalance ?? 0,
    derivativeAssetValue: value.derivativeAssetValue ?? 0,
    derivativeRealizedProfitLoss: value.derivativeRealizedProfitLoss ?? 0,
  };
}

/* 소식 id는 목록 순번이 아니라 내용으로 정한다. 새 소식이 앞에 끼어도 /news/:id 주소가 같은 기사를 가리킨다. */
function stableNewsIds() {
  const used = new Set<number>();
  return {
    next(event: BackendEvent): number {
      const key = `${event.stockCode}|${event.publishedAt ?? ""}|${event.title}`;
      let hash = 0x811c9dc5;
      for (let i = 0; i < key.length; i++) hash = Math.imul(hash ^ key.charCodeAt(i), 0x01000193);
      let id = (hash >>> 0) % 2_000_000_000 + 1;
      while (used.has(id)) id += 1;
      used.add(id);
      return id;
    },
  };
}

function toSnapshot(stocks: BackendStock[], events: BackendEvent[], portfolio?: BackendPortfolio, watch: string[] = [], market?: BackendMarketStatus): MarketSnapshot {
  const quotes: Record<string, Quote> = {};
  const series: number[] = [];
  let weighted = 0;
  let weight = 0;
  for (const stock of stocks) {
    const change = Number(stock.changePercent) || 0;
    const price = Math.max(1, Math.round(stock.price));
    const prevClose = stock.referencePrice ?? Math.max(1, roundToTick(price / (1 + change / 100)));
    const band = dailyPriceBand(stock.code, prevClose);
    const listing = LISTING_BY_CODE[stock.code];
    if (!listing) continue;
    const profile = stockMarketProfile(stock.code);
    quotes[stock.code] = {
      code: stock.code,
      restriction: stock.restriction,
      activeUsers: stock.activeUsers ?? profile.activeUsers,
      marketCap: stock.marketCap ?? profile.marketCap,
      movementWeight: stock.movementWeight ?? profile.movementWeight,
      price,
      prevClose,
      open: price,
      high: price,
      low: price,
      volume: Math.max(0, Math.round(stock.volume)),
      trades: Math.max(0, Math.round(stock.volume / 12)),
      series: [prevClose, price],
      limitUp: stock.limitUp ?? band.limitUp,
      limitDown: stock.limitDown ?? band.limitDown,
      buyVolume: Math.round(stock.volume / 2),
      sellVolume: Math.round(stock.volume / 2),
      prints: [],
      asks: [],
      bids: [],
    };
    weighted += profile.marketCap * (price / prevClose);
    weight += profile.marketCap;
    series.push(price);
  }
  const newsIds = stableNewsIds();
  // 서버 값이 없을 때만 오늘 기준가 대비 비율로 대신한다. 이때는 어제 종가 자리에 1,000을 둔다.
  const indexValue = market?.indexValue ?? (weight > 0 ? (weighted / weight) * 1000 : 1000);
  const indexPrevClose = market?.indexValue != null && market.indexPrevClose ? market.indexPrevClose : 1000;
  return {
    quotes,
    marketRestriction: market?.restriction ?? null,
    index: { value: indexValue, prevClose: indexPrevClose, series: [indexPrevClose, indexValue], tradingDate: market?.tradingDate },
    codes: stocks.map((stock) => stock.code).filter((code) => Boolean(quotes[code])),
    news: events.map((event) => ({
      id: newsIds.next(event),
      at: event.publishedAt ? serverTimestamp(event.publishedAt) || Date.now() : Date.now(),
      code: event.stockCode,
      source: classifyNewsSource(event),
      title: event.title,
      description: event.description,
      excerpt: event.excerpt?.length ? event.excerpt : undefined,
      articleUrl: event.articleUrl ?? undefined,
      priceAtPublish: Number(event.priceAtPublish ?? quotes[event.stockCode]?.price ?? 0),
      priceChangeRatio: Number.isFinite(Number(event.priceChangePercent))
        ? Number(event.priceChangePercent) / 100
        : undefined,
      direction: (Number(event.priceChangePercent ?? event.impact ?? 0) >= 0 ? 1 : -1) as 1 | -1,
    })),
    portfolio: toPortfolio(portfolio),
    watch: watch.filter((code) => Boolean(quotes[code])),
    updatedAt: Date.now(),
    tickMs: 2000,
  };
}

/* 새로고침 때 백엔드 응답을 기다리며 로딩 화면만 보이지 않도록, 마지막 공개 시세를
   브라우저에 남겨 두고 먼저 그린다. 오래된 값은 시세가 크게 달라 보일 수 있어 쓰지 않는다. */
const PUBLIC_CACHE_KEY = "gamestock:public-market";
const PUBLIC_CACHE_MAX_AGE_MS = 30 * 60_000;
type PublicMarketCache = { savedAt: number; stocks: BackendStock[]; events: BackendEvent[]; market: BackendMarketStatus };

function readPublicCache(): PublicMarketCache | null {
  try {
    const cached = JSON.parse(window.localStorage.getItem(PUBLIC_CACHE_KEY) ?? "null") as PublicMarketCache | null;
    if (!cached || Date.now() - cached.savedAt > PUBLIC_CACHE_MAX_AGE_MS || !Array.isArray(cached.stocks) || cached.stocks.length === 0) return null;
    return cached;
  } catch {
    return null;
  }
}

let publicCacheSavedAt = 0;
function writePublicCache(stocks: BackendStock[], events: BackendEvent[], market: BackendMarketStatus) {
  // 2초 주기 조회마다 수십 KB를 다시 쓰지 않도록 저장 간격을 둔다.
  if (Date.now() - publicCacheSavedAt < 10_000) return;
  publicCacheSavedAt = Date.now();
  try {
    window.localStorage.setItem(PUBLIC_CACHE_KEY, JSON.stringify({ savedAt: Date.now(), stocks, events, market }));
  } catch {
    /* 저장 공간이 없거나 막혀 있으면 다음 새로고침에서 로딩 화면을 보여 준다. */
  }
}

export function MarketProvider({ children }: { children: ReactNode }) {
  const [initialCache] = useState(readPublicCache);
  const [snapshot, setSnapshot] = useState<MarketSnapshot>(() =>
    initialCache ? toSnapshot(initialCache.stocks, initialCache.events, undefined, [], initialCache.market) : fallbackSnapshot());
  const [serverAvailable, setServerAvailable] = useState(Boolean(initialCache));
  const watchRef = useRef<string[]>([]);
  /* 토글 요청과 2초 watchlist 조회의 경쟁 상태를 가리는 번호.
     mutation은 토글 때마다, settled은 그 요청이 끝날 때 따라 잡는다. */
  const watchMutationSeq = useRef(0);
  const watchSettledSeq = useRef(0);
  const hasServerSnapshotRef = useRef(Boolean(initialCache));
  const activeDetailCodeRef = useRef<string | null>(null);
  const streamRef = useRef<ReturnType<typeof openMarketStream> | null>(null);
  const streamReadyRef = useRef(false);
  const lastRestRef = useRef(0);
  const privateRevisionRef = useRef(0);
  const refreshInFlightRef = useRef(false);
  const refreshAgainRef = useRef(false);
  const detailRefreshInFlightRef = useRef(false);
  const snapshotFillsRef = useRef<Portfolio["fills"]>([]);

  useEffect(() => {
    let cancelled = false;
    const stream = openMarketStream((message) => {
      if (cancelled) return;
      if (message.type === "MARKET_SYMBOL") {
        const data = message.payload as { symbol: string; stock: BackendStock; orderbook?: BackendOrderBook; trades?: BackendTrade[] };
        if (!data?.stock || !LISTING_BY_CODE[data.symbol]) return;
        setSnapshot((current) => {
          const old = current.quotes[data.symbol];
          if (!old) return current;
          const stock = data.stock;
          const prevClose = stock.referencePrice ?? old.prevClose;
          const band = dailyPriceBand(data.symbol, prevClose);
          const prices = old.series[old.series.length - 1] === stock.price ? old.series : [...old.series, stock.price].slice(-60);
          const prints = data.trades?.length ? data.trades.map(toPrint) : old.prints;
          return { ...current, updatedAt: Date.now(), quotes: { ...current.quotes, [data.symbol]: {
            ...old, price: stock.price, volume: stock.volume, restriction: stock.restriction, prevClose,
            limitUp: stock.limitUp ?? band.limitUp, limitDown: stock.limitDown ?? band.limitDown,
            high: Math.max(old.high, stock.price), low: Math.min(old.low, stock.price), series: prices, prints,
            bids: data.orderbook ? topLevels(data.orderbook.bids) : old.bids, asks: data.orderbook ? topLevels(data.orderbook.asks) : old.asks,
          } } };
        });
      } else if (["AUTHENTICATED", "ACCOUNT_UPDATED", "PORTFOLIO_UPDATED", "ORDER_CANCELLED", "ORDER_EXPIRED", "LOAN_LIQUIDATED", "TAP_SETTLED"].includes(message.type)) {
        // 탭 베팅 결과는 화면(NxTapPage)에도 넘긴다. 계좌 현금은 아래에서 같이 갱신한다.
        if (message.type === "TAP_SETTLED") window.dispatchEvent(new CustomEvent("gamestock:tap", { detail: message.payload }));
        const data = message.payload as { portfolio?: BackendPortfolio; settlements?: BackendSettlement[] };
        if (!firebaseAuth.currentUser || !data?.portfolio) return;
        privateRevisionRef.current++;
        if (data.settlements) snapshotFillsRef.current = toFills(data.settlements);
        setSnapshot((current) => ({ ...current, portfolio: { ...toPortfolio(data.portfolio), fills: snapshotFillsRef.current }, updatedAt: Date.now() }));
      }
    }, (ready) => { streamReadyRef.current = ready; });
    streamRef.current = stream;
    const refresh = async () => {
      // A slow request must not overlap the next two-second poll. Overlapping
      // retries make a short backend restart look like a request storm.
      if (refreshInFlightRef.current) {
        // 로그인 복원처럼 진행 중인 조회와 다른 사용자 기준의 갱신이 필요하면
        // 다음 2초 주기를 기다리지 않고 끝나는 즉시 다시 조회한다.
        if (lastRestRef.current === 0) refreshAgainRef.current = true;
        return;
      }
      if (streamReadyRef.current && Date.now() - lastRestRef.current < 30_000) return;
      lastRestRef.current = Date.now();
      refreshInFlightRef.current = true;
      const refreshingUid = firebaseAuth.currentUser?.uid;
      const privateRevision = privateRevisionRef.current;
      try {
        const [stocks, events, market] = await Promise.all([
          apiFetch<BackendStock[]>("/api/stocks"),
          apiFetch<BackendEvent[]>("/api/market-events"),
          apiFetch<BackendMarketStatus>("/api/market-status"),
        ]);
        writePublicCache(stocks, events, market);
        let portfolio: BackendPortfolio | undefined;
        let fills: Portfolio["fills"] = [];
        if (firebaseAuth.currentUser) {
          try {
            const watchQuerySeq = watchMutationSeq.current;
            // 세 요청은 서로 의존하지 않으므로 한 번의 왕복 시간 안에 함께 받는다.
            const [nextPortfolio, settlements, watch] = await Promise.all([
              apiFetch<BackendPortfolio>("/api/portfolio"),
              apiFetch<BackendSettlement[]>("/api/settlements").catch(() => null),
              apiFetch<{ stockCode: string }[]>("/api/watchlist").catch(() => null),
            ]);
            portfolio = nextPortfolio;
            if (settlements) {
              fills = toFills(settlements);
            } else {
              fills = snapshotFillsRef.current;
            }
            if (firebaseAuth.currentUser?.uid !== refreshingUid) return;
            // 조회 시작 이후 토글이 있었거나 아직 끝나지 않은 토글이 남아 있으면
            // 이 응답은 토글 이전 서버 상태일 수 있으므로 watch만 버리고 낙관 상태를 유지한다.
            // (시세 갱신은 계속 적용된다)
            const togglesSettled = watchSettledSeq.current >= watchMutationSeq.current;
            if (watch && watchMutationSeq.current === watchQuerySeq && togglesSettled) {
              watchRef.current = watch.map((item) => item.stockCode);
            }
          } catch {
            /* 공개 시세는 로그인 API가 실패해도 계속 표시한다. */
          }
        }
        if (!cancelled && firebaseAuth.currentUser?.uid === refreshingUid) {
          const nextSnapshot = toSnapshot(stocks, events, portfolio, watchRef.current, market);
          nextSnapshot.portfolio.fills = fills;
          // 로그인 상태에서 계좌 조회만 실패하면 portfolio가 비어 초기 자산으로 바뀐다.
          // 그 응답으로 마지막 정상 계좌를 덮어쓰지 않는다.
          const portfolioLoaded = portfolio !== undefined || !firebaseAuth.currentUser;
          if (portfolioLoaded && privateRevision === privateRevisionRef.current) snapshotFillsRef.current = fills;
          // The two-second quote refresh must not clear the one-second detail
          // data while the order book/trade requests are in flight. Keeping
          // the previous rows prevents a visible empty-frame flicker.
          setSnapshot((current) => {
            const quotes = Object.fromEntries(Object.entries(nextSnapshot.quotes).map(([code, quote]) => {
              const previous = hasServerSnapshotRef.current ? current.quotes[code] : undefined;
              const previousSeries = previous?.series ?? [];
              const nextSeries = previousSeries.length > 0 ? [...previousSeries] : [quote.prevClose];
              if (nextSeries[nextSeries.length - 1] !== quote.price) nextSeries.push(quote.price);
              if (nextSeries.length < 2) nextSeries.push(quote.price);
              return [code, previous ? {
                ...quote,
                open: previous.open,
                high: Math.max(previous.high, quote.price),
                low: Math.min(previous.low, quote.price),
                series: nextSeries.slice(-60),
                asks: previous.asks,
                bids: previous.bids,
                prints: previous.prints,
              } : { ...quote, series: nextSeries.slice(-60) }];
            }));
            hasServerSnapshotRef.current = true;
            return { ...nextSnapshot, quotes, portfolio: portfolioLoaded && privateRevision === privateRevisionRef.current ? nextSnapshot.portfolio : current.portfolio };
          });
          setServerAvailable(true);
          stream.subscribe(activeDetailCodeRef.current ? [activeDetailCodeRef.current] : nextSnapshot.codes, activeDetailCodeRef.current);
        }
        void refreshDetails();
      } catch {
        // Keep the last usable snapshot on transient backend failures. The
        // loading screen is only for the initial connection; replacing an
        // already-rendered app on every failed poll causes visible flicker.
        if (!cancelled && !hasServerSnapshotRef.current) setServerAvailable(false);
      } finally {
        refreshInFlightRef.current = false;
        if (refreshAgainRef.current && !cancelled) {
          refreshAgainRef.current = false;
          lastRestRef.current = 0;
          void refresh();
        }
      }
    };
    const refreshDetails = async () => {
      const code = activeDetailCodeRef.current;
      // Order books and recent trades are only needed on a stock detail page.
      // Fetching every listing here made every route issue 30 requests per second.
      // Keep one active-symbol REST refresh even when the stream is connected:
      // a stream subscription can be acknowledged before its first symbol
      // snapshot, which otherwise leaves the initial book empty until the next
      // matching event happens.
      if (!code) return;
      if (detailRefreshInFlightRef.current) return;
      detailRefreshInFlightRef.current = true;
      try {
        const entries = await Promise.all([code].map(async (code) => {
          try {
            const [book, trades] = await Promise.all([
              apiFetch<BackendOrderBook>(`/api/stocks/${code}/orderbook`),
              apiFetch<BackendTrade[]>(`/api/stocks/${code}/trades`),
            ]);
            return [code, { book, trades }] as const;
          } catch {
            return null;
          }
        }));
        if (cancelled) return;
        setSnapshot((current) => {
          const quotes = { ...current.quotes };
          let changed = false;
          const sameLevels = (left: Quote["asks"], right: Quote["asks"]) =>
            left.length === right.length && left.every((level, index) => level.price === right[index]?.price && level.qty === right[index]?.qty);
          const samePrints = (left: Quote["prints"], right: Quote["prints"]) =>
            left.length === right.length && left.every((print, index) => {
              const other = right[index];
              return other && print.at === other.at && print.price === other.price && print.qty === other.qty && print.side === other.side;
            });
          for (const entry of entries) {
            if (!entry) continue;
            const [code, detail] = entry;
            const quote = quotes[code];
            if (!quote) continue;
            const asks = topLevels(detail.book.asks ?? []);
            const bids = topLevels(detail.book.bids ?? []);
            const prints = (detail.trades ?? []).map(toPrint);
            if (sameLevels(quote.asks, asks) && sameLevels(quote.bids, bids) && samePrints(quote.prints, prints)) continue;
            quotes[code] = {
              ...quote,
              asks,
              bids,
              prints,
            };
            changed = true;
          }
          return changed ? { ...current, quotes, updatedAt: Date.now() } : current;
        });
      } finally {
        detailRefreshInFlightRef.current = false;
      }
    };
    void refresh();
    const timer = window.setInterval(() => void refresh(), 2000);
    const detailTimer = window.setInterval(() => void refreshDetails(), 2000);
    const unsubscribe = onAuthStateChanged(firebaseAuth, () => {
      lastRestRef.current = 0;
      watchRef.current = [];
      snapshotFillsRef.current = [];
      setSnapshot((current) => ({ ...current, watch: [], portfolio: toPortfolio() }));
      void refresh();
    });
    return () => {
      cancelled = true;
      stream.close(); streamRef.current = null;
      window.clearInterval(timer);
      window.clearInterval(detailTimer);
      unsubscribe();
    };
  }, []);

  /**
   * 주문·취소 응답에는 계좌가 없다. 웹소켓 계좌 이벤트가 늦거나 빠지면 다음 30초 조회까지
   * 잔고가 낡으므로, 성공 직후 커밋된 계좌를 한 번 받아 온다. 그사이 웹소켓으로 더 새로운
   * 계좌가 들어왔으면 그 값을 덮어쓰지 않는다.
   */
  const refreshPortfolio = async () => {
    const owner = firebaseAuth.currentUser;
    if (!owner) return;
    const revision = privateRevisionRef.current;
    try {
      const [portfolio, settlements] = await Promise.all([
        apiFetch<BackendPortfolio>("/api/portfolio"),
        apiFetch<BackendSettlement[]>("/api/settlements").catch(() => null),
      ]);
      if (firebaseAuth.currentUser?.uid !== owner.uid || privateRevisionRef.current !== revision) return;
      privateRevisionRef.current++;
      if (settlements) snapshotFillsRef.current = toFills(settlements);
      setSnapshot((current) => ({ ...current, portfolio: { ...toPortfolio(portfolio), fills: snapshotFillsRef.current }, updatedAt: Date.now() }));
    } catch {
      /* 다음 주기 조회나 웹소켓 이벤트가 계좌를 맞춘다. */
    }
  };

  // setSnapshot과 모듈 함수만 사용하므로 렌더마다 새로 만들어도 동작이 같다.
  const claimMissionReward = async (missionId: string) => {
    const claimant = firebaseAuth.currentUser;
    if (!claimant) {
      requireSignIn();
      throw new Error("미션 보상은 로그인 후 받을 수 있습니다.");
    }
    const result = await apiFetch<BackendMissionReward>(`/api/missions/${encodeURIComponent(missionId)}/reward`, { method: "POST" });
    if (firebaseAuth.currentUser?.uid === claimant.uid) {
      setSnapshot((current) => ({
        ...current,
        portfolio: toPortfolio(result.portfolio),
        updatedAt: Date.now(),
      }));
    }
    return { rewardCash: result.rewardCash, awarded: result.awarded, missions: result.missions };
  };

  const loanAction = async (kind: "borrow" | "repay", amount: number) => {
    const borrower = firebaseAuth.currentUser;
    if (!borrower) {
      requireSignIn("/loan");
      throw new Error("대출은 로그인 후 이용할 수 있습니다.");
    }
    const result = await apiFetch<BackendLoanAction>(`/api/loan/${kind}`, { method: "POST", body: JSON.stringify({ amount }) });
    if (firebaseAuth.currentUser?.uid === borrower.uid) {
      privateRevisionRef.current++;
      setSnapshot((current) => ({
        ...current,
        portfolio: { ...toPortfolio(result.portfolio), fills: current.portfolio.fills },
        updatedAt: Date.now(),
      }));
    }
    return { message: result.message, loan: result.loan };
  };

  const api = useMemo<MarketApi>(() => ({
    orderable: (code, side) => {
      const quote = snapshot.quotes[code];
      if (!quote) return 0;
      if (side === "buy") return Math.floor(snapshot.portfolio.cash / quote.price);
      // 서버가 미정산분과 대기 중인 매도 주문을 뺀 수량을 준다. 예전 서버 응답이면 보유 수량을 쓴다.
      const position = snapshot.portfolio.positions[code];
      return position?.availableQty ?? position?.qty ?? 0;
    },
    placeOrder: async (request) => {
      if (!requireSignIn(`/market/${request.code}`)) return { ok: false, message: "로그인 후 주문할 수 있습니다." };
      try {
        const result = await apiFetch<{ message?: string; price?: number }>("/api/orders", {
          method: "POST",
          body: JSON.stringify({
            stockCode: request.code,
            side: request.side,
            quantity: request.qty,
            orderType: request.orderType ?? "MARKET",
            ...(request.price ? { price: request.price } : {}),
          }),
        });
        void refreshPortfolio();
        return {
          ok: true,
          message: result.message ?? `${request.qty}주 ${request.side === "buy" ? "매수" : "매도"} 체결`,
          fill: { id: Date.now(), at: Date.now(), code: request.code, side: request.side, qty: request.qty, price: result.price ?? snapshot.quotes[request.code]?.price ?? 0 },
        };
      } catch (error) {
        return { ok: false, message: error instanceof Error ? error.message : "주문 처리에 실패했습니다." };
      }
    },
    cancelOpenOrder: async (code) => {
      if (!requireSignIn(`/market/${code}`)) return { ok: false, cancelled: 0, message: "로그인 후 주문을 취소할 수 있습니다." };
      try {
        const orders = await apiFetch<BackendActiveOrder[]>("/api/orders");
        const target = orders.find((order) => order.stockCode === code && order.status === "OPEN");
        if (!target) return { ok: true, cancelled: 0, message: "현재 종목에 취소할 미체결 주문이 없습니다." };
        await apiFetch(`/api/orders/${target.id}`, { method: "DELETE" });
        void refreshPortfolio();
        return { ok: true, cancelled: 1, message: "현재 종목의 미체결 주문 1건을 취소했습니다." };
      } catch (error) {
        return { ok: false, cancelled: 0, message: error instanceof Error ? error.message : "주문 취소에 실패했습니다." };
      }
    },
    cancelAllOpenOrders: async () => {
      if (!requireSignIn()) return { ok: false, cancelled: 0, message: "로그인 후 주문을 취소할 수 있습니다." };
      let cancelled = 0;
      try {
        // /api/orders는 화면용으로 최대 5건만 반환하므로, 남은 주문이 없어질 때까지 반복한다.
        for (let attempt = 0; attempt < 20; attempt += 1) {
          const orders = await apiFetch<BackendActiveOrder[]>("/api/orders");
          if (orders.length === 0) break;
          await Promise.all(orders.map((order) => apiFetch(`/api/orders/${order.id}`, { method: "DELETE" })));
          cancelled += orders.length;
          if (orders.length < 5) break;
        }
        if (cancelled > 0) void refreshPortfolio();
        return {
          ok: true,
          cancelled,
          message: cancelled > 0 ? `미체결 주문 ${cancelled}건을 모두 취소했습니다.` : "취소할 미체결 주문이 없습니다.",
        };
      } catch (error) {
        return { ok: false, cancelled, message: error instanceof Error ? error.message : "전체 주문 취소에 실패했습니다." };
      }
    },
    toggleWatch: (code) => {
      if (!requireSignIn()) return;
      const exists = watchRef.current.includes(code);
      const previous = watchRef.current;
      const optimistic = exists ? previous.filter((item) => item !== code) : [...previous, code];
      watchRef.current = optimistic;
      setSnapshot((current) => ({ ...current, watch: watchRef.current }));
      const seq = ++watchMutationSeq.current;
      void apiFetch(`/api/watchlist/${code}`, { method: exists ? "DELETE" : "PUT" })
        .then(() => {
          if (exists) return;
          // 관심종목 미션은 서버가 등록 여부를 확인한 뒤 지급한다. 오늘 배정되지 않았거나 이미
          // 받은 날에 요청하면 서버가 400으로 거절하므로 배정 여부를 먼저 확인한다.
          return apiFetch<DailyMissionStatus>("/api/missions")
            .then((missions) => {
              if (!missions.availableMissionIds.includes("watch") || missions.completedMissionIds.includes("watch")) return;
              return claimMissionReward("watch")
                .then((result) => { if (result.awarded) announceMissionReward(result.rewardCash); });
            })
            .catch(() => undefined);
        })
        .catch(() => {
          // 실패한 토글은 다음 조회가 서버 값을 따르기 전까지 화면에 머무르지 않게 되돌린다.
          if (watchRef.current === optimistic) {
            watchRef.current = previous;
            setSnapshot((current) => ({ ...current, watch: watchRef.current }));
          }
        })
        .finally(() => {
          if (watchSettledSeq.current < seq) watchSettledSeq.current = seq;
        });
    },
    setActiveDetailCode: (code) => {
      activeDetailCodeRef.current = code;
      streamRef.current?.subscribe(code ? [code] : snapshot.codes, code);
    },
    refreshPortfolio,
    claimMissionReward,
    loanAction,
  }), [snapshot]);

  return (
    <ApiContext.Provider value={api}>
      <SnapshotContext.Provider value={snapshot}>
        {serverAvailable || hasServerSnapshotRef.current ? children : <BackendLoadingScreen />}
      </SnapshotContext.Provider>
    </ApiContext.Provider>
  );
}

export function useMarket(): MarketSnapshot {
  const snapshot = useContext(SnapshotContext);
  if (!snapshot) throw new Error("MarketProvider 안에서만 사용할 수 있습니다.");
  return snapshot;
}

export function useMarketApi(): MarketApi {
  const api = useContext(ApiContext);
  if (!api) throw new Error("MarketProvider 안에서만 사용할 수 있습니다.");
  return api;
}
