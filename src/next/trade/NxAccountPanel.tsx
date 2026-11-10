import { useEffect, useRef, useState } from "react";
import { Link } from "../../components/Link";
import { apiFetch } from "../../lib/api";
import { useAuthUser } from "../../lib/auth";
import { fillTime, signedWon, won } from "../../market/format";
import { useMarket } from "../../market/MarketProvider";
import { LISTING_BY_CODE } from "../../market/universe";
import type { Fill } from "../../market/types";
import { TRADE_PATH } from "../../router";
import { AnimatedNav } from "../AnimatedNav";
import { StopOrders } from "./StopOrders";

export type NxAccountTab = "holdings" | "fills" | "orders" | "stops";

type ActiveOrder = {
  id: number; stockCode: string; side: string; quantity: number;
  remainingQuantity: number; price: number; status: string; orderType: string;
};

const TABS = [["holdings", "보유 잔고"], ["fills", "체결 내역"], ["orders", "미체결"], ["stops", "자동 매도"]] as const;

/**
 * 이 종목 체결의 복기 줄. 서버는 다 판 종목의 실현 손익을 종목별로 남기지 않으므로,
 * 지금 받은 체결만으로 평균 매수가·매도가와 그 차이를 보인다.
 */
function FillReview({ fills, price, avgCost, qty }: { fills: Fill[]; price: number; avgCost: number; qty: number }) {
  const side = (which: Fill["side"]) => {
    const rows = fills.filter((fill) => fill.side === which);
    const total = rows.reduce((sum, fill) => sum + fill.qty, 0);
    const amount = rows.reduce((sum, fill) => sum + fill.qty * fill.price, 0);
    return { count: rows.length, total, average: total > 0 ? amount / total : 0 };
  };
  const buys = side("buy");
  const sells = side("sell");
  const spread = buys.total > 0 && sells.total > 0 ? (sells.average - buys.average) / buys.average : null;
  const open = qty > 0 && avgCost > 0 ? (price - avgCost) / avgCost : null;
  const tone = (value: number) => value > 0 ? "is-up" : value < 0 ? "is-down" : "";
  const pct = (value: number) => `${value > 0 ? "+" : ""}${(value * 100).toFixed(2)}%`;
  return (
    <dl className="nx-review">
      <div><dt>매수 <span className="nx-num">{buys.count}</span>번</dt><dd className="nx-num">{buys.total > 0 ? `평균 ${won(Math.round(buys.average))}` : "-"}</dd></div>
      <div><dt>매도 <span className="nx-num">{sells.count}</span>번</dt><dd className="nx-num">{sells.total > 0 ? `평균 ${won(Math.round(sells.average))}` : "-"}</dd></div>
      <div><dt>매도가 − 매수가</dt><dd className={`nx-num ${spread === null ? "" : tone(spread)}`}>{spread === null ? "사고팔면 보여요" : pct(spread)}</dd></div>
      <div><dt>남은 잔고 평가</dt><dd className={`nx-num ${open === null ? "" : tone(open)}`}>{open === null ? "잔고 없음" : pct(open)}</dd></div>
    </dl>
  );
}

/** 거래창 아래 줄. 지금 보고 있는 종목의 잔고·체결·미체결만 보여준다. */
export function NxAccountPanel({ code, tab, onTabChange, onFocusTicket }: {
  code: string;
  tab: NxAccountTab;
  onTabChange: (tab: NxAccountTab) => void;
  onFocusTicket: () => void;
}) {
  const snapshot = useMarket();
  const { user, ready } = useAuthUser();
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

  const positions = user ? Object.values(snapshot.portfolio.positions).filter((position) => position.qty > 0 && position.code === code) : [];
  const fills = user ? snapshot.portfolio.fills.filter((fill) => fill.code === code) : [];
  const visibleOrders = user ? orders.filter((order) => order.stockCode === code) : [];
  const count = tab === "holdings" ? positions.length : tab === "fills" ? fills.length : tab === "orders" ? visibleOrders.length : 0;
  const tone = (amount: number) => amount > 0 ? "is-up" : amount < 0 ? "is-down" : "";
  const name = (stock: string) => LISTING_BY_CODE[stock]?.name ?? stock;

  const emptyText = !ready ? "계좌를 확인하고 있어요."
    : !user ? "로그인하면 잔고와 거래 내역을 볼 수 있어요."
    : tab === "orders" && loading ? "미체결 주문을 조회하고 있어요."
    : tab === "orders" && error ? "조회에 실패했어요. 아래 다시 시도를 눌러 주세요."
    : tab === "holdings" ? "이 종목을 아직 갖고 있지 않아요."
    : tab === "fills" ? "이 종목에서 체결된 주문이 아직 없어요."    : "이 종목에 걸려 있는 미체결 주문이 없어요.";

  return (
    <div className="nx-acct">
      <h2 className="nx-acct-title">잔고</h2>
      <div className="nx-acct-bar">
        <AnimatedNav as="div" activeKey={tab} className="nx-tabs" role="group" aria-label="계좌 내역 선택">
          {TABS.map(([value, label]) => (
            <button key={value} type="button" className={tab === value ? "is-active" : undefined} aria-pressed={tab === value} onClick={() => onTabChange(value)}>
              {label}{user && value === "orders" && visibleOrders.length > 0 && <span className="nx-tab-count nx-num">{visibleOrders.length}</span>}
            </button>
          ))}
        </AnimatedNav>
      </div>

      {tab === "fills" && fills.length > 0 && (
        <FillReview fills={fills} price={snapshot.quotes[code]?.price ?? 0}
          avgCost={snapshot.portfolio.positions[code]?.avgCost ?? 0} qty={snapshot.portfolio.positions[code]?.qty ?? 0} />
      )}

      {tab === "stops" && user ? <StopOrders code={code} uid={user.uid} /> : (
      <div className="nx-acct-scroll" tabIndex={0} role="region" aria-label="계좌 내역 표" aria-busy={tab === "orders" && loading}>
        {count === 0 ? (
          <div className="nx-acct-empty">
            <p>{emptyText}</p>
            {/* 로그아웃 상태의 로그인 버튼은 상단 바에 하나만 둔다. */}
            {ready && user && !(tab === "orders" && (loading || error)) && (
              <button type="button" className="nx-btn" onClick={onFocusTicket}>주문 입력하기</button>
            )}
          </div>
        ) : (
          <table className="nx-grid-table">
            <caption className="vh">{tab === "holdings" ? "보유 잔고" : tab === "fills" ? "체결 내역" : "미체결 주문"}</caption>
            <thead><tr>
              {(tab === "holdings" ? ["종목", "수량", "평균단가", "현재가", "평가손익", "수익률"]
                : tab === "fills" ? ["종목", "구분", "수량", "체결단가", "체결금액", "시각"]
                : ["종목", "구분", "주문단가", "주문", "미체결", ""]).map((label, index) => <th key={`${label}${index}`} scope="col">{label}</th>)}
            </tr></thead>
            <tbody>
              {tab === "holdings" && positions.map((position) => {
                const price = snapshot.quotes[position.code]?.price ?? position.avgCost;
                const pnl = (price - position.avgCost) * position.qty;
                const ratio = position.avgCost > 0 ? ((price - position.avgCost) / position.avgCost) * 100 : 0;
                return <tr key={position.code}>
                  <th scope="row"><Link to={TRADE_PATH(position.code)}>{name(position.code)}</Link></th>
                  <td className="nx-num">{position.qty.toLocaleString()}주</td><td className="nx-num">{won(position.avgCost)}</td><td className="nx-num">{won(price)}</td>
                  <td className={`nx-num ${tone(pnl)}`}>{signedWon(pnl)}</td><td className={`nx-num ${tone(pnl)}`}>{ratio > 0 ? "+" : ""}{ratio.toFixed(2)}%</td>
                </tr>;
              })}
              {tab === "fills" && fills.map((fill) => <tr key={fill.id}>
                <th scope="row"><Link to={TRADE_PATH(fill.code)}>{name(fill.code)}</Link></th>
                <td><span className={`nx-side is-${fill.side}`}>{fill.side === "buy" ? "매수" : "매도"}</span></td>
                <td className="nx-num">{fill.qty.toLocaleString()}주</td><td className="nx-num">{won(fill.price)}</td><td className="nx-num">{won(fill.price * fill.qty)}</td><td className="nx-num">{fillTime(fill.at)}</td>
              </tr>)}
              {tab === "orders" && visibleOrders.map((order) => {
                const buy = order.side.toLowerCase() === "buy";
                return <tr key={order.id}>
                  <th scope="row"><Link to={TRADE_PATH(order.stockCode)}>{name(order.stockCode)}</Link></th>
                  <td><span className={`nx-side is-${buy ? "buy" : "sell"}`}>{buy ? "매수" : "매도"}</span></td>
                  <td className="nx-num">{order.orderType === "MARKET" ? "시장가" : won(order.price)}</td>
                  <td className="nx-num">{order.quantity.toLocaleString()}주</td>
                  <td className="nx-num">{order.remainingQuantity.toLocaleString()}주</td>
                  <td><button type="button" className="nx-btn is-small" disabled={cancelling !== null} onClick={() => void cancel(order.id)}>{cancelling === order.id ? "취소 중" : "취소"}</button></td>
                </tr>;
              })}
            </tbody>
          </table>
        )}
      </div>
      )}

      {/* 미체결 탭에서만 취소 결과와 조회 실패를 알린다. 잔고·체결 탭은 표만 둔다. */}
      {tab === "orders" && user && (
        <div className={`nx-acct-status${error ? " is-error" : ""}`} role="status" aria-live="polite">
          <span>{error || message || `미체결 ${count}건`}</span>
          {error && <button type="button" className="nx-link-button" onClick={() => setRevision((current) => current + 1)}>다시 시도</button>}
        </div>
      )}
    </div>
  );
}
