/**
 * 시세 엔진의 난수 도구. 시드 고정 난수 생성기와 잘린 정규분포 표본을 담는다.
 * 같은 시드면 같은 순서로 값을 뽑으므로 호출 순서를 바꾸지 않는다.
 */

export function mulberry32(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Box-Muller 표본을 ±3.5 로 자른다. 난수를 두 번 이상 뽑는다. */
export function gaussian(rng: () => number): number {
  let u = 0;
  let v = 0;
  while (u === 0) u = rng();
  while (v === 0) v = rng();
  const value = Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
  return Math.max(-3.5, Math.min(3.5, value));
}
