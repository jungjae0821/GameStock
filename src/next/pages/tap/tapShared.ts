import { won } from "../../../market/format";
import { tableProbability, tapMultiplier, type TapTable } from "../../../market/tapOdds";

export type BetStatus = "PENDING" | "OPEN" | "WON" | "LOST" | "VOID";
export type TapBet = {
  id: number; code: string; slotStart: number; slotEnd: number; bandLow: number; bandHigh: number;
  stake: number; multiplier: number; status: BetStatus; payout: number; settledAt?: string | null;
};
export type TapState = {
  code: string; ready: boolean; serverTime: number; price: number; sigma: number; band: number; previousBand: number;
  tick: number; slotMs: number; minLeadMs: number; maxColumns: number; houseEdge: number; minMultiplier: number;
  maxMultiplier: number; stakes: number[]; codes: string[]; path: [number, number][]; bets: TapBet[];
  probabilities: TapTable; recentLow: number; recentHigh: number; recentMs: number; leadFromMs: number; leadStepMs: number;
  today: { staked: number; paid: number; wins: number; losses: number };
  /** 탭 베팅은 운용계좌가 아니라 내 지갑 돈으로 한다. */
  walletCash: number;
};
export type PlaceResult = { message: string; bet: TapBet; walletCash: number };
export type Cell = { slot: number; low: number };
export type Geometry = { width: number; height: number; nowX: number; pxPerMs: number; rowH: number; center: number; now: number };
export type Burst = { key: string; payout: number; at: number };
export type Palette = ReturnType<typeof readPalette>;

/** 서버의 최소 선행 시간보다 조금 더 여유를 둬 화면에서 열린 칸이 서버에서 거절되지 않게 한다. */
export const LEAD_MARGIN_MS = 350;
export const cellKey = (code: string, slot: number, low: number) => `${code}:${slot}:${low}`;
export const shortWon = (value: number) => (value >= 10_000 && value % 10_000 === 0 ? `${value / 10_000}만` : won(value));

export function readPalette(element: Element) {
  const style = getComputedStyle(element);
  const get = (name: string, fallback: string) => style.getPropertyValue(name).trim() || fallback;
  return {
    stage: get("--nx-stage", "#12101c"), panel: get("--nx-panel", "#1a1726"), raised: get("--nx-raised", "#241f34"),
    line: get("--nx-line", "#2e2942"), lane: get("--nx-lane", "#221d31"), ink: get("--ink", "#f1eef8"),
    muted: get("--ink-muted", "#aaa4be"), faint: get("--ink-faint", "#6f6986"), brand: get("--brand", "#fd5886"),
    brandInk: get("--brand-ink", "#12101c"), brandWash: get("--brand-wash", "#3a1d2c"),
    up: get("--up", "#ff4d5e"), down: get("--down", "#4f7cff"),
  };
}

/**
 * 화면에 보이는 미래 칸마다 배당을 계산한다. 키는 `${slot}:${low}`이고, 0은 걸 수 없는 칸이다.
 * 매 프레임 계산하기엔 칸이 많아 그리기 루프가 0.25초마다(칸 높이가 바뀌면 즉시) 부른다.
 */
export function computeOdds(s: TapState, geometry: Geometry, samples: [number, number][], now: number) {
  const price = samples.at(-1)?.[1] ?? s.price;
  const odds = new Map<string, number>();
  // 최근 15초 체결 범위. 서버도 같은 범위로 "방금 오간 가격"인지 가른다.
  let recentLow = price, recentHigh = price;
  for (let i = samples.length - 1; i >= 0 && samples[i][0] >= now - s.recentMs; i--) {
    recentLow = Math.min(recentLow, samples[i][1]);
    recentHigh = Math.max(recentHigh, samples[i][1]);
  }
  const firstSlot = Math.floor(now / s.slotMs);
  const rows = Math.ceil(geometry.height / geometry.rowH / 2) + 2;
  const priceRow = Math.floor(price / s.band);
  for (let slot = firstSlot; slot <= firstSlot + s.maxColumns; slot++) {
    const lead = slot * s.slotMs - now;
    if (lead < s.minLeadMs + LEAD_MARGIN_MS || lead > s.maxColumns * s.slotMs) continue;
    for (let row = priceRow - rows; row <= priceRow + rows; row++) {
      const low = row * s.band;
      if (low <= 0) continue;
      if (row === priceRow) { odds.set(`${slot}:${low}`, 0); continue; }
      const p = tableProbability(s.probabilities, Math.abs(row - priceRow), lead, low >= recentLow && low <= recentHigh, s.leadFromMs, s.leadStepMs);
      odds.set(`${slot}:${low}`, p === null ? 0 : tapMultiplier(p, s.houseEdge, s.minMultiplier, s.maxMultiplier));
    }
  }
  return odds;
}
