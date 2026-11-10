import { INITIAL_CASH } from "../engine";
import { serverTimestamp } from "../format";
import { stockMarketProfile } from "../marketCap";
import { classifyNewsSource } from "../newsSource";
import { LISTING_BY_CODE, dailyPriceBand, roundToTick } from "../universe";
import type { DailyMissionStatus, LoanStatus, MarketSnapshot, Portfolio, Position, Quote, TradingRestriction } from "../types";

/* 백엔드 응답 모양과, 그것을 화면용 MarketSnapshot으로 바꾸는 순수 함수들. React 상태는 다루지 않는다. */

export type BackendStock = {
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
export type BackendMarketStatus = { indexValue: number; indexPrevClose?: number; tradingDate: string; restriction?: TradingRestriction | null };
type BackendOrderBookLevel = { price: number; quantity: number; orderCount: number };
export type BackendOrderBook = { stockCode: string; bids: BackendOrderBookLevel[]; asks: BackendOrderBookLevel[] };
export type BackendTrade = { side: string; quantity: number; price: number; orderType?: string; createdAt: string };

export const toPrint = (trade: BackendTrade) => ({
  at: serverTimestamp(trade.createdAt) || Date.now(),
  price: trade.price,
  qty: trade.quantity,
  side: trade.side.toUpperCase() === "BUY" ? "buy" as const : "sell" as const,
});
export const topLevels = (value: BackendOrderBookLevel[]) => value.slice(0, 5).map((level) => ({ price: level.price, qty: level.quantity }));
export type BackendEvent = {
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
export type BackendPortfolio = { cash: number; positions: BackendPosition[]; realizedProfitLoss?: number; attendanceRewardCash?: number; missionRewardCash?: number; loanBalance?: number; derivativeAssetValue?: number; derivativeRealizedProfitLoss?: number };
export type BackendSettlement = { id: number; stockCode: string; side: string; quantity: number; grossAmount: number; createdAt: string };
export type BackendActiveOrder = { id: number; stockCode: string; status: string };
export type BackendMissionReward = { rewardCash: number; awarded: boolean; portfolio: BackendPortfolio; missions: DailyMissionStatus };
export type BackendLoanAction = { message: string; loan: LoanStatus; portfolio: BackendPortfolio };

export function toFills(settlements: BackendSettlement[]): Portfolio["fills"] {
  return settlements.map((trade) => ({
    id: trade.id,
    code: trade.stockCode,
    side: trade.side.toUpperCase() === "BUY" ? "buy" : "sell",
    qty: trade.quantity,
    price: trade.quantity > 0 ? trade.grossAmount / trade.quantity : 0,
    at: serverTimestamp(trade.createdAt),
  }));
}

export function toPortfolio(value?: BackendPortfolio): Portfolio {
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

export function toSnapshot(stocks: BackendStock[], events: BackendEvent[], portfolio?: BackendPortfolio, watch: string[] = [], market?: BackendMarketStatus): MarketSnapshot {
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
