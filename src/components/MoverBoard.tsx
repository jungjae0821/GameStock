import { Chip, rateTone } from "./Chip";
import { Link } from "./Link";
import { PriceCell } from "./PriceCell";
import { rate, trendArrow } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { movers, sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";
import { useHeldOrder, useHoldWhilePointing } from "../lib/useHeldOrder";

const COUNT = 5;

/** 등락 상위·하위를 막대 길이로 비교하는 보드. */
export function MoverBoard() {
  const snapshot = useMarket();
  const [hold, holdHandlers] = useHoldWhilePointing();
  const liveUp = movers(snapshot, 1, COUNT).filter((code) => sessionRate(snapshot.quotes[code]) > 0);
  const liveDown = movers(snapshot, -1, COUNT).filter((code) => sessionRate(snapshot.quotes[code]) < 0);
  const up = useHeldOrder(liveUp, hold);
  const down = useHeldOrder(liveDown, hold);
  const scale = Math.max(
    0.001,
    ...[...up, ...down].map((code) => Math.abs(sessionRate(snapshot.quotes[code]))),
  );

  const column = (codes: string[], tone: "up" | "down", title: string) => (
    <div className="mover-col">
      <h3 className="mover-title">
        <span className={`panel-mark is-${tone}`} aria-hidden="true" />
        {title}
        <span className="num mover-count">{codes.length}</span>
      </h3>
      {codes.length === 0 ? (
        <p className="empty is-inline">해당 종목이 없습니다.</p>
      ) : (
        <ol className="mover-list">
          {codes.map((code, index) => {
            const quote = snapshot.quotes[code];
            const listing = LISTING_BY_CODE[code];
            const ratio = sessionRate(quote);
            return (
              <li key={code}>
                <Link className="mover-row" to={`/market/${code}`}>
                  <span className="mover-rank num">{index + 1}</span>
                  <span className="mover-name">
                    <span className="mover-name-text">{listing?.name ?? code}</span>
                    <span className="bar mover-bar">
                      <span
                        className={`bar-fill is-${tone}`}
                        style={{ width: `${Math.max(6, (Math.abs(ratio) / scale) * 100)}%` }}
                      />
                    </span>
                  </span>
                  <span className="mover-price">
                    <PriceCell className="num" value={quote.price} />
                  </span>
                  <Chip tone={rateTone(ratio)}>
                    <span aria-hidden="true">{trendArrow(ratio)}</span>
                    <span className="num">{rate(ratio)}</span>
                  </Chip>
                </Link>
              </li>
            );
          })}
        </ol>
      )}
    </div>
  );

  return (
    <div className="movers" {...holdHandlers}>
      {column(up, "up", "상승 상위")}
      {column(down, "down", "하락 상위")}
    </div>
  );
}
