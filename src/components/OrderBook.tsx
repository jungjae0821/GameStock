import { Delta } from "./Delta";
import { shares, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";

interface Props {
  code: string;
  onPriceSelect?: (price: number) => void;
}

interface DepthRow {
  side: "ask" | "bid";
  price: number;
  qty: number;
  cumulative: number;
  best: boolean;
}

/** 매도·매수 5단계와 누적 잔량을 보여주는 호가 사다리. */
export function OrderBook({ code, onPriceSelect }: Props) {
  const snapshot = useMarket();
  const quote = snapshot.quotes[code];
  if (!quote) return null;

  // 매도는 낮은 가격이 최우선, 매수는 높은 가격이 최우선이다.
  const asks = [...quote.asks].sort((left, right) => left.price - right.price).slice(0, 5);
  const bids = [...quote.bids].sort((left, right) => right.price - left.price).slice(0, 5);
  const askTotal = asks.reduce((sum, level) => sum + level.qty, 0);
  const bidTotal = bids.reduce((sum, level) => sum + level.qty, 0);
  const totalDepth = Math.max(1, askTotal, bidTotal);
  const totalVisibleQty = askTotal + bidTotal;
  const buyShare = totalVisibleQty > 0 ? bidTotal / totalVisibleQty : 0.5;
  const askRows: DepthRow[] = asks
    .map((level, index) => ({
      side: "ask" as const,
      price: level.price,
      qty: level.qty,
      cumulative: asks.slice(0, index + 1).reduce((sum, item) => sum + item.qty, 0),
      best: index === 0,
    }))
    .reverse();
  const bidRows: DepthRow[] = bids.map((level, index) => ({
    side: "bid" as const,
    price: level.price,
    qty: level.qty,
    cumulative: bids.slice(0, index + 1).reduce((sum, item) => sum + item.qty, 0),
    best: index === 0,
  }));
  const change = quote.price - quote.prevClose;
  const bestAsk = asks[0]?.price;
  const bestBid = bids[0]?.price;
  const spread = bestAsk !== undefined && bestBid !== undefined ? bestAsk - bestBid : 0;

  const priceButton = (row: DepthRow) => (
    <button
      type="button"
      className={`book-price-button is-${row.side}${row.best ? " is-best" : ""}${onPriceSelect ? " is-clickable" : ""}`}
      onClick={() => onPriceSelect?.(row.price)}
      aria-label={`${row.side === "ask" ? "매도" : "매수"} ${won(row.price)} 지정가로 선택`}
    >
      {won(row.price)}
    </button>
  );

  return (
    <div className="book">
      <div className="book-summary" aria-label="공개 호가 요약">
        <span className="book-summary-ask">매도 {shares(askTotal)}</span>
        <span className="book-summary-buy">매수 {shares(bidTotal)}</span>
        <span>매수 비중 {Math.round(buyShare * 100)}%</span>
      </div>
      <div className="book-header" aria-hidden="true">
        <span>매도 누적</span>
        <span>매도 잔량</span>
        <span>가격</span>
        <span>매수 잔량</span>
        <span>매수 누적</span>
      </div>
      <div className="book-ladder" role="table" aria-label="매도·매수 호가 5단계">
        {askRows.map((row) => (
          <div className={`book-row is-ask${row.best ? " is-best" : ""}`} role="row" key={`ask-${row.price}`}>
            <span className="book-cumulative is-ask num" role="cell">{shares(row.cumulative)}</span>
            <div className="book-cell is-ask" role="cell">
              <span className="book-bar is-ask" style={{ width: `${(row.cumulative / totalDepth) * 100}%` }} aria-hidden="true" />
              <span className="book-qty num">{shares(row.qty)}</span>
            </div>
            <span className="book-price" role="cell">{priceButton(row)}</span>
            <span className="book-empty" role="cell" aria-hidden="true" />
            <span className="book-empty" role="cell" aria-hidden="true" />
          </div>
        ))}
        <div className="book-current-row" role="row" aria-label={`현재가 ${won(quote.price)}`}>
          <span className="book-current-line" aria-hidden="true" />
          <span className="book-current-label">현재가</span>
          <span className="book-current-price num">{won(quote.price)}</span>
          <Delta change={change} ratio={sessionRate(quote)} showAmount={false} />
          <span className="book-current-line" aria-hidden="true" />
        </div>
        {bidRows.map((row) => (
          <div className={`book-row is-bid${row.best ? " is-best" : ""}`} role="row" key={`bid-${row.price}`}>
            <span className="book-empty" role="cell" aria-hidden="true" />
            <span className="book-empty" role="cell" aria-hidden="true" />
            <span className="book-price" role="cell">{priceButton(row)}</span>
            <div className="book-cell is-bid" role="cell">
              <span className="book-bar is-bid" style={{ width: `${(row.cumulative / totalDepth) * 100}%` }} aria-hidden="true" />
              <span className="book-qty num">{shares(row.qty)}</span>
            </div>
            <span className="book-cumulative is-bid num" role="cell">{shares(row.cumulative)}</span>
          </div>
        ))}
      </div>
      <p className="book-spread num">
        최우선 호가 간격 {spread > 0 ? won(spread) : "-"} · 가격을 누르면 지정가 주문에 반영됩니다.
      </p>
    </div>
  );
}
