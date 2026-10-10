import { useSyncExternalStore } from "react";
import { onAuthStateChanged } from "firebase/auth";
import { apiFetch } from "./api";
import { firebaseAuth } from "./firebase";

export type Profile = {
  nickname: string;
  email: string;
  profileImageUrl: string | null;
  profileCompleted: boolean;
  resetAvailable: boolean;
  nicknameChangeAvailable?: boolean;
  nicknameChangeAvailableAt?: string | null;
};

type ProfileState = { uid: string | null; profile: Profile | null };

/* 상단 프로필 버튼과 설정 화면이 같은 프로필을 보도록 한 곳에서 관리한다. */
let state: ProfileState = { uid: null, profile: null };
let started = false;
let session = 0;
const listeners = new Set<() => void>();

function publish(next: ProfileState): void {
  state = next;
  for (const listener of listeners) listener();
}

async function load(uid: string, current: number, attempt = 0): Promise<void> {
  try {
    const profile = await apiFetch<Profile>("/api/profile");
    if (current === session) publish({ uid, profile });
  } catch {
    // 로그인 직후 서버 연결이 늦을 수 있어 몇 번만 다시 시도한다.
    if (current === session && attempt < 3) window.setTimeout(() => void load(uid, current, attempt + 1), 1500 * (attempt + 1));
  }
}

function start(): void {
  if (started) return;
  started = true;
  onAuthStateChanged(firebaseAuth, (user) => {
    session += 1;
    publish({ uid: user?.uid ?? null, profile: null });
    if (user) void load(user.uid, session);
  });
}

export function useProfile(): Profile | null {
  return useSyncExternalStore(
    (listener) => {
      start();
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => state.profile,
    () => state.profile,
  );
}

/** 설정 화면에서 저장한 결과를 상단 프로필 버튼에도 바로 반영한다. */
export function setProfile(profile: Profile): void {
  if (!state.uid || firebaseAuth.currentUser?.uid !== state.uid) return;
  publish({ uid: state.uid, profile });
}
