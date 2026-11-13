import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "../components/Link";
import { SeriesChart, type Marker } from "../components/SeriesChart";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { won } from "../market/format";
import { LISTING_BY_CODE } from "../market/universe";
import { TALK_PATH } from "../router";
import { NxAccountPanel, type NxAccountTab } from "./trade/NxAccountPanel";
import { NxOrderTicket } from "./trade/NxOrderTicket";
import { OrderDock } from "./trade/OrderDock";
import { PriceDrivers } from "./trade/PriceDrivers";
import { MarketTabs } from "./MarketTabs";
import { acknowledgeOjt, useCareer } from "../lib/career";
import { Book } from "./trade/OrderBook";
import { StockHead, StockNews, StockSelector, StockStats } from "./trade/StockPanels";

export function TradeTerminal({ ticker }: { ticker: string | null }) {
  const snapshot = useMarket();
  const { setActiveDetailCode } = useMarketApi();
  const [accountTab, setAccountTab] = useState<NxAccountTab>("holdings");
  const [selected, setSelected] = useState<{ code: string; price: number; revision: number } | null>(null);
  const [orderOpen, setOrderOpen] = useState(false);
  const accountFocusPending = useRef(false);
  const code = ticker?.toUpperCase() ?? snapshot.codes[0] ?? null;
  const listing = code ? LISTING_BY_CODE[code] : undefined;
  const quote = code ? snapshot.quotes[code] : undefined;
  const fills = snapshot.portfolio.fills;
  const markers = useMemo<Marker[]>(() => [
    ...snapshot.news.filter((item) => item.code === code).map((item) => ({ at: item.at, label: item.title })),
    ...fills.filter((fill) => fill.code === code).map((fill) => ({
      at: fill.at, price: fill.price, kind: fill.side,
      label: `내 ${fill.side === "buy" ? "매수" : "매도"} ${fill.qty.toLocaleString("ko-KR")}주 · ${won(fill.price)}`,
    })),
  ], [snapshot.news, fills, code]);

  useEffect(() => { setOrderOpen(false); }, [code]);
  // 수습 OJT의 "종목 화면에서 호가·뉴스 보기"는 이 화면을 연 것으로 끝난다.
  const careerGrade = useCareer()?.grade;
  useEffect(() => { if (code && careerGrade === "TRAINEE") void acknowledgeOjt("MARKET").catch(() => undefined); }, [code, careerGrade]);

  useEffect(() => {
    if (!code) return undefined;
    setActiveDetailCode(code);
    return () => setActiveDetailCode(null);
  }, [setActiveDetailCode, code]);

  if (!code || !listing) {
    return <p className="nx-empty">거래할 종목을 찾을 수 없어요. <Link to="/market">시장으로 돌아가기</Link></p>;
  }
  if (!quote) {
    return <p className="nx-empty" role="status">시세를 불러오는 중이에요.</p>;
  }

  const focusTicket = () => {
    if (window.matchMedia("(max-width: 960px)").matches) setOrderOpen(true);
    else {
      const ticket = document.querySelector<HTMLFormElement>(".nx-ticket");
      ticket?.focus({ preventScroll: true });
      ticket?.scrollIntoView({ block: "nearest" });
    }
  };
  const focusAccount = () => {
    const account = document.querySelector<HTMLElement>(".nx-account");
    account?.focus({ preventScroll: true });
    account?.scrollIntoView({ block: "nearest" });
    accountFocusPending.current = false;
  };

  return (
    <div className="nx-trade-page">
      <MarketTabs active="stocks" />
      <div className="nx-trade-toolbar">
        <Link to="/market" className="nx-back-link"><span aria-hidden="true">←</span> 주식 목록</Link>
        <StockSelector current={code} />
      </div>
      <div className="nx-workspace">
        {snapshot.portfolioError && <p role="status" className="nx-alert">{snapshot.portfolioError}</p>}
        <div className="nx-trade-main">
        <section className="nx-trade-chart" aria-label={`${listing.name} 시세와 차트`}>
          <StockHead code={code} quote={quote} />
          <div className="nx-chart integration-tools">
            <SeriesChart key={code} code={code} markers={markers} initialMode="candle" studies />
          </div>
        </section>
        <StockStats quote={quote} />
        <PriceDrivers code={code} />
        <section className="nx-panel nx-account" aria-label="내 잔고와 주문 내역" tabIndex={-1}>
          <NxAccountPanel code={code} tab={accountTab} onTabChange={setAccountTab}
            onFocusTicket={focusTicket} />
        </section>
        <div className="nx-trade-details">
          <Book quote={quote} onPriceSelect={(price) => { setSelected((previous) => ({ code, price, revision: (previous?.revision ?? 0) + 1 })); focusTicket(); }} />
          <StockNews code={code} quote={quote} />
        </div>
        {/* 글과 채팅은 종목토론 화면에만 둔다. 여기서는 그 화면으로 가는 길만 낸다. */}
        <section className="nx-talk-entry" aria-labelledby="nx-talk-entry-title">
          <div>
            <h2 id="nx-talk-entry-title">종목토론</h2>
            <p className="nx-muted">글과 실시간 채팅은 종목토론 화면에 모여 있어요.</p>
          </div>
          <Link to={TALK_PATH(code)} className="nx-btn">토론방 열기</Link>
        </section>
        <p className="nx-trade-disclaimer">게임 뉴스와 모의 주문으로 형성되는 시세입니다. 실제 금융상품이나 투자 수익과 관계없습니다.</p>
        </div>
        <OrderDock title={listing.name} open={orderOpen} onOpenChange={setOrderOpen}
          onAfterClose={() => { if (accountFocusPending.current) focusAccount(); }}>
          <NxOrderTicket
            key={code}
            code={code}
            onShowOrders={() => {
              setAccountTab("orders");
              if (orderOpen) accountFocusPending.current = true;
              else window.requestAnimationFrame(focusAccount);
              setOrderOpen(false);
            }}
            selectedLimitPrice={selected?.code === code ? selected.price : null}
            selectedLimitPriceRevision={selected?.code === code ? selected.revision : undefined}
          />
        </OrderDock>
      </div>
    </div>
  );
}
