import { useEffect, useState, useSyncExternalStore } from "react";
import { onIdTokenChanged, signInAnonymously, type User } from "firebase/auth";
import { apiFetch } from "./api";
import { firebaseAuth } from "./firebase";
import { isReturnablePath, navigate, type LoginMethod } from "../router";

export function useAuthUser() {
  const [state, setState] = useState<{ user: User | null; ready: boolean }>({ user: firebaseAuth.currentUser, ready: false });
  /* 토큰이 바뀔 때도 다시 그린다. 체험 계정에 가입 수단을 붙이면 같은 사용자 객체가 그대로 남아
     로그인 상태 이벤트는 오지 않고, 새 토큰을 받을 때에만 isAnonymous가 false로 바뀐 걸 알 수 있다. */
  useEffect(() => onIdTokenChanged(firebaseAuth, (user) => setState({ user, ready: true })), []);
  return state;
}

/* ── 로그인 없이 체험하기 ──
 * Firebase 익명 로그인으로 서버에 체험 계정을 만든다. 같은 브라우저에서 가입하면 익명 계정에 가입 수단을
 * 붙이므로(LoginCard) 자산과 기록이 그대로 이어진다. 앱(WebView) 셸은 앱 로그인 흐름이 따로 있어 쓰지 않는다. */

export function guestModeAvailable(): boolean {
  return document.documentElement.dataset.appShell !== "mobile" && !window.ReactNativeWebView;
}

export function isGuest(user: User | null | undefined): boolean {
  return Boolean(user?.isAnonymous);
}

export async function startGuestSession(): Promise<void> {
  if (firebaseAuth.currentUser) return;
  await signInAnonymously(firebaseAuth);
  await apiFetch("/api/auth/login", { method: "POST" });
}

/** 가입한 회원만 쓰는 기능(글쓰기·채팅·전광판 등) 앞에서 부른다. 체험 중이면 가입 창을 띄우고 false. */
export function requireMember(next?: string): boolean {
  const user = firebaseAuth.currentUser;
  if (user && !user.isAnonymous) return true;
  openLoginPrompt(next ?? window.location.pathname);
  return false;
}

/** Only return to an existing page in this app, never a supplied external URL. */
export function safeReturnPath(value: string | null): string {
  if (!value || !value.startsWith("/")) return "/";
  try {
    const url = new URL(value, window.location.origin);
    if (url.origin !== window.location.origin || !isReturnablePath(url.pathname)) return "/";
    return url.pathname;
  } catch {
    return "/";
  }
}

export function loginPath(method: LoginMethod = "choose", next?: string): string {
  const current = new URLSearchParams(window.location.search);
  const params = new URLSearchParams();
  params.set("next", safeReturnPath(next ?? current.get("next") ?? window.location.pathname));
  // 앱 로그인 흐름(mobileReturn.ts)에 필요한 값. 로그인 방법을 바꿔도 함께 넘겨야 앱으로 돌아갈 수 있다.
  for (const key of ["mobileAuth", "returnUri", "state", "codeChallenge"]) {
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
    case "auth/admin-restricted-operation": return "지금은 로그인 없이 체험하기를 쓸 수 없어요. 로그인해 주세요.";
    case "auth/credential-already-in-use": return "이미 가입된 계정이에요. 그 계정으로 로그인해 주세요.";
    case "auth/provider-already-linked": return "이미 이 방법으로 가입했어요.";
    case "auth/network-request-failed": return "연결을 확인한 뒤 다시 시도해 주세요.";
    default: return code ? "로그인을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요." : "계정 정보를 연결하지 못했습니다. 다시 시도해 주세요.";
  }
}
