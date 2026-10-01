import { useState } from "react";
import { useHeldOrder, useHoldWhilePointing } from "../lib/useHeldOrder";
import { QuoteTable } from "../components/QuoteTable";
import { StockDetail } from "../components/StockDetail";
import { useMarket } from "../market/MarketProvider";
import { sessionRate, sortCodes } from "../market/selectors";
import type { SortDirection, SortKey } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";
import { navigate } from "../router";

type View = "all" | "up" | "down" | "watch";

const VIEWS: { id: View; label: string }[] = [
  { id: "all", label: "전체" },
  { id: "up", label: "상승" },
  { id: "down", label: "하락" },
  { id: "watch", label: "관심" },
];

export function MarketPage({ ticker }: { ticker: string | null }) {
  const snapshot = useMarket();
  const [view, setView] = useState<View>("all");
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<{ key: SortKey; direction: SortDirection }>({ key: "volume", direction: "desc" });

  const matches = (code: string): boolean => {
    const quote = snapshot.quotes[code];
    const listing = LISTING_BY_CODE[code];
    if (!quote || !listing) return false;
    if (view === "up" && sessionRate(quote) <= 0) return false;
    if (view === "down" && sessionRate(quote) >= 0) return false;
    if (view === "watch" && !snapshot.watch.includes(code)) return false;
    const needle = query.trim().toLowerCase();
    if (!needle) return true;
    return [listing.name, listing.code, listing.publisher].some((field) => field.toLowerCase().includes(needle));
  };

  const [hold, holdHandlers] = useHoldWhilePointing();
  const codes = useHeldOrder(sortCodes(snapshot, sort.key, sort.direction).filter(matches), hold);

  const changeSort = (key: SortKey) => {
    setSort((current) =>
      current.key === key
        ? { key, direction: current.direction === "desc" ? "asc" : "desc" }
        : { key, direction: key === "name" ? "asc" : "desc" },
    );
  };

  return (
    <div className="page-stack">
      <h1 className="page-title">시장</h1>

      <div className="controls">
        <div className="control-group" role="group" aria-label="종목 필터">
          {VIEWS.map((item) => (
            <button
              key={item.id}
              type="button"
              className={`filter-button${view === item.id ? " is-active" : ""}`}
              aria-pressed={view === item.id}
              onClick={() => setView(item.id)}
            >
              {item.label}
            </button>
          ))}
        </div>
        <div className="control-field">
          <label htmlFor="market-search" className="vh">
            종목 검색
          </label>
          <input
            id="market-search"
            type="search"
            value={query}
            placeholder="게임 이름 검색"
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
      </div>

      <div className={`market-layout${ticker ? " has-detail" : ""}`}>
        <div className="market-list" {...holdHandlers}>
          {codes.length === 0 ? (
            <div className="empty">
              <p>조건에 맞는 종목이 없습니다.</p>
              <button
                type="button"
                className="text-button"
                onClick={() => {
                  setView("all");
                  setQuery("");
                }}
              >
                필터 초기화
              </button>
            </div>
          ) : (
            <QuoteTable
              codes={codes}
              caption="상장 종목 시세"
              selected={ticker}
              onSelect={(code) => navigate(`/market/${code}`)}
              watchable
              sort={sort}
              onSort={changeSort}
            />
          )}
        </div>

        {ticker && (
          <aside className="market-detail" aria-label="종목 상세">
            <StockDetail code={ticker} />
          </aside>
        )}
      </div>
    </div>
  );
}
