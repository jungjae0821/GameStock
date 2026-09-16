import { Chip } from "./Chip";
import { clock, shares, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { buyShare, strengthRatio } from "../market/selectors";

/** 최근 체결과 체결강도. 체결강도 100이 매수·매도 균형이다. */
export function TradePrints({ code }: { code: string }) {
  const snapshot = useMarket();
  const quote = snapshot.quotes[code];
  if (!quote) return null;

  const strength = strengthRatio(quote);
  const buy = buyShare(quote);

  return (
    <div className="tape">
      <div className="tape-strength">
        <span className="tape-label">체결강도</span>
        <div className="bar" role="img" aria-label={`매수 ${Math.round(buy * 100)}%, 매도 ${Math.round((1 - buy) * 100)}%`}>
          <div className="bar-fill is-up" style={{ width: `${buy * 100}%` }} />
        </div>
        <Chip tone={strength >= 1 ? "up" : "down"}>
          <span className="num">{Math.round(strength * 100)}%</span>
        </Chip>
      </div>

      {quote.prints.length === 0 ? (
        <p className="empty is-inline">체결 대기 중입니다.</p>
      ) : (
        <ul className="tape-list">
          {quote.prints.map((print) => (
            <li key={`${print.at}-${print.price}-${print.qty}`}>
              <span className="tape-time num">{clock(print.at)}</span>
              <span className={`tape-price num is-${print.side}`}>{won(print.price)}</span>
              <span className="tape-qty num">{shares(print.qty)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
