import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { GameIcon } from "../../components/GameIcon";
import { ApiError, apiFetch } from "../../lib/api";
import { openLoginPrompt, useAuthUser } from "../../lib/auth";
import { won } from "../../market/format";
import { useMarket, useMarketApi } from "../../market/MarketProvider";
import { LISTING_BY_CODE } from "../../market/universe";
import { TAP_PATH, navigate } from "../../router";
import { useNxTheme } from "../uiMode";
import { drawTapFrame, type TapBoardRefs } from "./tap/tapBoard";
import { TapControls, TapFoot } from "./tap/TapPanels";
import { LEAD_MARGIN_MS, cellKey, readPalette, shortWon, type Burst, type Cell, type Geometry, type Palette, type PlaceResult, type TapBet, type TapState } from "./tap/tapShared";

export const TAP_CODES = ["GI", "SR", "OW", "ZZZ", "WH", "GOV", "BA", "UMA"] as const;
const POLL_MS = 3_000;
const SAMPLE_KEEP_MS = 150_000;


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
  const paletteRef = useRef<Palette | null>(null);
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

  /* 격자·캔들·현재가를 매 프레임 그린다. React 상태는 거치지 않고 ref만 읽는다. */
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    paletteRef.current = readPalette(canvas);
    const refs: TapBoardRefs = {
      state: stateRef, samples: samplesRef, center: centerRef, geometry: geometryRef, hover: hoverRef, keyCell: keyCellRef,
      bets: betsRef, odds: oddsRef, oddsAt: oddsAtRef, oddsBand: oddsBandRef, bursts: burstsRef,
    };
    let frame = 0;
    let last = performance.now();
    const draw = (time: number) => {
      frame = requestAnimationFrame(draw);
      const dt = Math.min(0.1, (time - last) / 1000);
      last = time;
      const s = stateRef.current;
      const dpr = window.devicePixelRatio || 1;
      const width = canvas.clientWidth, height = canvas.clientHeight;
      if (canvas.width !== Math.round(width * dpr) || canvas.height !== Math.round(height * dpr)) {
        canvas.width = Math.round(width * dpr);
        canvas.height = Math.round(height * dpr);
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, width, height);
      if (!s?.ready) return;
      drawTapFrame(ctx, refs, s, paletteRef.current!, code, width, height, serverNow(), time, dt);
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

  const open = bets.filter((bet) => bet.code === code && (bet.status === "OPEN" || bet.status === "PENDING"));
  const recent = bets.filter((bet) => bet.code === code && (bet.status === "WON" || bet.status === "LOST" || bet.status === "VOID"))
    .sort((a, b) => b.slotStart - a.slotStart).slice(0, 8);
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

      <TapControls stakes={state?.stakes ?? [10_000, 50_000, 100_000]} stake={stake} onStake={setStake} signedIn={Boolean(user)}
        cash={snapshot.portfolio.cash} today={state?.today} openCount={open.length} />

      <TapFoot code={code} name={listing?.name ?? code} signedIn={Boolean(user)} recent={recent} />
    </section>
  );
}
