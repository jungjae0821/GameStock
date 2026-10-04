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
}

const LARGE_CAP_CODES = new Set([
  "WH", "ZZZ", "GOV", "GI", "BA", "UMA", "SR", "EL",
]);
const LARGE_CAP_ACTIVE_USERS = 8_000_000;
const SMALL_CAP_ACTIVE_USERS = 450_000;
const VALUE_PER_ACTIVE_USER = 100_000;

export function stockMarketProfile(code: string): StockMarketProfile {
  const largeCap = LARGE_CAP_CODES.has(code.toUpperCase());
  const activeUsers = largeCap ? LARGE_CAP_ACTIVE_USERS : SMALL_CAP_ACTIVE_USERS;
  return {
    activeUsers,
    marketCap: activeUsers * VALUE_PER_ACTIVE_USER,
    movementWeight: largeCap ? 0.55 : 1,
    liquidityWeight: largeCap ? 1.65 : 1,
  };
}
