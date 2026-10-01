import { useCallback, useEffect, useRef, useState } from "react";
import { createUserWithEmailAndPassword, sendPasswordResetEmail, signInWithEmailAndPassword, signInWithPopup, validatePassword } from "firebase/auth";
import { BrandMark } from "../components/BrandMark";
import { Link } from "../components/Link";
import { firebaseAuth, googleProvider } from "../lib/firebase";
import { apiFetch } from "../lib/api";
import { authErrorMessage, loginPath, safeReturnPath, useAuthUser } from "../lib/auth";
import { MARKET_PATH, navigate, type LoginMethod } from "../router";

const TITLES: Record<LoginMethod, string> = {
  choose: "로그인", google: "Google 로그인", email: "이메일 로그인", register: "이메일로 회원가입", reset: "비밀번호 찾기",
};

const DESCRIPTIONS: Record<LoginMethod, string> = {
  choose: "로그인하면 주문·관심종목·투자 미션을 이용할 수 있어요.",
  google: "Google 계정으로 계속 진행합니다.",
  email: "이메일과 비밀번호로 로그인하세요.",
  register: "이메일과 비밀번호로 새 계정을 만듭니다.",
  reset: "가입할 때 사용한 이메일을 입력해 주세요.",
};

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

export function LoginPage({ method }: { method: LoginMethod }) {
  const { user, ready } = useAuthUser();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [nativeWaiting, setNativeWaiting] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const completionStarted = useRef(false);
  const params = new URLSearchParams(window.location.search);
  const next = safeReturnPath(params.get("next"));
  const returnUri = params.get("mobileAuth") === "1" ? params.get("returnUri") : null;
  const mobileReturnUri = returnUri && /^(gamestock|exp):\/\//.test(returnUri) ? returnUri : null;
  const mobileAuthState = params.get("state");

  const finishSession = useCallback(async () => {
    setBusy(true);
    setError("");
    try {
      // Create/load the same trading account for either Firebase provider before returning.
      await apiFetch("/api/auth/login", { method: "POST" });
      if (mobileReturnUri && mobileAuthState) {
        const result = await apiFetch<{ code: string }>("/api/auth/mobile/issue", { method: "POST" });
        const callback = new URL(mobileReturnUri);
        callback.searchParams.set("code", result.code);
        callback.searchParams.set("state", mobileAuthState);
        window.location.replace(callback.toString());
      } else {
        navigate(next, { replace: true });
      }
    } catch (failure) {
      setError(authErrorMessage(failure));
    } finally {
      setBusy(false);
      setNativeWaiting(false);
    }
  }, [next, mobileReturnUri, mobileAuthState]);

  useEffect(() => {
    // Visiting a browser-to-app URL alone must never issue a login exchange code.
    if (!ready || !user || mobileReturnUri || completionStarted.current) return;
    completionStarted.current = true;
    void finishSession();
  }, [ready, user, mobileReturnUri, finishSession]);

  useEffect(() => {
    setPassword("");
    setConfirmPassword("");
    setShowPassword(false);
    setError("");
    setNotice("");
    setNativeWaiting(false);
    if (!firebaseAuth.currentUser) setBusy(false);
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

  const googleLogin = async () => {
    setBusy(true);
    setError("");
    if (window.ReactNativeWebView) {
      setNativeWaiting(true);
      window.ReactNativeWebView.postMessage(JSON.stringify({ type: "GOOGLE_LOGIN", next }));
      return;
    }
    try {
      await signInWithPopup(firebaseAuth, googleProvider);
    } catch (failure) {
      setError(authErrorMessage(failure));
    } finally {
      if (!firebaseAuth.currentUser || mobileReturnUri) setBusy(false);
    }
  };

  const submitEmail = async () => {
    setError("");
    setNotice("");
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
        const validation = await validatePassword(firebaseAuth, password);
        if (!validation.isValid) {
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
        await createUserWithEmailAndPassword(firebaseAuth, email.trim(), password);
      } else {
        await signInWithEmailAndPassword(firebaseAuth, email.trim(), password);
      }
    } catch (failure) {
      setError(authErrorMessage(failure));
    } finally {
      if (method === "reset" || !firebaseAuth.currentUser || mobileReturnUri) setBusy(false);
    }
  };

  const isEmailForm = method === "email" || method === "register" || method === "reset";
  return (
    <section className="login-page" aria-labelledby="login-title">
      <div className="login-backdrop" aria-hidden="true">
        <div className="login-backdrop-dim" />
      </div>
      <div className="login-panel">
        {!mobileReturnUri && (
          <Link className="login-close" to={MARKET_PATH(null)} aria-label="로그인을 닫고 시장으로 가기">
            <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true">
              <path d="M5.5 5.5l9 9m0-9l-9 9" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" fill="none" />
            </svg>
          </Link>
        )}
        <div className="login-brand">
          <BrandMark size={26} />
          <span>씹덕주식</span>
        </div>
        <header className="login-header">
          <h1 id="login-title">{TITLES[method]}</h1>
          <p>{DESCRIPTIONS[method]}</p>
        </header>
        {!ready ? <p className="login-status" role="status">로그인 상태를 확인하고 있어요.</p> : user ? (
          <div className="login-options">
            <p className="login-status" role="status">{busy ? "계정을 연결하고 있어요…" : mobileReturnUri ? "이 계정으로 앱에 로그인할 수 있습니다." : "로그인은 완료됐습니다. 계정 연결을 다시 시도해 주세요."}</p>
            {(error || mobileReturnUri) && <button type="button" className="login-button is-primary" onClick={() => void finishSession()} disabled={busy}>{error ? "다시 연결하기" : "앱 로그인 완료하기"}</button>}
          </div>
        ) : (
          <>
            {(method === "choose" || method === "google") && (
              <div className="login-options">
                <button type="button" className="login-button is-google" onClick={() => void googleLogin()} disabled={busy}>
                  <span className="login-google-badge" aria-hidden="true"><GoogleIcon /></span>
                  {nativeWaiting ? "브라우저에서 로그인 중…" : busy ? "로그인 중…" : "Google로 계속하기"}
                </button>
                {method === "choose" && <Link className="login-button" to={loginPath("email", next)}>이메일·비밀번호로 로그인</Link>}
                {method === "choose" && !mobileReturnUri && <Link className="login-later" to={MARKET_PATH(null)}>나중에</Link>}
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
                <button type="submit" className="login-button is-primary" disabled={busy}>{busy ? "처리 중…" : method === "register" ? "회원가입" : method === "reset" ? "재설정 메일 받기" : "로그인"}</button>
              </form>
            )}
            {method === "email" && <nav className="login-links" aria-label="이메일 계정 도움말"><Link to={loginPath("register", next)}>이메일로 회원가입</Link><Link to={loginPath("reset", next)}>비밀번호 찾기</Link></nav>}
            {(method === "register" || method === "reset") && <Link className="login-back" to={loginPath("email", next)}>이메일 로그인으로 돌아가기</Link>}
            {method !== "choose" && <Link className="login-back" to={loginPath("choose", next)}>다른 로그인 방법 선택</Link>}
            {nativeWaiting && <button type="button" className="login-back" onClick={() => { setBusy(false); setNativeWaiting(false); }}>다시 시도하기</button>}
          </>
        )}
        {error && <p className="login-message is-error" role="alert">{error}</p>}
        {notice && <p className="login-message" role="status">{notice}</p>}
        <p className="login-footer">시세와 뉴스는 로그인 없이도 볼 수 있어요.</p>
      </div>
    </section>
  );
}
