import { BrandMark } from "./BrandMark";
import { Link } from "./Link";
import type { Route } from "../router";
import { useMarket } from "../market/MarketProvider";
import { clock } from "../market/format";
import { firebaseAuth, googleProvider } from "../lib/firebase";
import { onAuthStateChanged, signInWithPopup, signOut, type User } from "firebase/auth";
import { useEffect, useState } from "react";

const NAV = [
  { label: "홈", to: "/", match: "home" },
  { label: "시장", to: "/market", match: "market" },
  { label: "속보", to: "/news", match: "news" },
] as const;

export function Topbar({ route, account }: { route: Route; account: string }) {
  const snapshot = useMarket();
  const [user, setUser] = useState<User | null>(firebaseAuth.currentUser);
  const [busy, setBusy] = useState(false);

  useEffect(() => onAuthStateChanged(firebaseAuth, setUser), []);

  const login = async () => {
    setBusy(true);
    try {
      await signInWithPopup(firebaseAuth, googleProvider);
    } finally {
      setBusy(false);
    }
  };

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
          {user ? (
            <button type="button" className="account-button" onClick={() => void signOut(firebaseAuth)}>
              {user.displayName ?? user.email ?? account} · 로그아웃
            </button>
          ) : (
            <button type="button" className="account-button" onClick={() => void login()} disabled={busy}>
              {busy ? "로그인 중…" : "Google 로그인"}
            </button>
          )}
        </div>
      </div>
    </header>
  );
}
