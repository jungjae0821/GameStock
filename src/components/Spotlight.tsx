import { Delta } from "./Delta";
import { GameIcon } from "./GameIcon";
import { Link } from "./Link";
import { useMarket } from "../market/MarketProvider";
import { movers, sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";

/** 많이 오른 게임을 아트워크로 보여 준다. 오른 게임이 없으면 구획째 숨긴다. */
export function Spotlight() {
  const snapshot = useMarket();
  const codes = movers(snapshot, 1, 6).filter((code) => sessionRate(snapshot.quotes[code]) > 0);
  if (codes.length === 0) return null;

  return (
    <section className="panel" aria-labelledby="spot-title">
      <div className="panel-head">
        <h2 id="spot-title" className="panel-title">
          많이 오른 게임
        </h2>
      </div>
      <ol className="spot">
        {codes.map((code) => {
          const ratio = sessionRate(snapshot.quotes[code]);
          return (
            <li key={code}>
              <Link className="spot-item" to={`/market/${code}`}>
                <GameIcon code={code} size="xl" />
                <span className="spot-name">{LISTING_BY_CODE[code]?.name ?? code}</span>
                <Delta change={ratio} ratio={ratio} showAmount={false} />
              </Link>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
