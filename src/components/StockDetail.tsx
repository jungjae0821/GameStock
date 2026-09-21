import { Chip, rateTone } from "./Chip";
import { Delta } from "./Delta";
import { Link } from "./Link";
import { OrderTicket } from "./OrderTicket";
import { Panel } from "./Panel";
import { PriceCell } from "./PriceCell";
import { SeriesChart } from "./SeriesChart";
import { compactWon, rate, shares, trendArrow, won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { sessionRate, strengthRatio } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";

export function StockDetail({ code, selectedLimitPrice }: { code: string; selectedLimitPrice?: number | null }) {
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
  const sparkTone: "up" | "down" | "flat" = ratio > 0 ? "up" : ratio < 0 ? "down" : "flat";
  const position = snapshot.portfolio.positions[code];
  const watched = snapshot.watch.includes(code);

  return (
    <article className="detail" aria-labelledby="detail-name">
      <header className="detail-head">
        <div className="detail-ident">
          <span className="code-badge is-large" aria-hidden="true">
            {listing.code}
          </span>
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

      <Panel
        id="detail-session"
        title="가격 차트"
        meta={`전일 종가 ${won(quote.prevClose)}`}
      >
        <dl className="stat-grid">
          <div>
            <dt>시가</dt>
            <dd className="num">{won(quote.open)}</dd>
          </div>
          <div>
            <dt>고가</dt>
            <dd className="num">{won(quote.high)}</dd>
          </div>
          <div>
            <dt>저가</dt>
            <dd className="num">{won(quote.low)}</dd>
          </div>
          <div>
            <dt>상한가</dt>
            <dd className="num">{won(quote.limitUp)}</dd>
          </div>
          <div>
            <dt>거래량</dt>
            <dd className="num">{shares(quote.volume)}</dd>
          </div>
          <div>
            <dt>거래대금</dt>
            <dd className="num">{compactWon(quote.volume * quote.price)}</dd>
          </div>
          <div>
            <dt>하한가</dt>
            <dd className="num">{won(quote.limitDown)}</dd>
          </div>
          <div>
            <dt>체결강도</dt>
            <dd>
              <Chip tone={strengthRatio(quote) >= 1 ? "up" : "down"}>
                <span className="num">{Math.round(strengthRatio(quote) * 100)}%</span>
              </Chip>
            </dd>
          </div>
        </dl>
        <SeriesChart code={code} tone={sparkTone} />
      </Panel>

      <div className="detail-trade-column">
        <Panel id="detail-order" title="주문" meta="시장가·호가 지정가">
          <OrderTicket key={code} code={code} selectedLimitPrice={selectedLimitPrice} />
        </Panel>

        {position && (
          <Panel id="detail-holding" title="내 보유">
            <dl className="stat-grid is-holding">
              <div>
                <dt>보유 수량</dt>
                <dd className="num">{position.qty}주</dd>
              </div>
              <div>
                <dt>평균 단가</dt>
                <dd className="num">{won(position.avgCost)}</dd>
              </div>
              <div>
                <dt>평가액</dt>
                <dd className="num">{won(quote.price * position.qty)}</dd>
              </div>
              <div>
                <dt>평가 손익</dt>
                <dd>
                  <Chip tone={(quote.price - position.avgCost) * position.qty >= 0 ? "up" : "down"}>
                    <Delta
                      change={(quote.price - position.avgCost) * position.qty}
                      ratio={position.avgCost > 0 ? (quote.price - position.avgCost) / position.avgCost : 0}
                    />
                  </Chip>
                </dd>
              </div>
            </dl>
          </Panel>
        )}
      </div>

    </article>
  );
}
