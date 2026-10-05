/**
 * Keep this deterministic fallback profile aligned with the backend's
 * StockMarketProfile. The browser fallback is only used while the API is
 * unavailable, but it should teach the same market-size relationship.
 */
export interface StockMarketProfile {
  activeUsers: number;
  marketCap: number;
  movementWeight: number;
  liquidityWeight: number;
  dailyUpRate: number;
  dailyDownRate: number;
}

const LARGE_CAP_CODES = new Set([
  "WH", "ZZZ", "GOV", "GI", "BA", "UMA", "SR", "EL",
]);
const SMALL_CAP_ACTIVE_USERS = 450_000;
const VALUE_PER_ACTIVE_USER = 100_000;
// Virtual game-balance values, kept identical to StockMarketProfile.java.
const ACTIVE_USERS: Record<string, number> = {
  GI: 15_000_000, SR: 12_000_000, EL: 10_000_000, ZZZ: 8_000_000,
  WH: 7_000_000, GOV: 6_000_000, BA: 4_000_000, UMA: 3_000_000,
  AK: 600_000, LT: 500_000, MH: 400_000, PW: 200_000,
  PX: 150_000, ES: 100_000, SD: 50_000,
};

export function stockMarketProfile(code: string): StockMarketProfile {
  const normalized = code.toUpperCase();
  const largeCap = LARGE_CAP_CODES.has(normalized);
  const speculative = Object.hasOwn(ACTIVE_USERS, normalized) && !largeCap;
  const activeUsers = ACTIVE_USERS[normalized] ?? SMALL_CAP_ACTIVE_USERS;
  const size = Math.sqrt(activeUsers / SMALL_CAP_ACTIVE_USERS);
  return {
    activeUsers,
    marketCap: activeUsers * VALUE_PER_ACTIVE_USER,
    movementWeight: largeCap ? Math.max(0.10, 0.45 / size) : Math.min(2.5, 1 / size),
    liquidityWeight: largeCap ? Math.min(10, 2 * size) : Math.max(0.35, size * 0.65),
    dailyUpRate: speculative ? 2 : 0.3,
    dailyDownRate: speculative ? 0.8 : 0.3,
  };
}
