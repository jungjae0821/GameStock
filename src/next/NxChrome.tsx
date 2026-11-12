import { useEffect } from "react";
import { BrandLogo, BrandMark } from "../components/BrandMark";
import { Link } from "../components/Link";
import { ProfileMenu } from "../components/ProfileMenu";
import { API_DOCS_PATH, HEATMAP_PATH, type Route } from "../router";
import { toggleNxTheme, useNxTheme } from "./uiMode";
import { AnimatedNav } from "./AnimatedNav";

/* 모든 화면이 함께 쓰는 위쪽 메뉴, 아래쪽 탭 막대, 루트 테마 연결. */
const NAV = [
  { label: "홈", to: "/", match: "home" },
  { label: "거래", to: "/market", match: "market" },
  { label: "히트맵", to: HEATMAP_PATH, match: "heatmap" },
  { label: "탭 베팅", to: "/tap", match: "tap" },
  { label: "뉴스", to: "/news", match: "news" },
  { label: "랭킹", to: "/ranking", match: "ranking" },
  { label: "토론", to: "/talk", match: "talk" },
  { label: "퀀트", to: "/quant", match: "quant" },
  { label: "API", to: API_DOCS_PATH, match: "apiDocs" },
] as const;

/* 휴대폰 아래 탭바는 칸이 좁아 자주 안 쓰는 화면을 뺀다. 빠진 화면은 계정 메뉴(ProfileMenu)에서 연다. */
const TABBAR_NAV = NAV.filter((item) => item.match !== "quant" && item.match !== "apiDocs" && item.match !== "tap");

/** 테마와 새 화면 표시는 html에 단다. 포털로 뜨는 팝오버도 같은 색을 받아야 한다. */
export function useNxRoot(): void {
  const theme = useNxTheme();
  useEffect(() => {
    const root = document.documentElement;
    root.dataset.nx = "on";
    root.dataset.nxTheme = theme;
    root.style.colorScheme = theme;
    return () => {
      delete root.dataset.nx;
      delete root.dataset.nxTheme;
      root.style.colorScheme = "light";
    };
  }, [theme]);
}

function navActive(route: Route, match: (typeof NAV)[number]["match"]): boolean {
  return route.name === match || (match === "market" && (route.name === "trade" || route.name === "etf" || route.name === "derivatives"));
}

export function NxTopbar({ route }: { route: Route }) {
  const theme = useNxTheme();
  return (
    <header className="nx-top">
      <Link className="nx-brand" to="/" aria-label="슈엔증권 홈">
        <BrandMark size={26} />
        <BrandLogo />
      </Link>
      <nav className="nx-nav" aria-label="주요 화면">
        {NAV.map((item) => {
          const active = navActive(route, item.match);
          return (
            <Link key={item.to} to={item.to} className={`nx-nav-link${active ? " is-active" : ""}`} aria-current={active ? "page" : undefined}>
              {item.label}
            </Link>
          );
        })}
      </nav>
      <button type="button" className="nx-icon-button" onClick={toggleNxTheme} aria-label={theme === "dark" ? "밝은 화면으로 바꾸기" : "어두운 화면으로 바꾸기"}>
        {theme === "dark" ? (
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4.5" /><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8" /></svg>
        ) : (
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z" /></svg>
        )}
      </button>
      <ProfileMenu route={route} />
    </header>
  );
}

/**
 * 휴대폰 브라우저의 아래 탭바. 본문 뒤에 두어 Tab 이동도 보이는 순서(위 → 본문 → 아래)를 따른다.
 * 다른 화면에서는 CSS로 숨기고, 그때는 상단 바의 메뉴가 같은 일을 한다.
 */
export function NxTabbar({ route }: { route: Route }) {
  return (
    <AnimatedNav animationId="main-bottom" activeKey={TABBAR_NAV.find(item => navActive(route, item.match))?.match ?? ""} className="nx-tabbar" aria-label="주요 화면" inset={.28}>
      {TABBAR_NAV.map((item) => {
        const active = navActive(route, item.match);
        return (
          <Link key={item.to} to={item.to} className={`nx-tabbar-link${active ? " is-active" : ""}`} aria-current={active ? "page" : undefined}>
            {item.label}
          </Link>
        );
      })}
    </AnimatedNav>
  );
}
