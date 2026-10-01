import { Delta } from "./Delta";
import { GameIcon } from "./GameIcon";
import { Link } from "./Link";
import { since } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { newsEffect } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";

interface Props {
  limit?: number;
  emptyMessage?: string;
}

/** 소식 목록. 게임 아이콘, 제목, 발행 후 등락만 보여 준다. */
export function NewsFeed({ limit, emptyMessage }: Props) {
  const snapshot = useMarket();
  const items = limit ? snapshot.news.slice(0, limit) : snapshot.news;

  if (items.length === 0) return <p className="empty">{emptyMessage ?? "표시할 소식이 없습니다."}</p>;

  return (
    <ol className="news-list">
      {items.map((item) => {
        const quote = snapshot.quotes[item.code];
        const effect = item.priceChangeRatio ?? newsEffect(item.priceAtPublish, quote);
        return (
          <li key={item.id} className="news-item">
            <GameIcon code={item.code} size="large" />
            <div className="news-body">
              <h3 className="news-title">
                <Link to={`/market/${item.code}`}>{item.title}</Link>
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
