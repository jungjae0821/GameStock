import { Delta } from "./Delta";
import { shares, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";

/**
 * 호가 5단계. 백엔드의 공개 주문 잔량을 1초 주기로 반영한다.
 * 매도 호가는 청색, 매수 호가는 적색으로 그린다.
 */
export function OrderBook({ code }: { code: string }) {
  const snapshot = useMarket();
  const quote = snapshot.quotes[code];
  if (!quote) return null;

  const maxQty = Math.max(1, ...quote.asks.map((level) => level.qty), ...quote.bids.map((level) => level.qty));
  const change = quote.price - quote.prevClose;

  return (
    <div className="book">
      <ul className="book-side is-ask">
        {quote.asks.map((level) => (
          <li key={level.price} className="book-row">
            <span className="book-bar is-ask" style={{ width: `${(level.qty / maxQty) * 100}%` }} aria-hidden="true" />
            <span className="book-price num">{won(level.price)}</span>
            <span className="book-qty num">{shares(level.qty)}</span>
          </li>
        ))}
      </ul>
      <p className="book-now">
        <span className="num">{won(quote.price)}</span>
        <Delta change={change} ratio={sessionRate(quote)} showAmount={false} />
      </p>
      <ul className="book-side is-bid">
        {quote.bids.map((level) => (
          <li key={level.price} className="book-row">
            <span className="book-bar is-bid" style={{ width: `${(level.qty / maxQty) * 100}%` }} aria-hidden="true" />
            <span className="book-price num">{won(level.price)}</span>
            <span className="book-qty num">{shares(level.qty)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
