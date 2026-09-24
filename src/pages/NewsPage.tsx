import { useState } from "react";
import { NewsFeed } from "../components/NewsFeed";
import { Panel } from "../components/Panel";
import { tickLabel } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { LISTING_BY_CODE } from "../market/universe";
import { VISIBLE_NEWS_SOURCES, type NewsSource } from "../market/types";

export function NewsPage() {
  const snapshot = useMarket();
  const [code, setCode] = useState<string | null>(null);
  const [source, setSource] = useState<"all" | NewsSource>("all");

  const visibleNews = snapshot.news.filter((item) => VISIBLE_NEWS_SOURCES.includes(item.source));
  const counts = new Map<string, number>();
  for (const item of visibleNews) counts.set(item.code, (counts.get(item.code) ?? 0) + 1);
  /* 소식이 있는 종목만 레일에 올린다. 0건 항목은 길찾기에 도움이 되지 않는다. */
  const rail = snapshot.codes
    .filter((item) => (counts.get(item) ?? 0) > 0)
    .sort((a, b) => (counts.get(b) ?? 0) - (counts.get(a) ?? 0));
  const items = visibleNews.filter((item) => {
    if (code && item.code !== code) return false;
    if (source !== "all" && item.source !== source) return false;
    return true;
  });
  const sourceOptions: Array<"all" | NewsSource> = ["all", "업데이트 노트", "미디어 보도"];

  return (
    <div className="page-stack">
      <div className="page-title">
        <h1>뉴스</h1>
        <span className="page-meta num">
          {visibleNews.length}건 · {tickLabel(snapshot.tickMs)}마다 시세 갱신
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
                  <span className="rail-count num">{visibleNews.length}</span>
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
          <div className="controls news-filters">
            <div className="control-group" role="group" aria-label="소식 종류">
              {sourceOptions.map((item) => (
                <button key={item} type="button" className={`filter-button${source === item ? " is-active" : ""}`} aria-pressed={source === item} onClick={() => setSource(item)}>
                  {item === "all" ? "전체 종류" : item}
                </button>
              ))}
            </div>
          </div>
          <Panel
            id="news-feed"
            title="뉴스"
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
