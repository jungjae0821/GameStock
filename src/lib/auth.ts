import { useEffect, useState, useSyncExternalStore } from "react";
import { onAuthStateChanged, type User } from "firebase/auth";
import { firebaseAuth } from "./firebase";
import { navigate, type LoginMethod } from "../router";

export function useAuthUser() {
  const [state, setState] = useState<{ user: User | null; ready: boolean }>({ user: firebaseAuth.currentUser, ready: false });
  useEffect(() => onAuthStateChanged(firebaseAuth, (user) => setState({ user, ready: true })), []);
  return state;
}

/** Only return to an existing page in this app, never a supplied external URL. */
export function safeReturnPath(value: string | null): string {
  if (!value || !value.startsWith("/")) return "/";
  try {
    const url = new URL(value, window.location.origin);
    if (url.origin !== window.location.origin || !/^\/(?:market(?:\/[A-Za-z0-9_-]+)?\/?|news\/?|ranking\/?|mypage\/?|)$/.test(url.pathname)) return "/";
    return url.pathname;
  } catch {
    return "/";
  }
}

export function loginPath(method: LoginMethod = "choose", next?: string): string {
  const current = new URLSearchParams(window.location.search);
  const params = new URLSearchParams();
  params.set("next", safeReturnPath(next ?? current.get("next") ?? window.location.pathname));
  for (const key of ["mobileAuth", "returnUri", "state"]) {
    const value = current.get(key);
    if (value) params.set(key, value);
  }
  if (document.documentElement.dataset.appShell === "mobile") params.set("app-shell", "1");
  return `/login${method === "choose" ? "" : `/${method}`}?${params}`;
}

export function requestLogin(next?: string, replace = false): void {
  navigate(loginPath("choose", next), { replace });
}

export function requireSignIn(next?: string): boolean {
  if (firebaseAuth.currentUser) return true;
  openLoginPrompt(next ?? "/");
  return false;
}

/* ── 화면 이동 없이 현재 화면 위에 띄우는 로그인 모달 상태 ── */

type LoginPrompt = { next: string } | null;
let loginPrompt: LoginPrompt = null;
const loginPromptListeners = new Set<() => void>();

export function useLoginPrompt(): LoginPrompt {
  return useSyncExternalStore(
    (listener) => {
      loginPromptListeners.add(listener);
      return () => loginPromptListeners.delete(listener);
    },
    () => loginPrompt,
    () => loginPrompt,
  );
}

export function openLoginPrompt(next = "/"): void {
  // 앱(WebView) 셸에서는 기존처럼 /login 화면으로 이동한다(딥링크 콜백 흐름 유지).
  if (document.documentElement.dataset.appShell === "mobile") {
    requestLogin(next);
    return;
  }
  const target = safeReturnPath(next);
  if (loginPrompt?.next === target) return;
  loginPrompt = { next: target };
  for (const listener of loginPromptListeners) listener();
}

export function closeLoginPrompt(): void {
  if (!loginPrompt) return;
  loginPrompt = null;
  for (const listener of loginPromptListeners) listener();
}

export function authErrorMessage(error: unknown): string {
  const code = typeof error === "object" && error !== null && "code" in error ? String(error.code) : "";
  switch (code) {
    case "auth/invalid-email": return "이메일 주소를 확인해 주세요.";
    case "auth/invalid-credential":
    case "auth/user-not-found":
    case "auth/wrong-password": return "이메일 또는 비밀번호를 확인해 주세요.";
    case "auth/email-already-in-use": return "이미 사용 중인 이메일입니다. 로그인하거나 비밀번호를 재설정해 주세요.";
    case "auth/weak-password":
    case "auth/password-does-not-meet-requirements": return "비밀번호가 가입 조건에 맞지 않습니다. 더 길고 복잡한 비밀번호를 입력해 주세요.";
    case "auth/operation-not-allowed": return "이 로그인 방법을 현재 사용할 수 없습니다. 다른 방법을 선택해 주세요.";
    case "auth/popup-closed-by-user":
    case "auth/cancelled-popup-request": return "Google 로그인이 취소됐습니다. 다시 선택해 주세요.";
    case "auth/popup-blocked": return "팝업이 차단됐습니다. 이 사이트의 팝업을 허용한 뒤 다시 눌러 주세요.";
    case "auth/account-exists-with-different-credential": return "같은 이메일로 가입한 다른 로그인 방법을 사용해 주세요.";
    case "auth/too-many-requests": return "요청이 많아 잠시 제한됐습니다. 잠시 후 다시 시도해 주세요.";
    case "auth/user-disabled": return "이 계정은 현재 로그인할 수 없습니다.";
    case "auth/network-request-failed": return "연결을 확인한 뒤 다시 시도해 주세요.";
    default: return code ? "로그인을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요." : "계정 정보를 연결하지 못했습니다. 다시 시도해 주세요.";
  }
}
