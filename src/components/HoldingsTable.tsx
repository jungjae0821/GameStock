import { Chip } from "./Chip";
import { Link } from "./Link";
import { Delta } from "./Delta";
import { PriceCell } from "./PriceCell";
import { plainRate, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { holdings, totals } from "../market/selectors";

export function HoldingsTable({ caption }: { caption: string }) {
  const snapshot = useMarket();
  const money = totals(snapshot);
  const rows = holdings(snapshot, money.total);
  if (rows.length === 0) return null;

  return (
    <div className="table-scroll">
      <table className="quote-table holdings-table">
        <caption className="vh">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">종목</th>
            <th scope="col">보유 수량</th>
            <th scope="col">평균 단가</th>
            <th scope="col">현재가</th>
            <th scope="col">평가액</th>
            <th scope="col">평가 손익</th>
            <th scope="col">비중</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.listing.code}>
              <th scope="row" className="cell-name">
                <Link className="name-link" to={`/market/${row.listing.code}`}>
                  <span className="code-badge" aria-hidden="true">
                    {row.listing.code}
                  </span>
                  <span className="name-stack">
                    <span className="name-text">{row.listing.name}</span>
                    <span className="name-sub">
                      {row.listing.genre} · {row.listing.publisher}
                    </span>
                  </span>
                </Link>
              </th>
              <td className="num">{row.position.qty}주</td>
              <td className="num">{won(row.position.avgCost)}</td>
              <td>
                <PriceCell className="num" value={row.quote.price} />
              </td>
              <td>
                <PriceCell className="num" value={row.value} />
              </td>
              <td>
                <Chip tone={row.pnl > 0 ? "up" : row.pnl < 0 ? "down" : "flat"}>
                  <Delta change={row.pnl} ratio={row.pnlRate} />
                </Chip>
              </td>
              <td>
                <span className="volume-cell">
                  <span className="num volume-value">{plainRate(row.weight, 1)}</span>
                  <span className="bar volume-bar" aria-hidden="true">
                    <span className="bar-fill" style={{ width: `${Math.max(2, row.weight * 100)}%` }} />
                  </span>
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
