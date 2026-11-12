import { useId, useMemo } from "react";
import { Delta } from "../../components/Delta";
import { GameIcon } from "../../components/GameIcon";
import { Link } from "../../components/Link";
import { PriceCell } from "../../components/PriceCell";
import { RestrictionBadge } from "../../components/RestrictionBadge";
import { useMarket, useMarketApi } from "../../market/MarketProvider";
import { rate, shares, since, won } from "../../market/format";
import { marketOpinion, newsEffect, sessionRate, type OpinionTone } from "../../market/selectors";
import type { Quote } from "../../market/types";
import { LISTING_BY_CODE } from "../../market/universe";
import { MARKET_PATH, NEWS_PATH, navigate } from "../../router";

/* 거래 화면의 종목 고르기, 종목 머리, 시세 지표, 투자 의견, 관련 뉴스. */
export function StockSelector({ current }: { current: string }) {
  const snapshot = useMarket();
  return (
    <label className="nx-stock-selector">
      <span>종목 선택</span>
      <select value={current} onChange={(event) => navigate(MARKET_PATH(event.target.value))}>
        {snapshot.codes.map((code) => {
          const listing = LISTING_BY_CODE[code];
          return listing ? <option key={code} value={code}>{listing.name} · {code}{snapshot.watch.includes(code) ? " · 관심" : ""}</option> : null;
        })}
      </select>
    </label>
  );
}

export function StockHead({ code, quote }: { code: string; quote: Quote }) {
  const snapshot = useMarket();
  const api = useMarketApi();
  const listing = LISTING_BY_CODE[code];
  const watched = snapshot.watch.includes(code);
  return (
    <header className="nx-head">
      <div className="nx-head-id">
        <GameIcon code={code} name={listing.name} size="large" />
        <div>
          <p className="nx-head-sub"><span className="nx-code">{code}</span>{listing.publisher} · {listing.genre}</p>
          <h1 className="nx-head-name">{listing.name}</h1>
        </div>
        <button type="button" className={`nx-watch-toggle${watched ? " is-on" : ""}`} aria-label={`${listing.name} 관심 ${watched ? "해제" : "등록"}`} aria-pressed={watched} onClick={() => api.toggleWatch(code)}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.3-4.1 5.9-.9z" /></svg>
          {watched ? "관심 종목" : "관심 등록"}
        </button>
      </div>
      <div className="nx-head-price">
        <PriceCell className="nx-num nx-price" value={quote.price} />
        <Delta change={quote.price - quote.prevClose} ratio={sessionRate(quote)} className="nx-head-delta" />
        <RestrictionBadge code={code} />
      </div>
    </header>
  );
}

export function StockStats({ quote }: { quote: Quote }) {
  return (
    <section className="nx-trade-facts" aria-labelledby="nx-stock-info-title">
      <h2 id="nx-stock-info-title">종목 정보</h2>
      <dl className="nx-stats">
        <div><dt>전일</dt><dd className="nx-num">{won(quote.prevClose)}</dd></div>
        <div><dt>시가</dt><dd className="nx-num">{won(quote.open)}</dd></div>
        <OpinionStat quote={quote} />
        <div><dt>고가</dt><dd className="nx-num is-up">{won(quote.high)}</dd></div>
        <div><dt>저가</dt><dd className="nx-num is-down">{won(quote.low)}</dd></div>
        <div><dt>상한가</dt><dd className="nx-num">{won(quote.limitUp)}</dd></div>
        <div><dt>하한가</dt><dd className="nx-num">{won(quote.limitDown)}</dd></div>
        <div><dt>거래량</dt><dd className="nx-num">{shares(quote.volume)}</dd></div>
      </dl>
    </section>
  );
}

const OPINION_LABEL: Record<OpinionTone, string> = {
  buy: "매수 우위",
  sell: "매도 우위",
  neutral: "중립",
  caution: "변동성 주의",
};

const signalScore = (score: number) => (score > 0 ? "+1" : score < 0 ? "-1" : "0");

/** 시세 칸의 시장 의견. 누르거나 마우스를 올리면 어떤 신호로 나온 값인지 펼친다. */
function OpinionStat({ quote }: { quote: Quote }) {
  const snapshot = useMarket();
  const id = useId();
  const opinion = marketOpinion(quote, snapshot.news, snapshot.updatedAt || Date.now());
  const goodNews = (opinion.newsCount + opinion.newsBalance) / 2;
  const badNews = opinion.newsCount - goodNews;
  return (
    <div className="nx-opinion">
      <dt>의견</dt>
      <dd>
        <button type="button" className={`nx-opinion-value is-${opinion.tone}`} aria-describedby={id}
          onKeyDown={(event) => { if (event.key === "Escape") event.currentTarget.blur(); }}>
          {OPINION_LABEL[opinion.tone]}
        </button>
        <div className="nx-opinion-panel" id={id} role="tooltip">
          {opinion.caution && <p className="nx-opinion-caution">{opinion.caution}: 가격이 크게 흔들릴 수 있어 의견을 내지 않아요.</p>}
          <table>
            <tbody>
              <tr><th scope="row">최근 체결 <span className="nx-num">{opinion.printCount}</span>건</th><td>매수 <span className="nx-num">{Math.round(opinion.buyShare * 100)}%</span></td><td className="nx-num">{signalScore(opinion.strengthScore)}</td></tr>
              <tr><th scope="row">시가 대비</th><td className="nx-num">{rate(opinion.fromOpen)}</td><td className="nx-num">{signalScore(opinion.openScore)}</td></tr>
              <tr><th scope="row">24시간 뉴스</th><td>호재 <span className="nx-num">{goodNews}</span> · 악재 <span className="nx-num">{badNews}</span></td><td className="nx-num">{signalScore(opinion.newsScore)}</td></tr>
            </tbody>
          </table>
          <p className="nx-opinion-rule">합이 +2 이상이면 매수 우위, -2 이하면 매도 우위예요. 지금 거래 흐름을 요약한 값이고, 앞으로의 가격을 알려 주지는 않아요.</p>
        </div>
      </dd>
    </div>
  );
}

export function StockNews({ code, quote }: { code: string; quote: Quote }) {
  const snapshot = useMarket();
  const items = useMemo(() => snapshot.news.filter((item) => item.code === code).sort((a, b) => b.at - a.at).slice(0, 4), [snapshot.news, code]);
  return (
    <section className="nx-stock-news" aria-labelledby="nx-stock-news-title">
      <header><h2 id="nx-stock-news-title">관련 뉴스</h2><Link to="/news">전체 뉴스</Link></header>
      {items.length === 0 ? <p className="nx-muted">이 종목의 최근 뉴스가 아직 없어요.</p> : items.map((item) => {
        const effect = item.priceChangeRatio ?? newsEffect(item.priceAtPublish, quote);
        return <Link key={item.id} to={NEWS_PATH(item.id)} className="nx-stock-article">
          <span>{item.source} · {since(item.at, snapshot.updatedAt || Date.now())}</span>
          <h3>{item.title}</h3>
          <span>발행 후 <strong className={`nx-num ${effect > 0 ? "is-up" : effect < 0 ? "is-down" : ""}`}>{rate(effect)}</strong></span>
        </Link>;
      })}
    </section>
  );
}

/** 체결 테이프에 남기는 최근 체결 수. 칸 폭이 고정이라 넘치는 것은 오른쪽 끝에서 잘린다. */
