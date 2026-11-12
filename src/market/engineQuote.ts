/**
 * 시세 엔진의 종목별 시세 계산. 시작 시세 생성, 틱마다 가격·체결량·체결 흐름 갱신,
 * 표시용 호가창 분배를 담는다. 난수는 엔진의 rng 를 그대로 받아 같은 순서로 뽑는다.
 */
import { dailyPriceBand, nextTickPrice, previousTickPrice, roundToTick } from "./universe";
import { stockMarketProfile } from "./marketCap";
import { gaussian } from "./engineRandom";
import type { BookLevel, Listing, Quote, TradePrint } from "./types";

export const SERIES_LIMIT = 720;
const PRINT_LIMIT = 14;
const BOOK_DEPTH = 5;
const TICK_SIGMA = 0.005;
const MEAN_REVERSION = 0.012;

/** 진행 중인 소식 충격. 남은 틱 동안 틱마다 per 만큼 한 방향으로 민다. */
export type Shock = { remaining: number; per: number; direction: 1 | -1 };

/** 세션 시작 시세를 만든다. 호가창은 호출한 쪽에서 refreshBook 으로 채운다. */
export function createQuote(listing: Listing, rng: () => number): Quote {
  const profile = stockMarketProfile(listing.code);
  const open = roundToTick(listing.prevClose * (1 + gaussian(rng) * 0.003));
  const { limitUp, limitDown } = dailyPriceBand(listing.code, listing.prevClose);
  return {
    code: listing.code,
    activeUsers: profile.activeUsers,
    marketCap: profile.marketCap,
    movementWeight: profile.movementWeight,
    price: open,
    prevClose: listing.prevClose,
    open,
    high: open,
    low: open,
    volume: Math.round(listing.activity * profile.liquidityWeight * 12_000 * (0.6 + rng())),
    trades: Math.round(listing.activity * profile.liquidityWeight * 900 * (0.6 + rng())),
    series: [open],
    limitUp,
    limitDown,
    buyVolume: Math.round(listing.activity * profile.liquidityWeight * 5_000 * (0.5 + rng() * 0.6)),
    sellVolume: Math.round(listing.activity * profile.liquidityWeight * 5_000 * (0.5 + rng() * 0.6)),
    prints: [],
    asks: [],
    bids: [],
  };
}

/** 한 틱 동안 종목 하나의 가격·거래량·체결 흐름·호가창을 갱신한다. 충격 차감은 엔진이 한다. */
export function advanceQuote(
  quote: Quote,
  listing: Listing,
  shock: Shock | undefined,
  now: number,
  rng: () => number,
): void {
  const profile = stockMarketProfile(listing.code);
  const z = gaussian(rng);
  /* 모든 종목이 같은 틱에 움직이지 않는다: 활동성에 따라 이번 틱에 움직일 종목을 뽑는다. */
  const moves = rng() < 0.5 + listing.activity * 0.09 || shock != null;
  const impulse = shock ? shock.direction * shock.per : 0;
  const reversion = Math.log(quote.prevClose / quote.price) * MEAN_REVERSION;
  const drift = moves
    ? (z * TICK_SIGMA * (1 + listing.activity * 0.2) + impulse + reversion) * profile.movementWeight
    : 0;
  /* 가격은 항상 호가 단위로만 움직인다. 틱 미만 변화는 한 단위로 밀어준다. */
  const target = roundToTick(quote.price * Math.exp(drift));
  quote.price = Math.min(quote.limitUp, Math.max(quote.limitDown,
    target !== quote.price ? target
    : moves && Math.abs(drift) > 0.0005 ? (drift > 0 ? nextTickPrice(quote.price) : previousTickPrice(quote.price))
    : quote.price));
  quote.high = Math.max(quote.high, quote.price);
  quote.low = Math.min(quote.low, quote.price);
  const step = Math.round(listing.activity * profile.liquidityWeight * 620
    * (0.35 + Math.abs(z) * 1.4 + (shock ? 1.5 : 0)) * (0.7 + rng() * 0.6));
  quote.volume += step;
  quote.trades += Math.max(1, Math.round(step / (6 + rng() * 5)));
  quote.series.push(quote.price);
  if (quote.series.length > SERIES_LIMIT) quote.series.splice(0, quote.series.length - SERIES_LIMIT);

  /* 체결 흐름: 이번 틱의 체결량을 매수·매도로 나누고 개별 체결로 쪼갠다. */
  const pressure = Math.max(-0.42, Math.min(0.42, drift * 42));
  const buyShare = 0.5 + pressure;
  const buyStep = Math.round(step * buyShare);
  quote.buyVolume += buyStep;
  quote.sellVolume += step - buyStep;
  const prints = 2 + Math.floor(rng() * 4);
  for (let i = 0; i < prints; i += 1) {
    const side: TradePrint["side"] = rng() < buyShare ? "buy" : "sell";
    const at = now - (prints - i - 1) * 420;
    const distance = Math.floor(rng() * 3);
    let printPrice = quote.price;
    for (let step = 0; step < distance; step += 1) {
      printPrice = side === "buy" ? previousTickPrice(printPrice) : nextTickPrice(printPrice);
    }
    const print: TradePrint = {
      at,
      price: i === 0 ? quote.price : printPrice,
      qty: Math.max(1, Math.round((step / prints) * (0.9 + rng() * 1.7))),
      side,
    };
    print.price = Math.min(quote.limitUp, Math.max(quote.limitDown, print.price));
    quote.prints.unshift(print);
  }
  if (quote.prints.length > PRINT_LIMIT) quote.prints.splice(PRINT_LIMIT);
  refreshBook(quote, pressure, rng);
}

/**
 * 호가창은 별도 주문 스트림이 없으므로 지금 틱의 체결량과 매수·매도 압력을
 * 호가 단위로 분배해 만든 표시용 데이터다.
 */
export function refreshBook(quote: Quote, pressure: number, rng: () => number): void {
  const profile = stockMarketProfile(quote.code);
  const base = Math.max(12, Math.round((quote.volume / 900) * profile.liquidityWeight * (0.4 + pressure * 0.5)));
  const asks: BookLevel[] = [];
  const bids: BookLevel[] = [];
  let askPrice = quote.price;
  let bidPrice = quote.price;
  for (let i = 0; i < BOOK_DEPTH; i += 1) {
    const near = BOOK_DEPTH - i;
    const nextAsk = nextTickPrice(askPrice);
    if (nextAsk <= askPrice || nextAsk > quote.limitUp) break;
    askPrice = nextAsk;
    asks.push({
      price: askPrice,
      qty: Math.max(1, Math.round((base * near * (1 + pressure * 0.9) * (0.7 + rng() * 0.6)) / BOOK_DEPTH)),
    });
  }

  for (let i = 0; i < BOOK_DEPTH; i += 1) {
    const near = BOOK_DEPTH - i;
    const nextBid = previousTickPrice(bidPrice);
    if (nextBid >= bidPrice || nextBid < quote.limitDown) break;
    bidPrice = nextBid;
    bids.push({
      price: bidPrice,
      qty: Math.max(1, Math.round((base * near * (1 - pressure * 0.9) * (0.7 + rng() * 0.6)) / BOOK_DEPTH)),
    });
  }
  quote.asks = asks.reverse(); /* 위쪽에서 아래로 내려오며 가격이 낮아진다 */
  quote.bids = bids;
}
