import { Chip, rateTone } from "./Chip";
import { Delta } from "./Delta";
import { GameIcon } from "./GameIcon";
import { Link } from "./Link";
import { NewsFeed } from "./NewsFeed";
import { OrderTicket } from "./OrderTicket";
import { OrderBook } from "./OrderBook";
import { Panel } from "./Panel";
import { PriceCell } from "./PriceCell";
import { TradePrints } from "./TradePrints";
import { rate, trendArrow, won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";
import { VISIBLE_NEWS_SOURCES } from "../market/types";
import { useState } from "react";

type DetailTab = "trade" | "news" | "info";

export function StockDetail({ code, selectedLimitPrice, onPriceSelect }: { code: string; selectedLimitPrice?: number | null; onPriceSelect?: (price: number) => void }) {
  const snapshot = useMarket();
  const api = useMarketApi();
  const listing = LISTING_BY_CODE[code];
  const quote = snapshot.quotes[code];

  if (!listing || !quote) {
    return (
      <p className="empty">
        상장 목록에 없는 종목입니다. <Link to="/market">전체 종목 보기</Link>
      </p>
    );
  }

  const ratio = sessionRate(quote);
  const tone = rateTone(ratio);
  const position = snapshot.portfolio.positions[code];
  const watched = snapshot.watch.includes(code);
  const [tab, setTab] = useState<DetailTab>("trade");
  const stockNews = snapshot.news.filter((item) => item.code === code && VISIBLE_NEWS_SOURCES.includes(item.source));
  const spread = quote.asks[0] && quote.bids[0] ? quote.asks[0].price - quote.bids[0].price : 0;
  const liquidity = quote.volume > 0 && quote.prints.length > 0 ? "활발" : quote.volume > 0 ? "보통" : "거래 대기";

  return (
    <article className="detail" aria-labelledby="detail-name">
      <header className="detail-head">
        <div className="detail-ident">
          <GameIcon code={listing.code} name={listing.name} />
          <div>
            <h2 id="detail-name" className="detail-name">
              {listing.name}
            </h2>
            <p className="detail-sub">
              {listing.genre} · {listing.publisher} · {listing.code}
            </p>
          </div>
          <button
            type="button"
            className={`watch-button is-text${watched ? " is-on" : ""}`}
            aria-pressed={watched}
            onClick={() => api.toggleWatch(code)}
          >
            <span aria-hidden="true">{watched ? "★" : "☆"}</span> 관심
          </button>
        </div>
        <div className="detail-price">
          <PriceCell className="num detail-price-value" value={quote.price} />
          <Chip tone={tone}>
            <span aria-hidden="true">{trendArrow(ratio)}</span>
            <span className="num">{won(quote.price - quote.prevClose)}</span>
            <span className="num">{rate(ratio)}</span>
          </Chip>
        </div>
      </header>

      <nav className="detail-tabs" aria-label="종목 상세 메뉴">
        {([
          ["trade", "호가·주문"],
          ["news", "소식"],
          ["info", "종목정보"],
        ] as const).map(([id, label]) => (
          <button key={id} type="button" className={`detail-tab${tab === id ? " is-active" : ""}`} aria-pressed={tab === id} onClick={() => setTab(id)}>
            {label}{id === "news" && <span className="detail-tab-count num">{stockNews.length}</span>}
          </button>
        ))}
      </nav>

      {tab === "trade" && (
        <div className="detail-trade-grid">
          <Panel id="detail-order" title="주문" meta="시장가·호가 지정가"><OrderTicket key={code} code={code} selectedLimitPrice={selectedLimitPrice} /></Panel>
          <div className="detail-trade-quotes">
            <Panel id="detail-book" title="호가" meta="5단계"><OrderBook code={code} onPriceSelect={onPriceSelect} /></Panel>
            <Panel id="detail-tape" title="최근 체결" meta={`${quote.prints.length}건`}><TradePrints code={code} /></Panel>
          </div>
          {position && <Panel id="detail-holding" title="내 보유"><dl className="stat-grid is-holding">
            <div><dt>보유 수량</dt><dd className="num">{position.qty}주</dd></div>
            <div><dt>평균 단가</dt><dd className="num">{won(position.avgCost)}</dd></div>
            <div><dt>평가액</dt><dd className="num">{won(quote.price * position.qty)}</dd></div>
            <div><dt>평가 손익</dt><dd><Chip tone={(quote.price - position.avgCost) * position.qty >= 0 ? "up" : "down"}><Delta change={(quote.price - position.avgCost) * position.qty} ratio={position.avgCost > 0 ? (quote.price - position.avgCost) / position.avgCost : 0} /></Chip></dd></div>
          </dl></Panel>}
        </div>
      )}

      {tab === "news" && <Panel id="detail-news" title="관련 소식" meta={`${stockNews.length}건`}><NewsFeed items={stockNews} emptyMessage="이 종목의 소식이 아직 없습니다." /></Panel>}

      {tab === "info" && <Panel id="detail-info" title="종목정보" meta="현재 시장 기준"><dl className="detail-info-grid">
        <div><dt>게임</dt><dd>{listing.name}</dd></div>
        <div><dt>퍼블리셔</dt><dd>{listing.publisher}</dd></div>
        <div><dt>장르</dt><dd>{listing.genre}</dd></div>
        <div><dt>종목 코드</dt><dd className="num">{listing.code}</dd></div>
        <div><dt>거래 상태</dt><dd>실시간 갱신 중</dd></div>
        <div><dt>유동성</dt><dd>{liquidity}</dd></div>
        <div><dt>호가 간격</dt><dd className="num">{spread > 0 ? won(spread) : "집계 중"}</dd></div>
        <div><dt>세션 갱신</dt><dd className="num">{snapshot.tickMs / 1000}초</dd></div>
      </dl></Panel>}

    </article>
  );
}
