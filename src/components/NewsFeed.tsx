import { Chip, rateTone } from "./Chip";
import { Link } from "./Link";
import { dayLabel, rate, since, trendArrow } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { newsEffect, sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";
import type { NewsItem } from "../market/types";

interface Props {
  items: NewsItem[];
  /** 날짜별로 묶어서 볼 때 true. */
  grouped?: boolean;
  emptyMessage?: string;
}

export function NewsFeed({ items, grouped = false, emptyMessage }: Props) {
  const snapshot = useMarket();

  if (items.length === 0) {
    return <p className="empty">{emptyMessage ?? "표시할 소식이 없습니다."}</p>;
  }

  if (!grouped) {
    return (
      <ol className="news-list">
        {items.map((item) => (
          <NewsRow key={item.id} item={item} now={snapshot.updatedAt} />
        ))}
      </ol>
    );
  }

  const groups: { label: string; items: NewsItem[] }[] = [];
  for (const item of items) {
    const label = dayLabel(item.at, snapshot.updatedAt);
    const last = groups[groups.length - 1];
    if (last && last.label === label) last.items.push(item);
    else groups.push({ label, items: [item] });
  }

  return (
    <div className="news-days">
      {groups.map((group) => (
        <section key={group.label} className="news-day" aria-labelledby={`day-${group.label}`}>
          <h3 id={`day-${group.label}`} className="news-day-label">
            {group.label}
            <span className="num">{group.items.length}건</span>
          </h3>
          <ol className="news-list">
            {group.items.map((item) => (
              <NewsRow key={item.id} item={item} now={snapshot.updatedAt} />
            ))}
          </ol>
        </section>
      ))}
    </div>
  );
}

function NewsRow({ item, now }: { item: NewsItem; now: number }) {
  const snapshot = useMarket();
  const listing = LISTING_BY_CODE[item.code];
  const quote = snapshot.quotes[item.code];
  const effect = newsEffect(item.priceAtPublish, quote);
  const ratio = quote ? sessionRate(quote) : 0;
  const effectTone = rateTone(effect);

  return (
    <li className="news-item">
      <div className="news-meta">
        <time className="num" dateTime={new Date(item.at).toISOString()}>
          {since(item.at, now)}
        </time>
        <span className="news-source">{item.source}</span>
      </div>
      <h3 className="news-title">
        <Link to={`/market/${item.code}`}>{item.title}</Link>
      </h3>
      <div className="news-foot">
        <Chip>
          <span className="code-text" aria-hidden="true">
            {item.code}
          </span>
          <span className="news-listing">{listing?.name ?? item.code}</span>
        </Chip>
        <Chip tone={rateTone(ratio)}>
          <span aria-hidden="true">{trendArrow(ratio)}</span>
          <span className="num">{rate(ratio)}</span>
        </Chip>
        <span className={`news-effect is-${effectTone}`}>
          소식 후 <span className="num">{rate(effect)}</span>
        </span>
      </div>
    </li>
  );
}
