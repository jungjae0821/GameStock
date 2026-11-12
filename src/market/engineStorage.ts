/**
 * 시세 엔진의 로컬 포트폴리오 저장. 초기 자본, 빈 포트폴리오, localStorage 저장·복원을 담는다.
 */
import { LISTING_BY_CODE } from "./universe";
import type { Fill, Portfolio, Position } from "./types";

export const INITIAL_CASH = 1_000_000;

const STORAGE_KEY = "ssokdex.market.v1";

type Stored = {
  cash: number;
  positions: Record<string, Position>;
  fills: Fill[];
  realized: number;
  watch: string[];
};

/** 복원 결과. 중간에 실패하면 portfolio 만 빈 값으로 오고 그때까지 읽은 값은 남는다. */
export type RestoredState = { portfolio: Portfolio; fillId?: number; watch?: Set<string> };

export function emptyPortfolio(): Portfolio {
  return { cash: INITIAL_CASH, positions: {}, fills: [], realized: 0 };
}

export function persistState(portfolio: Portfolio, watch: Set<string>): void {
  const stored: Stored = {
    cash: portfolio.cash,
    positions: portfolio.positions,
    fills: portfolio.fills,
    realized: portfolio.realized,
    watch: [...watch],
  };
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
  } catch {
    /* 저장이 막힌 환경에서는 세션 동안만 유지한다. */
  }
}

/** 저장된 값이 없거나 읽을 수 없으면 null. 엔진은 이때 현재 상태를 그대로 둔다. */
export function restoreState(): RestoredState | null {
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
  if (!raw) return null;
  const result: RestoredState = { portfolio: emptyPortfolio() };
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
    result.portfolio = {
      cash: hasTradingHistory ? savedCash : INITIAL_CASH,
      positions,
      fills,
      realized,
    };
    result.fillId = result.portfolio.fills.reduce((max, fill) => Math.max(max, fill.id ?? 0), 0);
    result.watch = new Set((stored.watch ?? []).filter((code) => Boolean(LISTING_BY_CODE[code])));
  } catch {
    result.portfolio = emptyPortfolio();
  }
  return result;
}
