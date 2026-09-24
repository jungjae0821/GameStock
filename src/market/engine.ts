import { DAILY_LIMIT, LISTINGS, LISTING_BY_CODE, roundToTick, tickSize } from "./universe";
import { generateNews } from "./newsTemplates";
import { won } from "./format";
import type {
  BookLevel,
  Fill,
  MarketIndex,
  MarketSnapshot,
  NewsItem,
  OrderRequest,
  OrderResult,
  Portfolio,
  Position,
  Quote,
  TradePrint,
} from "./types";

export const TICK_MS = 2000;
export const INITIAL_CASH = 1_000_000;

const STORAGE_KEY = "ssokdex.market.v1";
const SERIES_LIMIT = 720;
const NEWS_LIMIT = 60;
const PRINT_LIMIT = 14;
const BOOK_DEPTH = 5;
/** 지수 기준값. 세션 시작 시점이 1000이다. */
const INDEX_BASE = 1000;
const TICK_SIGMA = 0.005;
const MEAN_REVERSION = 0.012;

type Stored = {
  cash: number;
  positions: Record<string, Position>;
  fills: Fill[];
  realized: number;
  watch: string[];
};

type Shock = { remaining: number; per: number; direction: 1 | -1 };

function mulberry32(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function emptyPortfolio(): Portfolio {
  return { cash: INITIAL_CASH, positions: {}, fills: [], realized: 0 };
}

export class MarketEngine {
  private readonly rng: () => number;
  private readonly quotes = new Map<string, Quote>();
  private readonly shocks = new Map<string, Shock>();
  private readonly listeners = new Set<() => void>();
  private portfolio: Portfolio = emptyPortfolio();
  private watch = new Set<string>();
  private news: NewsItem[] = [];
  private index: MarketIndex = { value: INDEX_BASE, prevClose: INDEX_BASE, series: [INDEX_BASE] };
  private snapshot: MarketSnapshot;
  private timer: ReturnType<typeof setInterval> | null = null;
  private newsId = 0;
  private fillId = 0;
  private nextNewsAt = 0;

  constructor(seed = Date.now()) {
    this.rng = mulberry32(seed);
    for (const listing of LISTINGS) {
      const open = roundToTick(listing.prevClose * (1 + this.gauss() * 0.003));
      const limitUp = roundToTick(listing.prevClose * (1 + DAILY_LIMIT));
      const limitDown = Math.max(1, roundToTick(listing.prevClose * (1 - DAILY_LIMIT)));
      const quote: Quote = {
        code: listing.code,
        price: open,
        prevClose: listing.prevClose,
        open,
        high: open,
        low: open,
        volume: Math.round(listing.activity * 12_000 * (0.6 + this.rng())),
        trades: Math.round(listing.activity * 900 * (0.6 + this.rng())),
        series: [open],
        limitUp,
        limitDown,
        buyVolume: Math.round(listing.activity * 5_000 * (0.5 + this.rng() * 0.6)),
        sellVolume: Math.round(listing.activity * 5_000 * (0.5 + this.rng() * 0.6)),
        prints: [],
        asks: [],
        bids: [],
      };
      this.quotes.set(listing.code, quote);
      this.refreshBook(quote, 0);
    }
    this.restore();
    this.seedNews();
    this.snapshot = this.build();
  }

  // ---------------------------------------------------------------- 구독

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getSnapshot = (): MarketSnapshot => this.snapshot;

  start(): void {
    if (this.timer !== null) return;
    this.nextNewsAt = Date.now() + 5000 + this.rng() * 5000;
    this.timer = setInterval(() => this.tick(), TICK_MS);
  }

  stop(): void {
    if (this.timer === null) return;
    clearInterval(this.timer);
    this.timer = null;
  }

  // ---------------------------------------------------------------- 상태

  private build(): MarketSnapshot {
    return {
      quotes: Object.fromEntries(this.quotes),
      index: this.index,
      codes: LISTINGS.map((listing) => listing.code),
      news: this.news,
      portfolio: this.portfolio,
      watch: [...this.watch],
      updatedAt: Date.now(),
      tickMs: TICK_MS,
    };
  }

  private notify(): void {
    this.snapshot = this.build();
    for (const listener of this.listeners) listener();
  }

  private gauss(): number {
    let u = 0;
    let v = 0;
    while (u === 0) u = this.rng();
    while (v === 0) v = this.rng();
    const value = Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    return Math.max(-3.5, Math.min(3.5, value));
  }

  // ---------------------------------------------------------------- 시세

  private tick(): void {
    const now = Date.now();
    for (const listing of LISTINGS) {
      const quote = this.quotes.get(listing.code);
      if (!quote) continue;
      const z = this.gauss();
      const shock = this.shocks.get(listing.code);
      const impulse = shock ? shock.direction * shock.per : 0;
      const reversion = Math.log(quote.prevClose / quote.price) * MEAN_REVERSION;
      const drift = z * TICK_SIGMA * (1 + listing.activity * 0.2) + impulse + reversion;
      quote.price = Math.min(quote.limitUp, Math.max(quote.limitDown, roundToTick(quote.price * Math.exp(drift))));
      quote.high = Math.max(quote.high, quote.price);
      quote.low = Math.min(quote.low, quote.price);
      const step = Math.round(listing.activity * 620 * (0.35 + Math.abs(z) * 1.4 + (shock ? 1.5 : 0)) * (0.7 + this.rng() * 0.6));
      quote.volume += step;
      quote.trades += Math.max(1, Math.round(step / (9 + this.rng() * 7)));
      quote.series.push(quote.price);
      if (quote.series.length > SERIES_LIMIT) quote.series.splice(0, quote.series.length - SERIES_LIMIT);

      /* 체결 흐름: 이번 틱의 체결량을 매수·매도로 나누고 개별 체결로 쪼갠다. */
      const pressure = Math.max(-0.42, Math.min(0.42, drift * 42));
      const buyShare = 0.5 + pressure;
      const buyStep = Math.round(step * buyShare);
      quote.buyVolume += buyStep;
      quote.sellVolume += step - buyStep;
      const prints = 1 + Math.floor(this.rng() * 3);
      for (let i = 0; i < prints; i += 1) {
        const side: TradePrint["side"] = this.rng() < buyShare ? "buy" : "sell";
        const at = now - (prints - i - 1) * 420;
        const print: TradePrint = {
          at,
          price: i === 0 ? quote.price : side === "buy" ? quote.price - tickSize(quote.price) * Math.floor(this.rng() * 3) : quote.price + tickSize(quote.price) * Math.floor(this.rng() * 3),
          qty: Math.max(1, Math.round((step / prints) * (0.5 + this.rng()))),
          side,
        };
        print.price = Math.min(quote.limitUp, Math.max(quote.limitDown, print.price));
        quote.prints.unshift(print);
      }
      if (quote.prints.length > PRINT_LIMIT) quote.prints.splice(PRINT_LIMIT);
      this.refreshBook(quote, pressure);

      if (shock) {
        shock.remaining -= 1;
        if (shock.remaining <= 0) this.shocks.delete(listing.code);
      }
    }
    this.advanceIndex();
    if (now >= this.nextNewsAt) this.publishNews(now);
    this.notify();
  }

  /**
   * 호가창은 별도 주문 스트림이 없으므로 지금 틱의 체결량과 매수·매도 압력을
   * 호가 단위로 분배해 만든 표시용 데이터다.
   */
  private refreshBook(quote: Quote, pressure: number): void {
    const size = tickSize(quote.price);
    const base = Math.max(12, Math.round((quote.volume / 900) * (0.4 + pressure * 0.5)));
    const asks: BookLevel[] = [];
    const bids: BookLevel[] = [];
    for (let i = 0; i < BOOK_DEPTH; i += 1) {
      const near = BOOK_DEPTH - i;
      asks.push({
        price: Math.min(quote.limitUp, quote.price + size * (i + 1)),
        qty: Math.max(1, Math.round((base * near * (1 + pressure * 0.9) * (0.7 + this.rng() * 0.6)) / BOOK_DEPTH)),
      });
      bids.push({
        price: Math.max(quote.limitDown, quote.price - size * (i + 1)),
        qty: Math.max(1, Math.round((base * near * (1 - pressure * 0.9) * (0.7 + this.rng() * 0.6)) / BOOK_DEPTH)),
      });
    }
    quote.asks = asks.reverse(); /* 위쪽에서 아래로 내려오며 가격이 낮아진다 */
    quote.bids = bids;
  }

  /** 거래량 계수로 가중한 동일 비중 지수. 세션 시작이 1000이다. */
  private advanceIndex(): void {
    let weighted = 0;
    let weight = 0;
    for (const listing of LISTINGS) {
      const quote = this.quotes.get(listing.code);
      if (!quote) continue;
      weighted += listing.activity * (quote.price / quote.prevClose);
      weight += listing.activity;
    }
    const value = weight > 0 ? (weighted / weight) * INDEX_BASE : INDEX_BASE;
    this.index = { ...this.index, value, series: [...this.index.series, value].slice(-SERIES_LIMIT) };
  }

  private publishNews(now: number): void {
    const weighted = LISTINGS.filter((listing) => !this.shocks.has(listing.code));
    const pool = weighted.length > 0 ? weighted : LISTINGS;
    const listing = pool[Math.floor(this.rng() * pool.length)];
    const quote = this.quotes.get(listing.code);
    if (!quote) return;
    /* 최근 소식과 제목이 겹치면 다시 뽑는다. 같은 헤드라인이 줄줄이 붙는 걸 막는다. */
    let generated = generateNews(listing, this.rng);
    for (let attempt = 0; attempt < 4 && this.news.slice(0, 8).some((item) => item.title === generated.title); attempt += 1) {
      generated = generateNews(listing, this.rng);
    }
    const item: NewsItem = {
      id: (this.newsId += 1),
      at: now,
      code: listing.code,
      source: generated.source,
      title: generated.title,
      priceAtPublish: quote.price,
      direction: generated.direction,
    };
    this.news = [item, ...this.news].slice(0, NEWS_LIMIT);
    this.shocks.set(listing.code, {
      remaining: 3,
      per: generated.shock / 3,
      direction: generated.direction,
    });
    this.nextNewsAt = now + 6000 + this.rng() * 14_000;
  }

  /** 세션 시작 시점에 이미 지나간 소식 몇 건을 깔아 둔다. */
  private seedNews(): void {
    const now = Date.now();
    const offsets = [38_000, 126_000, 254_000, 402_000];
    for (const offset of offsets.reverse()) {
      const listing = LISTINGS[Math.floor(this.rng() * LISTINGS.length)];
      const quote = this.quotes.get(listing.code);
      if (!quote) continue;
      const generated = generateNews(listing, this.rng);
      const item: NewsItem = {
        id: (this.newsId += 1),
        at: now - offset,
        code: listing.code,
        source: generated.source,
        title: generated.title,
        priceAtPublish: roundToTick(quote.price / (1 + generated.direction * generated.shock * 1.4)),
        direction: generated.direction,
      };
      this.news = [item, ...this.news];
    }
  }

  // ---------------------------------------------------------------- 주문

  orderable(code: string, side: "buy" | "sell"): number {
    const quote = this.quotes.get(code);
    if (!quote) return 0;
    if (side === "buy") return Math.floor(this.portfolio.cash / quote.price);
    return this.portfolio.positions[code]?.qty ?? 0;
  }

  placeOrder({ code, side, qty }: OrderRequest): OrderResult {
    const quote = this.quotes.get(code);
    if (!quote) return { ok: false, message: "상장되지 않은 종목입니다." };
    if (!Number.isInteger(qty) || qty < 1) return { ok: false, message: "수량은 1주 이상 정수로 입력하세요." };
    const price = quote.price;
    const amount = price * qty;

    if (side === "buy") {
      const shortfall = amount - this.portfolio.cash;
      if (shortfall > 0) return { ok: false, message: `현금이 ${won(shortfall)} 부족합니다.` };
      const position = this.portfolio.positions[code];
      const nextQty = (position?.qty ?? 0) + qty;
      const nextCost = ((position?.avgCost ?? 0) * (position?.qty ?? 0) + amount) / nextQty;
      this.portfolio.positions[code] = { code, qty: nextQty, avgCost: nextCost };
      this.portfolio.cash -= amount;
    } else {
      const position = this.portfolio.positions[code];
      if (!position || position.qty < qty) {
        return {
          ok: false,
          message: position ? `보유 ${position.qty}주를 초과했습니다.` : "보유 수량이 없습니다.",
        };
      }
      this.portfolio.realized += (price - position.avgCost) * qty;
      const remaining = position.qty - qty;
      if (remaining === 0) delete this.portfolio.positions[code];
      else this.portfolio.positions[code] = { ...position, qty: remaining };
      this.portfolio.cash += amount;
    }

    const fill: Fill = { id: (this.fillId += 1), at: Date.now(), code, side, qty, price };
    this.portfolio.fills = [fill, ...this.portfolio.fills].slice(0, 50);
    this.persist();
    this.notify();
    return { ok: true, fill, message: `${qty}주 ${side === "buy" ? "매수" : "매도"} 체결 · ${won(price)}` };
  }

  toggleWatch(code: string): void {
    if (this.watch.has(code)) this.watch.delete(code);
    else this.watch.add(code);
    this.persist();
    this.notify();
  }

  reset(): void {
    this.portfolio = emptyPortfolio();
    this.watch = new Set();
    this.persist();
    this.notify();
  }

  // ---------------------------------------------------------------- 저장

  private persist(): void {
    const stored: Stored = {
      cash: this.portfolio.cash,
      positions: this.portfolio.positions,
      fills: this.portfolio.fills,
      realized: this.portfolio.realized,
      watch: [...this.watch],
    };
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
    } catch {
      /* 저장이 막힌 환경에서는 세션 동안만 유지한다. */
    }
  }

  private restore(): void {
    let raw: string | null = null;
    try {
      raw = window.localStorage.getItem(STORAGE_KEY);
    } catch {
      return;
    }
    if (!raw) return;
    try {
      const stored = JSON.parse(raw) as Partial<Stored>;
      const positions: Record<string, Position> = {};
      for (const [code, position] of Object.entries(stored.positions ?? {})) {
        if (!LISTING_BY_CODE[code] || !position || typeof position.qty !== "number" || position.qty < 1) continue;
        positions[code] = { code, qty: Math.floor(position.qty), avgCost: Number(position.avgCost) || 0 };
      }
      const savedCash = typeof stored.cash === "number" && stored.cash >= 0 ? stored.cash : INITIAL_CASH;
      const fills = Array.isArray(stored.fills) ? stored.fills.slice(0, 50) : [];
      const realized = typeof stored.realized === "number" ? stored.realized : 0;
      // 비로그인 상태에서는 미션 보상을 지급하지 않으므로 거래 이력이 없는
      // 로컬 잔액은 항상 초기 자본으로 맞춘다. 거래 이력이 있는 상태는 보존한다.
      const hasTradingHistory = Object.keys(positions).length > 0 || fills.length > 0 || realized !== 0;
      this.portfolio = {
        cash: hasTradingHistory ? savedCash : INITIAL_CASH,
        positions,
        fills,
        realized,
      };
      this.fillId = this.portfolio.fills.reduce((max, fill) => Math.max(max, fill.id ?? 0), 0);
      this.watch = new Set((stored.watch ?? []).filter((code) => Boolean(LISTING_BY_CODE[code])));
    } catch {
      this.portfolio = emptyPortfolio();
    }
  }
}
