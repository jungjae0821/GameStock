import { useSyncExternalStore } from "react";
import { onAuthStateChanged } from "firebase/auth";
import { apiFetch } from "./api";
import { firebaseAuth } from "./firebase";
import { refreshEngagement } from "./engagement";

/* 프랍 데스크 커리어(직급·승진·손실한도)와 개인 지갑. 서버 /api/career*와 맞춘다. */

export type CareerGrade = "TRAINEE" | "STAFF" | "ASSOCIATE" | "MANAGER" | "SENIOR_MANAGER" | "GENERAL_MANAGER" | "EXECUTIVE";
/** PERMIL: 0.1% 단위 수익률 · COUNT: 횟수 · CHECK: 했는지 여부 */
export type CareerStep = { id: string; label: string; unit: "PERMIL" | "COUNT" | "CHECK"; current: number; target: number; done: boolean };
export type CareerEvent = { kind: "PROMOTE" | "DEMOTE" | "RETRAIN" | "DAY_LIMIT" | "MIGRATE"; fromGrade: string | null; toGrade: string | null; assetAt: number; topUp: number; at: string };
export type Career = {
  /** 아직 커리어 이전 전인 기존 회원. 예전처럼 모든 기능이 열려 있다. */
  legacy: boolean;
  exempt: boolean;
  grade: CareerGrade;
  gradeLabel: string;
  level: number;
  allocation: number;
  bonusRate: number;
  nextGrade: CareerGrade | null;
  nextGradeLabel: string | null;
  gradeSince: string;
  gradeBase: number;
  markAsset: number;
  confirmedAsset: number;
  highWaterMark: number;
  returnPercent: number;
  /** 평가자산이 이 값 아래로 내려가면 강등(사원은 재교육). 0이면 강등이 없는 직급. */
  demotionAsset: number;
  dayOpenAsset: number;
  /** 하루에 잃어도 되는 금액. 0이면 일일 손실한도가 없는 직급. */
  dayLossLimit: number;
  lockedUntil: string | null;
  lockReason: "RETRAIN" | "DAY_LIMIT" | null;
  personalCash: number;
  promotion: CareerStep[];
  events: CareerEvent[];
};

/** failed: 아직 한 번도 읽지 못했고 마지막 요청이 실패했다. */
type State = { uid: string | null; data: Career | null; failed: boolean };

let state: State = { uid: null, data: null, failed: false };
let loadedAt = 0;
let requestSeq = 0;
let started = false;
let session = 0;
let refreshTimer: number | undefined;
const listeners = new Set<() => void>();

function publish(next: State): void {
  state = next;
  for (const listener of listeners) listener();
}

function accept(data: Career): void {
  if (!state.uid) return;
  const before = state.data;
  publish({ uid: state.uid, data, failed: false });
  loadedAt = Date.now();
  // 직급이 바뀌면 열리는 데스크도 바뀐다. 해금 정보도 바로 다시 읽는다.
  if (before && before.grade !== data.grade) refreshEngagement();
}

let inFlight: Promise<void> | null = null;
let again = false;

/**
 * 한 번에 한 요청만 보낸다. 여러 화면이 동시에 열려도 같은 요청을 함께 기다린다.
 * fresh는 무언가 바뀐 뒤(보상·승진 알림, 직접 새로 고침)라는 뜻이라, 진행 중인 요청이
 * 그 전에 출발했을 수 있으니 끝나면 한 번 더 읽는다.
 */
function load(current: number, fresh = false): Promise<void> {
  if (inFlight) {
    if (fresh) again = true;
    return inFlight;
  }
  inFlight = fetchCareer(current).finally(() => {
    inFlight = null;
    if (again && current === session) {
      again = false;
      void load(current);
    }
  });
  return inFlight;
}

async function fetchCareer(current: number): Promise<void> {
  // 늦게 끝난 요청이 더 새 응답(OJT 확인 결과 등)을 덮지 않게 순번을 맞춘다.
  const seq = ++requestSeq;
  try {
    const data = await apiFetch<Career>("/api/career");
    if (current === session && seq === requestSeq) accept(data);
  } catch {
    // 커리어가 없는 서버이거나 일시 오류다. 이미 읽은 값이 있으면 그대로 두고, 없으면 실패로 표시한다.
    if (current === session && seq === requestSeq && state.uid && !state.data) publish({ ...state, failed: true });
  }
}

function start(): void {
  if (started) return;
  started = true;
  onAuthStateChanged(firebaseAuth, (user) => {
    session += 1;
    window.clearInterval(refreshTimer);
    publish({ uid: user?.uid ?? null, data: null, failed: false });
    if (!user) return;
    const current = session;
    void load(current);
    // 거래로 승진 진행이 바뀌므로 1분마다, 화면에 돌아올 때, 서버가 알릴 때 다시 읽는다.
    refreshTimer = window.setInterval(() => { if (document.visibilityState === "visible") void load(current); }, 60_000);
  });
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible" && state.uid) void load(session); });
  window.addEventListener("gamestock:career", () => { if (state.uid) void load(session, true); });
}

function subscribe(listener: () => void): () => void {
  start();
  listeners.add(listener);
  // 화면을 열 때마다 다시 읽는다. 승진은 서버가 읽힐 때 판정하므로, 거래하고 돌아온 화면이 옛 직급을 보이면 안 된다.
  if (state.uid && Date.now() - loadedAt > 5_000) void load(session);
  return () => { listeners.delete(listener); };
}

export function useCareer(): Career | null {
  return useSyncExternalStore(subscribe, () => state.data, () => state.data);
}

/** 패널처럼 로딩·실패를 구분해 보여 줄 화면용. */
export function useCareerFailed(): boolean {
  return useSyncExternalStore(subscribe, () => state.failed, () => state.failed);
}

export function refreshCareer(): void {
  if (state.uid) void load(session, true);
}

/** 서버가 스스로 볼 수 없는 OJT 단계(종목 화면 보기, 리스크 교육 확인)를 알린다. 이미 했으면 요청하지 않는다. */
export async function acknowledgeOjt(step: "MARKET" | "RISK"): Promise<void> {
  const data = state.data;
  if (!state.uid || !data || data.legacy || data.grade !== "TRAINEE") return;
  if (data.promotion.some((item) => item.id === step && item.done)) return;
  requestSeq += 1;
  // 서버는 바뀐 게 없으면(이미 끝낸 단계 등) 204로 답한다. 그때는 다시 읽어 맞춘다.
  const next = await apiFetch<Career | undefined>(`/api/career/ojt/${step}`, { method: "POST" });
  if (next) accept(next);
  else refreshCareer();
}

export type WalletEntry = { id: number; kind: string; amount: number; balanceAfter: number; memo: string; at: string };
export const fetchWallet = () => apiFetch<{ balance: number; entries: WalletEntry[] }>("/api/career/wallet");
