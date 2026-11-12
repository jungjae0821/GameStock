import { useRef } from "react";
import { Delta } from "../../components/Delta";
import { PriceCell } from "../../components/PriceCell";
import { won } from "../../market/format";
import { sessionRate, strengthRatio } from "../../market/selectors";
import type { Quote } from "../../market/types";
import { PrintTape, TAPE_LIMIT, printKeys, useArrival } from "./PrintTape";

export function Book({ quote, onPriceSelect }: { quote: Quote; onPriceSelect: (price: number) => void }) {
  const asks = [...quote.asks].sort((a, b) => a.price - b.price).slice(0, 5).reverse();
  const bids = [...quote.bids].sort((a, b) => b.price - a.price).slice(0, 5);
  const depth = Math.max(1, ...asks.map((level) => level.qty), ...bids.map((level) => level.qty));
  const strength = strengthRatio(quote);
  const keys = printKeys(quote.prints.slice(0, TAPE_LIMIT));
  const arrival = useArrival(quote.code, keys);
  /* 이번 묶음의 체결. 현재가 줄을 그 방향 색으로 한 번 쓸고, 묶음 수량을 잠깐 띄운다. */
  const hits = quote.prints.slice(0, TAPE_LIMIT).filter((_, index) => arrival.fresh.includes(keys[index]));
  const hitSide = hits[0]?.side;
  const hitQty = hits.filter((print) => print.side === hitSide).reduce((sum, print) => sum + print.qty, 0);

  /*
   * 가격별 잔량과 마지막 변화 방향. 잔량이 늘거나 줄면 그 숫자만 한 번 반짝인다(숫자 칸의 key가 바뀌어 다시 붙는다).
   * 잔량이 그대로인 다시 그리기에서도 방향 클래스를 유지해야 진행 중인 반짝임이 끊기지 않는다.
   */
  const lastQty = useRef(new Map<string, { qty: number; change: string }>());
  const changeOf = (side: "ask" | "bid", level: { price: number; qty: number }) => {
    const id = `${side}:${level.price}`;
    const before = lastQty.current.get(id);
    const change = !before ? "" : before.qty === level.qty ? before.change : level.qty > before.qty ? " is-more" : " is-less";
    lastQty.current.set(id, { qty: level.qty, change });
    return change;
  };

  const row = (side: "ask" | "bid", level: { price: number; qty: number } | undefined, gridRow: number) => {
    const change = level ? changeOf(side, level) : "";
    return (
      <div className={`nx-book-row is-${side}`} style={{ gridRow }} key={`${side}-${gridRow}`} role="row">
        {level ? <>
          <button type="button" className="nx-book-price nx-num" onClick={() => onPriceSelect(level.price)} aria-label={`${side === "ask" ? "매도" : "매수"} 호가 ${won(level.price)}을 주문 가격으로`}>
            {won(level.price).replace("₩", "")}
          </button>
          <span className="nx-book-qty nx-num" role="cell">
            <span className="nx-book-bar" style={{ width: `${(level.qty / depth) * 100}%` }} aria-hidden="true" />
            <span key={`${level.price}:${level.qty}`} className={`nx-book-count${change}`}>{level.qty.toLocaleString("ko-KR")}</span>
            <span className="nx-book-unit">주</span>
          </span>
        </> : <span className="nx-book-empty" aria-hidden="true">-</span>}
      </div>
    );
  };

  return (
    <section className="nx-panel nx-book" aria-labelledby="nx-book-title">
      <header className="nx-panel-head">
        <h2 id="nx-book-title" className="nx-panel-title">호가 · 체결</h2>
        <span className="nx-muted" title={`최근 체결 ${quote.prints.length}건 기준`}>체결 강도 <span className="nx-num">{strength === null ? "-" : `${Math.round(strength * 100)}%`}</span></span>
      </header>
      <div className="nx-ladder" role="table" aria-label="매도 5단계, 현재가, 매수 5단계">
        {Array.from({ length: 5 }, (_, index) => row("ask", asks[index - (5 - asks.length)], index + 1))}
        <div className="nx-judge" role="row" aria-label={`현재가 ${won(quote.price)}`}>
          {hitSide && <span key={`sweep-${arrival.signature}`} className={`nx-judge-sweep is-${hitSide}`} aria-hidden="true" />}
          <PriceCell className="nx-num nx-judge-price" value={quote.price} />
          <Delta change={quote.price - quote.prevClose} ratio={sessionRate(quote)} showAmount={false} />
          {hitSide && (
            <span key={`hit-${arrival.signature}`} className={`nx-judge-hit nx-num is-${hitSide}`} aria-hidden="true">
              {hitSide === "buy" ? "매수" : "매도"} {hitQty.toLocaleString("ko-KR")}주
            </span>
          )}
        </div>
        {Array.from({ length: 5 }, (_, index) => row("bid", bids[index], index + 7))}
      </div>
      <PrintTape quote={quote} keys={keys} arrival={arrival} />
    </section>
  );
}
