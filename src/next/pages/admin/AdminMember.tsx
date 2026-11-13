import { useEffect, useState } from "react";
import { Link } from "../../../components/Link";
import { missionIds, missionTitle } from "../../../components/MissionBoard";
import { apiFetch } from "../../../lib/api";
import { won } from "../../../market/format";
import { LISTING_BY_CODE } from "../../../market/universe";
import { ORDER_STATUS, adminTime, count, num, type Row } from "./adminShared";

type Portfolio = {
  cash: number;
  assetValue: number;
  totalAsset: number;
  unsettledCash: number;
  loanBalance: number;
  attendanceRewardCash: number;
  missionRewardCash: number;
  adminAdjustmentCash: number;
};
type Detail = Row & { portfolio: Portfolio; holdings: Row[] };

const stockName = (code: string) => LISTING_BY_CODE[code]?.name ?? code;

/**
 * 회원 한 명의 자산과 최근 주문. 문의가 오면 가장 먼저 보는 화면이다.
 * refreshKey가 바뀌면(현금 조정·보상 복구 뒤) 다시 읽고, 읽은 현금을 목록에 알린다.
 */
export function AdminMemberDetail({ userId, refreshKey, onCash }: { userId: number; refreshKey: number; onCash: (cash: number) => void }) {
  const [detail, setDetail] = useState<Detail | null>(null);
  const [orders, setOrders] = useState<Row[]>([]);
  const [error, setError] = useState("");

  useEffect(() => {
    let alive = true;
    setError("");
    Promise.all([
      apiFetch<Detail>(`/api/admin/users/${userId}`),
      apiFetch<Row[]>(`/api/admin/orders?userId=${userId}&limit=10`),
    ]).then(([nextDetail, nextOrders]) => {
      if (!alive) return;
      setDetail(nextDetail);
      setOrders(nextOrders);
      onCash(nextDetail.portfolio.cash);
    }).catch((failure) => {
      if (alive) setError(failure instanceof Error ? failure.message : "회원 정보를 불러오지 못했습니다.");
    });
    return () => { alive = false; };
    // onCash는 목록의 현금만 고치는 콜백이라, 바뀌어도 다시 읽지 않는다.
  }, [userId, refreshKey]);

  if (error) return <p className="account-message" role="alert">{error}</p>;
  if (!detail) return <p className="empty" role="status">회원 정보를 불러오는 중입니다.</p>;

  const p = detail.portfolio;
  const holdings = detail.holdings.filter((row) => num(row.quantity) > 0);
  const rewards = [
    p.attendanceRewardCash ? `출석 ${won(p.attendanceRewardCash)}` : "",
    p.missionRewardCash ? `미션 ${won(p.missionRewardCash)}` : "",
    p.adminAdjustmentCash ? `관리자 조정 ${won(p.adminAdjustmentCash)}` : "",
  ].filter(Boolean);

  return (
    <div className="member">
      <dl className="member-ledger">
        <div><dt>현금</dt><dd className="num">{won(p.cash)}</dd></div>
        <div><dt>주식 평가액</dt><dd className="num">{won(p.assetValue)}</dd></div>
        <div><dt>정산 대기 금액</dt><dd className="num">{won(p.unsettledCash)}</dd></div>
        <div><dt>대출</dt><dd className={`num${p.loanBalance > 0 ? " is-debt" : ""}`}>{p.loanBalance > 0 ? `-${won(p.loanBalance)}` : won(0)}</dd></div>
        <div className="is-total"><dt>총자산</dt><dd className="num">{won(p.totalAsset)}</dd></div>
      </dl>
      {rewards.length > 0 && <p className="member-note">받은 보상과 조정: {rewards.join(", ")}. 수익률 계산에서는 빠집니다.</p>}

      <div className="member-columns">
        <section aria-labelledby="member-holdings">
          <h4 id="member-holdings" className="member-title">보유 주식</h4>
          {holdings.length ? (
            <div className="table-scroll">
              <table className="table member-table">
                <thead>
                  <tr>
                    <th scope="col">종목</th>
                    <th scope="col" className="cell-num">수량</th>
                    <th scope="col" className="cell-num">평균 단가</th>
                    <th scope="col" className="cell-num">평가손익</th>
                  </tr>
                </thead>
                <tbody>
                  {holdings.map((row) => {
                    const quantity = num(row.quantity);
                    const gain = (num(row.current_price) - num(row.average_price)) * quantity;
                    return (
                      <tr key={String(row.stock_code)}>
                        <td>{stockName(String(row.stock_code))}</td>
                        <td className="cell-num num" title={`결제 완료 ${count(num(row.settled_quantity))}주`}>{count(quantity)}주</td>
                        <td className="cell-num num">{won(num(row.average_price))}</td>
                        <td className={`cell-num num ${gain > 0 ? "is-up" : gain < 0 ? "is-down" : ""}`}>{gain > 0 ? "+" : ""}{won(gain)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          ) : <p className="member-empty">보유한 주식이 없습니다.</p>}
        </section>

        <section aria-labelledby="member-orders">
          <h4 id="member-orders" className="member-title">
            최근 주문
            <Link className="member-more" to={`/admin/orders?userId=${userId}`}>전체 주문 보기</Link>
          </h4>
          {orders.length ? (
            <ul className="member-orders">
              {orders.map((row) => {
                const buy = row.side === "BUY";
                return (
                  <li key={String(row.id)}>
                    <span className="num member-order-time">{adminTime(row.created_at)}</span>
                    <span>{stockName(String(row.stock_code))}</span>
                    <span className={buy ? "is-up" : "is-down"}>{buy ? "매수" : "매도"}</span>
                    <span className="num">{count(num(row.quantity) - num(row.remaining_quantity))}/{count(num(row.quantity))}주</span>
                    <span className="member-order-status">{ORDER_STATUS[String(row.status)] ?? String(row.status)}</span>
                  </li>
                );
              })}
            </ul>
          ) : <p className="member-empty">주문 기록이 없습니다.</p>}
        </section>
      </div>
    </div>
  );
}

/** 같은 조치를 두 번 보내도 한 번만 처리되도록 서버에 넘기는 키. 폼을 새로 쓸 때마다 바꾼다. */
const newRequestKey = (userId: number) =>
  `cash-${userId}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

/**
 * 현금 지급·차감. 같은 키로 다시 보내면 서버가 중복 처리를 막으므로, 응답을 못 받고
 * 다시 눌러도 두 번 지급되지 않는다. 성공한 뒤에만 키를 새로 만든다.
 */
export function CashAdjustment({ userId, nickname, cash, onDone }: { userId: number; nickname: string; cash: number; onDone: () => void }) {
  const [direction, setDirection] = useState<"add" | "subtract">("add");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [requestKey, setRequestKey] = useState(() => newRequestKey(userId));
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const value = Number(amount.replace(/,/g, ""));
  const signed = direction === "add" ? value : -value;
  const valid = Number.isInteger(value) && value > 0 && value <= 1_000_000_000 && reason.trim().length > 0;

  const submit = async () => {
    if (!valid) return;
    if (direction === "subtract" && value > cash) {
      setMessage(`현재 현금 ${won(cash)}보다 많이 차감할 수 없습니다.`);
      return;
    }
    const verb = direction === "add" ? "지급" : "차감";
    if (!window.confirm(`${nickname} 계정에 ${won(value)}을 ${verb}할까요?\n처리 후 현금: ${won(cash + signed)}`)) return;
    setBusy(true);
    setMessage("");
    try {
      const result = await apiFetch<{ applied: boolean }>(`/api/admin/users/${userId}/cash-adjustments`, {
        method: "POST",
        body: JSON.stringify({ requestKey, amount: signed, reason: reason.trim() }),
      });
      setMessage(result.applied ? `${won(value)}을 ${verb}했습니다.` : "이미 처리된 요청이라 다시 반영하지 않았습니다.");
      setAmount("");
      setReason("");
      setRequestKey(newRequestKey(userId));
      onDone();
    } catch (failure) {
      setMessage(failure instanceof Error ? failure.message : "현금을 조정하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="account-form" aria-labelledby="admin-cash-title">
      <h4 id="admin-cash-title" className="admin-action-title">현금 조정</h4>
      <p className="account-hint">현재 {won(cash)}. 조정한 금액은 수익률 계산에서 빠집니다.</p>
      <div className="admin-presets" role="group" aria-label="조정 방향">
        <button type="button" className={`text-button${direction === "add" ? " is-active" : ""}`} aria-pressed={direction === "add"} onClick={() => setDirection("add")}>지급</button>
        <button type="button" className={`text-button${direction === "subtract" ? " is-active" : ""}`} aria-pressed={direction === "subtract"} onClick={() => setDirection("subtract")}>차감</button>
      </div>
      <label htmlFor="admin-cash-amount">금액(원)</label>
      <input id="admin-cash-amount" inputMode="numeric" value={amount} placeholder="예: 50,000"
        onChange={(event) => {
          const digits = event.target.value.replace(/\D/g, "").slice(0, 10);
          setAmount(digits ? Number(digits).toLocaleString("ko-KR") : "");
        }} />
      <label htmlFor="admin-cash-reason">사유 (감사 기록에 남음)</label>
      <div className="account-input-row">
        <input id="admin-cash-reason" value={reason} maxLength={300} placeholder="예: 점검 중 누락된 보상 보전" onChange={(event) => setReason(event.target.value)} />
        <button type="button" className={`account-button ${direction === "add" ? "is-primary" : "is-danger"}`} disabled={busy || !valid} onClick={() => void submit()}>
          {direction === "add" ? "지급" : "차감"}
        </button>
      </div>
      {message && <p className="admin-form-message" role="status">{message}</p>}
    </section>
  );
}

type MissionConfig = { mission_id: string; reward_cash: number };

const todayInSeoul = () => new Date().toLocaleDateString("sv-SE", { timeZone: "Asia/Seoul" });

/**
 * 받지 못한 미션 보상을 다시 지급한다. 그날 그 미션의 수령 기록을 함께 만들므로
 * 회원이 같은 날 같은 미션으로 또 받을 수 없다. 이미 받은 기록이 있으면 지급하지 않는다.
 */
export function MissionRecovery({ userId, nickname, onDone }: { userId: number; nickname: string; onDone: () => void }) {
  const [rewards, setRewards] = useState<Record<string, number>>({});
  const [missionId, setMissionId] = useState(missionIds[0]);
  const [date, setDate] = useState(todayInSeoul);
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => {
    apiFetch<MissionConfig[]>("/api/admin/missions")
      .then((list) => setRewards(Object.fromEntries(list.map((row) => [row.mission_id, num(row.reward_cash)]))))
      .catch(() => { /* 기본 보상액을 모르면 금액을 직접 입력한다. */ });
  }, []);

  const configured = rewards[missionId];
  const value = amount ? Number(amount.replace(/,/g, "")) : configured ?? 0;
  const valid = Number.isInteger(value) && value >= 1 && value <= 1_000_000 && reason.trim().length > 0 && Boolean(date);

  const submit = async () => {
    if (!valid) return;
    if (!window.confirm(`${nickname} 계정에 ${date} "${missionTitle(missionId)}" 보상 ${won(value)}을 지급할까요?`)) return;
    setBusy(true);
    setMessage("");
    try {
      const result = await apiFetch<{ awarded: boolean }>(`/api/admin/users/${userId}/mission-rewards`, {
        method: "POST",
        body: JSON.stringify({ missionId, rewardedOn: date, amount: value, reason: reason.trim() }),
      });
      if (result.awarded) {
        setMessage(`${won(value)}을 지급했습니다.`);
        setReason("");
        setAmount("");
        onDone();
      } else {
        setMessage("그날 이미 받은 보상이라 지급하지 않았습니다. 미션 보상은 회원의 내 지갑으로 들어가며, 현금 조정은 운용계좌만 바꿉니다.");
      }
    } catch (failure) {
      setMessage(failure instanceof Error ? failure.message : "보상을 지급하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="account-form" aria-labelledby="admin-mission-title">
      <h4 id="admin-mission-title" className="admin-action-title">미션 보상 복구</h4>
      <p className="account-hint">받지 못한 미션 보상을 그날 받은 것으로 기록하고 지급합니다.</p>
      <label htmlFor="admin-mission-id">미션</label>
      <select id="admin-mission-id" value={missionId} onChange={(event) => { setMissionId(event.target.value); setAmount(""); }}>
        {missionIds.map((id) => <option key={id} value={id}>{missionTitle(id)}</option>)}
      </select>
      <div className="admin-form-pair">
        <div className="account-field">
          <label htmlFor="admin-mission-date">보상일</label>
          <input id="admin-mission-date" type="date" value={date} max={todayInSeoul()} onChange={(event) => setDate(event.target.value)} />
        </div>
        <div className="account-field">
          <label htmlFor="admin-mission-amount">금액(원)</label>
          <input id="admin-mission-amount" inputMode="numeric" value={amount}
            placeholder={configured ? `기본 ${configured.toLocaleString("ko-KR")}` : "1~1,000,000"}
            onChange={(event) => {
              const digits = event.target.value.replace(/\D/g, "").slice(0, 7);
              setAmount(digits ? Number(digits).toLocaleString("ko-KR") : "");
            }} />
        </div>
      </div>
      <label htmlFor="admin-mission-reason">사유 (감사 기록에 남음)</label>
      <div className="account-input-row">
        <input id="admin-mission-reason" value={reason} maxLength={300} placeholder="예: 지급 오류로 누락" onChange={(event) => setReason(event.target.value)} />
        <button type="button" className="account-button is-primary" disabled={busy || !valid} onClick={() => void submit()}>지급</button>
      </div>
      {message && <p className="admin-form-message" role="status">{message}</p>}
    </section>
  );
}
