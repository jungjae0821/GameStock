import { useEffect, useRef } from "react";
import { onAuthStateChanged } from "firebase/auth";
import { apiFetch } from "../../lib/api";
import { firebaseAuth } from "../../lib/firebase";
import { openMarketStream } from "../stream";
import { LISTING_BY_CODE, dailyPriceBand } from "../universe";
import type { Portfolio, Quote } from "../types";
import {
  toFills, toPortfolio, toPrint, toSnapshot, topLevels,
  type BackendEvent, type BackendMarketStatus, type BackendOrderBook, type BackendPortfolio, type BackendSettlement, type BackendStock, type BackendTrade,
} from "./backendMapping";
import { writePublicCache } from "./publicCache";
import type { MarketSync } from "./syncRefs";

/**
 * 시세 연결. 웹소켓으로 종목·계좌 이벤트를 받고, 2초마다 REST로 전체 시세와 계좌를 맞추며,
 * 상세 화면의 종목은 호가·체결을 따로 조회한다. 로그인 사용자가 바뀌면 계좌를 비우고 다시 조회한다.
 */
export function useMarketFeed(sync: MarketSync, setServerAvailable: (available: boolean) => void) {
  const streamReadyRef = useRef(false);
  const lastRestRef = useRef(0);
  const refreshInFlightRef = useRef(false);
  const refreshAgainRef = useRef(false);
  const detailRefreshInFlightRef = useRef(false);

  useEffect(() => {
    const {
      setSnapshot, watchRef, watchMutationSeq, watchSettledSeq, hasServerSnapshotRef,
      activeDetailCodeRef, streamRef, privateRevisionRef, snapshotFillsRef,
    } = sync;
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
    // 연결은 마운트 때 한 번만 연다. sync와 setServerAvailable은 ref·setState라 바뀌지 않는다.
  }, []);
}
