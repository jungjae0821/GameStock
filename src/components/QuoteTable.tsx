import { Delta } from "./Delta";
import { GameIcon } from "./GameIcon";
import { Link } from "./Link";
import { PriceCell } from "./PriceCell";
import { RestrictionBadge } from "./RestrictionBadge";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";
import type { SortDirection, SortKey } from "../market/selectors";

interface Props {
  codes: string[];
  caption: string;
  selected?: string | null;
  onSelect?: (code: string) => void;
  watchable?: boolean;
  sort?: { key: SortKey; direction: SortDirection };
  onSort?: (key: SortKey) => void;
}

export function QuoteTable({ codes, caption, selected, onSelect, watchable = false, sort, onSort }: Props) {
  const header = (key: SortKey, label: string, className: string) => {
    const active = sort?.key === key;
    return (
      <th
        scope="col"
        className={className}
        aria-sort={active ? (sort.direction === "asc" ? "ascending" : "descending") : "none"}
      >
        {onSort ? (
          <button type="button" className={`sort-button${active ? " is-active" : ""}`} onClick={() => onSort(key)}>
            {label}
            {active && <span aria-hidden="true">{sort.direction === "asc" ? " ↑" : " ↓"}</span>}
          </button>
        ) : (
          label
        )}
      </th>
    );
  };

  return (
    <div className="table-scroll">
      <table className="table quote-table">
        <caption className="vh">{caption}</caption>
        <thead>
          <tr>
            {watchable && (
              <th scope="col" className="cell-watch">
                <span className="vh">관심</span>
              </th>
            )}
            {header("name", "게임", "cell-name")}
            {header("price", "현재가", "cell-num")}
            {header("rate", "등락", "cell-num")}
          </tr>
        </thead>
        <tbody>
          {codes.map((code) => (
            <QuoteRow key={code} code={code} selected={selected === code} onSelect={onSelect} watchable={watchable} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

interface RowProps {
  code: string;
  selected: boolean;
  onSelect?: (code: string) => void;
  watchable: boolean;
}

function QuoteRow({ code, selected, onSelect, watchable }: RowProps) {
  const snapshot = useMarket();
  const api = useMarketApi();
  const listing = LISTING_BY_CODE[code];
  const quote = snapshot.quotes[code];
  if (!listing || !quote) return null;

  const ratio = sessionRate(quote);
  const watched = snapshot.watch.includes(code);

  return (
    <tr className={selected ? "is-selected" : undefined} onClick={() => onSelect?.(code)}>
      {watchable && (
        <td className="cell-watch">
          <button
            type="button"
            className="watch-button"
            aria-pressed={watched}
            aria-label={`${listing.name} 관심 ${watched ? "해제" : "등록"}`}
            onClick={(event) => {
              event.stopPropagation();
              api.toggleWatch(code);
            }}
          >
            {watched ? "★" : "☆"}
          </button>
        </td>
      )}
      <th scope="row" className="cell-name">
        <div className="stock-name-group">
        <Link
          className="name-link"
          to={`/market/${code}`}
          onClick={(event) => {
            if (!onSelect) return;
            event.stopPropagation();
            onSelect(code);
          }}
        >
          <GameIcon code={code} />
          <span className="name-text">{listing.name}</span>
        </Link>
        {(quote.restriction?.kind === "DYNAMIC_VI" || quote.restriction?.kind === "STATIC_VI") && <RestrictionBadge code={code} />}
        </div>
      </th>
      <td className="cell-num">
        <PriceCell className="num" value={quote.price} />
      </td>
      <td className="cell-num">
        <Delta change={ratio} ratio={ratio} showAmount={false} />
      </td>
    </tr>
  );
}
