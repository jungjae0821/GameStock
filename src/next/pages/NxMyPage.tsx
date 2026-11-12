import { useEffect, useState } from "react";
import { GameIcon } from "../../components/GameIcon";
import { Link } from "../../components/Link";
import { PriceCell } from "../../components/PriceCell";
import { RestrictionBadge } from "../../components/RestrictionBadge";
import { apiFetch } from "../../lib/api";
import { fillTime, rate, serverTimestamp, shares, signedWon, won } from "../../market/format";
import { useMarket, useMarketApi } from "../../market/MarketProvider";
import { holdings, sessionRate, totals } from "../../market/selectors";
import { LISTING_BY_CODE } from "../../market/universe";
import { TRADE_PATH } from "../../router";
import { AssetHistory } from "../AssetHistory";
import { ShareCardButton } from "../engagement/ShareCard";

type ActiveOrder = {
  id: number; stockCode: string; side: string; quantity: number; remainingQuantity: number;
  price: number; status: string; orderType: string; createdAt: string;
};

const tone = (value: number) => (value > 0 ? "is-up" : value < 0 ? "is-down" : "");
const name = (code: string) => LISTING_BY_CODE[code]?.name ?? code;

/** 자산 구성: 현금과 종목별 평가액이 순자산에서 차지하는 몫. 화려함이 곧 정보인 자리. */
function Allocation({ cash, derivative, rows, total }: { cash: number; derivative: number; rows: Array<{ code: string; value: number }>; total: number }) {
  if (total <= 0) return null;
  const parts = [
    ...rows.map((row) => ({ key: row.code, label: name(row.code), value: row.value })),
    { key: "cash", label: "현금", value: cash },
    { key: "derivative", label: "ETF·파생", value: derivative },
  ].filter((part) => part.value > 0);
  return (
    <figure className="nx-alloc">
      <div className="nx-alloc-bar" role="img" aria-label={parts.map((part) => `${part.label} ${Math.round((part.value / total) * 100)}%`).join(", ")}>
        {parts.map((part, index) => (
          <span key={part.key} className={part.key === "cash" ? "is-cash" : `is-${index % 5}`} style={{ flexGrow: part.value }} />
        ))}
      </div>
      <figcaption className="nx-alloc-legend">
        {parts.map((part, index) => (
          <span key={part.key}>
            <i className={part.key === "cash" ? "is-cash" : `is-${index % 5}`} aria-hidden="true" />
            {part.label} <span className="nx-num">{((part.value / total) * 100).toFixed(1)}%</span>
          </span>
        ))}
      </figcaption>
    </figure>
  );
}

export function NxMyPage() {
  const snapshot = useMarket();
  const api = useMarketApi();
  const money = totals(snapshot);
  const rows = holdings(snapshot, money.total);
  const [orders, setOrders] = useState<ActiveOrder[]>([]);
  const [ordersLoaded, setOrdersLoaded] = useState(false);
  const [ordersFailed, setOrdersFailed] = useState(false);
  const [message, setMessage] = useState("");

  // 미체결 주문은 체결·만료로 자주 바뀌므로 1초마다 다시 읽는다(기존 내 계좌 화면과 같은 주기).
  useEffect(() => {
    let active = true;
    const refresh = async () => {
      try {
        const next = await apiFetch<ActiveOrder[]>("/api/orders");
        if (!active) return;
        setOrders(next);
        setOrdersLoaded(true);
        setOrdersFailed(false);
      } catch {
        if (active) setOrdersFailed(true);
      }
    };
    void refresh();
    const timer = window.setInterval(() => void refresh(), 1_000);
    return () => { active = false; window.clearInterval(timer); };
  }, []);

  const cancelOrder = async (id: number) => {
    setMessage("");
    try {
      await apiFetch(`/api/orders/${id}`, { method: "DELETE" });
      setOrders((current) => current.filter((order) => order.id !== id));
      setMessage("미체결 주문을 취소했어요.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "주문 취소에 실패했어요.");
    }
  };

  return (
    <div className="nx-screen nx-mypage">
      <h1 className="vh">내 계좌</h1>
      {snapshot.portfolioError && <p className="nx-note is-error" role="status">{snapshot.portfolioError}</p>}

      <section className="nx-account-hero" aria-label="계좌 요약">
        <div>
          <p className="nx-panel-title">{money.loan > 0 ? "순자산" : "총 자산"}</p>
          <PriceCell className="nx-num nx-big" value={money.net} />
          <p className={`nx-num nx-account-pnl ${tone(money.pnl)}`}>{signedWon(money.pnl)} ({rate(money.pnlRate)}) <span className="nx-muted">시작 자금 대비</span></p>
          <ShareCardButton pnl={money.pnl} pnlRate={money.pnlRate}
            best={rows.length ? (([top]) => ({ code: top.listing.code, name: top.listing.name, pnlRate: top.pnlRate }))([...rows].sort((a, b) => b.pnl - a.pnl)) : null} />
        </div>
        <dl className="nx-figures">
          <div><dt>현금</dt><dd className="nx-num">{won(money.cash)}</dd></div>
          <div><dt>주식 평가액</dt><dd className="nx-num">{won(money.stockValue)}</dd></div>
          <div><dt>ETF·파생</dt><dd className="nx-num">{won(money.derivativeValue)} <Link to="/market/etf" className="nx-link-button">ETF</Link> <Link to="/derivatives" className="nx-link-button">파생</Link></dd></div>
          <div><dt>실현 손익</dt><dd className={`nx-num ${tone(snapshot.portfolio.realized)}`}>{signedWon(snapshot.portfolio.realized)}</dd></div>
          {money.loan > 0 && <div><dt>대출 잔액</dt><dd className="nx-num is-up">−{won(money.loan)} <Link to="/loan" className="nx-link-button">관리</Link></dd></div>}
        </dl>
      </section>

      <Allocation cash={money.cash} derivative={money.derivativeValue} rows={rows.map((row) => ({ code: row.listing.code, value: row.value }))} total={money.total} />

      <AssetHistory />

      {message && <p className="nx-msg" role="status">{message}</p>}

      <div className="nx-board nx-mypage-board">
        <section className="nx-panel nx-mypage-holdings" aria-labelledby="nx-holdings-title">
          <header className="nx-panel-head">
            <h2 id="nx-holdings-title" className="nx-panel-title">보유 종목 <span className="nx-num">{rows.length}</span></h2>
            <Link to="/market" className="nx-more">종목 찾기</Link>
          </header>
          {rows.length === 0 ? <p className="nx-empty-state">보유한 종목이 없어요.</p> : (
            <div className="nx-scroll-x">
              <table className="nx-grid-table">
                <caption className="vh">보유 종목과 평가 손익</caption>
                <thead><tr><th scope="col">종목</th><th scope="col">수량</th><th scope="col">평균단가</th><th scope="col">평가액</th><th scope="col">손익</th><th scope="col">비중</th></tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.listing.code}>
                      <th scope="row"><Link to={TRADE_PATH(row.listing.code)} className="nx-cell-name"><GameIcon code={row.listing.code} size="small" />{row.listing.name}</Link></th>
                      <td className="nx-num">{row.position.qty.toLocaleString("ko-KR")}주</td>
                      <td className="nx-num">{won(row.position.avgCost)}</td>
                      <td className="nx-num">{won(row.value)}</td>
                      <td className={`nx-num ${tone(row.pnl)}`}>{signedWon(row.pnl)}<br /><small>{rate(row.pnlRate)}</small></td>
                      <td className="nx-num">{(row.weight * 100).toFixed(1)}%</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="nx-panel nx-mypage-orders" aria-labelledby="nx-orders-title">
          <header className="nx-panel-head">
            <h2 id="nx-orders-title" className="nx-panel-title">미체결 주문</h2>
            <span className="nx-muted">{ordersLoaded ? `${orders.length}건` : ordersFailed ? "연결 끊김" : "불러오는 중"}</span>
          </header>
          {!ordersLoaded ? (
            ordersFailed
              ? <p className="nx-note is-error" role="alert">미체결 주문을 불러오지 못했어요. 서버에 다시 연결되면 자동으로 불러와요.</p>
              : <div className="nx-skeleton" role="status" aria-label="주문을 불러오는 중"><span /><span /></div>
          ) : orders.length === 0 ? <p className="nx-empty-state">걸려 있는 미체결 주문이 없어요.</p> : (
            <div className="nx-scroll-x">
              <table className="nx-grid-table">
                <caption className="vh">미체결 주문 목록</caption>
                <thead><tr><th scope="col">종목</th><th scope="col">구분</th><th scope="col">주문가</th><th scope="col">수량 / 잔량</th><th scope="col">시각</th><th scope="col"><span className="vh">취소</span></th></tr></thead>
                <tbody>
                  {orders.map((order) => {
                    const buy = order.side === "BUY";
                    const at = serverTimestamp(order.createdAt);
                    return (
                      <tr key={order.id}>
                        <th scope="row">
                          <Link to={TRADE_PATH(order.stockCode)} className="nx-cell-name"><GameIcon code={order.stockCode} size="small" />{name(order.stockCode)}</Link>
                          <RestrictionBadge code={order.stockCode} />
                        </th>
                        <td><span className={`nx-side is-${buy ? "buy" : "sell"}`}>{buy ? "매수" : "매도"}</span> <small className="nx-muted">{order.orderType === "LIMIT" ? "지정가" : "시장가"}</small></td>
                        <td className="nx-num">{order.price > 0 ? won(order.price) : "-"}</td>
                        <td className="nx-num">{order.quantity.toLocaleString("ko-KR")} / {order.remainingQuantity.toLocaleString("ko-KR")}주</td>
                        <td className="nx-num">{Number.isNaN(at) ? "-" : fillTime(at)}</td>
                        <td><button type="button" className="nx-btn is-small" onClick={() => void cancelOrder(order.id)}>취소</button></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="nx-panel nx-mypage-fills" aria-labelledby="nx-fills-title">
          <header className="nx-panel-head">
            <h2 id="nx-fills-title" className="nx-panel-title">체결 내역</h2>
            <span className="nx-muted nx-num">{snapshot.portfolio.fills.length}건</span>
          </header>
          {snapshot.portfolio.fills.length === 0 ? <p className="nx-empty-state">아직 체결된 주문이 없어요.</p> : (
            <div className="nx-scroll-x nx-scroll-y">
              <table className="nx-grid-table">
                <caption className="vh">최근 체결 내역</caption>
                <thead><tr><th scope="col">종목</th><th scope="col">구분</th><th scope="col">수량</th><th scope="col">금액</th><th scope="col">시각</th></tr></thead>
                <tbody>
                  {snapshot.portfolio.fills.map((fill) => (
                    <tr key={fill.id}>
                      <th scope="row"><Link to={TRADE_PATH(fill.code)} className="nx-cell-name"><GameIcon code={fill.code} size="small" />{name(fill.code)}</Link></th>
                      <td><span className={`nx-side is-${fill.side}`}>{fill.side === "buy" ? "매수" : "매도"}</span></td>
                      <td className="nx-num">{fill.qty.toLocaleString("ko-KR")}주</td>
                      <td className="nx-num">{won(fill.price * fill.qty)}</td>
                      <td className="nx-num">{fillTime(fill.at)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="nx-panel nx-mypage-watch" aria-labelledby="nx-watch-title">
          <header className="nx-panel-head">
            <h2 id="nx-watch-title" className="nx-panel-title">관심 종목 <span className="nx-num">{snapshot.watch.length}</span></h2>
            <Link to="/market" className="nx-more">종목 찾기</Link>
          </header>
          {snapshot.watch.length === 0 ? <p className="nx-empty-state">시장 목록에서 별을 눌러 관심 종목을 담아 보세요.</p> : (
            <div className="nx-scroll-x">
              <table className="nx-grid-table">
                <caption className="vh">관심 종목 시세</caption>
                <thead><tr><th scope="col">종목</th><th scope="col">현재가</th><th scope="col">등락률</th><th scope="col">거래량</th><th scope="col"><span className="vh">해제</span></th></tr></thead>
                <tbody>
                  {snapshot.watch.map((code) => {
                    const quote = snapshot.quotes[code];
                    const ratio = quote ? sessionRate(quote) : 0;
                    return (
                      <tr key={code}>
                        <th scope="row">
                          <Link to={TRADE_PATH(code)} className="nx-cell-name"><GameIcon code={code} size="small" />{name(code)}</Link>
                          <RestrictionBadge code={code} />
                        </th>
                        <td className="nx-num">{quote ? won(quote.price) : "-"}</td>
                        <td className={`nx-num ${tone(ratio)}`}>{quote ? rate(ratio) : "-"}</td>
                        <td className="nx-num">{quote ? shares(quote.volume) : "-"}</td>
                        <td><button type="button" className="nx-btn is-small" aria-label={`${name(code)} 관심 종목 해제`} onClick={() => api.toggleWatch(code)}>해제</button></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
