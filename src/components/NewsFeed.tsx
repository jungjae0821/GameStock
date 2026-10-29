import { useMemo } from "react";
import { Delta } from "./Delta";
import { GameIcon } from "./GameIcon";
import { Link } from "./Link";
import { since } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { newsEffect } from "../market/selectors";
import type { NewsSource } from "../market/types";
import { LISTING_BY_CODE } from "../market/universe";
import { NEWS_PATH } from "../router";

interface Props {
  limit?: number;
  source?: NewsSource;
  emptyMessage?: string;
  /** 뉴스 화면에서 상세 패널과 나란히 놓일 때의 좁은 카드. */
  compact?: boolean;
  selectedId?: number | null;
}

/** 소식 목록. 게임 아이콘, 제목, 발행 후 등락만 보여 준다. 제목을 누르면 뉴스 화면에서 본문을 연다. */
export function NewsFeed({ limit, source, emptyMessage, compact = false, selectedId = null }: Props) {
  const snapshot = useMarket();
  const items = useMemo(() => {
    const filtered = source ? snapshot.news.filter((item) => item.source === source) : snapshot.news;
    return limit ? filtered.slice(0, limit) : filtered;
  }, [snapshot.news, source, limit]);

  if (items.length === 0) return <p className="empty">{emptyMessage ?? "표시할 소식이 없습니다."}</p>;

  return (
    <ol className={`news-list${compact ? " is-compact" : ""}`}>
      {items.map((item) => {
        const quote = snapshot.quotes[item.code];
        /* 본문 화면과 같은 값이 되게 발행가와 실시간 현재가로 계산한다. 서버 값은 시세가 없을 때만 쓴다. */
        const effect = quote && item.priceAtPublish > 0 ? newsEffect(item.priceAtPublish, quote) : item.priceChangeRatio ?? 0;
        const selected = item.id === selectedId;
        return (
          <li key={item.id} className={`news-item${selected ? " is-selected" : ""}`} data-news-id={item.id}>
            <GameIcon code={item.code} size={compact ? "small" : "large"} />
            <div className="news-body">
              <h3 className="news-title">
                <Link to={NEWS_PATH(item.id)} aria-current={selected ? "true" : undefined}>
                  {item.title}
                </Link>
              </h3>
              <p className="news-meta">
                {LISTING_BY_CODE[item.code]?.name ?? item.code} · {since(item.at, snapshot.updatedAt)}
              </p>
            </div>
            <Delta change={effect} ratio={effect} showAmount={false} />
          </li>
        );
      })}
    </ol>
  );
}
