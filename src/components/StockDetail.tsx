import { useState } from "react";
import { Delta } from "./Delta";
import { GameIcon } from "./GameIcon";
import { Link } from "./Link";
import { NewsFeed } from "./NewsFeed";
import { OrderBook } from "./OrderBook";
import { OrderDialog } from "./OrderDialog";
import { Panel } from "./Panel";
import { PriceCell } from "./PriceCell";
import { StockChartPanel } from "./StockChartPanel";
import { TradePrints } from "./TradePrints";
import { RestrictionBadge } from "./RestrictionBadge";
import { rate, signedWon, won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";
import { VISIBLE_NEWS_SOURCES } from "../market/types";

export function StockDetail({ code }: { code: string }) {
  const snapshot = useMarket();
  const api = useMarketApi();
  const [tab, setTab] = useState<"news" | "info">("news");
  const [orderOpen, setOrderOpen] = useState(false);
  const [orderSide, setOrderSide] = useState<"buy" | "sell">("buy");
  const [selectedLimitPrice, setSelectedLimitPrice] = useState<number | null>(null);
  const listing = LISTING_BY_CODE[code];
  const quote = snapshot.quotes[code];

  if (!listing || !quote) {
    return <p className="empty">상장 목록에 없는 종목입니다. <Link to="/market">전체 종목 보기</Link></p>;
  }

  const ratio = sessionRate(quote);
  const tone = ratio > 0 ? "up" : ratio < 0 ? "down" : "flat";
  const position = snapshot.portfolio.positions[code];
  const watched = snapshot.watch.includes(code);
  const stockNews = snapshot.news.filter((item) => item.code === code && VISIBLE_NEWS_SOURCES.includes(item.source));
  const spread = quote.asks[0] && quote.bids[0] ? quote.asks[0].price - quote.bids[0].price : 0;
  const liquidity = quote.volume > 0 && quote.prints.length > 0 ? "활발" : quote.volume > 0 ? "보통" : "거래 대기";
  const holdingQty = position?.qty ?? 0;

  const openOrder = (side: "buy" | "sell", price: number | null = null) => {
    setOrderSide(side);
    setSelectedLimitPrice(price);
    setOrderOpen(true);
  };

  return (
    <article className="stock-detail-page" aria-labelledby="detail-name">
      <header className="detail-head stock-detail-head">
        <div className="detail-ident">
          <GameIcon code={listing.code} name={listing.name} />
          <div className="stock-detail-ident-text">
            <div className="stock-name-group">
              <h1 id="detail-name" className="detail-name">{listing.name}</h1>
              <RestrictionBadge code={code} />
            </div>
            <p className="detail-sub">{listing.genre} · {listing.publisher} · {listing.code}</p>
          </div>
          <button type="button" className={`watch-button is-text${watched ? " is-on" : ""}`} aria-pressed={watched} onClick={() => api.toggleWatch(code)}>
            <span aria-hidden="true">{watched ? "★" : "☆"}</span> 관심
          </button>
        </div>
        <div className="stock-detail-price">
          <PriceCell className="num detail-price-value" value={quote.price} />
          <p className={`stock-detail-change is-${tone}`}>
            <span>전일보다 </span><strong className="num">{signedWon(quote.price - quote.prevClose)} ({rate(ratio)})</strong>
          </p>
        </div>
      </header>

      <div className="stock-detail-layout">
        <div className="stock-detail-main">
          <StockChartPanel code={code} />
          <nav className="detail-tabs" aria-label="종목 상세 메뉴">
            <button type="button" className={`detail-tab${tab === "news" ? " is-active" : ""}`} aria-pressed={tab === "news"} onClick={() => setTab("news")}>
              소식 <span className="detail-tab-count num">{stockNews.length}</span>
            </button>
            <button type="button" className={`detail-tab${tab === "info" ? " is-active" : ""}`} aria-pressed={tab === "info"} onClick={() => setTab("info")}>종목정보</button>
          </nav>
          {tab === "news" ? (
            <Panel id="detail-news" title="관련 소식" meta={`${stockNews.length}건`}><NewsFeed items={stockNews} emptyMessage="이 종목의 소식이 아직 없습니다." /></Panel>
          ) : (
            <Panel id="detail-info" title="종목정보"><dl className="detail-info-grid">
              <div><dt>게임</dt><dd>{listing.name}</dd></div>
              <div><dt>퍼블리셔</dt><dd>{listing.publisher}</dd></div>
              <div><dt>장르</dt><dd>{listing.genre}</dd></div>
              <div><dt>종목 코드</dt><dd className="num">{listing.code}</dd></div>
              <div><dt>거래 상태</dt><dd>{quote.restriction?.label ?? "실시간 갱신 중"}</dd></div>
              <div><dt>유동성</dt><dd>{liquidity}</dd></div>
              <div><dt>호가 간격</dt><dd className="num">{spread > 0 ? won(spread) : "집계 중"}</dd></div>
            </dl></Panel>
          )}
        </div>

        <aside className="stock-detail-sidebar" aria-label={`${listing.name} 거래 현황`}>
          <Panel id="detail-book" title="호가" meta="5단계"><OrderBook code={code} onPriceSelect={(price) => openOrder("buy", price)} /></Panel>
          <Panel id="detail-tape" title="최근 체결" meta={`${quote.prints.length}건`}><TradePrints code={code} /></Panel>
          <Panel id="detail-holding" title="내 보유"><dl className="stat-grid is-holding">
            <div><dt>보유 수량</dt><dd className="num">{holdingQty.toLocaleString("ko-KR")}주</dd></div>
            <div><dt>평균 단가</dt><dd className="num">{position ? won(position.avgCost) : "—"}</dd></div>
            <div><dt>평가액</dt><dd className="num">{won(quote.price * holdingQty)}</dd></div>
            <div><dt>평가 손익</dt><dd>{position ? <Delta change={(quote.price - position.avgCost) * holdingQty} ratio={position.avgCost > 0 ? (quote.price - position.avgCost) / position.avgCost : 0} /> : "—"}</dd></div>
          </dl></Panel>
        </aside>
      </div>

      <div className="stock-purchase-bar">
        <div className={`stock-trade-actions${holdingQty > 0 ? " has-holding" : ""}`}>
          <button type="button" className="stock-purchase-button" aria-haspopup="dialog" onClick={() => openOrder("buy")}>구매하기</button>
          {holdingQty > 0 && <button type="button" className="stock-purchase-button is-sell" aria-haspopup="dialog" onClick={() => openOrder("sell")}>판매하기</button>}
        </div>
      </div>
      {orderOpen && <OrderDialog code={code} initialSide={orderSide} selectedLimitPrice={selectedLimitPrice} onClose={() => setOrderOpen(false)} />}
    </article>
  );
}
