import { Delta } from "./Delta";
import { GameIcon } from "./GameIcon";
import { Link } from "./Link";
import { won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { holdings, totals } from "../market/selectors";

export function HoldingsTable({ caption }: { caption: string }) {
  const snapshot = useMarket();
  const rows = holdings(snapshot, totals(snapshot).total);
  if (rows.length === 0) return null;

  return (
    <div className="table-scroll">
      <table className="table">
        <caption className="vh">{caption}</caption>
        <thead>
          <tr>
            <th scope="col" className="cell-name">
              게임
            </th>
            <th scope="col" className="cell-num">
              수량
            </th>
            <th scope="col" className="cell-num">
              평가액
            </th>
            <th scope="col" className="cell-num">
              손익
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.listing.code}>
              <th scope="row" className="cell-name">
                <Link className="name-link" to={`/market/${row.listing.code}`}>
                  <GameIcon code={row.listing.code} />
                  <span className="name-text">{row.listing.name}</span>
                </Link>
              </th>
              <td className="cell-num num">{row.position.qty}주</td>
              <td className="cell-num num">{won(row.value)}</td>
              <td className="cell-num">
                <Delta change={row.pnl} ratio={row.pnlRate} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
