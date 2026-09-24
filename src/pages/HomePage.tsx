import { IndexStrip } from "../components/IndexStrip";
import { GameIndexBoard } from "../components/GameIndexBoard";
import { MissionBoard } from "../components/MissionBoard";
import { MoverBoard } from "../components/MoverBoard";
import { NewsFeed } from "../components/NewsFeed";
import { Panel } from "../components/Panel";
import { PortfolioStrip } from "../components/PortfolioStrip";
import { QuoteTable } from "../components/QuoteTable";
import { TickerBoard } from "../components/TickerBoard";
import { useMarket } from "../market/MarketProvider";
import { sortCodes } from "../market/selectors";
import { navigate } from "../router";
import { useEffect, useRef, useState } from "react";

function copySeries(snapshot: ReturnType<typeof useMarket>): Record<string, number[]> {
  return Object.fromEntries(Object.entries(snapshot.quotes).map(([code, quote]) => [code, [...quote.series]]));
}

export function HomePage() {
  const snapshot = useMarket();
  const latest = useRef(snapshot);
  latest.current = snapshot;
  const [chartSeries, setChartSeries] = useState(() => copySeries(snapshot));
  useEffect(() => {
    const timer = window.setInterval(() => setChartSeries(copySeries(latest.current)), 10_000);
    return () => window.clearInterval(timer);
  }, []);
  const top = sortCodes(snapshot, "volume", "desc").slice(0, 8);
  const visibleNews = snapshot.news.filter((item) => item.source === "미디어 보도");

  return (
    <div className="page-stack">
      <h1 className="vh">씹덕주식 홈</h1>
      <IndexStrip />
      <PortfolioStrip />
      <TickerBoard />

      <div className="split home-insight-row">
        <div className="split-main">
          <GameIndexBoard />
        </div>
        <div className="split-side">
          <MissionBoard />
        </div>
      </div>

      <div className="split">
        <div className="split-main">
          <Panel
            id="home-market"
            title="시세표"
            meta={`${snapshot.codes.length}종목 · 거래량순`}
            action={{ label: "전체 종목", to: "/market" }}
            flush
          >
            <QuoteTable
              codes={top}
              caption="거래량 상위 8개 종목"
              chartSeries={chartSeries}
              onSelect={(code) => navigate(`/market/${code}`)}
            />
          </Panel>
          <div className="home-movers">
            <Panel id="home-movers" title="등락 흐름" meta="세션 등락률 기준">
              <MoverBoard />
            </Panel>
          </div>
        </div>
        <div className="split-side">
          <Panel
            id="home-news"
            title="뉴스"
            meta={`${visibleNews.length}건`}
            action={{ label: "전체 뉴스", to: "/news" }}
          >
            <NewsFeed items={visibleNews.slice(0, 6)} />
          </Panel>
        </div>
      </div>

    </div>
  );
}
