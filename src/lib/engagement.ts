import { useSyncExternalStore } from "react";
import { onAuthStateChanged } from "firebase/auth";
import { apiFetch } from "./api";
import { firebaseAuth } from "./firebase";

/* 팬덤·시즌·공개 프로필·체결 피드·일일 예측·기능 해금. 서버 /api/engagement/*와 맞춘다. */

export type FeatureId = "FUTURES" | "OPTIONS" | "QUANT" | "TAP";
export type UnlockStep = { label: string; current: number; target: number; done: boolean };
/** grade는 그 기능이 열리는 직급이다. */
export type FeatureProgress = { id: FeatureId; name: string; unlocked: boolean; steps: UnlockStep[]; hint: string; grade: string; gradeLabel: string };
export type UnlockProgress = {
  allUnlocked: boolean;
  /** EARLY_MEMBER(커리어 이전 전 기존 회원) · STAFF(관리자·시연) · null(직급으로 여는 중) */
  reason: string | null;
  /** 지금 내 직급 */
  grade: string;
  gradeLabel: string;
  features: FeatureProgress[];
};

export type Prediction = {
  day: string;
  stockCode: string | null;
  basePrice: number;
  closePrice: number | null;
  result: "HIT" | "MISS" | null;
  reward: number;
  /** 아직 오늘 예측을 고를 수 있으면 true. */
  open: boolean;
  closesAt: string | null;
  previous: Prediction | null;
};

export type Engagement = {
  fandomCode: string | null;
  fandomChangeAvailableAt: string | null;
  publicProfile: boolean;
  prediction: Prediction;
  unlocks: UnlockProgress;
};

export type Fandom = { stockCode: string; name: string; fans: number; holders: number; weeklyReturnPercent: number | null; rank: number };

export type SeasonEntry = {
  rank: number;
  nickname: string;
  profileImageUrl: string | null;
  openingAsset: number;
  currentAsset: number;
  returnPercent: number;
  publicProfile: boolean;
};
export type Season = {
  id: number;
  name: string;
  status: "ACTIVE" | "CLOSED" | "RESET";
  startedAt: string | null;
  endedAt: string | null;
  participants: number;
  entries: SeasonEntry[];
  me: SeasonEntry | null;
};

export type PublicHolding = { stockCode: string; name: string; quantity: number; averagePrice: number; currentPrice: number; value: number; returnPercent: number };
export type PublicProfile = { nickname: string; profileImageUrl: string | null; equippedTitle: string | null; fandomCode: string | null; totalAsset: number; holdings: PublicHolding[] };

export type FeedItem = { id: number; stockCode: string; name: string; side: "BUY" | "SELL"; quantity: number; price: number; amount: number; nickname: string | null; whale: boolean; at: string };

type State = { uid: string | null; data: Engagement | null };

let state: State = { uid: null, data: null };
let started = false;
let session = 0;
let refreshTimer: number | undefined;
const listeners = new Set<() => void>();

function publish(next: State): void {
  state = next;
  for (const listener of listeners) listener();
}

async function load(current: number): Promise<void> {
  try {
    const data = await apiFetch<Engagement>("/api/engagement/me");
    if (current === session && state.uid) publish({ uid: state.uid, data });
  } catch {
    /* 아직 이 기능이 없는 서버이거나 일시 오류다. 화면은 해금 정보 없이 원래대로 동작한다. */
  }
}

function start(): void {
  if (started) return;
  started = true;
  onAuthStateChanged(firebaseAuth, (user) => {
    session += 1;
    window.clearInterval(refreshTimer);
    publish({ uid: user?.uid ?? null, data: null });
    if (!user) return;
    const current = session;
    void load(current);
    // 승진으로 해금이 바뀌므로 1분마다, 그리고 화면에 돌아올 때 다시 읽는다.
    refreshTimer = window.setInterval(() => { if (document.visibilityState === "visible") void load(current); }, 60_000);
  });
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible" && state.uid) void load(session); });
  // 승진·강등 알림이 오면 열리는 데스크가 바뀌었을 수 있다.
  window.addEventListener("gamestock:career", () => { if (state.uid) void load(session); });
}

export function useEngagement(): Engagement | null {
  return useSyncExternalStore(
    (listener) => {
      start();
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => state.data,
    () => state.data,
  );
}

export function refreshEngagement(): void {
  if (state.uid) void load(session);
}

function accept(data: Engagement): Engagement {
  if (state.uid) publish({ uid: state.uid, data });
  return data;
}

export const setFandom = (stockCode: string) =>
  apiFetch<Engagement>("/api/engagement/fandom", { method: "PUT", body: JSON.stringify({ stockCode }) }).then(accept);
export const setPublicProfile = (publicProfile: boolean) =>
  apiFetch<Engagement>("/api/engagement/public", { method: "PUT", body: JSON.stringify({ publicProfile }) }).then(accept);
export const submitPrediction = (stockCode: string) =>
  apiFetch<Engagement>("/api/engagement/prediction", { method: "POST", body: JSON.stringify({ stockCode }) }).then(accept);

export const fetchFandoms = (signal?: AbortSignal) => apiFetch<Fandom[]>("/api/engagement/fandoms", { auth: false, signal });
export const fetchSeason = () => apiFetch<Season | undefined>("/api/engagement/season");
export const fetchPublicProfile = (nickname: string) =>
  apiFetch<PublicProfile>(`/api/engagement/profiles/${encodeURIComponent(nickname)}`, { auth: false });
export const fetchFeed = (signal?: AbortSignal) => apiFetch<FeedItem[]>("/api/engagement/feed", { auth: false, signal });

/** 로그인하지 않았거나 서버가 아직 해금 정보를 주지 않으면 잠그지 않는다(서버가 최종 판정). */
export function featureLock(data: Engagement | null, id: FeatureId): FeatureProgress | null {
  const item = data?.unlocks.features.find((feature) => feature.id === id);
  return item && !item.unlocked ? item : null;
}

/* 포트폴리오를 공개한 닉네임. 랭킹 행이 누를 수 있는 이름인지 정할 때 쓴다. 화면 여러 줄이 한 요청을 나눠 쓴다. */
let publicNames: ReadonlySet<string> = new Set();
let publicLoadedAt = 0;
let publicLoading = false;
const publicListeners = new Set<() => void>();

function loadPublicNames(): void {
  if (publicLoading || Date.now() - publicLoadedAt < 30_000) return;
  publicLoading = true;
  apiFetch<string[]>("/api/engagement/public-nicknames", { auth: false })
    .then((names) => {
      publicNames = new Set(names);
      publicLoadedAt = Date.now();
      for (const listener of publicListeners) listener();
    })
    .catch(() => { publicLoadedAt = Date.now(); /* 기능이 없는 서버면 아무 이름도 누를 수 없게 둔다. */ })
    .finally(() => { publicLoading = false; });
}

export function usePublicNicknames(): ReadonlySet<string> {
  return useSyncExternalStore(
    (listener) => {
      publicListeners.add(listener);
      loadPublicNames();
      const timer = window.setInterval(loadPublicNames, 30_000);
      return () => { publicListeners.delete(listener); window.clearInterval(timer); };
    },
    () => publicNames,
    () => publicNames,
  );
}
