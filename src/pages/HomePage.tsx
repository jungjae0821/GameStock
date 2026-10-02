import { HoldingsTable } from "../components/HoldingsTable";
import { MarketHead } from "../components/MarketHead";
import { NewsFeed } from "../components/NewsFeed";
import { Panel } from "../components/Panel";
import { QuoteTable } from "../components/QuoteTable";
import { Spotlight } from "../components/Spotlight";
import { CircuitBreakerNotice } from "../components/CircuitBreakerNotice";
import { useMarket } from "../market/MarketProvider";
import { holdings, sortCodes, totals } from "../market/selectors";
import { navigate } from "../router";

export function HomePage() {
  const snapshot = useMarket();
  const top = sortCodes(snapshot, "volume", "desc").slice(0, 8);
  const rows = holdings(snapshot, totals(snapshot).total);

  return (
    <div className="page-stack">
      <CircuitBreakerNotice />
      <h1 className="vh">씹덕주식 홈</h1>
      <MarketHead />
      <Spotlight />

      <div className="split">
        <Panel id="home-market" title="인기 종목" action={{ label: "전체 보기", to: "/market" }}>
          <QuoteTable codes={top} caption="거래량 상위 8개 종목" onSelect={(code) => navigate(`/market/${code}`)} />
        </Panel>
        <Panel id="home-news" title="속보" action={{ label: "전체 보기", to: "/news" }}>
          <NewsFeed limit={5} />
        </Panel>
      </div>

      {rows.length > 0 && (
        <Panel id="home-holdings" title="내 보유">
          <HoldingsTable caption="내 보유 종목과 평가 손익" />
        </Panel>
      )}
    </div>
  );
}
