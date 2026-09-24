import { BrandMark } from "./BrandMark";
import { Link } from "./Link";
import type { Route } from "../router";
import { clock } from "../market/format";
import { firebaseAuth, googleProvider } from "../lib/firebase";
import { onAuthStateChanged, signInWithCustomToken, signInWithPopup, signOut, type User } from "firebase/auth";
import { apiFetch } from "../lib/api";
import { useEffect, useRef, useState } from "react";
import { navigate } from "../router";

const NAV = [
  { label: "홈", to: "/", match: "home" },
  { label: "시장", to: "/market", match: "market" },
  { label: "뉴스", to: "/news", match: "news" },
] as const;

type TemperatureReading = {
  available: boolean;
  temperature?: number | null;
  location?: string;
  measuredAt?: string | null;
  message?: string;
};

type AccountProfile = {
  nickname?: string | null;
};

type NativeAuthEvent =
  | { type: "FIREBASE_CUSTOM_TOKEN"; customToken: string }
  | { type: "GOOGLE_AUTH_ERROR"; message?: string };

declare global {
  interface Window {
    ReactNativeWebView?: {
      postMessage(message: string): void;
    };
  }
}

const THEME_STORAGE_KEY = "gamestock-theme";
type Theme = "light" | "dark";

function mobileReturnUriFromLocation(): string | null {
  if (typeof window === "undefined") return null;
  const params = new URLSearchParams(window.location.search);
  if (params.get("mobileAuth") !== "1") return null;
  const returnUri = params.get("returnUri");
  return returnUri && /^(gamestock|exp):\/\//.test(returnUri) ? returnUri : null;
}

function readTheme(): Theme {
  if (typeof window === "undefined") return "light";
  return window.localStorage.getItem(THEME_STORAGE_KEY) === "dark" ? "dark" : "light";
}

export function Topbar({ route }: { route: Route }) {
  const [user, setUser] = useState<User | null>(firebaseAuth.currentUser);
  const [theme, setTheme] = useState<Theme>(readTheme);
  const [now, setNow] = useState(() => Date.now());
  const [busy, setBusy] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [nickname, setNickname] = useState<string | null>(null);
  const [temperature, setTemperature] = useState<TemperatureReading | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const mobileReturnUri = mobileReturnUriFromLocation();
  const mobileAuthState = typeof window === "undefined" ? null : new URLSearchParams(window.location.search).get("state");

  const loadProfile = async () => {
    if (!firebaseAuth.currentUser) return;
    try {
      const profile = await apiFetch<AccountProfile>("/api/profile");
      setNickname(profile.nickname?.trim() || null);
    } catch {
      // 로그인 직후에는 백엔드 계정 생성보다 메뉴 렌더가 먼저 될 수 있다.
      // 메뉴를 다시 열 때 재시도하므로 Google 표시명으로 대체하지 않는다.
    }
  };

  useEffect(() => onAuthStateChanged(firebaseAuth, (current) => {
    setUser(current);
    setNickname(null);
    if (current) void loadProfile();
  }), []);

  useEffect(() => {
    const handleNativeAuth = (event: Event) => {
      const detail = (event as CustomEvent<NativeAuthEvent>).detail;
      if (!detail) return;
      if (detail.type === "GOOGLE_AUTH_ERROR") {
        setBusy(false);
        return;
      }

      void signInWithCustomToken(firebaseAuth, detail.customToken)
        .catch(() => undefined)
        .finally(() => setBusy(false));
    };

    window.addEventListener("gamestock-native-auth", handleNativeAuth);
    return () => window.removeEventListener("gamestock-native-auth", handleNativeAuth);
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const completeMobileHandoff = async () => {
    if (!mobileReturnUri || !mobileAuthState || !firebaseAuth.currentUser) return;
    const result = await apiFetch<{ code: string }>("/api/auth/mobile/issue", {
      method: "POST",
      body: JSON.stringify({ state: mobileAuthState }),
    });
    const callback = new URL(mobileReturnUri);
    callback.searchParams.set("code", result.code);
    callback.searchParams.set("state", mobileAuthState);
    window.location.replace(callback.toString());
  };

  const login = async () => {
    setBusy(true);
    try {
      if (window.ReactNativeWebView) {
        window.ReactNativeWebView.postMessage(JSON.stringify({ type: "GOOGLE_LOGIN" }));
        return;
      }
      await signInWithPopup(firebaseAuth, googleProvider);
      await completeMobileHandoff();
    } finally {
      if (!window.ReactNativeWebView) setBusy(false);
    }
  };

  const loadTemperature = async () => {
    try {
      setTemperature(await apiFetch<TemperatureReading>("/api/han-river-temperature"));
    } catch {
      setTemperature({ available: false, message: "한강 수온을 불러오지 못했습니다." });
    }
  };

  const openRanking = () => {
    setMenuOpen(false);
    navigate("/ranking");
  };

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = theme;
    window.localStorage.setItem(THEME_STORAGE_KEY, theme);
  }, [theme]);

  useEffect(() => {
    if (!menuOpen) return;
    if (user) void loadProfile();
    void loadTemperature();
    const closeOnOutside = (event: MouseEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) setMenuOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setMenuOpen(false);
    };
    document.addEventListener("mousedown", closeOnOutside);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("mousedown", closeOnOutside);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [menuOpen]);

  return (
    <header className="topbar">
      <div className="topbar-inner">
        <Link className="brand" to="/">
          <BrandMark />
          <span className="brand-text">씹덕주식</span>
        </Link>
        <nav className="nav" aria-label="주요 화면">
          {NAV.map((item) => (
            <Link
              key={item.to}
              to={item.to}
              className={`nav-link${route.name === item.match ? " is-active" : ""}`}
              aria-current={route.name === item.match ? "page" : undefined}
            >
              {item.label}
            </Link>
          ))}
        </nav>
        <div className="topbar-end">
          {/* 상단에는 현재 시각과 메뉴만 표시한다. */}
          <span className="topbar-clock num">현재시각: {clock(now)}</span>
          <div className="menu-wrap" ref={menuRef}>
            <button
              type="button"
              className="account-button menu-button"
              aria-expanded={menuOpen}
              aria-haspopup="menu"
              onClick={() => setMenuOpen((open) => !open)}
            >
              메뉴 <span aria-hidden="true">⌄</span>
            </button>
            {menuOpen && (
              <div className="menu-panel" role="menu" aria-label="부가 기능">
                {user ? (
                  <>
                    <div className="menu-user">{nickname || "내 계정"}</div>
                    <button type="button" className="menu-item" role="menuitem" onClick={() => void signOut(firebaseAuth).then(() => setMenuOpen(false))}>
                      로그아웃
                    </button>
                  </>
                ) : (
                  <button type="button" className="menu-item menu-login" role="menuitem" onClick={() => void login().then(() => setMenuOpen(false))} disabled={busy}>
                    {busy ? "로그인 중…" : "Google 로그인"}
                  </button>
                )}
                <button type="button" className="menu-item" role="menuitem" onClick={() => { setMenuOpen(false); navigate("/mypage"); }}>
                  마이페이지
                </button>
                <button type="button" className="menu-item" role="menuitem" onClick={openRanking}>
                  랭킹
                </button>
                <button
                  type="button"
                  className="menu-item menu-theme-toggle"
                  role="menuitem"
                  aria-pressed={theme === "dark"}
                  onClick={() => setTheme((current) => current === "dark" ? "light" : "dark")}
                >
                  {theme === "dark" ? "라이트모드" : "다크모드"}
                </button>
                <div className="menu-temperature" role="status" aria-live="polite">
                  <span>한강물 온도 · 선유</span>
                  <strong>{temperature?.available && temperature.temperature != null ? `${temperature.temperature.toFixed(1)}℃` : "조회 중…"}</strong>
                  <small>{temperature?.measuredAt ?? temperature?.message ?? "30분마다 갱신"}</small>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </header>
  );
}
