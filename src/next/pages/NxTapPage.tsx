import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { GameIcon } from "../../components/GameIcon";
import { Link } from "../../components/Link";
import { ApiError, apiFetch } from "../../lib/api";
import { openLoginPrompt, useAuthUser } from "../../lib/auth";
import { won } from "../../market/format";
import { useMarket, useMarketApi } from "../../market/MarketProvider";
import { tableProbability, tapMultiplier, type TapTable } from "../../market/tapOdds";
import { LISTING_BY_CODE } from "../../market/universe";
import { TAP_PATH, navigate } from "../../router";
import { useNxTheme } from "../uiMode";

type BetStatus = "PENDING" | "OPEN" | "WON" | "LOST" | "VOID";
type TapBet = {
  id: number; code: string; slotStart: number; slotEnd: number; bandLow: number; bandHigh: number;
  stake: number; multiplier: number; status: BetStatus; payout: number; settledAt?: string | null;
};
type TapState = {
  code: string; ready: boolean; serverTime: number; price: number; sigma: number; band: number; previousBand: number;
  tick: number; slotMs: number; minLeadMs: number; maxColumns: number; houseEdge: number; minMultiplier: number;
  maxMultiplier: number; stakes: number[]; codes: string[]; path: [number, number][]; bets: TapBet[];
  probabilities: TapTable; recentLow: number; recentHigh: number; recentMs: number; leadFromMs: number; leadStepMs: number;
  today: { staked: number; paid: number; wins: number; losses: number };
};
type PlaceResult = { message: string; bet: TapBet };
type Cell = { slot: number; low: number };
type Geometry = { width: number; height: number; nowX: number; pxPerMs: number; rowH: number; center: number; now: number };
type Burst = { key: string; payout: number; at: number };

export const TAP_CODES = ["GI", "SR", "OW", "ZZZ", "WH", "GOV", "BA", "UMA"] as const;
const POLL_MS = 3_000;
/** 서버의 최소 선행 시간보다 조금 더 여유를 둬 화면에서 열린 칸이 서버에서 거절되지 않게 한다. */
const LEAD_MARGIN_MS = 350;
const SAMPLE_KEEP_MS = 150_000;
const reducedMotion = () => typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
const cellKey = (code: string, slot: number, low: number) => `${code}:${slot}:${low}`;
const shortWon = (value: number) => (value >= 10_000 && value % 10_000 === 0 ? `${value / 10_000}만` : won(value));

function readPalette(element: Element) {
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

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, r);
}

export function NxTapPage({ code: routeCode }: { code: string | null }) {
  const code = routeCode && (TAP_CODES as readonly string[]).includes(routeCode) ? routeCode : "UMA";
  const snapshot = useMarket();
  const api = useMarketApi();
  const { user } = useAuthUser();
  const theme = useNxTheme();
  const quote = snapshot.quotes[code];
  const listing = LISTING_BY_CODE[code];

  const [state, setState] = useState<TapState | null>(null);
  const [loadError, setLoadError] = useState("");
  const [stake, setStake] = useState(10_000);
  const [bets, setBets] = useState<TapBet[]>([]);
  const [notice, setNotice] = useState<{ text: string; tone: "ok" | "error" | "win" } | null>(null);
  const [announcement, setAnnouncement] = useState("");

  const canvasRef = useRef<HTMLCanvasElement>(null);
  const stateRef = useRef<TapState | null>(null);
  const betsRef = useRef<Map<string, TapBet>>(new Map());
  const samplesRef = useRef<[number, number][]>([]);
  const offsetRef = useRef(0);
  const oddsRef = useRef<Map<string, number>>(new Map());
  const oddsAtRef = useRef(0);
  const oddsBandRef = useRef(0);
  const hoverRef = useRef<Cell | null>(null);
  const keyCellRef = useRef<{ column: number; row: number } | null>(null);
  const geometryRef = useRef<Geometry | null>(null);
  const burstsRef = useRef<Burst[]>([]);
  const paletteRef = useRef<ReturnType<typeof readPalette> | null>(null);
  const centerRef = useRef<number | null>(null);
  const stakeRef = useRef(stake);
  stakeRef.current = stake;

  const serverNow = () => Date.now() + offsetRef.current;

  /** 서버 기록과 화면에 걸어 둔 칸을 합친다. 새로 당첨된 칸은 효과를 띄운다. */
  const mergeBets = useCallback((incoming: TapBet[], replaceOpen: boolean) => {
    const map = betsRef.current;
    const seen = new Set<string>();
    for (const bet of incoming) {
      const key = cellKey(bet.code, Math.floor(bet.slotStart / (stateRef.current?.slotMs ?? 5_000)), bet.bandLow);
      seen.add(key);
      const before = map.get(key);
      if (bet.status === "WON" && before && before.status !== "WON") {
        burstsRef.current.push({ key, payout: bet.payout, at: performance.now() });
        if (bet.code === code) {
          setAnnouncement(`${won(bet.bandLow)} 칸 당첨, ${won(bet.payout)} 받았어요.`);
          setNotice({ text: `당첨! ${won(bet.payout)}을 받았어요.`, tone: "win" });
        }
      }
      if (bet.status === "LOST" && before && before.status !== "LOST" && bet.code === code)
        setAnnouncement(`${won(bet.bandLow)} 칸은 닿지 않았어요.`);
      map.set(key, { ...before, ...bet });
    }
    if (replaceOpen) {
      for (const [key, bet] of map) if (bet.status === "OPEN" && bet.code === code && !seen.has(key)) map.delete(key);
    }
    setBets([...map.values()]);
  }, [code]);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const sent = Date.now();
      const next = await apiFetch<TapState>(`/api/tap/${code}`, { signal, auth: Boolean(user) });
      const received = Date.now();
      offsetRef.current = next.serverTime - (sent + received) / 2;
      const first = stateRef.current?.code !== next.code;
      stateRef.current = next;
      setState(next);
      setLoadError("");
      if (first || samplesRef.current.length === 0) samplesRef.current = next.path.slice();
      mergeBets(next.bets, true);
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") return;
      setLoadError(error instanceof Error ? error.message : "탭 베팅 정보를 불러오지 못했어요.");
    }
  }, [code, user, mergeBets]);

  useEffect(() => {
    stateRef.current = null;
    samplesRef.current = [];
    centerRef.current = null;
    betsRef.current = new Map();
    oddsRef.current = new Map();
    setBets([]);
    setState(null);
    const controller = new AbortController();
    void load(controller.signal);
    const timer = window.setInterval(() => void load(), POLL_MS);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, [load]);

  /* 실시간 체결가를 판정 시계(서버 시각)에 맞춰 쌓는다. */
  useEffect(() => {
    if (!quote?.price || !stateRef.current) return;
    const samples = samplesRef.current;
    const now = serverNow();
    samples.push([now, quote.price]);
    while (samples.length && samples[0][0] < now - SAMPLE_KEEP_MS) samples.shift();
  }, [quote?.price]);

  useEffect(() => {
    const onTap = (event: Event) => {
      const detail = (event as CustomEvent<{ bets?: TapBet[] }>).detail;
      if (detail?.bets?.length) mergeBets(detail.bets, false);
    };
    window.addEventListener("gamestock:tap", onTap);
    return () => window.removeEventListener("gamestock:tap", onTap);
  }, [mergeBets]);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), notice.tone === "error" ? 4_000 : 2_400);
    return () => window.clearTimeout(timer);
  }, [notice]);

  useEffect(() => {
    if (canvasRef.current) paletteRef.current = readPalette(canvasRef.current);
  }, [theme]);

  /* 칸 배당은 그리기 루프에서 0.25초마다(칸 높이가 바뀌면 즉시) 다시 계산한다. 매 프레임 계산하기엔 칸이 많다. */
  const refreshOdds = () => {
    const s = stateRef.current;
    const geometry = geometryRef.current;
    if (!s?.ready || !geometry) return;
    oddsBandRef.current = s.band;
    const now = serverNow();
    const price = samplesRef.current.at(-1)?.[1] ?? s.price;
    const odds = new Map<string, number>();
    // 최근 15초 체결 범위. 서버도 같은 범위로 "방금 오간 가격"인지 가른다.
    let recentLow = price, recentHigh = price;
    const samples = samplesRef.current;
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
    oddsRef.current = odds;
  };

  /* 격자·캔들·현재가를 매 프레임 그린다. React 상태는 거치지 않고 ref만 읽는다. */
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    paletteRef.current = readPalette(canvas);
    let frame = 0;
    let last = performance.now();
    const draw = (time: number) => {
      frame = requestAnimationFrame(draw);
      const dt = Math.min(0.1, (time - last) / 1000);
      last = time;
      const s = stateRef.current;
      const colors = paletteRef.current!;
      const dpr = window.devicePixelRatio || 1;
      const width = canvas.clientWidth, height = canvas.clientHeight;
      if (canvas.width !== Math.round(width * dpr) || canvas.height !== Math.round(height * dpr)) {
        canvas.width = Math.round(width * dpr);
        canvas.height = Math.round(height * dpr);
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, width, height);
      if (!s?.ready) return;

      const narrow = width < 640;
      const now = serverNow();
      const samples = samplesRef.current;
      const price = samples.at(-1)?.[1] ?? s.price;
      const nowX = Math.round(width * (narrow ? 0.3 : 0.38));
      const columns = narrow ? 4 : 6;
      const colW = (width - nowX) / columns;
      const pxPerMs = colW / s.slotMs;
      const rowH = narrow ? 42 : 48;
      const target = price + s.band / 2 - s.tick / 2;
      centerRef.current = centerRef.current === null || Math.abs(centerRef.current - target) > s.band * 12
        ? target : centerRef.current + (target - centerRef.current) * Math.min(1, dt * 2.5);
      const center = centerRef.current;
      const geometry: Geometry = { width, height, nowX, pxPerMs, rowH, center, now };
      geometryRef.current = geometry;
      if (time - oddsAtRef.current > 250 || oddsBandRef.current !== s.band) { oddsAtRef.current = time; refreshOdds(); }
      const xAt = (t: number) => nowX + (t - now) * pxPerMs;
      const yAt = (p: number) => height / 2 - ((p - center) / s.band) * rowH;

      const topPrice = center + (height / 2 / rowH) * s.band;
      const bottomPrice = center - (height / 2 / rowH) * s.band;
      const firstRow = Math.floor(bottomPrice / s.band) - 1;
      const lastRow = Math.ceil(topPrice / s.band) + 1;
      const firstSlot = Math.floor((now - nowX / pxPerMs) / s.slotMs) - 1;
      const lastSlot = Math.floor(now / s.slotMs) + s.maxColumns;
      const gap = narrow ? 3 : 4;
      const hover = hoverRef.current;
      const keyCell = keyCellRef.current;
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
          if (low <= 0 || betsRef.current.has(cellKey(code, slot, low))) continue;
          const y0 = yAt(low + s.band);
          const multiplier = oddsRef.current.get(`${slot}:${low}`);
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
      for (const bet of betsRef.current.values()) {
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
      burstsRef.current = burstsRef.current.filter((burst) => nowMs - burst.at < 1_600);
      for (const burst of burstsRef.current) {
        const bet = betsRef.current.get(burst.key);
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
    };
    frame = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(frame);
  }, [code]);

  const cellAt = (clientX: number, clientY: number): Cell | null => {
    const canvas = canvasRef.current, geometry = geometryRef.current, s = stateRef.current;
    if (!canvas || !geometry || !s?.ready) return null;
    const rect = canvas.getBoundingClientRect();
    const x = clientX - rect.left, y = clientY - rect.top;
    if (x < geometry.nowX) return null;
    const t = geometry.now + (x - geometry.nowX) / geometry.pxPerMs;
    const p = geometry.center + ((geometry.height / 2 - y) / geometry.rowH) * s.band;
    return { slot: Math.floor(t / s.slotMs), low: Math.floor(p / s.band) * s.band };
  };

  const place = async (cell: Cell) => {
    const s = stateRef.current;
    if (!s?.ready) return;
    if (!user) { openLoginPrompt(TAP_PATH(code)); return; }
    const key = cellKey(code, cell.slot, cell.low);
    if (betsRef.current.has(key)) return;
    const multiplier = oddsRef.current.get(`${cell.slot}:${cell.low}`);
    const slotStart = cell.slot * s.slotMs;
    if (!multiplier || slotStart - serverNow() < s.minLeadMs + LEAD_MARGIN_MS) {
      setNotice({ text: multiplier === 0 ? "지금 가격이 들어 있는 칸은 걸 수 없어요." : "이 칸은 지금 걸 수 없어요. 더 오른쪽 칸을 눌러 주세요.", tone: "error" });
      return;
    }
    const amount = stakeRef.current;
    const pending: TapBet = { id: -Date.now(), code, slotStart, slotEnd: slotStart + s.slotMs, bandLow: cell.low, bandHigh: cell.low + s.band,
      stake: amount, multiplier, status: "PENDING", payout: 0 };
    betsRef.current.set(key, pending);
    setBets([...betsRef.current.values()]);
    try {
      const result = await apiFetch<PlaceResult>("/api/tap/bets", {
        method: "POST",
        body: JSON.stringify({ code, slotStart, bandLow: cell.low, bandHigh: cell.low + s.band, stake: amount, quotedMultiplier: multiplier }),
      });
      const current = betsRef.current.get(key);
      // 서버 정산 알림이 응답보다 먼저 왔으면 그 결과를 지킨다.
      if (!current || current.status === "PENDING") betsRef.current.set(key, result.bet);
      setBets([...betsRef.current.values()]);
      void api.refreshPortfolio();
    } catch (error) {
      betsRef.current.delete(key);
      setBets([...betsRef.current.values()]);
      setNotice({ text: error instanceof ApiError || error instanceof Error ? error.message : "걸지 못했어요. 다시 눌러 주세요.", tone: "error" });
    }
  };

  const onPointerMove = (event: PointerEvent<HTMLCanvasElement>) => {
    hoverRef.current = event.pointerType === "mouse" ? cellAt(event.clientX, event.clientY) : null;
    event.currentTarget.style.cursor = hoverRef.current ? "pointer" : "default";
  };

  const onKeyDown = (event: KeyboardEvent<HTMLCanvasElement>) => {
    const s = stateRef.current;
    if (!s?.ready) return;
    const current = keyCellRef.current ?? { column: 1, row: 1 };
    const moves: Record<string, [number, number]> = { ArrowRight: [1, 0], ArrowLeft: [-1, 0], ArrowUp: [0, 1], ArrowDown: [0, -1] };
    if (event.key in moves) {
      event.preventDefault();
      const [dc, dr] = moves[event.key];
      keyCellRef.current = { column: Math.max(1, Math.min(s.maxColumns, current.column + dc)), row: Math.max(-8, Math.min(8, current.row + dr)) };
    } else if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      keyCellRef.current = current;
      const price = samplesRef.current.at(-1)?.[1] ?? s.price;
      void place({ slot: Math.floor(serverNow() / s.slotMs) + current.column, low: (Math.floor(price / s.band) + current.row) * s.band });
    }
  };

  const today = state?.today;
  const open = bets.filter((bet) => bet.code === code && (bet.status === "OPEN" || bet.status === "PENDING"));
  const recent = bets.filter((bet) => bet.code === code && (bet.status === "WON" || bet.status === "LOST" || bet.status === "VOID"))
    .sort((a, b) => b.slotStart - a.slotStart).slice(0, 8);
  const net = today ? today.paid - today.staked : 0;
  const change = quote ? (quote.price - quote.prevClose) / quote.prevClose : 0;

  return (
    <section className="nx-tap" aria-labelledby="nx-tap-title">
      <header className="nx-tap-head">
        <div>
          <h1 id="nx-tap-title" className="nx-page-title">탭 베팅</h1>
          <p className="nx-screen-sub">5초 뒤 가격이 지나갈 칸을 눌러 맞혀 보세요. 체결가가 칸에 닿는 순간 배당만큼 받아요.</p>
        </div>
        <div className="nx-tap-quote" aria-live="off">
          <GameIcon code={code} size="table" />
          <div>
            <strong>{listing?.name ?? code}</strong>
            <span className="nx-num">{quote ? won(quote.price) : "—"} <em className={change > 0 ? "is-up" : change < 0 ? "is-down" : ""}>{(change * 100).toFixed(2)}%</em></span>
          </div>
        </div>
      </header>

      <nav className="nx-tap-codes" aria-label="종목 고르기">
        {TAP_CODES.map((item) => (
          <button key={item} type="button" className={`nx-tap-code${item === code ? " is-active" : ""}`} aria-pressed={item === code}
            onClick={() => navigate(TAP_PATH(item), { replace: true })}>
            <GameIcon code={item} size="table" />
            <span>{LISTING_BY_CODE[item]?.name ?? item}</span>
          </button>
        ))}
      </nav>

      <div className="nx-tap-board">
        <canvas ref={canvasRef} className="nx-tap-canvas" tabIndex={0}
          role="application" aria-roledescription="탭 베팅 격자"
          aria-label={`${listing?.name ?? code} 탭 베팅 격자. 방향키로 칸을 고르고 Enter로 ${shortWon(stake)}원을 겁니다.`}
          onPointerMove={onPointerMove} onPointerLeave={() => { hoverRef.current = null; }}
          onPointerUp={(event) => { const cell = cellAt(event.clientX, event.clientY); if (cell) void place(cell); }}
          onKeyDown={onKeyDown} onBlur={() => { keyCellRef.current = null; }} />
        {!state?.ready && (
          <p className="nx-tap-overlay" role="status">{loadError || (state ? "판정 시세를 준비하고 있어요. 잠시 후 격자가 열려요." : "격자를 불러오고 있어요.")}</p>
        )}
        {notice && <p className={`nx-tap-notice is-${notice.tone}`} role={notice.tone === "error" ? "alert" : "status"}>{notice.text}</p>}
        <p className="vh" aria-live="polite">{announcement}</p>
      </div>

      <div className="nx-tap-controls">
        <fieldset className="nx-tap-stakes">
          <legend>한 칸에 걸 금액</legend>
          {(state?.stakes ?? [10_000, 50_000, 100_000]).map((amount) => (
            <button key={amount} type="button" className={`nx-tap-stake${amount === stake ? " is-active" : ""}`} aria-pressed={amount === stake} onClick={() => setStake(amount)}>
              {shortWon(amount)}원
            </button>
          ))}
        </fieldset>
        <dl className="nx-tap-stats">
          <div><dt>보유 현금</dt><dd className="nx-num">{user ? won(snapshot.portfolio.cash) : "—"}</dd></div>
          <div><dt>오늘 탭 손익</dt><dd className={`nx-num${net > 0 ? " is-up" : net < 0 ? " is-down" : ""}`}>{user && today ? `${net > 0 ? "+" : ""}${won(net)}` : "—"}</dd></div>
          <div><dt>오늘 적중</dt><dd className="nx-num">{user && today ? `${today.wins} / ${today.wins + today.losses}` : "—"}</dd></div>
          <div><dt>결과 대기</dt><dd className="nx-num">{open.length}칸</dd></div>
        </dl>
      </div>

      <div className="nx-tap-foot">
        <section className="nx-tap-rules" aria-labelledby="nx-tap-rules-title">
          <h2 id="nx-tap-rules-title">이렇게 정해져요</h2>
          <ul>
            <li>칸 하나는 앞으로의 5초와 가격 한 호가예요. 배당은 최근 실제로 그만큼 떨어진 가격까지 닿은 비율로 정해요.</li>
            <li>그 5초 안에 실제 체결가가 칸에 닿으면 바로 당첨이고, 끝까지 안 닿으면 건 금액을 잃어요.</li>
            <li>칸이 열리기 1.5초 전까지만 걸 수 있어요. 지금 가격이 든 칸, 거의 확실하거나 거의 안 닿는 칸은 열리지 않아요.</li>
            <li>건 금액과 받은 금액은 계좌 현금에 바로 반영돼 순자산과 랭킹에도 들어가요.</li>
          </ul>
          {!user && <button type="button" className="nx-btn is-primary" onClick={() => openLoginPrompt(TAP_PATH(code))}>로그인하고 걸기</button>}
          {user && <Link className="nx-tap-link" to={`/market/${code}`}>{listing?.name ?? code} 주식 거래하기</Link>}
        </section>
        <section className="nx-tap-history" aria-labelledby="nx-tap-history-title">
          <h2 id="nx-tap-history-title">최근 결과</h2>
          {recent.length === 0 ? <p className="nx-tap-empty">아직 결과가 난 칸이 없어요. 격자에서 칸을 눌러 걸어 보세요.</p> : (
            <ol>
              {recent.map((bet) => (
                <li key={bet.id} className={`is-${bet.status.toLowerCase()}`}>
                  <span className="nx-num">{won(bet.bandLow)}~</span>
                  <span className="nx-num">{bet.multiplier.toFixed(1)}× · {shortWon(bet.stake)}</span>
                  <strong className="nx-num">{bet.status === "WON" ? `+${won(bet.payout)}` : bet.status === "VOID" ? "환불" : `-${won(bet.stake)}`}</strong>
                </li>
              ))}
            </ol>
          )}
        </section>
      </div>
    </section>
  );
}
