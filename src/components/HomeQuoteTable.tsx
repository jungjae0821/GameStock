import { Delta } from "./Delta";
import { PriceCell } from "./PriceCell";
import { GameIcon } from "./GameIcon";
import { Link } from "./Link";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";

interface Props {
  codes: string[];
  caption: string;
  selected?: string | null;
  onSelect?: (code: string) => void;
  watchable?: boolean;
}

/** 홈 "인기 종목"용 단순 시세표: 게임 · 현재가 · 등락. */
export function HomeQuoteTable({ codes, caption, selected, onSelect, watchable = false }: Props) {
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
            <th scope="col" className="cell-name">
              게임
            </th>
            <th scope="col" className="cell-num">
              현재가
            </th>
            <th scope="col" className="cell-num">
              등락
            </th>
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
        <Link
          className="name-link"
          to={`/market/${code}`}
          onClick={(event) => {
            if (!onSelect) return;
            event.stopPropagation();
            onSelect(code);
          }}
        >
          <GameIcon code={code} name={listing.name} />
          <span className="name-text">{listing.name}</span>
        </Link>
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
