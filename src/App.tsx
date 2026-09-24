import { useEffect, useRef } from "react";
import { Topbar } from "./components/Topbar";
import { HomePage } from "./pages/HomePage";
import { MarketPage } from "./pages/MarketPage";
import { NewsPage } from "./pages/NewsPage";
import { RankingPage } from "./pages/RankingPage";
import { MyPage } from "./pages/MyPage";
import { MissionRewardToast } from "./components/MissionRewardToast";
import { LISTING_BY_CODE } from "./market/universe";
import { useRoute } from "./router";
import type { Route } from "./router";

function titleFor(route: Route): string {
  if (route.name === "market") {
    const name = route.ticker ? LISTING_BY_CODE[route.ticker]?.name : undefined;
    return name ? `${name} · 시장 · 씹덕주식` : "시장 · 씹덕주식";
  }
  if (route.name === "news") return "뉴스 · 씹덕주식";
  if (route.name === "ranking") return "투자 랭킹 · 씹덕주식";
  if (route.name === "mypage") return "마이페이지 · 씹덕주식";
  return "씹덕주식 · 게임 종목 모의 거래소";
}

export default function App() {
  const route = useRoute();
  const main = useRef<HTMLElement>(null);
  const previous = useRef(route);

  useEffect(() => {
    document.title = titleFor(route);
    /* 최초 렌더에서는 포커스를 옮기지 않는다. 이동할 때만 본문으로 보낸다. */
    if (previous.current === route) return;
    previous.current = route;
    main.current?.focus();
  }, [route]);

  return (
    <div className="app">
      <a className="skip-link" href="#content">
        본문으로 건너뛰기
      </a>
      <Topbar route={route} />
      <main id="content" className="content" ref={main} tabIndex={-1}>
        {route.name === "home" && <HomePage />}
        {route.name === "market" && <MarketPage ticker={route.ticker} />}
        {route.name === "news" && <NewsPage />}
        {route.name === "ranking" && <RankingPage />}
        {route.name === "mypage" && <MyPage />}
      </main>
      <MissionRewardToast />
    </div>
  );
}
