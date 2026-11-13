import { INITIAL_CASH } from "./engine";
import { LISTING_BY_CODE } from "./universe";
import type { Listing, MarketSnapshot, NewsItem, Position, Quote } from "./types";

export interface Totals {
  cash: number;
  stockValue: number;
  total: number;
  /** 갚지 않은 대출 잔액. */
  loan: number;
  /** ETF·선물·옵션 평가액. */
  derivativeValue: number;
  /** 총 자산에서 대출 잔액을 뺀 순자산. */
  net: number;
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
  const derivativeValue = snapshot.portfolio.derivativeAssetValue ?? 0;
  const total = snapshot.portfolio.cash + stockValue + derivativeValue;
  // 출석·미션 보상, 승진 충전·관리자 지급, 빌린 돈은 투자 성과가 아니므로 손익에서 뺀다.
  const rewardCash = (snapshot.portfolio.attendanceRewardCash ?? 0) + (snapshot.portfolio.missionRewardCash ?? 0);
  const outside = snapshot.portfolio.adminAdjustmentCash ?? 0;
  const loan = snapshot.portfolio.loanBalance ?? 0;
  const net = total - loan;
  const pnl = net - INITIAL_CASH - rewardCash - outside;
  // 수익률의 분모는 지금까지 맡은 돈(시작금 + 충전)이다. 시작금만 쓰면 큰 데스크의 작은 손익이 부풀려진다.
  const invested = Math.max(1, INITIAL_CASH + outside);
  return { cash: snapshot.portfolio.cash, stockValue, derivativeValue, total, loan, net, pnl, pnlRate: pnl / invested };
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

/**
 * 최근 체결 내역의 매수·매도 수량. 서버 시세의 누적 buyVolume·sellVolume은 거래량을 반씩 나눈 값이라
 * 쏠림이 보이지 않아서, 매수·매도가 표시된 체결 내역으로 센다.
 */
function printVolumes(quote: Quote): { bought: number; sold: number } {
  let bought = 0;
  let sold = 0;
  for (const print of quote.prints) {
    if (print.side === "buy") bought += print.qty;
    else sold += print.qty;
  }
  return { bought, sold };
}

/** 체결강도: 최근 매수 체결량 ÷ 매도 체결량 × 100. 100이 균형이다. 매도 체결이 없으면 셀 수 없어 null. */
export function strengthRatio(quote: Quote): number | null {
  const { bought, sold } = printVolumes(quote);
  return sold > 0 ? bought / sold : null;
}

/** 최근 체결에서 매수가 차지하는 수량 비중(0~1). 체결이 없으면 0.5. */
export function buyShare(quote: Quote): number {
  const { bought, sold } = printVolumes(quote);
  return bought + sold > 0 ? bought / (bought + sold) : 0.5;
}

/** 소식 발행 시점 대비 현재까지의 시세 반영률. */
export type OpinionTone = "buy" | "sell" | "neutral" | "caution";

export interface MarketOpinion {
  tone: OpinionTone;
  /** 최근 체결 내역에서 매수 체결이 차지하는 수량 비율. 체결이 없으면 0.5. */
  buyShare: number;
  printCount: number;
  /** 시가 대비 현재가 변화율. */
  fromOpen: number;
  /** 최근 24시간 뉴스의 호재 수 - 악재 수. */
  newsBalance: number;
  newsCount: number;
  /** 각 신호를 -1·0·+1로 매긴 값과 그 합. */
  strengthScore: number;
  openScore: number;
  newsScore: number;
  score: number;
  /** 변동성 주의를 띄운 까닭. 있으면 점수보다 먼저 보여 준다. */
  caution: string | null;
}

// 매수 55%는 체결강도 약 122, 45%는 약 82에 해당한다.
const OPINION_BUY_SHARE_HIGH = 0.55;
const OPINION_BUY_SHARE_LOW = 0.45;
const OPINION_OPEN_MOVE = 0.01;
const OPINION_NEWS_WINDOW_MS = 24 * 60 * 60 * 1000;
const OPINION_LIMIT_GAP = 0.03;

/**
 * 지금 어느 쪽 힘이 센지 보여 주는 화면용 지표. 앞으로의 가격을 예측하지 않고, 봇 주문에도 쓰지 않는다.
 * 최근 체결의 매수 비율·시가 대비·최근 뉴스 방향을 각각 -1·0·+1로 매겨 더하고, +2 이상이면 매수 우위, -2 이하면 매도 우위다.
 */
export function marketOpinion(quote: Quote, news: NewsItem[], now: number): MarketOpinion {
  const share = buyShare(quote);
  const fromOpen = quote.open > 0 ? (quote.price - quote.open) / quote.open : 0;
  const recent = news.filter((item) => item.code === quote.code && now - item.at <= OPINION_NEWS_WINDOW_MS);
  const newsBalance = recent.reduce((sum, item) => sum + item.direction, 0);
  const strengthScore = share >= OPINION_BUY_SHARE_HIGH ? 1 : share <= OPINION_BUY_SHARE_LOW ? -1 : 0;
  const openScore = fromOpen >= OPINION_OPEN_MOVE ? 1 : fromOpen <= -OPINION_OPEN_MOVE ? -1 : 0;
  const newsScore = Math.sign(newsBalance);
  const score = strengthScore + openScore + newsScore;
  const caution = quote.restriction ? `${quote.restriction.kind === "CIRCUIT_BREAKER" ? "CB" : "VI"} 발동 중`
    : quote.limitUp - quote.price <= quote.price * OPINION_LIMIT_GAP ? "상한가까지 3% 이내"
    : quote.price - quote.limitDown <= quote.price * OPINION_LIMIT_GAP ? "하한가까지 3% 이내"
    : null;
  const tone: OpinionTone = caution ? "caution" : score >= 2 ? "buy" : score <= -2 ? "sell" : "neutral";
  return { tone, buyShare: share, printCount: quote.prints.length, fromOpen, newsBalance, newsCount: recent.length, strengthScore, openScore, newsScore, score, caution };
}

export function newsEffect(priceAtPublish: number, quote: Quote | undefined): number {
  if (!quote || priceAtPublish <= 0) return 0;
  return (quote.price - priceAtPublish) / priceAtPublish;
}

export type SortKey = "name" | "price" | "rate" | "volume" | "open";
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
      case "open":
        return quote.open;
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
