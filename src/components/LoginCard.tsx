import { useCallback, useEffect, useRef, useState } from "react";
import {
  createUserWithEmailAndPassword, EmailAuthProvider, GoogleAuthProvider, linkWithCredential, linkWithPopup, sendPasswordResetEmail,
  signInWithCredential, signInWithEmailAndPassword, signInWithPopup, validatePassword,
} from "firebase/auth";
import { BrandLogo } from "../components/BrandMark";
import { Link } from "../components/Link";
import { firebaseAuth, googleProvider } from "../lib/firebase";
import { apiFetch } from "../lib/api";
import { authErrorMessage, guestModeAvailable, loginPath, safeReturnPath, startGuestSession, useAuthUser } from "../lib/auth";
import { reloadProfile } from "../lib/profile";
import { mobileReturnTarget } from "../lib/mobileReturn";
import { MARKET_PATH, PRIVACY_PATH, TERMS_PATH, navigate, type LoginMethod } from "../router";

const TITLES: Record<LoginMethod, string> = {
  choose: "로그인", google: "Google 로그인", email: "이메일 로그인", register: "이메일로 회원가입", reset: "비밀번호 찾기",
};

const DESCRIPTIONS: Record<LoginMethod, string> = {
  choose: "",
  google: "Google 계정으로 계속 진행합니다.",
  email: "이메일과 비밀번호로 로그인하세요.",
  register: "이메일과 비밀번호로 새 계정을 만듭니다.",
  reset: "가입할 때 사용한 이메일을 입력해 주세요.",
};

/* 로그인 중인 화면(모달)을 잃지 않게 약관은 새 탭으로 연다. */
const LegalLinks = () => (
  <>
    <a href={TERMS_PATH} target="_blank" rel="noopener">이용약관</a>과 <a href={PRIVACY_PATH} target="_blank" rel="noopener">개인정보처리방침</a>
  </>
);

/** Google 브랜드 4색 G 아이콘(장식). */
function GoogleIcon() {
  return (
    <svg viewBox="0 0 18 18" width="17" height="17" aria-hidden="true">
      <path fill="#4285F4" d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92a8.78 8.78 0 0 0 2.68-6.62z" />
      <path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18z" />
      <path fill="#FBBC05" d="M3.97 10.72a5.4 5.4 0 0 1 0-3.44V4.95H.96a9 9 0 0 0 0 8.1l3.01-2.33z" />
      <path fill="#EA4335" d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58A9 9 0 0 0 .96 4.95l3.01 2.33C4.68 5.16 6.66 3.58 9 3.58z" />
    </svg>
  );
}

/*
 * 로그인 카드. /login 페이지와 로그인 모달이 같이 쓴다.
 * onDone이 있으면 모달 모드: 화면 이동 대신 onDone으로 닫히고,
 * 로그인 방법 전환도 링크 대신 onMethodChange로 바꾼다.
 */
export function LoginCard({ method, next: nextProp, onDone, onMethodChange }: {
  method: LoginMethod;
  /** 모달에서 열었을 때 돌아갈 화면. 없으면 URL의 next를 따른다. */
  next?: string;
  onDone?: () => void;
  onMethodChange?: (method: LoginMethod) => void;
}) {
  const modal = onDone != null;
  const { user: authUser, ready } = useAuthUser();
  /* 체험 계정(익명 로그인)은 아직 가입 전이라 로그인 카드에서는 로그아웃 상태처럼 가입 방법을 보여 준다. */
  const guest = Boolean(authUser?.isAnonymous);
  const user = guest ? null : authUser;
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [nativeWaiting, setNativeWaiting] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const completionStarted = useRef(false);
  const params = new URLSearchParams(window.location.search);
  const next = nextProp ?? safeReturnPath(params.get("next"));
  const mobileReturn = mobileReturnTarget(params, import.meta.env.DEV);
  const mobileReturnUri = mobileReturn?.uri ?? null;
  const mobileAuthState = mobileReturn?.state ?? null;
  const mobileCodeChallenge = mobileReturn?.codeChallenge ?? null;
  // 앱이 열었지만 돌려보낼 주소를 믿을 수 없는 요청. 웹 로그인으로 바꿔 진행하지 않고 멈춘다.
  const mobileRequestRejected = params.get("mobileAuth") === "1" && !mobileReturn;

  const finishSession = useCallback(async () => {
    setBusy(true);
    setError("");
    try {
      // Create/load the same trading account for either Firebase provider before returning.
      await apiFetch("/api/auth/login", { method: "POST" });
      if (mobileReturnUri && mobileAuthState && mobileCodeChallenge) {
        const result = await apiFetch<{ code: string }>("/api/auth/mobile/issue", {
          method: "POST",
          body: JSON.stringify({ codeChallenge: mobileCodeChallenge }),
        });
        const callback = new URL(mobileReturnUri);
        callback.searchParams.set("code", result.code);
        callback.searchParams.set("state", mobileAuthState);
        window.location.replace(callback.toString());
      } else if (onDone) {
        // 모달: 열려 있던 화면에 머문다. 특정 화면을 요청받았을 때만 이동한다.
        if (next !== "/") navigate(next, { replace: true });
        onDone();
      } else {
        navigate(next, { replace: true });
      }
    } catch (failure) {
      setError(authErrorMessage(failure));
    } finally {
      setBusy(false);
      setNativeWaiting(false);
    }
  }, [next, mobileReturnUri, mobileAuthState, mobileCodeChallenge, onDone]);

  useEffect(() => {
    // Visiting a browser-to-app URL alone must never issue a login exchange code.
    if (!ready || !user || mobileReturnUri || mobileRequestRejected || completionStarted.current) return;
    completionStarted.current = true;
    void finishSession();
  }, [ready, user, mobileReturnUri, mobileRequestRejected, finishSession]);

  useEffect(() => {
    setPassword("");
    setConfirmPassword("");
    setShowPassword(false);
    setError("");
    setNotice("");
    setNativeWaiting(false);
    if (!firebaseAuth.currentUser || firebaseAuth.currentUser.isAnonymous) setBusy(false);
  }, [method]);

  useEffect(() => {
    const reportError = (event: Event) => {
      const detail = (event as CustomEvent<{ type?: string }>).detail;
      if (event.type === "gamestock-native-auth" && detail?.type !== "GOOGLE_AUTH_ERROR") return;
      setError("앱 로그인을 완료하지 못했습니다. 다시 시도해 주세요.");
      setBusy(false);
      setNativeWaiting(false);
    };
    window.addEventListener("gamestock-native-auth", reportError);
    window.addEventListener("gamestock-login-error", reportError);
    return () => {
      window.removeEventListener("gamestock-native-auth", reportError);
      window.removeEventListener("gamestock-login-error", reportError);
    };
  }, []);

  /* 체험 계정에 가입 수단을 붙인 뒤. UID가 그대로라 로그인 상태 이벤트가 오지 않으므로, 새 토큰을 받아
     서버가 일반 계정으로 바꾸게 하고 직접 마무리한다. */
  const finishUpgrade = async () => {
    completionStarted.current = true;
    await firebaseAuth.currentUser?.getIdToken(true);
    await finishSession();
    reloadProfile();
  };

  const startGuest = async () => {
    setBusy(true);
    setError("");
    try {
      await startGuestSession();
      reloadProfile();
      if (onDone) {
        if (next !== "/") navigate(next, { replace: true });
        onDone();
      } else {
        navigate(next === "/" ? "/trade" : next, { replace: true });
      }
    } catch (failure) {
      setError(authErrorMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  const googleLogin = async () => {
    setBusy(true);
    setError("");
    if (window.ReactNativeWebView) {
      setNativeWaiting(true);
      window.ReactNativeWebView.postMessage(JSON.stringify({ type: "GOOGLE_LOGIN", next }));
      return;
    }
    try {
      const current = firebaseAuth.currentUser;
      if (current?.isAnonymous) {
        try {
          await linkWithPopup(current, googleProvider);
          await finishUpgrade();
        } catch (failure) {
          /* 이미 가입한 Google 계정이면 그 계정으로 로그인한다. 체험 기록은 이어지지 않는다. */
          const credential = typeof failure === "object" && failure !== null && "code" in failure && failure.code === "auth/credential-already-in-use"
            ? GoogleAuthProvider.credentialFromError(failure as Parameters<typeof GoogleAuthProvider.credentialFromError>[0]) : null;
          if (!credential) throw failure;
          await signInWithCredential(firebaseAuth, credential);
        }
      } else {
        await signInWithPopup(firebaseAuth, googleProvider);
      }
    } catch (failure) {
      setError(authErrorMessage(failure));
    } finally {
      if (!firebaseAuth.currentUser || firebaseAuth.currentUser.isAnonymous || mobileReturnUri) setBusy(false);
    }
  };

  const submitEmail = async () => {
    setError("");
    setNotice("");
    if (method === "register" && !agreed) {
      setError("만 14세 이상인지, 약관에 동의하는지 확인해 주세요.");
      return;
    }
    if (method === "register" && password !== confirmPassword) {
      setError("비밀번호 확인이 일치하지 않습니다.");
      return;
    }
    setBusy(true);
    try {
      if (method === "reset") {
        await sendPasswordResetEmail(firebaseAuth, email.trim());
        setNotice("가입된 이메일이라면 비밀번호 재설정 메일을 보내드렸습니다. 받은편지함과 스팸함을 확인해 주세요.");
      } else if (method === "register") {
        // Auth Emulator does not implement getPasswordPolicy; account creation still validates the password.
        const validation = firebaseAuth.emulatorConfig ? null : await validatePassword(firebaseAuth, password);
        if (validation && !validation.isValid) {
          const missing = [
            validation.meetsMinPasswordLength === false ? `${validation.passwordPolicy.customStrengthOptions.minPasswordLength}자 이상` : "",
            validation.containsLowercaseLetter === false ? "영문 소문자" : "",
            validation.containsUppercaseLetter === false ? "영문 대문자" : "",
            validation.containsNumericCharacter === false ? "숫자" : "",
            validation.containsNonAlphanumericCharacter === false ? "특수문자" : "",
          ].filter(Boolean);
          setError(missing.length ? `비밀번호 조건을 확인해 주세요: ${missing.join(", ")}` : "비밀번호가 가입 조건에 맞지 않습니다.");
          return;
        }
        const current = firebaseAuth.currentUser;
        if (current?.isAnonymous) {
          await linkWithCredential(current, EmailAuthProvider.credential(email.trim(), password));
          await finishUpgrade();
        } else {
          await createUserWithEmailAndPassword(firebaseAuth, email.trim(), password);
        }
      } else {
        await signInWithEmailAndPassword(firebaseAuth, email.trim(), password);
      }
    } catch (failure) {
      setError(authErrorMessage(failure));
    } finally {
      if (method === "reset" || !firebaseAuth.currentUser || firebaseAuth.currentUser.isAnonymous || mobileReturnUri) setBusy(false);
    }
  };

  const isEmailForm = method === "email" || method === "register" || method === "reset";

  /** 모달에서는 화면 이동 대신 모달 안에서 로그인 방법만 바꾼다. */
  const methodLink = (target: LoginMethod, label: string, className?: string) =>
    modal ? (
      <button type="button" className={className} onClick={() => onMethodChange?.(target)}>{label}</button>
    ) : (
      <Link className={className} to={loginPath(target, next)}>{label}</Link>
    );

  return (
    <div className="login-panel">
      {!mobileReturnUri && (modal ? (
        <button type="button" className="login-close" onClick={onDone} aria-label="로그인 창 닫기">
          <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true">
            <path d="M5.5 5.5l9 9m0-9l-9 9" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" fill="none" />
          </svg>
        </button>
      ) : (
        <Link className="login-close" to={MARKET_PATH(null)} aria-label="로그인을 닫고 시장으로 가기">
          <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true">
            <path d="M5.5 5.5l9 9m0-9l-9 9" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" fill="none" />
          </svg>
        </Link>
      ))}
      <div className="login-brand">
        <BrandLogo />
      </div>
      <header className="login-header">
        <h1 id="login-title">{guest && method === "choose" ? "가입하고 이어가기" : TITLES[method]}</h1>
        {DESCRIPTIONS[method] && <p>{DESCRIPTIONS[method]}</p>}
      </header>
      {!ready ? <p className="login-status" role="status">로그인 상태를 확인하고 있어요.</p> : user ? (
        <div className="login-options">
          <p className="login-status" role="status">{busy ? "계정을 연결하고 있어요…" : mobileReturnUri ? "이 계정으로 앱에 로그인할 수 있습니다." : "로그인은 완료됐습니다. 계정 연결을 다시 시도해 주세요."}</p>
          {(error || mobileReturnUri) && <button type="button" className="login-button is-primary" onClick={() => void finishSession()} disabled={busy}>{error ? "다시 연결하기" : "앱 로그인 완료하기"}</button>}
        </div>
      ) : (
        <>
          {guest && (
            <p className="login-guest-note" role="note">
              지금은 <strong>체험 중</strong>이에요. 여기서 가입하면 체험하면서 쌓은 자산과 기록이 그대로 이어져요.
              이미 가입한 계정으로 로그인하면 체험 기록은 이어지지 않아요.
            </p>
          )}
          {(method === "choose" || method === "google") && (
            <div className="login-options">
              <button type="button" className="login-button is-google" onClick={() => void googleLogin()} disabled={busy}>
                <span className="login-google-badge" aria-hidden="true"><GoogleIcon /></span>
                {nativeWaiting ? "브라우저에서 로그인 중…" : busy ? "로그인 중…" : guest ? "Google로 가입하고 이어가기" : "Google로 로그인하기"}
              </button>
              {method === "choose" && methodLink(guest ? "register" : "email", guest ? "이메일로 가입하고 이어가기" : "이메일·비밀번호로 로그인", "login-button")}
              {method === "choose" && !guest && !mobileReturnUri && guestModeAvailable() && (
                <button type="button" className="login-button is-guest" onClick={() => void startGuest()} disabled={busy}>
                  로그인 없이 바로 체험하기
                </button>
              )}
            </div>
          )}
          {isEmailForm && (
            <form className="login-form" onSubmit={(event) => { event.preventDefault(); if (!busy) void submitEmail(); }}>
              <div className="login-field">
                <label htmlFor="login-email">이메일</label>
                <input id="login-email" type="email" inputMode="email" autoComplete="email" autoCapitalize="none" spellCheck={false} required value={email} onChange={(event) => setEmail(event.target.value)} disabled={busy} />
              </div>
              {method !== "reset" && (
                <>
                  <div className="login-field">
                    <label htmlFor="login-password">비밀번호</label>
                    <div className="login-password-field">
                      <input id="login-password" type={showPassword ? "text" : "password"} autoComplete={method === "register" ? "new-password" : "current-password"} required value={password} onChange={(event) => setPassword(event.target.value)} disabled={busy} />
                      <button type="button" className="login-password-toggle" aria-pressed={showPassword} onClick={() => setShowPassword((show) => !show)}>{showPassword ? "숨기기" : "표시"}</button>
                    </div>
                  </div>
                  {method === "register" && <div className="login-field"><label htmlFor="login-password-confirm">비밀번호 확인</label><input id="login-password-confirm" type={showPassword ? "text" : "password"} autoComplete="new-password" required value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} disabled={busy} /></div>}
                </>
              )}
              {method === "register" && (
                <label className="login-consent">
                  <input type="checkbox" required checked={agreed} onChange={(event) => setAgreed(event.target.checked)} disabled={busy} />
                  <span>만 14세 이상이며, <LegalLinks />에 동의합니다. (필수)</span>
                </label>
              )}
              <button type="submit" className="login-button is-primary" disabled={busy}>{busy ? "처리 중…" : method === "register" ? "회원가입" : method === "reset" ? "재설정 메일 받기" : "로그인"}</button>
            </form>
          )}
          {(method === "choose" || method === "google") && (
            <p className="login-legal">처음 로그인하면 계정이 만들어져요. 계속하면 만 14세 이상이며 <LegalLinks />에 동의한 것으로 봐요.</p>
          )}
          {method === "choose" && !guest && !mobileReturnUri && guestModeAvailable() && (
            <p className="login-legal">체험 계정은 랭킹에 오르지 않고, 글쓰기·채팅은 가입한 뒤에 쓸 수 있어요. 체험하면서 쌓은 기록은 가입할 때 그대로 이어져요.</p>
          )}
          {method === "email" && <nav className="login-links" aria-label="이메일 계정 도움말">{methodLink("register", "이메일로 회원가입")}{methodLink("reset", "비밀번호 찾기")}</nav>}
          {(method === "register" || method === "reset") && methodLink("email", "이메일 로그인으로 돌아가기", "login-back")}
          {method !== "choose" && methodLink("choose", "다른 로그인 방법 선택", "login-back")}
          {nativeWaiting && <button type="button" className="login-back" onClick={() => { setBusy(false); setNativeWaiting(false); }}>다시 시도하기</button>}
        </>
      )}
      {mobileRequestRejected && <p className="login-message is-error" role="alert">앱 로그인 요청을 확인하지 못했어요. 앱을 최신 버전으로 업데이트한 뒤 다시 로그인해 주세요.</p>}
      {error && <p className="login-message is-error" role="alert">{error}</p>}
      {notice && <p className="login-message" role="status">{notice}</p>}
    </div>
  );
}
