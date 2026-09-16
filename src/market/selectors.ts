import { INITIAL_CASH } from "./engine";
import { LISTING_BY_CODE } from "./universe";
import type { Listing, MarketSnapshot, Position, Quote } from "./types";

export interface Totals {
  cash: number;
  stockValue: number;
  total: number;
  /** 세션 시작 자본 대비 손익. */
  pnl: number;
  pnlRate: number;
}

export function totals(snapshot: MarketSnapshot): Totals {
  let stockValue = 0;
  for (const position of Object.values(snapshot.portfolio.positions)) {
    const quote = snapshot.quotes[position.code];
    if (quote) stockValue += quote.price * position.qty;
  }
  const total = snapshot.portfolio.cash + stockValue;
  const pnl = total - INITIAL_CASH;
  return { cash: snapshot.portfolio.cash, stockValue, total, pnl, pnlRate: pnl / INITIAL_CASH };
}

export interface Holding {
  listing: Listing;
  position: Position;
  quote: Quote;
  value: number;
  cost: number;
  pnl: number;
  pnlRate: number;
  weight: number;
}

export function holdings(snapshot: MarketSnapshot, total: number): Holding[] {
  const rows: Holding[] = [];
  for (const position of Object.values(snapshot.portfolio.positions)) {
    const listing = LISTING_BY_CODE[position.code];
    const quote = snapshot.quotes[position.code];
    if (!listing || !quote) continue;
    const value = quote.price * position.qty;
    const cost = position.avgCost * position.qty;
    rows.push({
      listing,
      position,
      quote,
      value,
      cost,
      pnl: value - cost,
      pnlRate: cost > 0 ? (value - cost) / cost : 0,
      weight: total > 0 ? value / total : 0,
    });
  }
  return rows.sort((a, b) => b.value - a.value);
}

export function sessionRate(quote: Quote): number {
  return (quote.price - quote.prevClose) / quote.prevClose;
}

/** 세션 누적 거래대금. */
export function turnover(snapshot: MarketSnapshot): number {
  let total = 0;
  for (const code of snapshot.codes) {
    const quote = snapshot.quotes[code];
    if (quote) total += quote.price * quote.volume;
  }
  return total;
}

/** 체결강도: 매수 체결량 ÷ 매도 체결량 × 100. 100이 균형이다. */
export function strengthRatio(quote: Quote): number {
  return quote.sellVolume > 0 ? quote.buyVolume / quote.sellVolume : 1;
}

/** 매수 체결 비중(0~1). 체결 흐름 막대에 쓴다. */
export function buyShare(quote: Quote): number {
  const total = quote.buyVolume + quote.sellVolume;
  return total > 0 ? quote.buyVolume / total : 0.5;
}

/** 등락 상위·하위 종목. */
export function movers(snapshot: MarketSnapshot, direction: 1 | -1, count: number): string[] {
  return [...snapshot.codes]
    .filter((code) => snapshot.quotes[code])
    .sort((a, b) => (sessionRate(snapshot.quotes[b]) - sessionRate(snapshot.quotes[a])) * direction)
    .slice(0, count);
}

/** 가장 많이 거래된 종목. */
export function busiest(snapshot: MarketSnapshot): string | null {
  let best: string | null = null;
  let bestVolume = -1;
  for (const code of snapshot.codes) {
    const quote = snapshot.quotes[code];
    if (!quote) continue;
    if (quote.volume > bestVolume) {
      bestVolume = quote.volume;
      best = code;
    }
  }
  return best;
}

/** 소식 발행 시점 대비 현재까지의 시세 반영률. */
export function newsEffect(priceAtPublish: number, quote: Quote | undefined): number {
  if (!quote || priceAtPublish <= 0) return 0;
  return (quote.price - priceAtPublish) / priceAtPublish;
}

export type SortKey = "name" | "price" | "rate" | "volume";
export type SortDirection = "asc" | "desc";

export function sortCodes(
  snapshot: MarketSnapshot,
  key: SortKey,
  direction: SortDirection,
): string[] {
  const factor = direction === "asc" ? 1 : -1;
  const value = (code: string): number | string => {
    const quote = snapshot.quotes[code];
    if (!quote) return 0;
    switch (key) {
      case "price":
        return quote.price;
      case "rate":
        return sessionRate(quote);
      case "volume":
        return quote.volume;
      case "name":
        return LISTING_BY_CODE[code]?.name ?? code;
    }
  };
  return [...snapshot.codes].sort((a, b) => {
    const left = value(a);
    const right = value(b);
    if (typeof left === "string" || typeof right === "string") {
      return String(left).localeCompare(String(right), "ko") * factor;
    }
    if (left === right) return a.localeCompare(b);
    return (left - right) * factor;
  });
}
