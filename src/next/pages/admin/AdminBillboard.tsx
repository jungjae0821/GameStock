import { useEffect, useState } from "react";
import { Panel } from "../../../components/Panel";
import { apiFetch } from "../../../lib/api";
import { won } from "../../../market/format";
import { adminTime, count, num, useCursorList, type Row } from "./adminShared";

const STATE_LABEL: Record<string, string> = {
  UPCOMING: "예약",
  LIVE: "노출 중",
  ENDED: "끝남",
  HIDDEN: "내림",
  CANCELLED: "본인 취소",
};

function stateOf(row: Row, now: number): string {
  const status = String(row.status ?? "");
  if (status !== "BOOKED") return status;
  const start = new Date(String(row.starts_at)).getTime();
  const end = new Date(String(row.ends_at)).getTime();
  return now < start ? "UPCOMING" : now < end ? "LIVE" : "ENDED";
}

/** 투자자가 올린 전광판 문구. 내리면 홈에서 바로 사라지고, 돌려주기를 고르면 낸 값을 전부 돌려준다. */
export function AdminBillboard() {
  const { rows, loading, done, error, load, lastId } = useCursorList("/api/admin/billboard");
  const [target, setTarget] = useState<Row | null>(null);
  const [reason, setReason] = useState("");
  const [refund, setRefund] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const now = Date.now();

  useEffect(() => { void load(new URLSearchParams(), false); }, [load]);

  const hide = async () => {
    if (!target || !reason.trim()) return;
    setBusy(true);
    setMessage("");
    try {
      const result = await apiFetch<{ refunded: number }>(`/api/admin/billboard/${String(target.id)}/hide`, {
        method: "POST",
        body: JSON.stringify({ reason: reason.trim(), refund }),
      });
      setMessage(`#${String(target.id)} 문구를 내렸습니다.${result.refunded > 0 ? ` ${won(result.refunded)}을 돌려줬습니다.` : ""}`);
      setTarget(null);
      setReason("");
      void load(new URLSearchParams(), false);
    } catch (cause) {
      setMessage(cause instanceof Error ? cause.message : "문구를 내리지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel id="admin-billboard" title="전광판 문구" meta={loading ? "불러오는 중" : `${count(rows.length)}건${done ? "" : " 이상"}`} level={2}>
      {error && <p className="account-message" role="alert">{error}</p>}
      {message && <p className="account-message" role="status">{message}</p>}

      {target && (
        <form className="account-form admin-billboard-hide" onSubmit={(event) => { event.preventDefault(); void hide(); }}>
          <p className="admin-billboard-target">
            <strong>#{String(target.id)}</strong> {String(target.nickname ?? "")}: “{String(target.body ?? "")}”
          </p>
          <div className="account-field">
            <label htmlFor="billboard-reason">사유 (감사 기록에 남음)</label>
            <input id="billboard-reason" value={reason} maxLength={300} placeholder="예: 욕설 신고 확인" onChange={(event) => setReason(event.target.value)} autoFocus />
          </div>
          <label className="notice-check">
            <input type="checkbox" checked={refund} onChange={(event) => setRefund(event.target.checked)} />
            낸 값 {won(num(target.price))} 돌려주기 (보상 권한 필요)
          </label>
          <div className="admin-filter-actions">
            <button type="submit" className="account-button is-danger" disabled={busy || !reason.trim()}>{busy ? "처리 중" : "내리기"}</button>
            <button type="button" className="text-button" disabled={busy} onClick={() => setTarget(null)}>그만두기</button>
          </div>
        </form>
      )}

      <div className="table-scroll admin-table-scroll">
        <table className="table admin-table">
          <caption className="vh">전광판 문구 목록</caption>
          <thead>
            <tr>
              <th scope="col" className="cell-num">번호</th>
              <th scope="col">회원</th>
              <th scope="col">문구</th>
              <th scope="col">노출 시간</th>
              <th scope="col" className="cell-num">값</th>
              <th scope="col">상태</th>
              <th scope="col"><span className="vh">조치</span></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const state = stateOf(row, now);
              const open = state === "UPCOMING" || state === "LIVE";
              return (
                <tr key={String(row.id)}>
                  <td className="cell-num num">{String(row.id)}</td>
                  <td>{String(row.nickname ?? "")}<small className="admin-uid">#{String(row.user_id ?? "")}</small></td>
                  <td className="admin-billboard-body">
                    {String(row.body ?? "")}
                    {row.hidden_reason ? <small className="admin-uid">사유: {String(row.hidden_reason)}</small> : null}
                  </td>
                  <td className="num">{adminTime(row.starts_at)}<small className="admin-uid">{num(row.hours)}시간</small></td>
                  <td className="cell-num num">{won(num(row.price))}{num(row.refunded) > 0 && <small className="admin-uid">환불 {won(num(row.refunded))}</small>}</td>
                  <td className={`admin-status is-${state.toLowerCase()}`}>{STATE_LABEL[state] ?? state}</td>
                  <td>{open && <button type="button" className="text-button" onClick={() => { setTarget(row); setMessage(""); }}>내리기</button>}</td>
                </tr>
              );
            })}
            {!loading && !error && rows.length === 0 && <tr><td colSpan={7} className="empty">아직 올라온 문구가 없습니다.</td></tr>}
          </tbody>
        </table>
      </div>
      {!done && rows.length > 0 && (
        <button type="button" className="account-button admin-more" disabled={loading} onClick={() => void load(new URLSearchParams(), true, lastId)}>
          {loading ? "불러오는 중" : "다음 50건"}
        </button>
      )}
    </Panel>
  );
}
