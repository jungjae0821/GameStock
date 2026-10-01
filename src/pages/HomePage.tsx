import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { Chip } from "../components/Chip";
import { Delta } from "../components/Delta";
import { GameIndexBoard } from "../components/GameIndexBoard";
import { HoldingsTable } from "../components/HoldingsTable";
import { Link } from "../components/Link";
import { MoverBoard } from "../components/MoverBoard";
import { NewsFeed } from "../components/NewsFeed";
import { PriceCell } from "../components/PriceCell";
import { QuoteTable } from "../components/QuoteTable";
import { openLoginPrompt, useAuthUser } from "../lib/auth";
import { useMarket } from "../market/MarketProvider";
import { holdings, sortCodes, totals } from "../market/selectors";
import { navigate } from "../router";

function copySeries(snapshot: ReturnType<typeof useMarket>): Record<string, number[]> {
  return Object.fromEntries(Object.entries(snapshot.quotes).map(([code, quote]) => [code, [...quote.series]]));
}

interface CardProps {
  id: string;
  title: string;
  meta?: string;
  action?: { label: string; to: string };
  /** 표처럼 본문을 카드 테두리에 붙일 때 true. */
  flush?: boolean;
  /** Panel 머리를 감싼 재사용 컴포넌트(GameIndexBoard)의 머리를 숨기고 카드 머리로 대체한다. */
  embed?: boolean;
  /** 실시간 갱신으로 높이가 변하지 않게 본문 최소 높이를 둔다. */
  grow?: boolean;
  children: ReactNode;
}

function HxCard({ id, title, meta, action, flush = false, embed = false, grow = false, children }: CardProps) {
  const name = ["hx-card", flush ? "hx-card-flush" : "", grow ? "hx-card-grow" : ""].filter(Boolean).join(" ");
  return (
    <section className={name} aria-labelledby={id}>
      <header className="hx-card-head">
        <h2 id={id} className="hx-card-title">
          {title}
        </h2>
        {meta && <span className="hx-card-meta num">{meta}</span>}
        {action && (
          <Link className="hx-card-action" to={action.to}>
            {action.label}
            <span aria-hidden="true">→</span>
          </Link>
        )}
      </header>
      <div className={embed ? "hx-card-body hx-card-embed" : "hx-card-body"}>{children}</div>
    </section>
  );
}

/** 전광판 없이 총 자산·수익률과 보유 주식 수익률만 담은 가벼운 요약 카드. */
function AssetSummary() {
  const snapshot = useMarket();
  const auth = useAuthUser();
  const money = totals(snapshot);
  const rows = holdings(snapshot, money.total);

  return (
    <HxCard
      id="hx-asset-title"
      title="내 자산"
    >
      {!auth.ready ? (
        <p className="hx-asset-empty" role="status">로그인 상태를 확인하고 있어요…</p>
      ) : !auth.user ? (
        <div className="hx-asset-login">
          <p className="hx-asset-empty">로그인하고 내 자산과 보유 주식 수익률을 확인하세요.</p>
          <button type="button" className="hx-asset-login-button" onClick={() => openLoginPrompt("/")}>
            <span aria-hidden="true">→</span> 로그인하기
          </button>
        </div>
      ) : (
        <>
          <div className="hx-asset-overview">
            <div className="hx-asset-total">
              <span className="hx-asset-label">총 자산</span>
              <p className="hx-asset-value">
                <PriceCell value={money.total} />
              </p>
              <Chip tone={money.pnl > 0 ? "up" : money.pnl < 0 ? "down" : "flat"}>
                <Delta change={money.pnl} ratio={money.pnlRate} />
              </Chip>
            </div>
            <dl className="hx-asset-breakdown">
              <div className="hx-asset-line">
                <dt>예수금</dt>
                <dd>
                  <PriceCell value={money.cash} />
                </dd>
              </div>
              <div className="hx-asset-line">
                <dt>주식 평가액</dt>
                <dd>
                  <PriceCell value={money.stockValue} />
                  <span className="hx-asset-percent">
                    {money.total > 0 ? `(${((money.stockValue / money.total) * 100).toFixed(1)}%)` : "(0.0%)"}
                  </span>
                </dd>
              </div>
              <div className="hx-asset-line">
                <dt>보유 종목</dt>
                <dd className="num">{rows.length}종목</dd>
              </div>
            </dl>
          </div>
          {rows.length > 0 ? (
            <div className="hx-asset-holdings">
              <h3>보유 주식 수익률</h3>
              <HoldingsTable caption="보유 종목별 평가 손익과 수익률" />
            </div>
          ) : (
            <p className="hx-asset-empty">아직 보유한 주식이 없어요. 시장에서 첫 거래를 시작해 보세요.</p>
          )}
        </>
      )}
    </HxCard>
  );
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
    <div className="hx-home">
      <h1 className="vh">씹덕주식 홈</h1>
      <AssetSummary />

      <div className="hx-body">
        <div className="hx-main">
          <HxCard id="hx-index" title="장르 지수" embed>
            <GameIndexBoard />
          </HxCard>
          <HxCard
            id="hx-quotes"
            title="실시간 시세"
            flush
          >
            <QuoteTable
              codes={top}
              caption="거래량 상위 8개 종목"
              chartSeries={chartSeries}
              onSelect={(code) => navigate(`/market/${code}`)}
            />
          </HxCard>
          <HxCard id="hx-movers" title="등락 흐름" grow>
            <MoverBoard />
          </HxCard>
        </div>
        <div className="hx-side">
          <HxCard
            id="hx-news"
            title="주요 뉴스"
            action={{ label: "전체 뉴스", to: "/news" }}
          >
            <NewsFeed items={visibleNews.slice(0, 6)} />
          </HxCard>
        </div>
      </div>
    </div>
  );
}
