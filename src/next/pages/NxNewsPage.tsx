import { useEffect, useMemo, useRef, useState } from "react";
import { GameIcon } from "../../components/GameIcon";
import { Link } from "../../components/Link";
import { EXCERPT_PARAGRAPHS, hostOf, readDescription, useExcerpt, type Excerpt } from "../../components/NewsDetail";
import { SeriesChart } from "../../components/SeriesChart";
import { prefersReducedMotion } from "../../lib/motion";
import { kstDateTime, rate, recentSince, since, won } from "../../market/format";
import { useMarket, useMarketApi } from "../../market/MarketProvider";
import { newsEffect } from "../../market/selectors";
import { VISIBLE_NEWS_SOURCES, type NewsItem, type NewsSource } from "../../market/types";
import { LISTING_BY_CODE } from "../../market/universe";
import { navigate, NEWS_PATH, TRADE_PATH } from "../../router";
import { AnimatedNav } from "../AnimatedNav";

type SourceFilter = "all" | NewsSource;

/** 본문 화면과 목록이 같은 값이 되게 발행가와 실시간 현재가로 계산한다. 서버 값은 시세가 없을 때만 쓴다. */
function useEffectRatio(item: NewsItem): number {
  const quote = useMarket().quotes[item.code];
  return quote && item.priceAtPublish > 0 ? newsEffect(item.priceAtPublish, quote) : item.priceChangeRatio ?? 0;
}

function tone(value: number): string {
  return value > 0 ? "is-up" : value < 0 ? "is-down" : "";
}

/** 게임사가 직접 올린 공지에만 붙는 표시. 대부분인 언론 기사에는 줄마다 같은 글자를 반복하지 않는다. */
function SourceTag({ source }: { source: NewsSource }) {
  return source === "공식 발표" ? <span className="nx-news-kind is-official">{source}</span> : null;
}

function NewsRow({ item, selected, now }: { item: NewsItem; selected: boolean; now: number }) {
  const effect = useEffectRatio(item);
  return (
    <li data-news-id={item.id}>
      <Link to={NEWS_PATH(item.id)} className={`nx-news-row${selected ? " is-active" : ""}`} aria-current={selected ? "true" : undefined}>
        <GameIcon code={item.code} size="small" />
        <span className="nx-news-row-body">
          <span className="nx-news-row-title">{item.title}</span>
          <span className="nx-news-row-meta">
            <SourceTag source={item.source} />
            <span>{LISTING_BY_CODE[item.code]?.name ?? item.code} · {since(item.at, now)}</span>
          </span>
        </span>
        <span className={`nx-num nx-news-row-effect ${tone(effect)}`}>{rate(effect)}</span>
      </Link>
    </li>
  );
}

function Article({ item }: { item: NewsItem }) {
  const snapshot = useMarket();
  const api = useMarketApi();
  const heading = useRef<HTMLHeadingElement>(null);
  const quote = snapshot.quotes[item.code];
  const effect = useEffectRatio(item);
  const name = LISTING_BY_CODE[item.code]?.name ?? item.code;
  const watched = snapshot.watch.includes(item.code);
  const stored = useMemo(() => readDescription(item.description, item.title), [item.description, item.title]);
  const preloaded = useMemo<Excerpt | null>(
    () => (item.articleUrl ? { paragraphs: (item.excerpt ?? []).slice(0, EXCERPT_PARAGRAPHS), url: item.articleUrl } : null),
    [item.articleUrl, item.excerpt],
  );
  const { excerpt, loading } = useExcerpt(stored.link, preloaded);
  const paragraphs = excerpt?.paragraphs.length ? excerpt.paragraphs : loading ? [] : stored.paragraphs;
  const original = excerpt?.url ?? stored.link;
  const recent = recentSince(item.at, snapshot.updatedAt);

  /* 기사를 바꿀 때마다 제목으로 포커스를 옮겨 화면 낭독기가 새 본문을 읽게 한다. */
  useEffect(() => {
    heading.current?.focus({ preventScroll: true });
    heading.current?.closest(".nx-news-article")?.scrollTo({ top: 0 });
  }, [item.id]);

  return (
    <article className="nx-panel nx-news-article" aria-labelledby="nx-article-title">
      <header className="nx-article-head">
        <Link to={TRADE_PATH(item.code)} className="nx-article-game">
          <GameIcon code={item.code} size="small" />
          <span>{name}</span>
        </Link>
        <SourceTag source={item.source} />
        <Link to={NEWS_PATH(null)} className="nx-icon-link" aria-label="본문 닫기">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
        </Link>
      </header>

      <h2 id="nx-article-title" className="nx-article-title" ref={heading} tabIndex={-1}>{item.title}</h2>
      <p className="nx-article-time">
        <time dateTime={new Date(item.at).toISOString()}>{kstDateTime(item.at)}</time>
        {recent && <> · {recent}</>}
      </p>

      {/* 이 화면의 핵심: 뉴스가 나온 순간과 지금의 가격. */}
      <dl className="nx-article-stats">
        <div><dt>발행 시점</dt><dd className="nx-num">{won(item.priceAtPublish)}</dd></div>
        <div><dt>현재가</dt><dd className="nx-num">{quote ? won(quote.price) : "-"}</dd></div>
        <div><dt>발행 후 등락</dt><dd className={`nx-num ${tone(effect)}`}>{rate(effect)}</dd></div>
      </dl>

      <div className="nx-chart nx-article-chart integration-tools">
        {/* 기본은 6시간, 그보다 오래된 뉴스면 발행 시점의 흰 점이 보이는 가장 짧은 기간까지 넓힌다. */}
        <SeriesChart key={item.id} code={item.code} markers={[{ at: item.at, label: item.title }]} fitMarkers />
      </div>

      <section className="nx-article-body" aria-label="기사 첫 문단" aria-busy={loading}>
        {loading && <div className="nx-skeleton" aria-hidden="true"><span /><span /><span /><span /></div>}
        {paragraphs.map((paragraph, index) => <p key={index}>{paragraph}</p>)}
        {!loading && paragraphs.length === 0 && <p className="nx-article-empty">원문에서 본문을 가져오지 못했어요.</p>}
        {original && !loading && (
          <a href={original} className="nx-article-origin" target="_blank" rel="noopener noreferrer">
            {excerpt?.url ? `${hostOf(excerpt.url)}에서 전체 기사 읽기` : "원문 기사 열기"}
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M6 3.5h6.5V10M12.5 3.5L4 12" /></svg>
          </a>
        )}
      </section>

      <footer className="nx-article-actions">
        <Link to={TRADE_PATH(item.code)} className="nx-btn is-buy">{name} 거래하기</Link>
        <button type="button" className="nx-btn" aria-pressed={watched} onClick={() => api.toggleWatch(item.code)}>
          <svg className={watched ? "nx-star-on" : undefined} viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.3-4.1 5.9-.9z" /></svg>
          {watched ? "관심 해제" : "관심 등록"}
        </button>
      </footer>
    </article>
  );
}

export function NxNewsPage({ id }: { id: number | null }) {
  const snapshot = useMarket();
  const list = useRef<HTMLOListElement>(null);
  const scroller = useRef<HTMLElement>(null);
  /* 목록 아래에 더 읽을 뉴스가 남아 있는지. 스크롤바를 숨긴 대신 아래 화살표로 알린다. */
  const [more, setMore] = useState(false);
  const [source, setSource] = useState<SourceFilter>("all");
  const [watchOnly, setWatchOnly] = useState(false);
  const [game, setGame] = useState<string>("all");
  /* 실시간 갱신으로 목록에서 밀려난 기사도 읽던 중이면 계속 보여 준다. */
  const lastSeen = useRef<NewsItem | null>(null);
  const found = id === null ? null : snapshot.news.find((item) => item.id === id) ?? null;
  if (found) lastSeen.current = found;
  const selected = id === null ? null : found ?? (lastSeen.current?.id === id ? lastSeen.current : null);
  /* 출처 버튼의 건수는 종목·관심 조건을 먼저 적용한 뒤 센다. 눌렀을 때 보일 개수와 같아야 한다. */
  const scoped = snapshot.news.filter((item) => (game === "all" || item.code === game) && (!watchOnly || snapshot.watch.includes(item.code)));
  const counts: Record<SourceFilter, number> = { all: scoped.length, "공식 발표": 0, "미디어 보도": 0, "퍼블리셔 공지": 0, "커뮤니티 집계": 0, "스토어 지표": 0 };
  for (const item of scoped) counts[item.source] += 1;
  const items = scoped.filter((item) => source === "all" || item.source === source);
  const games = [...new Set(snapshot.news.map((item) => item.code))]
    .map((code) => ({ code, name: LISTING_BY_CODE[code]?.name ?? code }))
    .sort((left, right) => left.name.localeCompare(right.name, "ko"));
  const resetFilters = () => { setSource("all"); setWatchOnly(false); setGame("all"); };
  const now = snapshot.updatedAt || Date.now();

  /* Esc로 닫고, ↑↓로 이웃 기사로 옮긴다. 입력칸에서는 가로채지 않는다. */
  useEffect(() => {
    if (id === null) return;
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (target?.closest("input, textarea, select, [contenteditable='true']")) return;
      if (event.altKey || event.ctrlKey || event.metaKey) return;
      if (event.key === "Escape") {
        event.preventDefault();
        navigate(NEWS_PATH(null));
        return;
      }
      if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
      const index = items.findIndex((item) => item.id === id);
      const next = items[index + (event.key === "ArrowDown" ? 1 : -1)];
      if (index < 0 || !next) return;
      event.preventDefault();
      navigate(NEWS_PATH(next.id), { replace: true });
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [id, items]);

  useEffect(() => {
    const element = scroller.current;
    if (!element) return;
    const update = () => setMore(element.scrollHeight - element.scrollTop - element.clientHeight > 4);
    update();
    element.addEventListener("scroll", update, { passive: true });
    const observer = new ResizeObserver(update);
    observer.observe(element);
    if (list.current) observer.observe(list.current);
    return () => {
      element.removeEventListener("scroll", update);
      observer.disconnect();
    };
  }, [items.length]);

  const scrollMore = () => {
    const element = scroller.current;
    element?.scrollBy({ top: element.clientHeight * 0.8, behavior: prefersReducedMotion() ? "auto" : "smooth" });
  };

  useEffect(() => {
    if (id === null) return;
    list.current?.querySelector(`[data-news-id="${id}"]`)?.scrollIntoView({ block: "nearest" });
  }, [id]);

  return (
    <div className={`nx-screen nx-news-screen${id !== null ? " has-article" : ""}`}>
      <header className="nx-screen-head">
        <h1 className="nx-panel-title">뉴스</h1>
        <div className="nx-news-filters">
          <AnimatedNav as="div" fill activeKey={source} className="nx-seg" role="group" aria-label="출처">
            {(["all", ...VISIBLE_NEWS_SOURCES] as SourceFilter[]).map((value) => (
              <button key={value} type="button" className={source === value ? "is-active" : undefined} aria-pressed={source === value} onClick={() => setSource(value)}>
                {value === "all" ? "전체" : value}
                <span className="nx-seg-count nx-num">{counts[value]}</span>
              </button>
            ))}
          </AnimatedNav>
          <label className="nx-news-game">
            <span className="vh">종목</span>
            <select className="nx-select" value={game} onChange={(event) => setGame(event.target.value)}>
              <option value="all">모든 종목</option>
              {games.map((option) => <option key={option.code} value={option.code}>{option.name}</option>)}
            </select>
          </label>
          <label className="nx-check">
            <input type="checkbox" checked={watchOnly} onChange={(event) => setWatchOnly(event.target.checked)} />
            <span>관심 종목만</span>
          </label>
        </div>
      </header>

      <div className="nx-news-layout">
        <section className="nx-panel nx-news-list" aria-label="뉴스 목록" ref={scroller}>
          {items.length === 0 ? (
            <div className="nx-empty-state">
              <p>{snapshot.news.length === 0 ? "아직 들어온 뉴스가 없어요." : source === "공식 발표" ? "이 조건에는 게임사가 직접 올린 공지가 없어요." : "조건에 맞는 뉴스가 없어요."}</p>
              {snapshot.news.length > 0 && <button type="button" className="nx-link-button" onClick={resetFilters}>필터 초기화</button>}
            </div>
          ) : (
            <ol ref={list}>
              {items.map((item) => <NewsRow key={item.id} item={item} selected={item.id === id} now={now} />)}
            </ol>
          )}
          {more && (
            <div className="nx-news-more">
              <button type="button" onClick={scrollMore}>
                <span>아래로 계속</span>
                <svg viewBox="0 0 44 12" aria-hidden="true"><path d="M2 2l20 8 20-8" /></svg>
              </button>
            </div>
          )}
        </section>

        {id !== null && (selected ? <Article item={selected} /> : (
          <section className="nx-panel nx-news-article">
            <Link to={NEWS_PATH(null)} className="nx-link-button">목록으로</Link>
            <p className="nx-empty-state" role="status">이 뉴스를 찾을 수 없어요. 목록에서 다른 뉴스를 골라 주세요.</p>
          </section>
        ))}
        {id === null && (
          <aside className="nx-panel nx-news-hint">
            <p>왼쪽에서 뉴스를 고르면 발행 시점과 지금의 가격, 그 사이의 차트를 함께 볼 수 있어요.</p>
          </aside>
        )}
      </div>
    </div>
  );
}
