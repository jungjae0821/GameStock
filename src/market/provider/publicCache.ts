import type { BackendEvent, BackendMarketStatus, BackendStock } from "./backendMapping";

/* 새로고침 때 백엔드 응답을 기다리며 로딩 화면만 보이지 않도록, 마지막 공개 시세를
   브라우저에 남겨 두고 먼저 그린다. 오래된 값은 시세가 크게 달라 보일 수 있어 쓰지 않는다. */
const PUBLIC_CACHE_KEY = "gamestock:public-market";
const PUBLIC_CACHE_MAX_AGE_MS = 30 * 60_000;
export type PublicMarketCache = { savedAt: number; stocks: BackendStock[]; events: BackendEvent[]; market: BackendMarketStatus };

export function readPublicCache(): PublicMarketCache | null {
  try {
    const cached = JSON.parse(window.localStorage.getItem(PUBLIC_CACHE_KEY) ?? "null") as PublicMarketCache | null;
    if (!cached || Date.now() - cached.savedAt > PUBLIC_CACHE_MAX_AGE_MS || !Array.isArray(cached.stocks) || cached.stocks.length === 0) return null;
    return cached;
  } catch {
    return null;
  }
}

let publicCacheSavedAt = 0;
export function writePublicCache(stocks: BackendStock[], events: BackendEvent[], market: BackendMarketStatus) {
  // 2초 주기 조회마다 수십 KB를 다시 쓰지 않도록 저장 간격을 둔다.
  if (Date.now() - publicCacheSavedAt < 10_000) return;
  publicCacheSavedAt = Date.now();
  try {
    window.localStorage.setItem(PUBLIC_CACHE_KEY, JSON.stringify({ savedAt: Date.now(), stocks, events, market }));
  } catch {
    /* 저장 공간이 없거나 막혀 있으면 다음 새로고침에서 로딩 화면을 보여 준다. */
  }
}
