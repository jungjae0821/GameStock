import { BrandMark } from "./BrandMark";
import { Link } from "./Link";
import type { Route } from "../router";

const NAV = [
  { label: "홈", to: "/", match: "home" },
  { label: "시장", to: "/market", match: "market" },
  { label: "속보", to: "/news", match: "news" },
  { label: "랭킹", to: "/ranking", match: "ranking" },
] as const;

export function Topbar({ route }: { route: Route }) {
  return (
    <header className="topbar">
      <div className="topbar-inner">
        <Link className="brand" to="/" aria-label="씹덕주식 홈">
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
        <Link
          className={`nav-link nav-me${route.name === "mypage" ? " is-active" : ""}`}
          to="/mypage"
          aria-current={route.name === "mypage" ? "page" : undefined}
        >
          내 계좌
        </Link>
      </div>
    </header>
  );
}
