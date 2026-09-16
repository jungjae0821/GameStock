import { Link } from "./Link";
import { PriceCell } from "./PriceCell";
import { rate, tickLabel, trendArrow } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";

/**
 * 거래소 로비의 전광판. 순서는 고정하고, 값이 바뀌면 그 숫자만 진행 방향에서
 * 흘러 들어온다. 자동 스크롤이나 반복 애니메이션은 쓰지 않는다.
 */
export function TickerBoard() {
  const { quotes, codes, tickMs } = useMarket();

  return (
    <section className="board" aria-labelledby="board-title">
      <div className="board-head">
        <h2 id="board-title" className="board-title">
          전광판
        </h2>
        <p className="board-meta num">
          {codes.length}종목 · 세션 등락 · {tickLabel(tickMs)}마다 갱신
        </p>
      </div>
      <ul className="board-tape">
        {codes.map((code) => {
          const quote = quotes[code];
          const listing = LISTING_BY_CODE[code];
          if (!quote || !listing) return null;
          const ratio = sessionRate(quote);
          const tone = ratio > 0 ? "up" : ratio < 0 ? "down" : "flat";
          return (
            <li key={code}>
              <Link className="board-cell" to={`/market/${code}`}>
                <span className="board-name">{listing.name}</span>
                <PriceCell className="board-price num" value={quote.price} motion="digits" />
                <span className={`board-delta num is-${tone}`}>
                  <span className="board-arrow" aria-hidden="true">
                    {trendArrow(ratio)}
                  </span>
                  {rate(ratio)}
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
