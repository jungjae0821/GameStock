import { BrandMark } from "./BrandMark";
import { Link } from "./Link";
import { ProfileMenu } from "./ProfileMenu";
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
        <Link className="brand" to="/" aria-label="슈엔증권 홈">
          <BrandMark />
          <span className="brand-text">슈엔증권</span>
        </Link>
        <nav className="nav" aria-label="주요 화면">
          {NAV.map((item) => {
            const isActive = route.name === item.match || (item.match === "market" && route.name === "trade");
            return (
              <Link
                key={item.to}
                to={item.to}
                className={`nav-link${isActive ? " is-active" : ""}`}
                aria-current={isActive ? "page" : undefined}
              >
                {item.label}
              </Link>
            );
          })}
        </nav>
        <ProfileMenu route={route} />
      </div>
    </header>
  );
}
