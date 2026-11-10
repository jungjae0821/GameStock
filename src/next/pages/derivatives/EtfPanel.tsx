import { useState } from "react";
import { Sparkline } from "../../../components/Sparkline";
import { Delta } from "../../../components/Delta";
import { Link } from "../../../components/Link";
import { OrderDock } from "../../trade/OrderDock";
import { MARKET_PATH } from "../../../router";
import { won, rate, indexValue } from "../../../market/format";
import type { DerivativeAccount, DerivativeOrderRequest, EtfQuote } from "../../../market/derivatives";
import { AnimatedNav } from "../../AnimatedNav";

interface Props {
  selected: EtfQuote;
  account: DerivativeAccount | null;
  busy: boolean;
  onOpen: (request: DerivativeOrderRequest) => void;
  onClose: (positionId: number, quantity: number) => void;
  notice?: { tone: "ok" | "error"; text: string } | null;
}

function tone(changePercent: number): "up" | "down" | "flat" {
  return changePercent > 0 ? "up" : changePercent < 0 ? "down" : "flat";
}

/** 목록에서 고른 ETF의 시세와 주문. 종목은 주소로 결정해 새로고침해도 유지한다. */
export function EtfPanel({ selected, account, busy, onOpen, onClose, notice }: Props) {
  const [orderOpen, setOrderOpen] = useState(false);
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [quantityInput, setQuantityInput] = useState("10");
  const holdings = account?.positions.filter((position) => position.kind === "ETF" && position.symbol === selected.code) ?? [];
  const quantity = Number(quantityInput);
  const validQuantity = Number.isInteger(quantity) && quantity >= 1 && quantity <= 100;
  const amount = Math.round(selected.price * (validQuantity ? quantity : 0));
  const fee = Math.ceil(amount * 0.001);
  const total = side === "BUY" ? amount + fee : amount - fee;

  return (
    <div className="derivative-panel nx-etf-panel">
        <section className="nx-workspace">
          <div className="nx-trade-main">
            <header className="derivative-card-head">
              <div>
                <h1 className="nx-etf-name">{selected.name}</h1>
                <p className="nx-etf-code nx-num">{selected.code} · {selected.theme}</p>
                <p>{selected.description}</p>
              </div>
            </header>
            <div className="nx-etf-price">
              <strong className="nx-num">{won(selected.price)}</strong>
              <Delta change={selected.price - selected.prevClose} ratio={selected.changePercent / 100} />
            </div>
            <figure className="nx-etf-chart" aria-label={`${selected.name} 최근 가격 흐름`}>
              {selected.series.length > 1 ? <Sparkline series={selected.series} prevClose={selected.prevClose} tone={tone(selected.changePercent)} width={760} height={260} /> : <p className="empty">가격 기록을 모으고 있어요.</p>}
              <figcaption>최근 가격 흐름 · 기준가 {won(selected.prevClose)}</figcaption>
            </figure>
            <dl className="derivative-facts">
              <div><dt>NAV</dt><dd className="num">{indexValue(selected.price)}</dd></div>
              <div><dt>기준가</dt><dd className="num">{indexValue(selected.prevClose)}</dd></div>
              <div><dt>구성 종목</dt><dd className="num">{selected.constituents.length}개</dd></div>
              <div><dt>괴리율</dt><dd className="num">0.00%</dd></div>
            </dl>
            <h2 className="nx-etf-constituents-title">구성 종목</h2>
            <div className="table-scroll" tabIndex={0} role="region" aria-label={`${selected.name} 구성 종목 표`}>
              <table className="table etf-constituents">
                <caption className="vh">{selected.name} 구성 종목</caption>
                <thead>
                  <tr><th scope="col">종목</th><th scope="col">비중</th><th scope="col" className="cell-num">등락</th></tr>
                </thead>
                <tbody>
                  {selected.constituents.length === 0 && (
                    <tr><td colSpan={3} className="empty">이 장르에 상장된 종목이 없습니다.</td></tr>
                  )}
                  {selected.constituents.map((item) => (
                    <tr key={item.code}>
                      <td><Link to={MARKET_PATH(item.code)}>{item.name} <span className="muted-code">{item.code}</span></Link></td>
                      <td className="num">{(item.weight * 100).toFixed(1)}%</td>
                      <td className={`cell-num is-${tone(item.changePercent)}`}>{rate(item.changePercent / 100)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <OrderDock title={selected.name} open={orderOpen} onOpenChange={setOrderOpen}>
          <form
            className="derivative-ticket nx-etf-ticket"
            aria-label="ETF 주문 입력" aria-busy={busy}
            onSubmit={(event) => {
              event.preventDefault();
              if (!validQuantity || busy) return;
              onOpen({ kind: "ETF", side, symbol: selected.code, quantity });
            }}
          >
            <AnimatedNav as="div" fill activeKey={side} className="terminal-tabs derivative-side-tabs" role="group" aria-label="매수·매도">
              <button type="button" className={`terminal-tab is-buy${side === "BUY" ? " is-active" : ""}`}
                aria-pressed={side === "BUY"} onClick={() => setSide("BUY")}>매수</button>
              <button type="button" className={`terminal-tab is-sell${side === "SELL" ? " is-active" : ""}`}
                aria-pressed={side === "SELL"} onClick={() => setSide("SELL")}>매도</button>
            </AnimatedNav>
            <label className="derivative-field">
              <span>수량</span>
              <input type="number" inputMode="numeric" min={1} max={100} step={1} value={quantityInput}
                onChange={(event) => setQuantityInput(event.target.value)} />
            </label>
            <dl className="derivative-order-summary">
              <div><dt>주문 가능 현금</dt><dd className="num">{account ? won(account.cash) : "로그인 후 확인"}</dd></div>
              <div><dt>NAV</dt><dd className="num">{indexValue(selected.price)}</dd></div>
              <div><dt>주문 금액</dt><dd className="num">{won(amount)}</dd></div>
              <div><dt>수수료 0.1%</dt><dd className="num">{won(fee)}</dd></div>
              <div className="is-emphasis"><dt>정산 예정</dt><dd className="num">{won(total)}</dd></div>
            </dl>
            <button type="submit" className={`derivative-submit is-${side === "BUY" ? "buy" : "sell"}`}
              disabled={busy || !validQuantity}>
              {busy ? "처리 중…" : side === "BUY" ? "ETF 매수" : "ETF 매도"}
            </button>
            <p className="derivative-note">ETF는 NAV로 즉시 체결되며, 보유 수량은 보유 목록에서도 청산할 수 있어요.</p>
            {notice && <p className={`derivative-notice is-${notice.tone}`} role="status">{notice.text}</p>}
          </form>
          </OrderDock>
        </section>

      <section className="derivative-card">
        <header className="derivative-card-head">
          <div><h2>보유 내역</h2><p>{selected.name} · 현재 NAV 기준입니다.</p></div>
        </header>
        {holdings.length === 0 ? (
          <p className="empty">{account ? "보유 중인 수량이 없습니다." : "로그인하면 보유 내역을 확인할 수 있어요."}</p>
        ) : (
          <div className="table-scroll">
            <table className="table derivative-table">
              <caption className="vh">보유 ETF</caption>
              <thead>
                <tr><th scope="col">상품</th><th scope="col" className="cell-num">수량</th><th scope="col" className="cell-num">평균 NAV</th>
                  <th scope="col" className="cell-num">현재 NAV</th><th scope="col" className="cell-num">평가액</th>
                  <th scope="col" className="cell-num">평가손익</th><th scope="col" className="cell-num">청산</th></tr>
              </thead>
              <tbody>
                {holdings.map((position) => (
                  <tr key={position.id}>
                    <td>{position.name}</td>
                    <td className="num">{position.quantity}</td>
                    <td className="num">{indexValue(position.entryPrice)}</td>
                    <td className="num">{indexValue(position.markPrice)}</td>
                    <td className="num">{won(position.value)}</td>
                    <td className="cell-num"><Delta change={position.unrealizedPnl} ratio={position.unrealizedPercent / 100} /></td>
                    <td className="cell-num">
                      <button type="button" className="derivative-close" disabled={busy}
                        onClick={() => onClose(position.id, position.quantity)}>전량</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
