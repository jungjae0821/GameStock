import { useEffect, useRef, useState } from "react";
import { apiFetch } from "../lib/api";
import { openLoginPrompt, useAuthUser } from "../lib/auth";
import { clock, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { LISTING_BY_CODE } from "../market/universe";
import { TRADE_PATH } from "../router";
import { Link } from "./Link";

export type AccountTab = "holdings" | "fills" | "orders";
type ActiveOrder = {
  id: number; stockCode: string; side: string; quantity: number;
  remainingQuantity: number; price: number; status: string; orderType: string;
};

export function TradeAccountPanel({ code, tab, onTabChange }: { code: string; tab: AccountTab; onTabChange: (tab: AccountTab) => void }) {
  const snapshot = useMarket();
  const { user, ready } = useAuthUser();
  const [onlyCurrent, setOnlyCurrent] = useState(false);
  const [orders, setOrders] = useState<ActiveOrder[]>([]);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [cancelling, setCancelling] = useState<number | null>(null);
  const cancelInFlight = useRef(false);
  const sessionRef = useRef(user?.uid);
  sessionRef.current = user?.uid;

  useEffect(() => {
    setOrders([]);
    setError("");
    setMessage("");
  }, [user?.uid]);

  useEffect(() => {
    if (!user || tab !== "orders") return;
    const controller = new AbortController();
    let pending = false;
    const refresh = async (showLoading = false) => {
      if (pending) return;
      pending = true;
      if (showLoading) setLoading(true);
      try {
        const result = await apiFetch<ActiveOrder[]>("/api/orders", { signal: controller.signal });
        if (!controller.signal.aborted) { setOrders(result.filter((order) => order.status === "OPEN")); setError(""); }
      } catch (cause) {
        if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "미체결 주문을 불러오지 못했습니다.");
      } finally {
        pending = false;
        if (!controller.signal.aborted) setLoading(false);
      }
    };
    void refresh(true);
    const timer = window.setInterval(() => void refresh(), 5000);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, [user?.uid, tab, revision]);

  const cancel = async (id: number) => {
    if (cancelInFlight.current) return;
    const uid = user?.uid;
    cancelInFlight.current = true;
    setCancelling(id);
    setMessage("");
    try {
      await apiFetch(`/api/orders/${id}`, { method: "DELETE" });
      if (sessionRef.current !== uid) return;
      setOrders((current) => current.filter((order) => order.id !== id));
      setMessage("미체결 주문을 취소했습니다.");
      setError("");
      setRevision((current) => current + 1);
    } catch (cause) {
      if (sessionRef.current === uid) setError(cause instanceof Error ? cause.message : "주문을 취소하지 못했습니다.");
    } finally {
      cancelInFlight.current = false;
      setCancelling(null);
    }
  };

  const positions = user ? Object.values(snapshot.portfolio.positions).filter((position) => position.qty > 0 && (!onlyCurrent || position.code === code)) : [];
  const fills = user ? snapshot.portfolio.fills.filter((fill) => !onlyCurrent || fill.code === code) : [];
  const visibleOrders = user ? orders.filter((order) => !onlyCurrent || order.stockCode === code) : [];
  const count = tab === "holdings" ? positions.length : tab === "fills" ? fills.length : visibleOrders.length;
  const signed = (amount: number) => `${amount > 0 ? "+" : ""}${won(amount)}`;
  const tone = (amount: number) => amount > 0 ? "is-up" : amount < 0 ? "is-down" : "";

  return (
    <section className="trade-account-panel terminal-panel" aria-label="내 잔고와 주문 내역">
      <div className="terminal-tabs" role="group" aria-label="계좌 내역 선택">
        {([["holdings", "실시간 잔고"], ["fills", "체결 내역"], ["orders", "미체결 / 취소"]] as const).map(([value, label]) => (
          <button key={value} type="button" className={`terminal-tab${tab === value ? " is-active" : ""}`} aria-pressed={tab === value} onClick={() => onTabChange(value)}>{label}</button>
        ))}
      </div>
      <div className="terminal-account-toolbar">
        <span>{user?.displayName || "내 모의투자 계좌"}</span>
        <label className="terminal-filter"><input type="checkbox" checked={onlyCurrent} onChange={(event) => setOnlyCurrent(event.target.checked)} />현재 종목만</label>
        <span className="terminal-currency">원화</span>
      </div>
      <div className="terminal-table-scroll" tabIndex={0} role="region" aria-label="계좌 내역 표" aria-busy={tab === "orders" && loading}>
        <table className="terminal-table">
          <caption className="vh">{tab === "holdings" ? "실시간 보유 잔고" : tab === "fills" ? "체결 내역" : "미체결 주문"}</caption>
          <thead><tr>
            {(tab === "holdings" ? ["종목명", "보유수량", "평균단가", "현재가", "평가손익", "수익률"] : tab === "fills" ? ["종목명", "구분", "체결수량", "체결단가", "체결금액", "체결시각"] : ["종목명", "구분", "주문단가", "주문수량", "미체결", "취소"]).map((label) => <th key={label} scope="col">{label}</th>)}
          </tr></thead>
          <tbody>
            {tab === "holdings" && positions.map((position) => {
              const price = snapshot.quotes[position.code]?.price ?? position.avgCost;
              const pnl = (price - position.avgCost) * position.qty;
              const ratio = position.avgCost > 0 ? ((price - position.avgCost) / position.avgCost) * 100 : 0;
              return <tr key={position.code}>
                <th scope="row"><Link to={TRADE_PATH(position.code)}>{LISTING_BY_CODE[position.code]?.name ?? position.code}</Link></th>
                <td>{position.qty.toLocaleString()}주</td><td>{won(position.avgCost)}</td><td>{won(price)}</td>
                <td className={tone(pnl)}>{signed(pnl)}</td><td className={tone(pnl)}>{ratio > 0 ? "+" : ""}{ratio.toFixed(2)}%</td>
              </tr>;
            })}
            {tab === "fills" && fills.map((fill) => <tr key={fill.id}>
              <th scope="row"><Link to={TRADE_PATH(fill.code)}>{LISTING_BY_CODE[fill.code]?.name ?? fill.code}</Link></th>
              <td className={fill.side === "buy" ? "is-up" : "is-down"}>{fill.side === "buy" ? "매수" : "매도"}</td>
              <td>{fill.qty.toLocaleString()}주</td><td>{won(fill.price)}</td><td>{won(fill.price * fill.qty)}</td><td>{clock(fill.at)}</td>
            </tr>)}
            {tab === "orders" && visibleOrders.map((order) => <tr key={order.id}>
              <th scope="row"><Link to={TRADE_PATH(order.stockCode)}>{LISTING_BY_CODE[order.stockCode]?.name ?? order.stockCode}</Link></th>
              <td className={order.side.toLowerCase() === "buy" ? "is-up" : "is-down"}>{order.side.toLowerCase() === "buy" ? "매수" : "매도"}</td>
              <td>{order.orderType === "MARKET" ? "시장가" : won(order.price)}</td><td>{order.quantity.toLocaleString()}주</td><td>{order.remainingQuantity.toLocaleString()}주</td>
              <td><button type="button" className="terminal-small-button" disabled={cancelling !== null} onClick={() => void cancel(order.id)}>{cancelling === order.id ? "처리 중" : "취소"}</button></td>
            </tr>)}
            {count === 0 && <tr><td colSpan={6} className="terminal-empty">
              <p>{!ready ? "계좌 확인 중…" : !user ? "로그인하면 잔고와 거래 내역을 확인할 수 있습니다." : tab === "orders" && loading ? "미체결 주문을 조회하고 있습니다…" : tab === "orders" && error ? "조회에 실패했습니다. 아래 재시도 버튼을 눌러 주세요." : tab === "holdings" ? "보유 중인 종목이 없습니다." : tab === "fills" ? "체결 내역이 없습니다." : "미체결 주문이 없습니다."}</p>
              {ready && (!user ? (
                <button type="button" className="terminal-empty-action" onClick={() => openLoginPrompt()}>로그인하기</button>
              ) : !(tab === "orders" && (loading || error)) && (onlyCurrent ? (
                <button type="button" className="terminal-empty-action" onClick={() => setOnlyCurrent(false)}>전체 종목 보기</button>
              ) : tab === "holdings" ? (
                <Link className="terminal-empty-action" to="/market">종목 둘러보기</Link>
              ) : (
                <button type="button" className="terminal-empty-action" onClick={(event) => {
                  const ticket = event.currentTarget.closest(".trade-page")?.querySelector<HTMLFormElement>(".terminal-ticket");
                  ticket?.focus({ preventScroll: true });
                  ticket?.scrollIntoView({ block: "nearest" });
                }}>주문 입력하기</button>
              )))}
            </td></tr>}
          </tbody>
        </table>
      </div>
      <div className={`terminal-status${tab === "orders" && error ? " is-error" : ""}`} role="status" aria-live="polite">
        <span>{tab === "orders" && error ? error : !user ? "로그인 후 이용할 수 있습니다." : tab === "orders" && message ? message : tab === "orders" ? `최근 미체결 ${count}건 · 최대 5건 표시` : `${count}건 · ${clock(snapshot.updatedAt)} 기준`}</span>
        {tab === "orders" && error && <button type="button" onClick={() => setRevision((current) => current + 1)}>재시도</button>}
      </div>
    </section>
  );
}
