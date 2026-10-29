import { AuthBridge } from "./components/AuthBridge";
import { useEffect, useLayoutEffect, useRef } from "react";
import { MissionRewardToast } from "./components/MissionRewardToast";
import { TitleWatcher } from "./components/TitleWatcher";
import { LoginModal } from "./components/LoginModal";
import { CircuitBreakerNotice } from "./components/CircuitBreakerNotice";
import { SiteNoticeDialog } from "./components/SiteNoticeDialog";
import { SuspensionNotice, useSuspendedUntil } from "./components/SuspensionNotice";
import { useProfile } from "./lib/profile";
import { openLoginPrompt, useAuthUser } from "./lib/auth";
import { LISTING_BY_CODE } from "./market/universe";
import { ETF_PATH, MARKET_PATH, PRIVATE_ROUTES, QUANT_PATH, navigate, useRoute } from "./router";
import { Link } from "./components/Link";
import { NxTabbar, NxTopbar, TradeTerminal, useNxRoot } from "./next/TradeTerminal";
import { HomeBoard } from "./next/HomeBoard";
import { MarketBoard } from "./next/MarketBoard";
import { HeatmapBoard } from "./next/HeatmapBoard";
import { NxAdminPage } from "./next/pages/NxAdminPage";
import { NxChargePage } from "./next/pages/NxChargePage";
import { NxLoanPage } from "./next/pages/NxLoanPage";
import { NxBillboardPage } from "./next/pages/NxBillboardPage";
import { NxLoginPage } from "./next/pages/NxLoginPage";
import { NxMyPage } from "./next/pages/NxMyPage";
import { NxNewsPage } from "./next/pages/NxNewsPage";
import { NxRankingPage } from "./next/pages/NxRankingPage";
import { NxApiDocsPage } from "./next/pages/NxApiDocsPage";
import { NxSettingsPage } from "./next/pages/NxSettingsPage";
import { NxTitlesPage } from "./next/pages/NxTitlesPage";
import { NxAttendancePage } from "./next/pages/NxAttendancePage";
import { NxQuantPage } from "./next/pages/NxQuantPage";
import { DerivativesPage } from "./pages/DerivativesPage";
import type { Route } from "./router";

function titleFor(route: Route): string {
  if (route.name === "market") {
    const name = route.ticker ? LISTING_BY_CODE[route.ticker]?.name : undefined;
    return name ? `${name} · 거래 · 슈엔증권` : "주식 · 거래 · 슈엔증권";
  }
  if (route.name === "trade") {
    const name = route.ticker ? LISTING_BY_CODE[route.ticker]?.name : undefined;
    return name ? `${name} · 거래 · 슈엔증권` : "거래 · 슈엔증권";
  }
  if (route.name === "derivatives") {
    const label = route.tab === "futures" ? "선물" : "옵션";
    return `${label} · 거래 · 슈엔증권`;
  }
  if (route.name === "etf") return `${route.ticker ? `${route.ticker} · ` : ""}ETF · 주식 · 슈엔증권`;
  if (route.name === "heatmap") return "히트맵 · 슈엔증권";
  if (route.name === "news") return "뉴스 · 슈엔증권";
  if (route.name === "ranking") return "투자 랭킹 · 슈엔증권";
  if (route.name === "apiDocs") return "OpenAPI · 슈엔증권";
  if (route.name === "mypage") return "내 계좌 · 슈엔증권";
  if (route.name === "settings") return "설정 · 슈엔증권";
  if (route.name === "titles") return "칭호 설정 · 슈엔증권";
  if (route.name === "attendance") return "출석체크 · 슈엔증권";
  if (route.name === "quant") return "퀀트 전략 · 슈엔증권";
  if (route.name === "charge") return "충전하기 · 슈엔증권";
  if (route.name === "loan") return "대출 · 슈엔증권";
  if (route.name === "billboard") return "전광판 · 슈엔증권";
  if (route.name === "admin") return "관리자 · 슈엔증권";
  if (route.name === "login") return "로그인 · 슈엔증권";
  if (route.name === "notFound") return "없는 화면 · 슈엔증권";
  return "슈엔증권";
}

/** 로그인 뒤 돌아올 주소. 퀀트는 열어 둔 전략까지 기억한다. */
const loginReturnPath = (route: Route) => (route.name === "quant" ? QUANT_PATH(route.id) : `/${route.name}`);

export default function App() {
  const route = useRoute();
  const auth = useAuthUser();
  const profile = useProfile();
  const suspendedUntil = useSuspendedUntil(auth.user ? profile : null);
  /* 종목 상세와 거래는 하나의 터미널로 합친다(해외 거래소처럼). */
  const terminal = route.name === "trade" || (route.name === "market" && route.ticker !== null);
  useNxRoot();
  const main = useRef<HTMLElement>(null);
  const previous = useRef(route);

  useEffect(() => {
    if (route.name === "etf" && (!route.ticker || window.location.pathname.startsWith("/derivatives"))) {
      navigate(`${route.ticker ? ETF_PATH(route.ticker) : MARKET_PATH(null)}${window.location.search}`, { replace: true });
    }
  }, [route]);

  useEffect(() => {
    if (PRIVATE_ROUTES.has(route.name) && auth.ready && !auth.user) openLoginPrompt(loginReturnPath(route));
  }, [route, auth.ready, auth.user]);

  useLayoutEffect(() => {
    document.title = titleFor(route);
    /* 최초 렌더에서는 포커스를 옮기지 않는다. 이동할 때만 본문으로 보낸다. */
    if (previous.current === route) return;
    const withinNews = previous.current.name === "news" && route.name === "news";
    previous.current = route;
    /* 뉴스 목록에서 기사를 고를 때는 목록 스크롤과 포커스를 그대로 둔다. */
    if (withinNews) return;
    if (document.documentElement.dataset.appShell !== "mobile") {
      main.current?.focus({ preventScroll: true });
    }
    window.scrollTo({ top: 0, left: 0, behavior: "instant" });
  }, [route]);

  return (
    <div className="app">
      <a className="skip-link" href="#content">
        본문으로 건너뛰기
      </a>
      <AuthBridge />
      <NxTopbar route={route} />
      <main id="content" className={`content nx-content${terminal ? "" : " nx-page"}`} ref={main} tabIndex={-1}>
        {suspendedUntil ? <SuspensionNotice until={suspendedUntil} reason={profile?.tradeBanReason} /> : <>
        {route.name === "home" && <HomeBoard />}
        {terminal && <TradeTerminal ticker={route.name === "trade" || route.name === "market" ? route.ticker : null} />}
        {route.name === "market" && !terminal && <MarketBoard />}
        {route.name === "heatmap" && <HeatmapBoard />}
        {route.name === "etf" && (route.ticker ? <DerivativesPage key={`etf:${route.ticker}`} tab="etf" etfTicker={route.ticker} /> : <MarketBoard />)}
        {route.name === "derivatives" && <DerivativesPage key={route.tab} tab={route.tab} />}
        {route.name === "news" && <NxNewsPage id={route.id} />}
        {route.name === "ranking" && <NxRankingPage />}
        {route.name === "apiDocs" && <NxApiDocsPage />}
        {PRIVATE_ROUTES.has(route.name) && !auth.user && (auth.ready ? (
          /* 로그인 창을 닫아도 이 화면에서 다시 열 수 있게 한다. */
          <section className="nx-gate">
            <h1 className="nx-page-title">로그인이 필요한 화면이에요</h1>
            <p className="nx-screen-sub">{titleFor(route).split(" · ")[0]} 화면은 로그인한 뒤에 볼 수 있어요. 로그인 없이도 시세와 뉴스는 볼 수 있어요.</p>
            <div className="nx-gate-actions">
              <button type="button" className="nx-btn is-primary" onClick={() => openLoginPrompt(loginReturnPath(route))}>로그인하기</button>
              <Link className="nx-btn" to={MARKET_PATH(null)}>시장 둘러보기</Link>
            </div>
          </section>
        ) : <p className="nx-empty-state" role="status">로그인 상태를 확인하고 있어요.</p>)}
        {route.name === "notFound" && (
          <section className="nx-gate">
            <h1 className="nx-page-title">없는 화면이에요</h1>
            <p className="nx-screen-sub">주소가 바뀌었거나 잘못 입력됐어요. 홈이나 시장에서 다시 찾아 주세요.</p>
            <div className="nx-gate-actions">
              <Link className="nx-btn is-primary" to="/">홈으로</Link>
              <Link className="nx-btn" to={MARKET_PATH(null)}>시장 보기</Link>
            </div>
          </section>
        )}
        {route.name === "mypage" && auth.user && <NxMyPage />}
        {route.name === "settings" && auth.user && <NxSettingsPage />}
        {route.name === "titles" && auth.user && <NxTitlesPage />}
        {route.name === "attendance" && auth.user && <NxAttendancePage />}
        {route.name === "quant" && auth.user && <NxQuantPage id={route.id} />}
        {route.name === "charge" && auth.user && <NxChargePage />}
        {route.name === "loan" && auth.user && <NxLoanPage />}
        {route.name === "billboard" && auth.user && <NxBillboardPage />}
        {route.name === "admin" && auth.user && <NxAdminPage />}
        {route.name === "login" && <NxLoginPage method={route.method} />}
        </>}
      </main>
      <NxTabbar route={route} />
      <MissionRewardToast />
      <TitleWatcher />
      <LoginModal />
      <SiteNoticeDialog />
      <CircuitBreakerNotice />
    </div>
  );
}
