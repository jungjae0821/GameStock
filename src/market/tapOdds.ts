/**
 * 탭 베팅 칸 배당. 서버(TapCalibration.java)가 실제 체결 기록으로 배운 적중률 표를 받아
 * 같은 규칙으로 칸 배당을 그린다. 걸 때는 서버가 다시 계산한 값이 기준이다.
 */
/** [seen][거리(틱)][선행 구간] 확률 표. seen은 목표 가격이 최근 체결 범위 안이었는지다. */
export type TapTable = number[][][];

/** leadFromMs·leadStepMs는 서버가 내려 준 선행 구간 경계다. */
export function tableProbability(table: TapTable, distanceTicks: number, leadMs: number, seen: boolean, leadFromMs: number, leadStepMs: number): number | null {
  const rows = table[seen ? 1 : 0];
  if (!rows || rows.length < 2) return null;
  const distance = Math.max(1, Math.min(rows.length - 1, distanceTicks));
  const buckets = rows[distance].length;
  const bucket = Math.max(0, Math.min(buckets - 1, Math.floor((leadMs - leadFromMs) / leadStepMs)));
  return rows[distance][bucket];
}

/** 0.1 단위로 내린 배당. 너무 가깝거나(1.1 미만) 너무 먼(상한 초과) 칸은 0. */
export function tapMultiplier(probability: number, houseEdge: number, min: number, max: number): number {
  if (!(probability > 0)) return 0;
  const rounded = Math.floor(((1 - houseEdge) / probability) * 10 + 1e-9) / 10;
  return rounded < min || rounded > max ? 0 : rounded;
}
