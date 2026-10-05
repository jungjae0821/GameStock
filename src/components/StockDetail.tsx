import { Delta } from "./Delta";
import { GameIcon } from "./GameIcon";
import { Link } from "./Link";
import { PriceCell } from "./PriceCell";
import { ReferenceSeriesChart } from "./ReferenceSeriesChart";
import { clock, shares, won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";
import { TRADE_PATH } from "../router";

export function StockDetail({ code }: { code: string }) {
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
  const position = snapshot.portfolio.positions[code];
  const fills = snapshot.portfolio.fills.filter((fill) => fill.code === code).slice(0, 5);
  const watched = snapshot.watch.includes(code);
  const pnl = position ? (quote.price - position.avgCost) * position.qty : 0;

  return (
    <article className="detail" aria-labelledby="detail-name">
      <header className="detail-head">
        <GameIcon code={code} size="xl" />
        <div className="detail-ident">
          <h2 id="detail-name" className="detail-name">
            {listing.name}
          </h2>
          <p className="detail-sub">{listing.publisher}</p>
        </div>
        <button
          type="button"
          className={`watch-button is-text${watched ? " is-on" : ""}`}
          aria-pressed={watched}
          onClick={() => api.toggleWatch(code)}
        >
          <span aria-hidden="true">{watched ? "★" : "☆"}</span> 관심
        </button>
      </header>

      <div className="detail-price">
        <PriceCell className="num detail-price-value" value={quote.price} />
        <Delta change={ratio} ratio={ratio} showAmount={false} />
      </div>

      <ReferenceSeriesChart code={code} />

      <dl className="facts">
        <div>
          <dt>시가</dt>
          <dd className="num">{won(quote.open)}</dd>
        </div>
        <div>
          <dt>전일 종가</dt>
          <dd className="num">{won(quote.prevClose)}</dd>
        </div>
        <div>
          <dt>거래량</dt>
          <dd className="num">{shares(quote.volume)}</dd>
        </div>
        <div>
          <dt>하한가</dt>
          <dd className="num">{won(quote.limitDown)}</dd>
        </div>
        <div>
          <dt>상한가</dt>
          <dd className="num">{won(quote.limitUp)}</dd>
        </div>
      </dl>

      {position && (
        <section className="mine" aria-labelledby="detail-holding">
          <h3 id="detail-holding" className="sub-title">
            내 보유
          </h3>
          <p className="mine-line">
            <span className="num">{position.qty}주</span>
            <span className="num">{won(quote.price * position.qty)}</span>
            <Delta change={pnl} ratio={position.avgCost > 0 ? (quote.price - position.avgCost) / position.avgCost : 0} />
          </p>
        </section>
      )}

      <section className="detail-trade-cta" aria-labelledby="detail-trade-title">
        <div>
          <h3 id="detail-trade-title" className="sub-title">
            거래
          </h3>
          <p>상세 차트와 주문창에서 매수·매도를 진행할 수 있습니다.</p>
        </div>
        <Link className="detail-trade-button" to={TRADE_PATH(code)}>
          거래 화면 열기
        </Link>
      </section>

      {fills.length > 0 && (
        <section aria-labelledby="detail-fills">
          <h3 id="detail-fills" className="sub-title">
            내 체결
          </h3>
          <ul className="fill-list">
            {fills.map((fill) => (
              <li key={fill.id}>
                <span className="num fill-time">{clock(fill.at)}</span>
                <span className={fill.side === "buy" ? "is-up" : "is-down"}>{fill.side === "buy" ? "매수" : "매도"}</span>
                <span className="num">{fill.qty}주</span>
                <span className="num fill-amount">{won(fill.price * fill.qty)}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </article>
  );
}
