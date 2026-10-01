import { AuthBridge } from "./components/AuthBridge";
import { useEffect, useRef } from "react";
import { Topbar } from "./components/Topbar";
import { HomePage } from "./pages/HomePage";
import { MarketPage } from "./pages/MarketPage";
import { NewsPage } from "./pages/NewsPage";
import { RankingPage } from "./pages/RankingPage";
import { MyPage } from "./pages/MyPage";
import { LoginPage } from "./pages/LoginPage";
import { StatusBar } from "./components/StatusBar";
import { MissionRewardToast } from "./components/MissionRewardToast";
import { LoginModal } from "./components/LoginModal";
import { openLoginPrompt, useAuthUser } from "./lib/auth";
import { LISTING_BY_CODE } from "./market/universe";
import { useRoute } from "./router";
import type { Route } from "./router";

function titleFor(route: Route): string {
  if (route.name === "market") {
    const name = route.ticker ? LISTING_BY_CODE[route.ticker]?.name : undefined;
    return name ? `${name} · 시장 · 씹덕주식` : "시장 · 씹덕주식";
  }
  if (route.name === "news") return "속보 · 씹덕주식";
  if (route.name === "ranking") return "투자 랭킹 · 씹덕주식";
  if (route.name === "mypage") return "마이페이지 · 씹덕주식";
  if (route.name === "login") return "로그인 · 씹덕주식";
  return "씹덕주식 · 게임 종목 모의 거래소";
}

export default function App() {
  const route = useRoute();
  const auth = useAuthUser();
  const main = useRef<HTMLElement>(null);
  const previous = useRef(route);

  useEffect(() => {
    if (route.name === "mypage" && auth.ready && !auth.user) openLoginPrompt("/mypage");
  }, [route, auth.ready, auth.user]);

  useEffect(() => {
    document.title = titleFor(route);
    /* 최초 렌더에서는 포커스를 옮기지 않는다. 이동할 때만 본문으로 보낸다. */
    if (previous.current === route) return;
    previous.current = route;
    window.requestAnimationFrame(() => {
      if (document.documentElement.dataset.appShell !== "mobile") {
        main.current?.focus({ preventScroll: true });
      }
      window.scrollTo({ top: 0, left: 0, behavior: "auto" });
    });
  }, [route]);

  return (
    <div className="app">
      <a className="skip-link" href="#content">
        본문으로 건너뛰기
      </a>
      <AuthBridge />
      <Topbar route={route} />
      <main id="content" className="content" ref={main} tabIndex={-1}>
        {route.name === "home" && <HomePage />}
        {route.name === "market" && <MarketPage ticker={route.ticker} />}
        {route.name === "news" && <NewsPage />}
        {route.name === "ranking" && <RankingPage />}
        {route.name === "mypage" && (auth.user ? <MyPage /> : <p className="empty" role="status">로그인 상태를 확인하고 있어요.</p>)}
        {route.name === "login" && <LoginPage method={route.method} />}
      </main>
      <StatusBar />
      <MissionRewardToast />
      <LoginModal />
    </div>
  );
}
