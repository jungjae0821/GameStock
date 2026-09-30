import { Chip, rateTone } from "./Chip";
import { GameIcon } from "./GameIcon";
import { Link } from "./Link";
import { RestrictionBadge } from "./RestrictionBadge";
import { dayLabel, kstDateTime, rate, trendArrow } from "../market/format";
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
          <NewsRow key={item.id} item={item} />
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
              <NewsRow key={item.id} item={item} />
            ))}
          </ol>
        </section>
      ))}
    </div>
  );
}

function NewsRow({ item }: { item: NewsItem }) {
  const snapshot = useMarket();
  const listing = LISTING_BY_CODE[item.code];
  const quote = snapshot.quotes[item.code];
  // 서버 뉴스에는 발행 시점 대비 현재가 변화율이 함께 온다.
  // 대체 엔진처럼 해당 값이 없는 경우에만 기존 가격 차이 계산을 사용한다.
  const effect = item.priceChangeRatio ?? newsEffect(item.priceAtPublish, quote);
  const ratio = quote ? sessionRate(quote) : 0;
  const effectTone = rateTone(effect);
  const isUpdateNote = item.source === "업데이트 노트";
  const updateBody = isUpdateNote
    ? (item.description ?? item.title).replace(/\n출처:\s*https?:\/\/x\.com\/\S+\s*$/, "").trim()
    : "";

  return (
    <li className="news-item">
      <div className="news-meta">
        <time className="num" dateTime={new Date(item.at).toISOString()}>
          {kstDateTime(item.at)}
        </time>
        <span className="news-source">{item.source}</span>
      </div>
      <h3 className="news-title">
        <Link to={`/market/${item.code}`}>{isUpdateNote ? updateBody : item.title}</Link>
      </h3>
      <div className="news-foot">
        <Chip className="news-game">
          <GameIcon code={item.code} name={listing?.name ?? item.code} size="news" />
          <span className="news-listing">{listing?.name ?? item.code}</span>
        </Chip>
        <RestrictionBadge code={item.code} />
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
