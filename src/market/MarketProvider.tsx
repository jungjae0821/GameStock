import { createContext, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { onAuthStateChanged } from "firebase/auth";
import { firebaseAuth } from "../lib/firebase";
import { apiFetch } from "../lib/api";
import { MarketEngine } from "./engine";
import { serverTimestamp } from "./format";
import { LISTING_BY_CODE, roundToTick } from "./universe";
import type { MarketSnapshot, OrderRequest, OrderResult, Portfolio, Position, Quote } from "./types";

export interface MarketApi {
  placeOrder: (request: OrderRequest) => Promise<OrderResult>;
  orderable: (code: string, side: "buy" | "sell") => number;
  toggleWatch: (code: string) => void;
  reset: () => void;
}

type BackendStock = { code: string; name: string; genre: string; price: number; changePercent: number; volume: number };
type BackendOrderBookLevel = { price: number; quantity: number; orderCount: number };
type BackendOrderBook = { stockCode: string; bids: BackendOrderBookLevel[]; asks: BackendOrderBookLevel[] };
type BackendTrade = { side: string; quantity: number; price: number; orderType?: string; createdAt: string };
type BackendEvent = {
  stockCode: string;
  title: string;
  impact?: number;
  sentiment?: string;
  publishedAt?: string;
  priceAtPublish?: number;
  priceChangePercent?: number;
  priceDirection?: string;
};
type BackendPosition = { stockCode: string; quantity: number; averagePrice: number };
type BackendPortfolio = { cash: number; positions: BackendPosition[]; realizedProfitLoss?: number };

const SnapshotContext = createContext<MarketSnapshot | null>(null);
const ApiContext = createContext<MarketApi | null>(null);

const fallbackEngine = new MarketEngine(20260910);

function fallbackSnapshot(): MarketSnapshot {
  return fallbackEngine.getSnapshot();
}

function toPortfolio(value?: BackendPortfolio): Portfolio {
  if (!value) return fallbackSnapshot().portfolio;
  const positions: Record<string, Position> = {};
  for (const item of value.positions ?? []) {
    if (!LISTING_BY_CODE[item.stockCode] || item.quantity < 1) continue;
    positions[item.stockCode] = { code: item.stockCode, qty: item.quantity, avgCost: item.averagePrice };
  }
  return { cash: value.cash, positions, fills: [], realized: value.realizedProfitLoss ?? 0 };
}

function toSnapshot(stocks: BackendStock[], events: BackendEvent[], portfolio?: BackendPortfolio, watch: string[] = []): MarketSnapshot {
  const quotes: Record<string, Quote> = {};
  const series: number[] = [];
  let weighted = 0;
  let weight = 0;
  for (const stock of stocks) {
    const change = Number(stock.changePercent) || 0;
    const price = Math.max(1, Math.round(stock.price));
    const prevClose = Math.max(1, roundToTick(price / (1 + change / 100)));
    const listing = LISTING_BY_CODE[stock.code];
    if (!listing) continue;
    quotes[stock.code] = {
      code: stock.code,
      price,
      prevClose,
      open: price,
      high: price,
      low: price,
      volume: Math.max(0, Math.round(stock.volume)),
      trades: Math.max(0, Math.round(stock.volume / 12)),
      series: [prevClose, price],
      limitUp: roundToTick(prevClose * 1.3),
      limitDown: Math.max(1, roundToTick(prevClose * 0.7)),
      buyVolume: Math.round(stock.volume / 2),
      sellVolume: Math.round(stock.volume / 2),
      prints: [],
      asks: [],
      bids: [],
    };
    const activity = listing.activity;
    weighted += activity * (price / prevClose);
    weight += activity;
    series.push(price);
  }
  const indexValue = weight > 0 ? (weighted / weight) * 1000 : 1000;
  return {
    quotes,
    index: { value: indexValue, prevClose: 1000, series: [1000, indexValue] },
    codes: stocks.map((stock) => stock.code).filter((code) => Boolean(quotes[code])),
    news: events.map((event, index) => ({
      id: index + 1,
      at: event.publishedAt ? serverTimestamp(event.publishedAt) || Date.now() : Date.now(),
      code: event.stockCode,
      source: "미디어 보도" as const,
      title: event.title,
      priceAtPublish: Number(event.priceAtPublish ?? quotes[event.stockCode]?.price ?? 0),
      direction: (Number(event.priceChangePercent ?? event.impact ?? 0) >= 0 ? 1 : -1) as 1 | -1,
    })),
    portfolio: toPortfolio(portfolio),
    watch: watch.filter((code) => Boolean(quotes[code])),
    updatedAt: Date.now(),
    tickMs: 2000,
  };
}

export function MarketProvider({ children }: { children: ReactNode }) {
  const [snapshot, setSnapshot] = useState<MarketSnapshot>(fallbackSnapshot);
  const [serverAvailable, setServerAvailable] = useState(false);
  const watchRef = useRef<string[]>([]);
  const hasServerSnapshotRef = useRef(false);

  useEffect(() => {
    let cancelled = false;
    const refresh = async () => {
      try {
        const [stocks, events] = await Promise.all([
          apiFetch<BackendStock[]>("/api/stocks"),
          apiFetch<BackendEvent[]>("/api/market-events"),
        ]);
        let portfolio: BackendPortfolio | undefined;
        if (firebaseAuth.currentUser) {
          try {
            portfolio = await apiFetch<BackendPortfolio>("/api/portfolio");
            const watch = await apiFetch<{ stockCode: string }[]>("/api/watchlist");
            watchRef.current = watch.map((item) => item.stockCode);
          } catch {
            /* 공개 시세는 로그인 API가 실패해도 계속 표시한다. */
          }
        }
        if (!cancelled) {
          const nextSnapshot = toSnapshot(stocks, events, portfolio, watchRef.current);
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
                prevClose: previous.prevClose,
                series: nextSeries.slice(-60),
                asks: previous.asks,
                bids: previous.bids,
                prints: previous.prints,
              } : { ...quote, series: nextSeries.slice(-60) }];
            }));
            hasServerSnapshotRef.current = true;
            return { ...nextSnapshot, quotes };
          });
          setServerAvailable(true);
        }
        void refreshDetails();
      } catch {
        if (!cancelled) setServerAvailable(false);
      }
    };
    const refreshDetails = async () => {
      const entries = await Promise.all(Object.keys(LISTING_BY_CODE).map(async (code) => {
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
          const asks = (detail.book.asks ?? []).slice(0, 5).map((level) => ({ price: level.price, qty: level.quantity }));
          const bids = (detail.book.bids ?? []).slice(0, 5).map((level) => ({ price: level.price, qty: level.quantity }));
          const prints = (detail.trades ?? []).map((trade) => ({
            at: serverTimestamp(trade.createdAt) || Date.now(),
            price: trade.price,
            qty: trade.quantity,
            side: trade.side.toUpperCase() === "BUY" ? "buy" as const : "sell" as const,
          }));
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
    };
    void refresh();
    const timer = window.setInterval(() => void refresh(), 2000);
    const detailTimer = window.setInterval(() => void refreshDetails(), 1000);
    const unsubscribe = onAuthStateChanged(firebaseAuth, () => void refresh());
    return () => {
      cancelled = true;
      window.clearInterval(timer);
      window.clearInterval(detailTimer);
      unsubscribe();
    };
  }, []);

  const api = useMemo<MarketApi>(() => ({
    orderable: (code, side) => {
      const quote = snapshot.quotes[code];
      if (!quote) return 0;
      return side === "buy" ? Math.floor(snapshot.portfolio.cash / quote.price) : snapshot.portfolio.positions[code]?.qty ?? 0;
    },
    placeOrder: async (request) => {
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
        return {
          ok: true,
          message: result.message ?? `${request.qty}주 ${request.side === "buy" ? "매수" : "매도"} 체결`,
          fill: { id: Date.now(), at: Date.now(), code: request.code, side: request.side, qty: request.qty, price: result.price ?? snapshot.quotes[request.code]?.price ?? 0 },
        };
      } catch (error) {
        return { ok: false, message: error instanceof Error ? error.message : "주문 처리에 실패했습니다." };
      }
    },
    toggleWatch: (code) => {
      const exists = watchRef.current.includes(code);
      watchRef.current = exists ? watchRef.current.filter((item) => item !== code) : [...watchRef.current, code];
      setSnapshot((current) => ({ ...current, watch: watchRef.current }));
      void apiFetch(`/api/watchlist/${code}`, { method: exists ? "DELETE" : "PUT" }).catch(() => undefined);
    },
    reset: () => {
      if (firebaseAuth.currentUser) {
        void apiFetch("/api/account/reset", { method: "DELETE" }).then(() => window.location.reload()).catch(() => undefined);
      } else {
        fallbackEngine.reset();
        setSnapshot(fallbackSnapshot());
      }
    },
  }), [snapshot]);

  return (
    <ApiContext.Provider value={api}>
      <SnapshotContext.Provider value={snapshot}>
        {children}
        {!serverAvailable && <span className="connection-fallback" role="status">백엔드 연결 대기 중 · 공개 모의 시세 표시</span>}
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
