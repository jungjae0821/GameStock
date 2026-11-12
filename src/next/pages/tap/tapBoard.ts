import type { MutableRefObject } from "react";
import { won } from "../../../market/format";
import { LEAD_MARGIN_MS, cellKey, computeOdds, shortWon, type Burst, type Cell, type Geometry, type Palette, type TapBet, type TapState } from "./tapShared";

/** 그리기 루프가 매 프레임 읽고 쓰는 값. React 상태는 거치지 않는다. */
export type TapBoardRefs = {
  state: MutableRefObject<TapState | null>;
  samples: MutableRefObject<[number, number][]>;
  center: MutableRefObject<number | null>;
  geometry: MutableRefObject<Geometry | null>;
  hover: MutableRefObject<Cell | null>;
  keyCell: MutableRefObject<{ column: number; row: number } | null>;
  bets: MutableRefObject<Map<string, TapBet>>;
  odds: MutableRefObject<Map<string, number>>;
  oddsAt: MutableRefObject<number>;
  oddsBand: MutableRefObject<number>;
  bursts: MutableRefObject<Burst[]>;
};

const reducedMotion = () => typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, r);
}

/**
 * 탭 베팅 격자 한 프레임: 빈 칸과 배당, 1초 캔들, 내가 건 칸, 당첨 효과, 현재선과 가격 눈금.
 * 캔버스 크기 맞추기와 지우기는 부르는 쪽이 하고, 판정 시세가 준비된 뒤에만 부른다.
 */
export function drawTapFrame(ctx: CanvasRenderingContext2D, refs: TapBoardRefs, s: TapState, colors: Palette, code: string,
  width: number, height: number, now: number, time: number, dt: number) {
  const narrow = width < 640;
  const samples = refs.samples.current;
  const price = samples.at(-1)?.[1] ?? s.price;
  const nowX = Math.round(width * (narrow ? 0.3 : 0.38));
  const columns = narrow ? 4 : 6;
  const colW = (width - nowX) / columns;
  const pxPerMs = colW / s.slotMs;
  const rowH = narrow ? 42 : 48;
  const target = price + s.band / 2 - s.tick / 2;
  refs.center.current = refs.center.current === null || Math.abs(refs.center.current - target) > s.band * 12
    ? target : refs.center.current + (target - refs.center.current) * Math.min(1, dt * 2.5);
  const center = refs.center.current;
  const geometry: Geometry = { width, height, nowX, pxPerMs, rowH, center, now };
  refs.geometry.current = geometry;
  if (time - refs.oddsAt.current > 250 || refs.oddsBand.current !== s.band) {
    refs.oddsAt.current = time;
    refs.oddsBand.current = s.band;
    refs.odds.current = computeOdds(s, geometry, samples, now);
  }
  const xAt = (t: number) => nowX + (t - now) * pxPerMs;
  const yAt = (p: number) => height / 2 - ((p - center) / s.band) * rowH;

  const topPrice = center + (height / 2 / rowH) * s.band;
  const bottomPrice = center - (height / 2 / rowH) * s.band;
  const firstRow = Math.floor(bottomPrice / s.band) - 1;
  const lastRow = Math.ceil(topPrice / s.band) + 1;
  const firstSlot = Math.floor((now - nowX / pxPerMs) / s.slotMs) - 1;
  const lastSlot = Math.floor(now / s.slotMs) + s.maxColumns;
  const gap = narrow ? 3 : 4;
  const hover = refs.hover.current;
  const keyCell = refs.keyCell.current;
  const keyTarget = keyCell ? { slot: Math.floor(now / s.slotMs) + keyCell.column, low: (Math.floor(price / s.band) + keyCell.row) * s.band } : null;

  // 미래 구역 바탕과 행 가격
  ctx.fillStyle = colors.lane;
  ctx.globalAlpha = 0.35;
  ctx.fillRect(nowX, 0, width - nowX, height);
  ctx.globalAlpha = 1;
  ctx.font = `500 ${narrow ? 10 : 11}px Pretendard Variable, Pretendard, system-ui, sans-serif`;
  ctx.textBaseline = "middle";

  // 빈 칸(현재선 오른쪽만)
  ctx.save();
  ctx.beginPath();
  ctx.rect(nowX, 0, width - nowX, height);
  ctx.clip();
  for (let slot = Math.floor(now / s.slotMs); slot <= lastSlot; slot++) {
    const x0 = xAt(slot * s.slotMs);
    const lead = slot * s.slotMs - now;
    for (let row = firstRow; row <= lastRow; row++) {
      const low = row * s.band;
      if (low <= 0 || refs.bets.current.has(cellKey(code, slot, low))) continue;
      const y0 = yAt(low + s.band);
      const multiplier = refs.odds.current.get(`${slot}:${low}`);
      const open = lead >= s.minLeadMs + LEAD_MARGIN_MS && multiplier !== undefined && multiplier > 0;
      const hovered = open && ((hover && hover.slot === slot && hover.low === low) || (keyTarget && keyTarget.slot === slot && keyTarget.low === low));
      roundRect(ctx, x0 + gap / 2, y0 + gap / 2, colW - gap, rowH - gap, 6);
      ctx.fillStyle = hovered ? colors.line : colors.raised;
      ctx.globalAlpha = open ? 1 : 0.45;
      ctx.fill();
      if (hovered) { ctx.strokeStyle = colors.brand; ctx.lineWidth = 1.5; ctx.stroke(); }
      ctx.globalAlpha = 1;
      if (open) {
        ctx.fillStyle = hovered ? colors.ink : colors.muted;
        ctx.textAlign = "center";
        ctx.fillText(`${multiplier!.toFixed(1)}×`, x0 + colW / 2, y0 + rowH / 2);
      }
    }
  }
  ctx.restore();

  // 1초 캔들
  const candleMs = 1_000;
  const candles = new Map<number, [number, number, number, number]>();
  for (const [t, p] of samples) {
    if (t < now - nowX / pxPerMs - candleMs) continue;
    const bucket = Math.floor(t / candleMs);
    const c = candles.get(bucket);
    if (!c) candles.set(bucket, [p, p, p, p]);
    else { c[1] = Math.max(c[1], p); c[2] = Math.min(c[2], p); c[3] = p; }
  }
  // 체결이 없던 초도 직전 종가로 이어 그린다.
  let previousClose: number | null = null;
  const startBucket = Math.floor((now - nowX / pxPerMs) / candleMs);
  const candleW = Math.max(3, candleMs * pxPerMs * 0.62);
  for (let bucket = startBucket; bucket <= Math.floor(now / candleMs); bucket++) {
    let c = candles.get(bucket);
    if (!c && previousClose !== null) c = [previousClose, previousClose, previousClose, previousClose];
    if (!c) continue;
    if (previousClose !== null) c[0] = previousClose;
    const open: number = c[0], high: number = Math.max(c[1], c[0]), low: number = Math.min(c[2], c[0]), close: number = c[3];
    previousClose = close;
    const x = xAt(bucket * candleMs + candleMs / 2);
    if (x > nowX) continue;
    const color = close > open ? colors.up : close < open ? colors.down : colors.faint;
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(Math.round(x) + 0.5, yAt(high));
    ctx.lineTo(Math.round(x) + 0.5, yAt(low));
    ctx.stroke();
    const top = yAt(Math.max(open, close)), bottom = yAt(Math.min(open, close));
    ctx.fillRect(x - candleW / 2, top, candleW, Math.max(1.5, bottom - top));
  }

  // 내가 건 칸(결과가 난 칸은 과거 구역까지 흘러간다)
  const nowMs = performance.now();
  for (const bet of refs.bets.current.values()) {
    if (bet.code !== code) continue;
    const slot = Math.floor(bet.slotStart / s.slotMs);
    if (slot < firstSlot) continue;
    const x0 = xAt(bet.slotStart), y0 = yAt(bet.bandHigh);
    const h = ((bet.bandHigh - bet.bandLow) / s.band) * rowH;
    const settled = bet.status === "LOST" || bet.status === "VOID";
    const won_ = bet.status === "WON";
    ctx.globalAlpha = settled ? 0.35 : 1;
    roundRect(ctx, x0 + gap / 2, y0 + gap / 2, colW - gap, h - gap, 6);
    ctx.fillStyle = won_ ? colors.brand : colors.ink;
    ctx.fill();
    if (bet.status === "PENDING") {
      ctx.setLineDash([4, 3]);
      ctx.strokeStyle = colors.brand;
      ctx.lineWidth = 1.5;
      ctx.stroke();
      ctx.setLineDash([]);
    }
    ctx.fillStyle = won_ ? colors.brandInk : colors.stage;
    ctx.textAlign = "center";
    ctx.font = `700 ${narrow ? 11 : 12}px Pretendard Variable, Pretendard, system-ui, sans-serif`;
    ctx.fillText(won_ ? `+${won(bet.payout)}` : `${bet.multiplier.toFixed(1)}×`, x0 + colW / 2, y0 + h / 2 - (narrow ? 6 : 7));
    ctx.font = `500 ${narrow ? 10 : 11}px Pretendard Variable, Pretendard, system-ui, sans-serif`;
    ctx.fillText(bet.status === "VOID" ? "환불" : shortWon(bet.stake), x0 + colW / 2, y0 + h / 2 + (narrow ? 7 : 8));
    ctx.globalAlpha = 1;
  }

  // 당첨 순간: 칸 위로 받은 금액이 떠오른다
  refs.bursts.current = refs.bursts.current.filter((burst) => nowMs - burst.at < 1_600);
  for (const burst of refs.bursts.current) {
    const bet = refs.bets.current.get(burst.key);
    if (!bet || bet.code !== code) continue;
    const progress = (nowMs - burst.at) / 1_600;
    const rise = reducedMotion() ? 0 : progress * 28;
    ctx.globalAlpha = 1 - progress * progress;
    ctx.fillStyle = colors.brand;
    ctx.textAlign = "center";
    ctx.font = `800 ${narrow ? 16 : 20}px Pretendard Variable, Pretendard, system-ui, sans-serif`;
    ctx.fillText(`+${won(burst.payout)}`, xAt(bet.slotStart) + colW / 2, yAt(bet.bandHigh) - 10 - rise);
    ctx.globalAlpha = 1;
  }

  // 현재선과 현재가
  const py = yAt(price);
  ctx.strokeStyle = colors.faint;
  ctx.setLineDash([2, 4]);
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(nowX, py);
  ctx.lineTo(width, py);
  ctx.moveTo(nowX + 0.5, 0);
  ctx.lineTo(nowX + 0.5, height);
  ctx.stroke();
  ctx.setLineDash([]);
  ctx.fillStyle = colors.ink;
  ctx.beginPath();
  ctx.arc(nowX, py, 3.5, 0, Math.PI * 2);
  ctx.fill();
  const label = price.toLocaleString("ko-KR");
  ctx.font = `700 ${narrow ? 11 : 12}px Pretendard Variable, Pretendard, system-ui, sans-serif`;
  const labelW = ctx.measureText(label).width + 14;
  roundRect(ctx, nowX - labelW - 8, py - 11, labelW, 22, 11);
  ctx.fillStyle = colors.ink;
  ctx.fill();
  ctx.fillStyle = colors.stage;
  ctx.textAlign = "center";
  ctx.fillText(label, nowX - labelW / 2 - 8, py);

  // 왼쪽 가격 눈금
  ctx.font = `500 ${narrow ? 10 : 11}px Pretendard Variable, Pretendard, system-ui, sans-serif`;
  ctx.fillStyle = colors.faint;
  ctx.textAlign = "left";
  const every = Math.max(1, Math.ceil(28 / rowH));
  for (let row = firstRow; row <= lastRow; row++) {
    if (row % every !== 0) continue;
    const y = yAt(row * s.band);
    if (y < 10 || y > height - 10 || Math.abs(y - py) < 14) continue;
    ctx.fillText((row * s.band).toLocaleString("ko-KR"), 8, y);
  }
}
