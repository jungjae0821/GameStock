import { useEffect, useState } from "react";
import { Panel } from "../../../components/Panel";
import { adminTime, count, useCursorList, type Row } from "./adminShared";

/* 서버가 남기는 조치 이름. 목록에 없는 새 조치는 원래 이름 그대로 보인다. */
const ACTIONS: Record<string, string> = {
  TRADE_BAN: "이용 정지",
  TRADE_BAN_LIFT: "이용 정지 해제",
  NICKNAME_FORCE: "닉네임 변경",
  USER_DELETE: "회원 삭제",
  ORDER_CANCEL: "주문 취소",
  SETTLEMENT_SETTLE: "정산 완료 처리",
  SETTLEMENT_REVERSE: "체결 취소",
  RUNTIME: "시장·봇 설정",
  STOCK_CREATE: "종목 등록",
  STOCK_UPDATE: "종목 정보 수정",
  STOCK_STATE: "종목 정지·재개",
  CASH_ADJUST: "현금 조정",
  CASH_BULK: "전체 보상",
  MISSION_RECOVERY: "미션 보상 복구",
  HOLDING_RESTORE: "보유 주식 복구",
  PERMISSIONS: "권한 변경",
  CONTENT_SAVE: "콘텐츠 저장",
  CONTENT_DELETE: "콘텐츠 삭제",
  BILLBOARD_HIDE: "전광판 문구 내림",
  MISSION_CONFIG: "미션 설정",
  REPORT_RESOLVE: "신고 처리",
  COMMENT_DELETE: "댓글 삭제",
  SEASON_CREATE: "시즌 생성",
  SEASON_START: "시즌 시작",
  SEASON_CLOSE: "시즌 종료",
  SEASON_RESET: "시즌 초기화",
  SEASON_USER_RESET: "시즌 회원 초기화",
};
const actionLabel = (action: unknown) => ACTIONS[String(action)] ?? String(action ?? "");

type Source = "audit" | "legacy";
type Filters = { actorId: string; action: string; userId: string };
const EMPTY: Filters = { actorId: "", action: "", userId: "" };

/** 저장된 JSON 문자열을 읽기 좋게 펼친다. 해석할 수 없으면 원문을 그대로 둔다. */
function pretty(value: unknown) {
  if (value === null || value === undefined || value === "") return "";
  try {
    const parsed = typeof value === "string" ? JSON.parse(value) : value;
    if (parsed && typeof parsed === "object" && Object.keys(parsed).length === 0) return "";
    return JSON.stringify(parsed, null, 2);
  } catch {
    return String(value);
  }
}

/**
 * 관리자가 한 일을 최신순으로 본다. 새 기록은 사유와 변경 전후 값을 담고,
 * 운영 API 이전에 저장된 계정 조치는 "이전 기록"에서 따로 본다.
 */
export function AdminAudit() {
  const [source, setSource] = useState<Source>("audit");
  const [draft, setDraft] = useState<Filters>(EMPTY);
  const [applied, setApplied] = useState<Filters>(EMPTY);
  const audit = useCursorList(source === "audit" ? "/api/admin/audit" : "/api/admin/audit/legacy");
  const { rows, loading, done, error, load, lastId } = audit;

  const params = () => {
    const next = new URLSearchParams();
    if (source === "audit") {
      if (applied.actorId) next.set("actorId", applied.actorId);
      if (applied.action) next.set("action", applied.action);
    } else if (applied.userId) next.set("userId", applied.userId);
    return next;
  };

  // source가 바뀌면 load도 새 경로로 바뀌므로 함께 다시 읽는다.
  useEffect(() => { void load(params(), false); }, [applied, load]);

  const switchSource = (next: Source) => {
    if (next === source) return;
    setSource(next);
    setDraft(EMPTY);
    setApplied(EMPTY);
  };
  const filtered = Object.values(applied).some(Boolean);

  return (
    <Panel id="admin-audit" title="감사 기록" meta={loading ? "불러오는 중" : `${count(rows.length)}건${done ? "" : " 이상"}`} level={2}>
      <div className="admin-source" role="group" aria-label="기록 종류">
        <button type="button" className={`text-button${source === "audit" ? " is-active" : ""}`} aria-pressed={source === "audit"}
          onClick={() => switchSource("audit")}>운영 기록</button>
        <button type="button" className={`text-button${source === "legacy" ? " is-active" : ""}`} aria-pressed={source === "legacy"}
          onClick={() => switchSource("legacy")}>이전 기록</button>
        <span className="admin-source-note">
          {source === "audit" ? "사유와 변경 전후 값이 함께 남은 기록입니다." : "운영 API 이전에 남은 이용 정지·닉네임 변경 기록입니다."}
        </span>
      </div>

      <form className="account-form admin-filters" onSubmit={(event) => { event.preventDefault(); setApplied(draft); }}>
        {source === "audit" ? (
          <>
            <div className="account-field">
              <label htmlFor="audit-actor">관리자 번호</label>
              <input id="audit-actor" inputMode="numeric" value={draft.actorId} placeholder="전체"
                onChange={(event) => setDraft({ ...draft, actorId: event.target.value.replace(/\D/g, "") })} />
            </div>
            <div className="account-field">
              <label htmlFor="audit-action">조치</label>
              <select id="audit-action" value={draft.action} onChange={(event) => setDraft({ ...draft, action: event.target.value })}>
                <option value="">전체</option>
                {Object.entries(ACTIONS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </div>
          </>
        ) : (
          <div className="account-field">
            <label htmlFor="audit-user">대상 회원 번호</label>
            <input id="audit-user" inputMode="numeric" value={draft.userId} placeholder="전체"
              onChange={(event) => setDraft({ ...draft, userId: event.target.value.replace(/\D/g, "") })} />
          </div>
        )}
        <div className="admin-filter-actions">
          <button type="submit" className="account-button is-primary" disabled={loading}>조회</button>
          {filtered && <button type="button" className="text-button" onClick={() => { setDraft(EMPTY); setApplied(EMPTY); }}>조건 지우기</button>}
        </div>
      </form>

      {error && <p className="account-message" role="alert">{error}</p>}

      {source === "audit" ? (
        <ol className="audit-list">
          {rows.map((row) => <AuditEntry key={String(row.id)} row={row} />)}
        </ol>
      ) : (
        <div className="table-scroll admin-table-scroll">
          <table className="table admin-table">
            <caption className="vh">이전 관리자 조치 기록</caption>
            <thead>
              <tr>
                <th scope="col">시각</th>
                <th scope="col">관리자</th>
                <th scope="col">대상 회원</th>
                <th scope="col">조치</th>
                <th scope="col">내용</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={String(row.id)}>
                  <td className="num">{adminTime(row.created_at)}</td>
                  <td className="num">#{String(row.admin_id)}</td>
                  <td className="num">#{String(row.target_user_id)}</td>
                  <td>{actionLabel(row.action)}</td>
                  <td>{String(row.detail ?? "") || "-"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {!loading && !error && rows.length === 0 && (
        <p className="empty">{filtered ? "조건에 맞는 기록이 없습니다." : "아직 남은 기록이 없습니다."}</p>
      )}
      {!done && rows.length > 0 && (
        <button type="button" className="account-button admin-more" disabled={loading} onClick={() => void load(params(), true, lastId)}>
          {loading ? "불러오는 중" : "다음 50건"}
        </button>
      )}
    </Panel>
  );
}

function AuditEntry({ row }: { row: Row }) {
  const before = pretty(row.before_json);
  const after = pretty(row.after_json);

  return (
    <li className="audit-entry">
      <div className="audit-line">
        <strong className="audit-action">{actionLabel(row.action)}</strong>
        <span className="audit-target">대상 {String(row.target ?? "-")}</span>
        <span className="audit-meta num">관리자 #{String(row.admin_id)}, {adminTime(row.created_at)}</span>
      </div>
      <p className="audit-reason">{String(row.reason ?? "")}</p>
      {(before || after) && (
        <details className="audit-diff">
          <summary>변경 내용</summary>
          <div className="audit-diff-grid">
            <div>
              <h4>변경 전</h4>
              <pre>{before || "없음"}</pre>
            </div>
            <div>
              <h4>변경 후</h4>
              <pre>{after || "없음"}</pre>
            </div>
          </div>
        </details>
      )}
    </li>
  );
}
