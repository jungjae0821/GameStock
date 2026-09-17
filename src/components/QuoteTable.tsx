import { Chip, rateTone } from "./Chip";
import { Link } from "./Link";
import { PriceCell } from "./PriceCell";
import { Sparkline } from "./Sparkline";
import { rate, shares, trendArrow } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";
import type { SortDirection, SortKey } from "../market/selectors";

/* 열 이름 뒤에 붙으므로 같은 단어를 반복하지 않는다. */
const SORT_LABEL: Record<SortKey, { asc: string; desc: string }> = {
  name: { asc: "가나다순", desc: "역순" },
  price: { asc: "낮은순", desc: "높은순" },
  rate: { asc: "하락순", desc: "상승순" },
  volume: { asc: "적은순", desc: "많은순" },
};

interface Props {
  codes: string[];
  caption: string;
  /** 그래프용 시계열을 외부에서 고정할 때 사용한다. 현재가·거래량은 실시간 스냅샷을 계속 사용한다. */
  chartSeries?: Record<string, number[]>;
  selected?: string | null;
  onSelect?: (code: string) => void;
  onToggleWatch?: boolean;
  sort?: { key: SortKey; direction: SortDirection };
  onSort?: (key: SortKey) => void;
  /** 거래량 열에 막대를 그릴 기준값. 없으면 계산한다. */
  volumeScale?: number;
}

export function QuoteTable({
  codes,
  caption,
  chartSeries,
  selected,
  onSelect,
  onToggleWatch = false,
  sort,
  onSort,
  volumeScale,
}: Props) {
  const snapshot = useMarket();
  const scale = volumeScale ?? Math.max(1, ...codes.map((code) => snapshot.quotes[code]?.volume ?? 0));

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
            {active && <span className="sort-note"> {SORT_LABEL[key][sort.direction]}</span>}
          </button>
        ) : (
          label
        )}
      </th>
    );
  };

  return (
    <div className="table-scroll">
      <table className="quote-table">
        <caption className="vh">{caption}</caption>
        <thead>
          <tr>
            {onToggleWatch && (
              <th scope="col" className="cell-watch">
                <span className="vh">관심</span>
              </th>
            )}
            {header("name", "종목", "cell-name")}
            {header("price", "현재가", "cell-price")}
            {header("rate", "등락", "cell-rate")}
            <th scope="col" className="cell-chart">
              세션 차트
            </th>
            {header("volume", "거래량", "cell-volume")}
          </tr>
        </thead>
        <tbody>
          {codes.map((code) => (
            <QuoteRow
              key={code}
              code={code}
              selected={selected === code}
              onSelect={onSelect}
              showWatch={onToggleWatch}
              volumeScale={scale}
              chartSeries={chartSeries}
            />
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
  showWatch: boolean;
  volumeScale: number;
  chartSeries?: Record<string, number[]>;
}

function QuoteRow({ code, selected, onSelect, showWatch, volumeScale, chartSeries }: RowProps) {
  const snapshot = useMarket();
  const api = useMarketApi();
  const listing = LISTING_BY_CODE[code];
  const quote = snapshot.quotes[code];
  if (!listing || !quote) return null;

  const ratio = sessionRate(quote);
  const tone = rateTone(ratio);
  const sparkTone: "up" | "down" | "flat" = ratio > 0 ? "up" : ratio < 0 ? "down" : "flat";
  const watched = snapshot.watch.includes(code);

  return (
    <tr className={selected ? "is-selected" : undefined} onClick={() => onSelect?.(code)}>
      {showWatch && (
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
          <span className="code-badge" aria-hidden="true">
            {code}
          </span>
          <span className="name-stack">
            <span className="name-text">{listing.name}</span>
            <span className="name-sub">
              {listing.genre} · {listing.publisher}
            </span>
          </span>
        </Link>
      </th>
      <td className="cell-price">
        <PriceCell className="num" value={quote.price} />
      </td>
      <td className="cell-rate">
        <Chip tone={tone}>
          <span aria-hidden="true">{trendArrow(ratio)}</span>
          <span className="num">{rate(ratio)}</span>
        </Chip>
      </td>
      <td className="cell-chart">
        <Sparkline series={chartSeries?.[code] ?? quote.series} prevClose={quote.prevClose} tone={sparkTone} />
      </td>
      <td className="cell-volume">
        <span className="volume-cell">
          <span className="num volume-value">{shares(quote.volume)}</span>
          <span className="bar volume-bar" aria-hidden="true">
            <span
              className="bar-fill"
              style={{ width: `${Math.max(2, (quote.volume / volumeScale) * 100)}%` }}
            />
          </span>
        </span>
      </td>
    </tr>
  );
}
