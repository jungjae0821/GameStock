import { useCallback, useEffect, useState } from "react";
import { Panel } from "../../../components/Panel";
import { apiFetch } from "../../../lib/api";
import { won } from "../../../market/format";
import { AdminMemberDetail, CashAdjustment, MissionRecovery } from "./AdminMember";

type AdminUser = {
  id: number;
  uid: string | null;
  email: string | null;
  nickname: string;
  role: string;
  cash: number;
  createdAt: string | null;
  nicknameChangedAt: string | null;
  tradeBannedUntil: string | null;
  tradeBanReason: string | null;
};

const BAN_PRESETS = [1, 3, 7, 30] as const;

const formatTime = (value: string | null) =>
  value ? new Date(value).toLocaleString("ko-KR", { timeZone: "Asia/Seoul", dateStyle: "short", timeStyle: "short" }) : "-";

export function AdminUsers() {
  const [query, setQuery] = useState("");
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState("");
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const search = useCallback(async (q: string) => {
    setLoading(true);
    setMessage("");
    try {
      setUsers(await apiFetch<AdminUser[]>(`/api/admin/users${q.trim() ? `?q=${encodeURIComponent(q.trim())}` : ""}`));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "계정 목록을 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void search(""); }, [search]);

  const replace = (next: AdminUser) => setUsers((list) => list.map((user) => (user.id === next.id ? next : user)));
  const remove = (removed: AdminUser) => {
    setUsers((list) => list.filter((user) => user.id !== removed.id));
    setSelectedId(null);
    setMessage(`${removed.nickname}(#${removed.id}) 계정을 삭제했습니다.`);
  };
  const selected = users.find((user) => user.id === selectedId) ?? null;

  return (
    <div className="account-settings admin-page">
      <Panel id="admin-users" title="계정 · 닉네임 조회" meta={loading ? "불러오는 중" : `${users.length}명`} level={3}>
        <form className="account-form" onSubmit={(event) => { event.preventDefault(); void search(query); }}>
          <label htmlFor="admin-search">이메일 · 닉네임 · UID · 계정 번호</label>
          <div className="account-input-row">
            <input id="admin-search" value={query} placeholder="비워 두면 최근 가입 50명" onChange={(event) => setQuery(event.target.value)} />
            <button type="submit" className="account-button is-primary" disabled={loading}>검색</button>
          </div>
        </form>
        {message && <p className="account-message" role="status">{message}</p>}
        <div className="table-scroll admin-table-scroll">
          <table className="table admin-table">
            <thead>
              <tr>
                <th scope="col"><span className="vh">관리</span></th>
                <th scope="col">번호</th>
                <th scope="col">로그인 계정</th>
                <th scope="col">닉네임</th>
                <th scope="col">가입</th>
                <th scope="col" className="cell-num">현금</th>
                <th scope="col">이용</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.id} className={user.id === selectedId ? "is-selected" : undefined}>
                  {/* 이 표에서 하는 일은 회원을 골라 조치하는 것이므로, 폰에서 가로로 밀지 않아도 보이게 맨 앞에 둔다. */}
                  <td>
                    <button type="button" className="text-button" aria-expanded={user.id === selectedId}
                      onClick={() => setSelectedId(user.id === selectedId ? null : user.id)}>
                      {user.id === selectedId ? "닫기" : "관리"}
                    </button>
                  </td>
                  <td className="num">{user.id}</td>
                  <td>
                    <span className="admin-email">{user.email || "(이메일 없음)"}</span>
                    <small className="admin-uid" title={user.uid ?? ""}>{user.uid}</small>
                  </td>
                  <td>
                    {user.nickname}
                    {user.role === "ADMIN" && <span className="admin-badge">관리자</span>}
                  </td>
                  <td className="num">{formatTime(user.createdAt)}</td>
                  <td className="cell-num num admin-cash">{won(user.cash)}</td>
                  <td>
                    {user.tradeBannedUntil
                      ? <span className="admin-banned" title={user.tradeBanReason ?? ""}>~{formatTime(user.tradeBannedUntil)}</span>
                      : <span className="admin-ok">정상</span>}
                  </td>
                </tr>
              ))}
              {!loading && users.length === 0 && (
                <tr><td colSpan={7} className="empty">검색 결과가 없습니다.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </Panel>
      {selected && <AdminUserActions key={selected.id} user={selected} onChange={replace} onDeleted={remove} />}
    </div>
  );
}

function AdminUserActions({ user, onChange, onDeleted }: { user: AdminUser; onChange: (user: AdminUser) => void; onDeleted: (user: AdminUser) => void }) {
  const [days, setDays] = useState("7");
  // 사유는 정지당한 사용자에게 그대로 보이므로 미리 채우지 않는다. 관리자가 직접 적어야 정지할 수 있다.
  const [reason, setReason] = useState("");
  const [nickname, setNickname] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  // 현금 조정·보상 복구가 끝나면 자산을 다시 읽는다.
  const [assetsVersion, setAssetsVersion] = useState(0);
  const refreshAssets = () => setAssetsVersion((version) => version + 1);
  const syncCash = (cash: number) => { if (cash !== user.cash) onChange({ ...user, cash }); };

  const run = async (action: () => Promise<AdminUser>, done: string) => {
    setBusy(true);
    setMessage("");
    try {
      onChange(await action());
      setMessage(done);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "처리하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  const ban = () => {
    const value = Number(days);
    if (!Number.isInteger(value) || value < 1 || value > 365) {
      setMessage("정지 기간은 1~365일로 입력해 주세요.");
      return;
    }
    if (!window.confirm(`${user.nickname} 계정을 ${value}일 동안 이용 정지할까요?\n정지 중에는 로그인과 정지 안내만 볼 수 있고, 미체결 주문은 모두 취소됩니다.`)) return;
    void run(() => apiFetch<AdminUser>(`/api/admin/users/${user.id}/trade-ban`, {
      method: "PUT",
      body: JSON.stringify({ days: value, reason }),
    }), `${value}일 이용 정지했습니다.`);
  };

  const lift = () => {
    if (!window.confirm(`${user.nickname} 계정의 이용 정지를 해제할까요?`)) return;
    void run(() => apiFetch<AdminUser>(`/api/admin/users/${user.id}/trade-ban`, { method: "DELETE" }), "이용 정지를 해제했습니다.");
  };

  const rename = () => {
    const value = nickname.trim();
    if (value && (value.length < 2 || value.length > 10)) {
      setMessage("닉네임은 2~10자로 입력해 주세요.");
      return;
    }
    const label = value || "자동 생성 닉네임";
    if (!window.confirm(`"${user.nickname}" 닉네임을 "${label}"(으)로 바꿀까요?`)) return;
    void run(async () => {
      const next = await apiFetch<AdminUser>(`/api/admin/users/${user.id}/nickname`, {
        method: "PUT",
        body: JSON.stringify({ nickname: value }),
      });
      setNickname("");
      return next;
    }, "닉네임을 변경했습니다.");
  };

  return (
    <Panel id="admin-user-actions" title={user.nickname} meta={`#${user.id} ${user.email || "이메일 없음"}`} level={3}>
      <AdminMemberDetail userId={user.id} refreshKey={assetsVersion} onCash={syncCash} />
      <h4 className="admin-actions-heading">조치</h4>
      {message && <p className="account-message" role="status">{message}</p>}
      <div className="admin-actions">
        <section className="account-form" aria-labelledby="admin-ban-title">
          <h4 id="admin-ban-title" className="admin-action-title">이용 정지</h4>
          {user.tradeBannedUntil ? (
            <>
              <p className="account-hint">
                {formatTime(user.tradeBannedUntil)}까지 모든 서비스 이용이 정지되어 있습니다.
                {user.tradeBanReason && <> 사유: {user.tradeBanReason}</>}
              </p>
              <div className="account-input-row">
                <button type="button" className="account-button" disabled={busy} onClick={lift}>정지 해제</button>
              </div>
            </>
          ) : (
            <p className="account-hint">정지 중에는 로그인과 정지 안내만 볼 수 있습니다. 미체결 주문은 바로 취소됩니다.</p>
          )}
          <div className="admin-presets" role="group" aria-label="정지 기간 빠른 선택">
            {BAN_PRESETS.map((preset) => (
              <button key={preset} type="button" className={`text-button${days === String(preset) ? " is-active" : ""}`}
                aria-pressed={days === String(preset)} onClick={() => setDays(String(preset))}>{preset}일</button>
            ))}
          </div>
          <label htmlFor="admin-ban-days">기간(일)</label>
          <input id="admin-ban-days" type="number" min={1} max={365} value={days} onChange={(event) => setDays(event.target.value)} />
          <label htmlFor="admin-ban-reason">사유 (사용자에게 표시됨)</label>
          <div className="account-input-row">
            <input id="admin-ban-reason" value={reason} maxLength={200} required placeholder="사용자에게 보일 정지 사유"
              onChange={(event) => setReason(event.target.value)} />
            <button type="button" className="account-button is-danger" disabled={busy || user.role === "ADMIN" || !reason.trim()} onClick={ban}>
              {user.tradeBannedUntil ? "기간 다시 설정" : "이용 정지"}
            </button>
          </div>
        </section>

        <section className="account-form" aria-labelledby="admin-nick-title">
          <h4 id="admin-nick-title" className="admin-action-title">닉네임 강제 변경</h4>
          <p className="account-hint">현재: {user.nickname} · 비워 두면 "슈엔단0000" 형식으로 자동 생성합니다. 3일 변경 제한을 무시합니다.</p>
          <label htmlFor="admin-nickname">새 닉네임</label>
          <div className="account-input-row">
            <input id="admin-nickname" value={nickname} maxLength={10} placeholder="자동 생성" onChange={(event) => setNickname(event.target.value)} />
            <button type="button" className="account-button is-primary" disabled={busy} onClick={rename}>변경</button>
          </div>
        </section>

        {user.role === "ADMIN" ? (
          <p className="account-hint admin-actions-wide">관리자 계정에는 현금 조정과 보상 복구를 할 수 없습니다.</p>
        ) : (
          <>
            <CashAdjustment userId={user.id} nickname={user.nickname} cash={user.cash} onDone={refreshAssets} />
            <MissionRecovery userId={user.id} nickname={user.nickname} onDone={refreshAssets} />
          </>
        )}
      </div>
      {user.role !== "ADMIN" && <AdminUserDelete user={user} onDeleted={onDeleted} />}
    </Panel>
  );
}

/**
 * 회원 완전 삭제. 되돌릴 수 없어서 다른 조치와 떨어진 맨 아래에 두고,
 * 닉네임을 그대로 입력해야 버튼이 켜진다. 서버도 같은 닉네임을 다시 확인한다.
 */
function AdminUserDelete({ user, onDeleted }: { user: AdminUser; onDeleted: (user: AdminUser) => void }) {
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const matches = typed.trim() === user.nickname;

  const submit = async () => {
    if (!matches) return;
    setBusy(true);
    setMessage("");
    try {
      await apiFetch<void>(`/api/admin/users/${user.id}?confirm=${encodeURIComponent(typed.trim())}`, { method: "DELETE" });
      onDeleted(user);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "계정을 삭제하지 못했습니다.");
      setBusy(false);
    }
  };

  return (
    <section className="account-form admin-delete" aria-labelledby="admin-delete-title">
      <h4 id="admin-delete-title" className="admin-action-title">계정 삭제</h4>
      <p className="account-hint">
        계정과 주문·체결·보유 주식·댓글 등 이 회원의 모든 기록을 지웁니다. 이 회원과 맺은 체결은 상대 회원의 거래 내역에서도 사라지고, 되돌릴 수 없습니다.
        미체결 주문은 먼저 취소하며, 정산 대기 중인 체결이 있으면 삭제하지 않습니다.
      </p>
      <label htmlFor="admin-delete-confirm">확인을 위해 닉네임 <strong>{user.nickname}</strong>을(를) 입력하세요</label>
      <div className="account-input-row">
        <input id="admin-delete-confirm" value={typed} autoComplete="off" spellCheck={false}
          onChange={(event) => setTyped(event.target.value)}
          onKeyDown={(event) => { if (event.key === "Enter") void submit(); }} />
        <button type="button" className="account-button is-danger" disabled={busy || !matches} onClick={() => void submit()}>
          {busy ? "삭제하는 중" : "계정 삭제"}
        </button>
      </div>
      {message && <p className="admin-form-message" role="alert">{message}</p>}
    </section>
  );
}
