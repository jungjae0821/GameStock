import { ApiError, apiFetch } from "./api";

/*
 * 종목 채팅방 · 지표 차트 · BlockQuant/snScript 서버 계약(docs/research-backend.md).
 * 시간은 UTC ISO 문자열, 가격은 원, 수량은 정수 주다. 모든 글과 소스는 일반 텍스트로만 그린다.
 */

const stock = (code: string) => `/api/stocks/${encodeURIComponent(code)}`;

/* ───────── 종목 채팅 ───────── */

export type ChatEntry = {
  id: number;
  stockCode: string;
  authorId: number;
  nickname: string;
  body: string;
  deleted: boolean;
  createdAt: string;
  updatedAt: string;
};
export type ChatPage = { items: ChatEntry[]; nextCursor: number | null; hasMore: boolean };

export const CHAT_MAX_LENGTH = 1000;

/** before와 after는 함께 보낼 수 없다. 둘 다 없으면 최신 목록(ID 내림차순)이다. */
export function loadChat(code: string, cursor: { before?: number; after?: number } = {}, signal?: AbortSignal, limit = 50) {
  const query = new URLSearchParams({ limit: String(limit) });
  if (cursor.before !== undefined) query.set("before", String(cursor.before));
  else if (cursor.after !== undefined) query.set("after", String(cursor.after));
  return apiFetch<ChatPage>(`${stock(code)}/community/chat?${query}`, { signal, auth: false });
}

export function sendChat(code: string, body: string) {
  return apiFetch<ChatEntry>(`${stock(code)}/community/chat`, { method: "POST", body: JSON.stringify({ body }) });
}

export function deleteChat(code: string, id: number) {
  return apiFetch<void>(`${stock(code)}/community/entries/${id}`, { method: "DELETE" });
}

/* ───────── 지표 차트 ───────── */

export type ChartRange = "1h" | "6h" | "12h" | "1d" | "3d" | "1w" | "2w" | "1m" | "1y";
export const CHART_RANGES: Array<{ value: ChartRange; label: string }> = [
  { value: "1h", label: "1분" }, { value: "6h", label: "5분" }, { value: "12h", label: "10분" },
  { value: "1d", label: "30분" }, { value: "3d", label: "1시간" }, { value: "1w", label: "2시간" },
  { value: "2w", label: "6시간" }, { value: "1m", label: "일" }, { value: "1y", label: "주" },
];
export const rangeLabel = (range: string) => CHART_RANGES.find((item) => item.value === range)?.label ?? range;

export type IndicatorType = "SMA" | "EMA" | "RSI" | "MACD" | "BBANDS" | "ATR";
export type IndicatorSpec = { id: string; type: IndicatorType; period: number };
export type Candle = {
  recordedAt: string; openPrice: number; highPrice: number; lowPrice: number; closePrice: number;
  /** 체결 건수. 거래량(주)이 아니다. */
  volume: number; updatedAt: string;
};
export type IndicatorSeries = { id: string; pane: "overlay" | "oscillator"; values: Record<string, Array<number | null>> };
export type ChartAnalysis = {
  stockCode: string; range: string; intervalSeconds: number; volumeUnit: "TRADE_COUNT";
  candles: Candle[]; indicators: IndicatorSeries[]; asOf: string;
};

export function analyzeChart(code: string, range: ChartRange, indicators: IndicatorSpec[], signal?: AbortSignal) {
  return apiFetch<ChartAnalysis>(`${stock(code)}/chart-analysis`, { method: "POST", body: JSON.stringify({ range, indicators }), signal, auth: false });
}

/* ───────── BlockQuant / snScript ───────── */

export type BinaryOp = "+" | "-" | "*" | "/" | ">" | ">=" | "<" | "<=" | "==" | "!=" | "and" | "or" | "crossover" | "crossunder";
export type SeriesOp = "sma" | "ema" | "rsi" | "stddev";
export type Expr =
  | { op: "number"; value: number }
  | { op: "ref"; name: string }
  | { op: SeriesOp; period: number; args: [Expr] }
  | { op: "atr"; period: number; args: [] }
  | { op: "abs" | "not" | "neg"; args: [Expr] }
  | { op: BinaryOp; args: [Expr, Expr] };
export type Binding = { name: string; expression: Expr };
export type Rule = { side: "BUY" | "SELL"; quantity: number; condition: Expr };
export type BlockProgram = { version: 1; bindings: Binding[]; plots: Binding[]; rules: Rule[] };

export type Source =
  | { language: "SNSCRIPT"; source: string; blocks?: null }
  | { language: "BLOCKS"; blocks: BlockProgram; source?: null };
export type Diagnostic = { line: number; column: number; message: string };
export type Compilation = { valid: boolean; program: BlockProgram | null; diagnostics: Diagnostic[] };

export type ProjectStatus = "PAUSED" | "ACTIVE" | "RUNNING" | "PAUSING";
export type Project = {
  id: number; userId: number; name: string; stockCode: string; range: string;
  language: "SNSCRIPT" | "BLOCKS"; source: string; program: BlockProgram;
  revision: number; status: ProjectStatus;
  maxOrders: number; maxNotional: number; ordersUsed: number; notionalUsed: number;
  lastBar: string | null; updatedAt: string;
};
export type ProjectSave = { name: string; stockCode: string; range: string; code: Source; maxOrders: number; maxNotional: number; revision?: number };
export type Run = {
  id: number; revision: number; barTime: string; side: "BUY" | "SELL"; quantity: number;
  limitPrice: number; status: "SUBMITTING" | "ACCEPTED" | "REJECTED" | "UNKNOWN";
  message: string; createdAt: string; orderId: number | null;
};
export type BacktestRequest = { stockCode: string; range: string; code: Source; initialCash: number; feeBps: number; slippageBps: number };
export type BacktestResult = {
  fillModel: "NEXT_BAR_OPEN_LONG_ONLY"; initialCash: number; finalEquity: number;
  returnPercent: number; maxDrawdownPercent: number; rejectedOrders: number;
  trades: Array<{ signalAt: string; filledAt: string; side: "BUY" | "SELL"; quantity: number; price: number; fee: number }>;
  equity: Array<{ time: string; cash: number; quantity: number; equity: number }>;
  plots: Record<string, Array<number | null>>;
};
export type Capabilities = {
  version: number; languages: string[]; ranges: Record<string, number>; indicators: string[]; functions: string[];
  maxProjects: number; maxCandles: number; maxNodes: number; liveOrderType: "LIMIT";
};

export const MAX_PROJECTS = 20;
export const RUN_PAGE = 30;

export const quantCapabilities = (signal?: AbortSignal) => apiFetch<Capabilities>("/api/quant/capabilities", { signal, auth: false });
export const compileQuant = (code: Source, signal?: AbortSignal) =>
  apiFetch<Compilation>("/api/quant/compile", { method: "POST", body: JSON.stringify(code), signal });
export const backtestQuant = (request: BacktestRequest, signal?: AbortSignal) =>
  apiFetch<BacktestResult>("/api/quant/backtest", { method: "POST", body: JSON.stringify(request), signal });
export const listProjects = (signal?: AbortSignal) => apiFetch<Project[]>("/api/quant/projects", { signal });
export const getProject = (id: number, signal?: AbortSignal) => apiFetch<Project>(`/api/quant/projects/${id}`, { signal });
export const createProject = (save: ProjectSave) =>
  apiFetch<Project>("/api/quant/projects", { method: "POST", body: JSON.stringify(save) });
export const replaceProject = (id: number, save: ProjectSave) =>
  apiFetch<Project>(`/api/quant/projects/${id}`, { method: "PUT", body: JSON.stringify(save) });
export const setProjectState = (id: number, active: boolean, revision: number) =>
  apiFetch<Project>(`/api/quant/projects/${id}/state`, { method: "PATCH", body: JSON.stringify({ active, revision }) });
export const deleteProject = (id: number) => apiFetch<void>(`/api/quant/projects/${id}`, { method: "DELETE" });
export function listRuns(id: number, before?: number, signal?: AbortSignal) {
  const query = new URLSearchParams({ limit: String(RUN_PAGE) });
  if (before !== undefined) query.set("before", String(before));
  return apiFetch<Run[]>(`/api/quant/projects/${id}/runs?${query}`, { signal });
}

/* ───────── 오류 문구 ───────── */

const STATUS_TEXT: Record<number, string> = {
  401: "로그인이 필요해요.",
  403: "이 요청을 할 수 있는 권한이 없어요.",
  404: "찾을 수 없어요. 이미 지워졌을 수 있어요.",
  409: "다른 곳에서 먼저 바뀌었어요. 최신 상태를 불러온 뒤 다시 시도해 주세요.",
  413: "내용이 너무 길어요.",
  429: "요청이 많아요. 잠시 후 다시 시도해 주세요.",
};

/** 서버 검증 문구를 그대로 보여 주고, Spring 기본 오류처럼 문구가 없으면 상태별 기본 문구를 쓴다. */
export function failure(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (/^API 요청 실패 \(\d+\)$/.test(error.message)) return STATUS_TEXT[error.status] ?? fallback;
    return error.message;
  }
  return error instanceof Error && error.message ? error.message : fallback;
}

export const isAbort = (error: unknown) => error instanceof DOMException && error.name === "AbortError";
