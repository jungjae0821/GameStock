import { BrandMark } from "./BrandMark";
import { Link } from "./Link";
import type { Route } from "../router";
import { clock } from "../market/format";
import { firebaseAuth } from "../lib/firebase";
import { onAuthStateChanged, signInWithCustomToken, signOut, type User } from "firebase/auth";
import { requestLogin, requireSignIn } from "../lib/auth";
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

function readTheme(): Theme {
  if (typeof window === "undefined") return "light";
  return window.localStorage.getItem(THEME_STORAGE_KEY) === "dark" ? "dark" : "light";
}

export function Topbar({ route }: { route: Route }) {
  const [user, setUser] = useState<User | null>(firebaseAuth.currentUser);
  const [theme, setTheme] = useState<Theme>(readTheme);
  const [now, setNow] = useState(() => Date.now());
  const [menuOpen, setMenuOpen] = useState(false);
  const [nickname, setNickname] = useState<string | null>(null);
  const [temperature, setTemperature] = useState<TemperatureReading | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  const loadProfile = async () => {
    if (!firebaseAuth.currentUser) return;
    try {
      const profile = await apiFetch<AccountProfile>("/api/profile");
      setNickname(profile.nickname?.trim() || null);
    } catch {
      // 로그인 직후에는 백엔드 계정 생성보다 메뉴 렌더가 먼저 될 수 있다.
      // 메뉴를 다시 열 때 재시도하므로 인증 계정의 표시명으로 대체하지 않는다.
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
      if (detail.type === "GOOGLE_AUTH_ERROR") return;

      void signInWithCustomToken(firebaseAuth, detail.customToken)
        .catch(() => window.dispatchEvent(new CustomEvent("gamestock-login-error", { detail: "앱 로그인을 완료하지 못했습니다. 다시 시도해 주세요." })));
    };

    window.addEventListener("gamestock-native-auth", handleNativeAuth);
    return () => window.removeEventListener("gamestock-native-auth", handleNativeAuth);
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    const handleLoginRequest = () => {
      if (!firebaseAuth.currentUser) requestLogin();
    };
    window.addEventListener("gamestock-request-login", handleLoginRequest);
    return () => window.removeEventListener("gamestock-request-login", handleLoginRequest);
  }, []);

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
        <Link
          className="brand"
          to="/"
          onClick={() => window.scrollTo({ top: 0, left: 0, behavior: "auto" })}
        >
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
          <span className="topbar-clock num" aria-label={`현재시각: ${clock(now)}`}><span className="topbar-clock-label">현재시각: </span>{clock(now)}</span>
          <div className="menu-wrap" ref={menuRef}>
            <button
              type="button"
              className="account-button menu-button"
              aria-expanded={menuOpen}
              aria-haspopup="menu"
              onClick={() => setMenuOpen((open) => !open)}
            >
              <span>메뉴</span>
              <svg className="menu-chevron" viewBox="0 0 12 12" aria-hidden="true" focusable="false">
                <path d="M3 4.5 6 7.5 9 4.5" />
              </svg>
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
                  <button type="button" className="menu-item menu-login" role="menuitem" onClick={() => { setMenuOpen(false); requestLogin(); }}>
                    로그인
                  </button>
                )}
                <button type="button" className="menu-item" role="menuitem" onClick={() => { setMenuOpen(false); if (requireSignIn("/mypage")) navigate("/mypage"); }}>
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
