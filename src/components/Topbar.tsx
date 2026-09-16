import { BrandMark } from "./BrandMark";
import { Link } from "./Link";
import type { Route } from "../router";
import { useMarket } from "../market/MarketProvider";
import { clock } from "../market/format";
import { firebaseAuth, googleProvider } from "../lib/firebase";
import { onAuthStateChanged, signInWithPopup, signOut, type User } from "firebase/auth";
import { apiFetch } from "../lib/api";
import { useEffect, useRef, useState } from "react";

const NAV = [
  { label: "홈", to: "/", match: "home" },
  { label: "시장", to: "/market", match: "market" },
  { label: "속보", to: "/news", match: "news" },
] as const;

type RankingEntry = {
  rank: number;
  nickname: string;
  totalAsset: number;
  changePercent: number;
};

type TemperatureReading = {
  available: boolean;
  temperature?: number | null;
  location?: string;
  measuredAt?: string | null;
  message?: string;
};

export function Topbar({ route, account }: { route: Route; account: string }) {
  const snapshot = useMarket();
  const [user, setUser] = useState<User | null>(firebaseAuth.currentUser);
  const [busy, setBusy] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [rankingOpen, setRankingOpen] = useState(false);
  const [ranking, setRanking] = useState<RankingEntry[]>([]);
  const [rankingLoading, setRankingLoading] = useState(false);
  const [temperature, setTemperature] = useState<TemperatureReading | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => onAuthStateChanged(firebaseAuth, setUser), []);

  const login = async () => {
    setBusy(true);
    try {
      await signInWithPopup(firebaseAuth, googleProvider);
    } finally {
      setBusy(false);
    }
  };

  const loadTemperature = async () => {
    try {
      setTemperature(await apiFetch<TemperatureReading>("/api/han-river-temperature"));
    } catch {
      setTemperature({ available: false, message: "한강 수온을 불러오지 못했습니다." });
    }
  };

  const openRanking = async () => {
    setMenuOpen(false);
    setRankingOpen(true);
    setRankingLoading(true);
    try {
      setRanking(await apiFetch<RankingEntry[]>("/api/ranking"));
    } catch {
      setRanking([]);
    } finally {
      setRankingLoading(false);
    }
  };

  useEffect(() => {
    if (!menuOpen) return;
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
          {/* 갱신 주기와 모의 시세 고지는 상태 막대가 담당한다. 여기서는 시각과 계좌만. */}
          <span className="topbar-clock num">{clock(snapshot.updatedAt)}</span>
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
                    <div className="menu-user">{user.displayName ?? user.email ?? account}</div>
                    <button type="button" className="menu-item" role="menuitem" onClick={() => void signOut(firebaseAuth).then(() => setMenuOpen(false))}>
                      로그아웃
                    </button>
                  </>
                ) : (
                  <button type="button" className="menu-item menu-login" role="menuitem" onClick={() => void login().then(() => setMenuOpen(false))} disabled={busy}>
                    {busy ? "로그인 중…" : "Google 로그인"}
                  </button>
                )}
                <button type="button" className="menu-item" role="menuitem" onClick={() => void openRanking()}>
                  랭킹
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
      {rankingOpen && (
        <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setRankingOpen(false); }}>
          <section className="ranking-dialog" role="dialog" aria-modal="true" aria-labelledby="ranking-title">
            <div className="dialog-heading">
              <h2 id="ranking-title">투자 랭킹</h2>
              <button type="button" className="dialog-close" onClick={() => setRankingOpen(false)} aria-label="랭킹 닫기">×</button>
            </div>
            {rankingLoading ? <p className="dialog-empty">랭킹을 불러오는 중…</p> : ranking.length === 0 ? <p className="dialog-empty">아직 랭킹에 참여한 사용자가 없습니다.</p> : (
              <ol className="ranking-list">
                {ranking.map((entry) => (
                  <li key={`${entry.rank}-${entry.nickname}`}>
                    <span className="ranking-rank num">{entry.rank}</span>
                    <span className="ranking-name">{entry.nickname}</span>
                    <span className={`ranking-change num${entry.changePercent < 0 ? " is-down" : ""}`}>{entry.changePercent >= 0 ? "+" : ""}{entry.changePercent.toFixed(2)}%</span>
                    <span className="ranking-asset num">{Math.round(entry.totalAsset).toLocaleString("ko-KR")}원</span>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </div>
      )}
    </header>
  );
}
