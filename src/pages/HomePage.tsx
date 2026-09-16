import { HoldingsTable } from "../components/HoldingsTable";
import { IndexStrip } from "../components/IndexStrip";
import { MoverBoard } from "../components/MoverBoard";
import { NewsFeed } from "../components/NewsFeed";
import { Panel } from "../components/Panel";
import { PortfolioStrip } from "../components/PortfolioStrip";
import { QuoteTable } from "../components/QuoteTable";
import { TickerBoard } from "../components/TickerBoard";
import { useMarket } from "../market/MarketProvider";
import { holdings, sortCodes, totals } from "../market/selectors";
import { navigate } from "../router";

export function HomePage() {
  const snapshot = useMarket();
  const money = totals(snapshot);
  const top = sortCodes(snapshot, "volume", "desc").slice(0, 8);
  const rows = holdings(snapshot, money.total);

  return (
    <div className="page-stack">
      <h1 className="vh">씹덕주식 홈</h1>
      <IndexStrip />
      <PortfolioStrip />
      <TickerBoard />

      <div className="split">
        <div className="split-main">
          <Panel
            id="home-market"
            title="시세표"
            meta={`${snapshot.codes.length}종목 · 거래량순`}
            action={{ label: "전체 종목", to: "/market" }}
            flush
          >
            <QuoteTable codes={top} caption="거래량 상위 8개 종목" onSelect={(code) => navigate(`/market/${code}`)} />
          </Panel>
        </div>
        <div className="split-side">
          <Panel
            id="home-news"
            title="속보"
            meta={`${snapshot.news.length}건`}
            action={{ label: "전체 속보", to: "/news" }}
          >
            <NewsFeed items={snapshot.news.slice(0, 6)} />
          </Panel>
        </div>
      </div>

      <Panel id="home-movers" title="등락 흐름" meta="세션 등락률 기준">
        <MoverBoard />
      </Panel>

      {rows.length > 0 && (
        <Panel
          id="home-holdings"
          title="내 보유"
          meta={`${rows.length}종목 · 평가액 ${Math.round(money.stockValue).toLocaleString("ko-KR")}원`}
          flush
        >
          <HoldingsTable caption="내 보유 종목과 평가 손익" />
        </Panel>
      )}
    </div>
  );
}
