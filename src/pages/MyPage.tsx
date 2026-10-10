import { useEffect, useState } from "react";
import { Delta } from "../components/Delta";
import { GameIcon } from "../components/GameIcon";
import { HoldingsTable } from "../components/HoldingsTable";
import { Link } from "../components/Link";
import { Panel } from "../components/Panel";
import { RestrictionBadge } from "../components/RestrictionBadge";
import { apiFetch } from "../lib/api";
import { clock as hm, serverTimestamp, won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { sessionRate, totals } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";

type ActiveOrder = {
  id: number;
  stockCode: string;
  side: string;
  quantity: number;
  remainingQuantity: number;
  price: number;
  status: string;
  orderType: string;
  createdAt: string;
};

export function MyPage() {
  const snapshot = useMarket();
  const api = useMarketApi();
  const money = totals(snapshot);
  const fills = snapshot.portfolio.fills;
  const holdingCount = Object.keys(snapshot.portfolio.positions).length;
  const [openOrders, setOpenOrders] = useState<ActiveOrder[]>([]);
  const [ordersLoaded, setOrdersLoaded] = useState(false);
  const [message, setMessage] = useState("");

  // 미체결 주문은 체결·만료로 자주 바뀌므로 1초마다 다시 읽는다.
  useEffect(() => {
    let active = true;
    const refresh = async () => {
      try {
        const next = await apiFetch<ActiveOrder[]>("/api/orders");
        if (active) {
          setOpenOrders(next);
          setOrdersLoaded(true);
        }
      } catch {
        // 일시적인 네트워크 오류가 있어도 마지막 목록을 유지한다.
      }
    };
    void refresh();
    const timer = window.setInterval(() => void refresh(), 1_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  const cancelOrder = async (id: number) => {
    setMessage("");
    try {
      await apiFetch(`/api/orders/${id}`, { method: "DELETE" });
      setOpenOrders((current) => current.filter((order) => order.id !== id));
      setMessage("미체결 주문을 취소했습니다.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "주문 취소에 실패했습니다.");
    }
  };

  return (
    <div className="page-stack">
      <h1 className="page-title">내 계좌</h1>

      <section className="head" aria-label="계좌 요약">
        <div>
          <p className="head-label">총 자산</p>
          <p className="head-value">{won(money.total)}</p>
          <p className="head-sub">
            <Delta change={money.pnl} ratio={money.pnlRate} />
          </p>
        </div>
        <dl className="head-me kv">
          <div>
            <dt>현금</dt>
            <dd className="num">{won(money.cash)}</dd>
          </div>
          <div>
            <dt>주식</dt>
            <dd className="num">{won(money.stockValue)}</dd>
          </div>
        </dl>
      </section>

      {message && <p className="account-message" role="status">{message}</p>}

      <Panel id="mypage-holdings" title="보유 종목" action={{ label: "종목 찾기", to: "/market" }}>
        {holdingCount > 0 ? (
          <HoldingsTable caption="내 보유 종목과 평가 손익" />
        ) : (
          <p className="empty">보유한 종목이 없습니다.</p>
        )}
      </Panel>

      <Panel id="mypage-open-orders" title="미체결 주문" meta={ordersLoaded ? openOrders.length + "건" : "불러오는 중…"}>
        {!ordersLoaded ? <p className="empty">주문을 불러오는 중…</p> : openOrders.length === 0 ? (
          <p className="empty">현재 미체결 주문이 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="table account-orders-table">
              <caption className="vh">미체결 주문 목록</caption>
              <thead><tr>
                <th scope="col" className="cell-name">게임</th>
                <th scope="col">구분</th><th scope="col">유형</th>
                <th scope="col" className="cell-num">주문가</th>
                <th scope="col" className="cell-num">수량 / 잔량</th>
                <th scope="col" className="cell-num">시각</th>
                <th scope="col" className="cell-num">관리</th>
              </tr></thead>
              <tbody>
                {openOrders.map((order) => (
                  <tr key={order.id}>
                    <th scope="row" className="cell-name">
                      <Link className="name-link" to={"/market/" + order.stockCode}>
                        <GameIcon code={order.stockCode} />
                        <span className="name-text">{LISTING_BY_CODE[order.stockCode]?.name ?? order.stockCode}</span>
                      </Link>
                      <RestrictionBadge code={order.stockCode} />
                    </th>
                    <td className={order.side === "BUY" ? "is-up" : "is-down"}>{order.side === "BUY" ? "매수" : "매도"}</td>
                    <td>{order.orderType === "LIMIT" ? "지정가" : "시장가"}</td>
                    <td className="cell-num num">{order.price > 0 ? won(order.price) : "—"}</td>
                    <td className="cell-num num">{order.quantity.toLocaleString("ko-KR")} / {order.remainingQuantity.toLocaleString("ko-KR")}주</td>
                    <td className="cell-num num">{Number.isNaN(serverTimestamp(order.createdAt)) ? "—" : hm(serverTimestamp(order.createdAt))}</td>
                    <td className="cell-num"><button type="button" className="account-button" onClick={() => void cancelOrder(order.id)}>취소</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <Panel id="mypage-fills" title="체결 내역">
        {fills.length > 0 ? (
          <div className="table-scroll">
            <table className="table">
              <caption className="vh">최근 체결 내역</caption>
              <thead>
                <tr>
                  <th scope="col" className="cell-name">
                    게임
                  </th>
                  <th scope="col">구분</th>
                  <th scope="col" className="cell-num">
                    수량
                  </th>
                  <th scope="col" className="cell-num">
                    금액
                  </th>
                  <th scope="col" className="cell-num">
                    시각
                  </th>
                </tr>
              </thead>
              <tbody>
                {fills.map((fill) => (
                  <tr key={fill.id}>
                    <th scope="row" className="cell-name">
                      <Link className="name-link" to={`/market/${fill.code}`}>
                        <GameIcon code={fill.code} />
                        <span className="name-text">{LISTING_BY_CODE[fill.code]?.name ?? fill.code}</span>
                      </Link>
                    </th>
                    <td className={fill.side === "buy" ? "is-up" : "is-down"}>{fill.side === "buy" ? "매수" : "매도"}</td>
                    <td className="cell-num num">{fill.qty}주</td>
                    <td className="cell-num num">{won(fill.price * fill.qty)}</td>
                    <td className="cell-num num">{hm(fill.at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="empty">체결 내역이 없습니다.</p>
        )}
      </Panel>

      <Panel id="mypage-watchlist" title="관심종목" meta={snapshot.watch.length + "종목"} action={{ label: "종목 찾기", to: "/market" }}>
        {snapshot.watch.length === 0 ? (
          <p className="empty">시장에서 별표를 눌러 관심종목을 담아 보세요.</p>
        ) : (
          <ul className="account-list">
            {snapshot.watch.map((code) => {
              const quote = snapshot.quotes[code];
              const rate = quote ? sessionRate(quote) : 0;
              return (
                <li key={code}>
                  <Link className="name-link" to={"/market/" + code}>
                    <GameIcon code={code} />
                    <span className="name-text">{LISTING_BY_CODE[code]?.name ?? code}</span>
                  </Link>
                  <span className="account-watch-quote num">
                    {quote ? won(quote.price) : "—"}
                    <Delta change={rate} ratio={rate} showAmount={false} />
                  </span>
                  <button type="button" className="text-button" aria-label={(LISTING_BY_CODE[code]?.name ?? code) + " 관심종목 해제"} onClick={() => api.toggleWatch(code)}>
                    해제
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </Panel>
    </div>
  );
}
