import { useCallback, useEffect, useMemo, useState } from "react";
import { Panel } from "../../../components/Panel";
import { SiteNoticeCard } from "../../../components/SiteNoticeDialog";
import { apiFetch } from "../../../lib/api";
import { joinNoticeCode, splitNoticeCode, type SiteNoticeItem } from "../../../notice";
import { adminTime, num, type Row } from "./adminShared";

type Draft = {
  title: string; body: string; code: string; image: string; actionLabel: string; actionUrl: string; dismissLabel: string;
  published: boolean; startsAt: string; endsAt: string;
};
type State = "live" | "scheduled" | "ended" | "hidden";

const EMPTY: Draft = { title: "", body: "", code: "", image: "", actionLabel: "", actionUrl: "", dismissLabel: "", published: true, startsAt: "", endsAt: "" };
/** 서버와 같은 규칙: 사이트 안 경로(/...)나 https 주소. */
const LINK = /^(https:\/\/\S+|\/(?!\/)\S*)$/i;
const STATE_LABEL: Record<State, string> = { live: "게시 중", scheduled: "예약", ended: "종료", hidden: "숨김" };

/** 서버 시각 → datetime-local 값(한국 시간). */
function toLocalInput(value: unknown) {
  if (!value) return "";
  const date = new Date(typeof value === "number" ? value : String(value));
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleString("sv-SE", { timeZone: "Asia/Seoul" }).slice(0, 16).replace(" ", "T");
}
/** datetime-local 값(한국 시간) → ISO. 비어 있으면 null. */
const toInstant = (value: string) => (value ? new Date(`${value}:00+09:00`).toISOString() : null);

function stateOf(published: boolean, startsAt: unknown, endsAt: unknown, now = Date.now()): State {
  if (!published) return "hidden";
  if (startsAt && new Date(String(startsAt)).getTime() > now) return "scheduled";
  if (endsAt && new Date(String(endsAt)).getTime() <= now) return "ended";
  return "live";
}
const rowState = (row: Row) => stateOf(Boolean(row.published), row.starts_at, row.ends_at);

function draftFrom(row: Row): Draft {
  const { body, code } = splitNoticeCode(String(row.body ?? ""));
  return {
    title: String(row.title ?? ""),
    body,
    code,
    image: String(row.image_url ?? ""),
    actionLabel: String(row.action_label ?? ""),
    actionUrl: String(row.action_url ?? ""),
    dismissLabel: String(row.dismiss_label ?? ""),
    published: Boolean(row.published),
    startsAt: toLocalInput(row.starts_at),
    endsAt: toLocalInput(row.ends_at),
  };
}

/**
 * 첫 방문 공지 편집. 게시 중인 공지는 접속할 때마다 아직 확인하지 않은 최신 공지 하나씩 사용자에게 보이고,
 * 고친 공지는 이미 확인한 사람에게도 다시 보인다. 오른쪽 미리보기는 실제 공지 창과 같은 마크업이다.
 */
export function AdminNotices() {
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editingId, setEditingId] = useState<number | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await apiFetch<Row[]>("/api/admin/content?kind=NOTICE&limit=100"));
      setError("");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "공지를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const startNew = () => { setEditingId(null); setDraft(EMPTY); setReason(""); setMessage(""); };
  const edit = (row: Row) => { setEditingId(num(row.id)); setDraft(draftFrom(row)); setReason(""); setMessage(""); };
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((current) => ({ ...current, [key]: value }));

  const draftState = stateOf(draft.published, toInstant(draft.startsAt), toInstant(draft.endsAt));
  const timeError = draft.startsAt && draft.endsAt && draft.endsAt <= draft.startsAt ? "끝나는 시각은 시작 시각보다 뒤여야 합니다." : "";
  const image = draft.image.trim(), actionLabel = draft.actionLabel.trim(), actionUrl = draft.actionUrl.trim();
  const linkError = (image && !LINK.test(image)) || (actionUrl && !LINK.test(actionUrl))
    ? "그림·이동 주소는 /로 시작하는 경로나 https:// 주소여야 합니다."
    : Boolean(actionLabel) !== Boolean(actionUrl) ? "이동 버튼은 문구와 주소를 함께 적어 주세요." : "";
  const valid = draft.title.trim() && draft.body.trim() && reason.trim() && !timeError && !linkError;
  const liveCount = rows.filter((row) => num(row.id) !== editingId && rowState(row) === "live").length + (draftState === "live" ? 1 : 0);

  const preview = useMemo<SiteNoticeItem>(() => ({
    key: "preview",
    heading: draft.title.trim() || "제목",
    body: draft.body.trim() || "본문",
    ...(draft.code.trim() ? { code: draft.code.trim() } : {}),
    ...(image ? { image } : {}),
    ...(actionLabel && actionUrl ? { action: { label: actionLabel, url: actionUrl } } : {}),
    ...(draft.dismissLabel.trim() ? { dismissLabel: draft.dismissLabel.trim() } : {}),
  }), [draft, image, actionLabel, actionUrl]);

  const save = async () => {
    if (!valid) return;
    setBusy(true);
    setMessage("");
    try {
      const saved = await apiFetch<Row>(editingId ? `/api/admin/content/${editingId}` : "/api/admin/content", {
        method: editingId ? "PUT" : "POST",
        body: JSON.stringify({
          kind: "NOTICE",
          title: draft.title.trim(),
          body: joinNoticeCode(draft.body, draft.code),
          imageUrl: image || null,
          actionLabel: actionLabel || null,
          actionUrl: actionUrl || null,
          dismissLabel: draft.dismissLabel.trim() || null,
          stockCode: null,
          published: draft.published,
          startsAt: toInstant(draft.startsAt),
          endsAt: toInstant(draft.endsAt),
          reason: reason.trim(),
        }),
      });
      setMessage(editingId ? "공지를 고쳤습니다. 이미 확인한 사용자에게도 다시 보입니다." : "공지를 등록했습니다.");
      setEditingId(num(saved.id));
      setReason("");
      await load();
    } catch (failure) {
      setMessage(failure instanceof Error ? failure.message : "공지를 저장하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!editingId || !reason.trim()) return;
    if (!window.confirm(`"${draft.title}" 공지를 삭제할까요?\n삭제한 내용은 감사 기록에만 남습니다.`)) return;
    setBusy(true);
    setMessage("");
    try {
      await apiFetch<void>(`/api/admin/content/${editingId}`, { method: "DELETE", body: JSON.stringify({ reason: reason.trim() }) });
      startNew();
      setMessage("공지를 삭제했습니다.");
      await load();
    } catch (failure) {
      setMessage(failure instanceof Error ? failure.message : "공지를 삭제하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel id="admin-notices" title="공지" meta={loading ? "불러오는 중" : `${rows.length}개`} level={2}>
      <div className="notice-admin">
        <div className="notice-editor">
          <div className="notice-list-head">
            <h3 className="member-title">공지 목록</h3>
            <button type="button" className="text-button" onClick={startNew} aria-pressed={editingId === null}>새 공지 쓰기</button>
          </div>
          {error && <p className="account-message" role="alert">{error}</p>}
          {rows.length > 0 ? (
            <ul className="notice-list">
              {rows.map((row) => {
                const state = rowState(row);
                const id = num(row.id);
                return (
                  <li key={id}>
                    <button type="button" className={`notice-list-item${editingId === id ? " is-selected" : ""}`} aria-current={editingId === id ? "true" : undefined} onClick={() => edit(row)}>
                      <span className={`notice-state is-${state}`}>{STATE_LABEL[state]}</span>
                      <span className="notice-list-title">{String(row.title)}</span>
                      <span className="notice-list-time num">{adminTime(row.updated_at ?? row.created_at)}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          ) : !loading && !error && <p className="member-empty">아직 공지가 없습니다. 첫 공지를 써 보세요.</p>}

          <form className="account-form notice-form" onSubmit={(event) => { event.preventDefault(); void save(); }}>
            <h3 className="member-title">{editingId ? "공지 고치기" : "새 공지"}</h3>
            <label htmlFor="notice-title">제목</label>
            <input id="notice-title" value={draft.title} maxLength={150} placeholder="예: 프로모션 코드" onChange={(event) => set("title", event.target.value)} />
            <label htmlFor="notice-body">본문</label>
            <textarea id="notice-body" value={draft.body} rows={5} maxLength={19_900} placeholder="사용자에게 보일 내용을 적어 주세요. 줄바꿈은 그대로 보입니다."
              onChange={(event) => set("body", event.target.value)} />
            <label htmlFor="notice-code">코드 (선택)</label>
            <input id="notice-code" value={draft.code} maxLength={40} placeholder="예: syuen" autoCapitalize="none" spellCheck={false}
              onChange={(event) => set("code", event.target.value.replace(/\s/g, ""))} />
            <p className="account-hint">적으면 본문 아래에 복사하기 쉬운 코드 상자로 보입니다.</p>

            <label htmlFor="notice-image">머리 그림 주소 (선택)</label>
            <input id="notice-image" value={draft.image} maxLength={500} placeholder="예: /notice/season-2.png" autoCapitalize="none" spellCheck={false}
              onChange={(event) => set("image", event.target.value)} />
            <p className="account-hint">창 위쪽에 16:7 비율로 잘려 보입니다(권장 960×420). 비우면 글만 보입니다.</p>
            <div className="admin-form-pair">
              <div className="account-field">
                <label htmlFor="notice-action-label">이동 버튼 문구 (선택)</label>
                <input id="notice-action-label" value={draft.actionLabel} maxLength={40} placeholder="예: 지금 확인하기" onChange={(event) => set("actionLabel", event.target.value)} />
              </div>
              <div className="account-field">
                <label htmlFor="notice-action-url">이동 주소</label>
                <input id="notice-action-url" value={draft.actionUrl} maxLength={500} placeholder="예: /market 또는 https://…" autoCapitalize="none" spellCheck={false}
                  onChange={(event) => set("actionUrl", event.target.value)} />
              </div>
            </div>
            <label htmlFor="notice-dismiss">닫기 버튼 문구 (선택)</label>
            <input id="notice-dismiss" value={draft.dismissLabel} maxLength={40} placeholder={actionLabel ? "닫기" : "확인했어요"} onChange={(event) => set("dismissLabel", event.target.value)} />
            <p className="account-hint">이동 버튼이 없으면 닫기 버튼 하나만 진하게 보입니다.</p>
            {linkError && <p className="admin-form-message" role="alert">{linkError}</p>}

            <label className="notice-check">
              <input type="checkbox" checked={draft.published} onChange={(event) => set("published", event.target.checked)} />
              사용자에게 보이기
            </label>
            <div className="admin-form-pair">
              <div className="account-field">
                <label htmlFor="notice-start">시작 (비우면 바로)</label>
                <input id="notice-start" type="datetime-local" value={draft.startsAt} onChange={(event) => set("startsAt", event.target.value)} />
              </div>
              <div className="account-field">
                <label htmlFor="notice-end">끝 (비우면 계속)</label>
                <input id="notice-end" type="datetime-local" value={draft.endsAt} min={draft.startsAt || undefined} onChange={(event) => set("endsAt", event.target.value)} />
              </div>
            </div>
            {timeError && <p className="admin-form-message" role="alert">{timeError}</p>}

            <label htmlFor="notice-reason">사유 (감사 기록에 남음)</label>
            <input id="notice-reason" value={reason} maxLength={300} placeholder={editingId ? "예: 문구 수정" : "예: 프로모션 안내"} onChange={(event) => setReason(event.target.value)} />
            <div className="notice-actions">
              <button type="submit" className="account-button is-primary" disabled={busy || !valid}>{editingId ? "변경 저장" : "공지 등록"}</button>
              {editingId && <button type="button" className="account-button is-danger" disabled={busy || !reason.trim()} onClick={() => void remove()}>삭제</button>}
            </div>
            {message && <p className="admin-form-message" role="status">{message}</p>}
          </form>
        </div>

        <aside className="notice-preview" aria-label="사용자에게 보이는 공지 창 미리보기">
          <p className="notice-preview-label">
            {draftState === "live"
              ? liveCount > 1 ? `사용자가 처음 들어오면 이렇게 보입니다. 게시 중인 공지가 ${liveCount}개라 접속할 때마다 최신 것부터 하나씩 뜹니다.` : "사용자가 처음 들어오면 이렇게 보입니다."
              : `이 공지는 지금 ${STATE_LABEL[draftState]} 상태라 사용자에게 뜨지 않습니다.`}
          </p>
          <div className="notice-preview-stage">
            <div className="notice-preview-card site-notice">
              <SiteNoticeCard item={preview} titleId="notice-preview-title" />
            </div>
          </div>
        </aside>
      </div>
    </Panel>
  );
}
