import { useEffect, useRef } from "react";
import { StatusBar } from "./components/StatusBar";
import { Topbar } from "./components/Topbar";
import { HomePage } from "./pages/HomePage";
import { MarketPage } from "./pages/MarketPage";
import { NewsPage } from "./pages/NewsPage";
import { LISTING_BY_CODE } from "./market/universe";
import { useRoute } from "./router";
import type { Route } from "./router";

const ACCOUNT = "모의계좌 5513";

function titleFor(route: Route): string {
  if (route.name === "market") {
    const name = route.ticker ? LISTING_BY_CODE[route.ticker]?.name : undefined;
    return name ? `${name} · 시장 · 씹덕주식` : "시장 · 씹덕주식";
  }
  if (route.name === "news") return "속보 · 씹덕주식";
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
      <Topbar route={route} account={ACCOUNT} />
      <main id="content" className="content" ref={main} tabIndex={-1}>
        {route.name === "home" && <HomePage />}
        {route.name === "market" && <MarketPage ticker={route.ticker} />}
        {route.name === "news" && <NewsPage />}
      </main>
      <StatusBar />
    </div>
  );
}
