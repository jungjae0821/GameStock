import { useId, useMemo, useState } from "react";
import { Delta } from "../../components/Delta";
import { GameIcon } from "../../components/GameIcon";
import { Link } from "../../components/Link";
import { rate, won } from "../../market/format";
import { useMarket } from "../../market/MarketProvider";
import { sessionRate } from "../../market/selectors";
import { LISTING_BY_CODE } from "../../market/universe";
import { MARKET_PATH, TALK_PATH, navigate } from "../../router";
import { StockChat } from "../trade/StockChat";
import { StockTalk } from "../trade/StockTalk";

/** 종목토론 화면. 왼쪽에서 종목을 고르고, 가운데에 글, 오른쪽에 실시간 채팅을 둔다. */
export function NxTalkPage({ code }: { code: string | null }) {
  const snapshot = useMarket();
  const formId = useId();
  const [query, setQuery] = useState("");
  const codes = snapshot.codes.filter((item) => LISTING_BY_CODE[item]);
  /* 주소에 종목이 없거나 없는 종목이면 목록 첫 종목을 보여 준다. */
  const current = code && LISTING_BY_CODE[code] ? code : codes[0] ?? null;
  const shown = useMemo(() => {
    const text = query.trim().toLowerCase();
    if (!text) return codes;
    return codes.filter((item) => item.toLowerCase().includes(text) || LISTING_BY_CODE[item].name.toLowerCase().includes(text));
  }, [codes, query]);

  const listing = current ? LISTING_BY_CODE[current] : null;
  const quote = current ? snapshot.quotes[current] : undefined;

  return (
    <div className="nx-screen nx-talk-page">
      <header className="nx-screen-head">
        <div>
          <h1 className="nx-page-title">종목토론</h1>
          <p className="nx-screen-sub">종목마다 글을 남기고 실시간으로 이야기할 수 있어요. 글은 첫 글이 올라온 지 7일 뒤 답글과 함께 사라져요.</p>
        </div>
      </header>

      <div className="nx-talk-layout">
        <nav className="nx-talk-stocks" aria-labelledby={`${formId}-stocks`}>
          <h2 id={`${formId}-stocks`} className="vh">종목 고르기</h2>
          <label htmlFor={`${formId}-search`} className="vh">종목 찾기</label>
          <input id={`${formId}-search`} className="nx-input nx-talk-search" type="search" value={query} placeholder="게임 이름이나 종목 코드"
            onChange={(event) => setQuery(event.target.value)} />
          {shown.length === 0 && <p className="nx-muted nx-talk-stocks-empty">찾는 종목이 없어요. 이름이나 종목 코드를 다시 확인해 주세요.</p>}
          <ul>
            {shown.map((item) => {
              const itemQuote = snapshot.quotes[item];
              const ratio = itemQuote ? sessionRate(itemQuote) : 0;
              return (
                <li key={item}>
                  <Link to={TALK_PATH(item)} className={`nx-talk-stock${item === current ? " is-active" : ""}`} aria-current={item === current ? "page" : undefined}>
                    <GameIcon code={item} size="table" />
                    <span className="nx-talk-stock-name">{LISTING_BY_CODE[item].name}</span>
                    <span className={`nx-num nx-talk-stock-rate is-${ratio > 0 ? "up" : ratio < 0 ? "down" : "flat"}`}>{rate(ratio)}</span>
                  </Link>
                </li>
              );
            })}
          </ul>
        </nav>

        {/* 좁은 화면에서는 왼쪽 목록 대신 선택 상자로 종목을 바꾼다. */}
        <div className="nx-talk-picker">
          <label htmlFor={`${formId}-picker`}>종목</label>
          <select id={`${formId}-picker`} className="nx-select" value={current ?? ""} onChange={(event) => navigate(TALK_PATH(event.target.value))}>
            {codes.map((item) => <option key={item} value={item}>{LISTING_BY_CODE[item].name}</option>)}
          </select>
        </div>

        {current && listing ? (
          <>
            <section className="nx-talk-main" aria-label={`${listing.name} 종목토론`}>
              <header className="nx-talk-stock-head">
                <GameIcon code={current} name={listing.name} size="large" />
                <div className="nx-talk-stock-id">
                  <h2>{listing.name}</h2>
                  {quote && <p className="nx-num">{won(quote.price)} <Delta change={quote.price - quote.prevClose} ratio={sessionRate(quote)} showAmount={false} /></p>}
                </div>
                <Link to={MARKET_PATH(current)} className="nx-btn">거래 화면</Link>
              </header>
              <StockTalk key={current} code={current} name={listing.name} />
            </section>
            <StockChat key={`chat-${current}`} code={current} name={listing.name} />
          </>
        ) : <p className="nx-empty-state">상장된 종목이 아직 없어요. 종목이 등록되면 여기서 토론할 수 있어요.</p>}
      </div>
    </div>
  );
}
