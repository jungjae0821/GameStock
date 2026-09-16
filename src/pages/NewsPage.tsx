import { useState } from "react";
import { NewsFeed } from "../components/NewsFeed";
import { Panel } from "../components/Panel";
import { tickLabel } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { LISTING_BY_CODE } from "../market/universe";

export function NewsPage() {
  const snapshot = useMarket();
  const [code, setCode] = useState<string | null>(null);

  const counts = new Map<string, number>();
  for (const item of snapshot.news) counts.set(item.code, (counts.get(item.code) ?? 0) + 1);
  /* 소식이 있는 종목만 레일에 올린다. 0건 항목은 길찾기에 도움이 되지 않는다. */
  const rail = snapshot.codes
    .filter((item) => (counts.get(item) ?? 0) > 0)
    .sort((a, b) => (counts.get(b) ?? 0) - (counts.get(a) ?? 0));
  const items = code ? snapshot.news.filter((item) => item.code === code) : snapshot.news;

  return (
    <div className="page-stack">
      <div className="page-title">
        <h1>속보</h1>
        <span className="page-meta num">
          {snapshot.news.length}건 · {tickLabel(snapshot.tickMs)}마다 시세 갱신
        </span>
      </div>

      <div className="news-layout">
        <nav className="news-rail" aria-label="종목별 소식">
          <Panel id="news-rail" title="종목별">
            <ul>
              <li>
                <button
                  type="button"
                  className={`rail-button${code === null ? " is-active" : ""}`}
                  aria-pressed={code === null}
                  onClick={() => setCode(null)}
                >
                  <span className="rail-name">전체</span>
                  <span className="rail-count num">{snapshot.news.length}</span>
                </button>
              </li>
              {rail.map((item) => (
                <li key={item}>
                  <button
                    type="button"
                    className={`rail-button${code === item ? " is-active" : ""}`}
                    aria-pressed={code === item}
                    onClick={() => setCode(item)}
                  >
                    <span className="code-badge is-small" aria-hidden="true">
                      {item}
                    </span>
                    <span className="rail-name">{LISTING_BY_CODE[item]?.name ?? item}</span>
                    <span className="rail-count num">{counts.get(item) ?? 0}</span>
                  </button>
                </li>
              ))}
            </ul>
          </Panel>
        </nav>

        <div className="news-main">
          <Panel
            id="news-feed"
            title="속보"
            meta={code ? `${LISTING_BY_CODE[code]?.name ?? code} · ${items.length}건` : `${items.length}건`}
          >
            <NewsFeed
              items={items}
              grouped
              emptyMessage={code ? "이 종목의 소식이 아직 없습니다." : "표시할 소식이 없습니다."}
            />
          </Panel>
        </div>
      </div>
    </div>
  );
}
