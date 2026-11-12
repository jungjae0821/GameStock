import { LISTINGS, roundToTick } from "./universe";
import { generateNews } from "./newsTemplates";
import { won } from "./format";
import { stockMarketProfile } from "./marketCap";
import { mulberry32 } from "./engineRandom";
import { advanceQuote, createQuote, refreshBook, SERIES_LIMIT, type Shock } from "./engineQuote";
import { emptyPortfolio, persistState, restoreState } from "./engineStorage";
import type {
  Fill,
  MarketIndex,
  MarketSnapshot,
  NewsItem,
  OrderRequest,
  OrderResult,
  Portfolio,
  Quote,
} from "./types";

export { INITIAL_CASH } from "./engineStorage";

export const TICK_MS = 2000;

const NEWS_LIMIT = 60;
/** 지수 기준값. 세션 시작 시점이 1000이다. */
const INDEX_BASE = 1000;

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
      const quote = createQuote(listing, this.rng);
      this.quotes.set(listing.code, quote);
      refreshBook(quote, 0, this.rng);
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

  // ---------------------------------------------------------------- 시세

  private tick(): void {
    const now = Date.now();
    for (const listing of LISTINGS) {
      const quote = this.quotes.get(listing.code);
      if (!quote) continue;
      const shock = this.shocks.get(listing.code);
      advanceQuote(quote, listing, shock, now, this.rng);

      if (shock) {
        shock.remaining -= 1;
        if (shock.remaining <= 0) this.shocks.delete(listing.code);
      }
    }
    this.advanceIndex();
    if (now >= this.nextNewsAt) this.publishNews(now);
    this.notify();
  }

  /** 가상 시가총액으로 가중한 지수. 세션 시작이 1000이다. */
  private advanceIndex(): void {
    let weighted = 0;
    let weight = 0;
    for (const listing of LISTINGS) {
      const quote = this.quotes.get(listing.code);
      if (!quote) continue;
      const profile = stockMarketProfile(listing.code);
      weighted += profile.marketCap * (quote.price / quote.prevClose);
      weight += profile.marketCap;
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
    persistState(this.portfolio, this.watch);
  }

  /** 저장된 값이 있으면 복원한다. 읽다 실패해도 그때까지 읽은 체결 번호·관심 종목은 반영한다. */
  private restore(): void {
    const restored = restoreState();
    if (!restored) return;
    this.portfolio = restored.portfolio;
    if (restored.fillId !== undefined) this.fillId = restored.fillId;
    if (restored.watch) this.watch = restored.watch;
  }
}
