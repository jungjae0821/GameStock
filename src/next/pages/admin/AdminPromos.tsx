import { useCallback, useEffect, useState } from "react";
import { Panel } from "../../../components/Panel";
import { apiFetch } from "../../../lib/api";
import { adminTime, count, num, toInstant, toLocalInput, type Row } from "./adminShared";

type CatalogTitle = { id: string; name: string; category: string };
type Draft = { code: string; rewardCash: string; titleIds: string[]; maxUses: string; startsAt: string; endsAt: string; active: boolean; memo: string };
type State = "live" | "scheduled" | "ended" | "full" | "off";

const EMPTY: Draft = { code: "", rewardCash: "", titleIds: [], maxUses: "", startsAt: "", endsAt: "", active: true, memo: "" };
const STATE_LABEL: Record<State, string> = { live: "사용 가능", scheduled: "예약", ended: "기간 끝", full: "소진", off: "꺼짐" };
/* 특별 칭호가 프로모션 코드의 본래 용도라 맨 앞에 둔다. */
const CATEGORY_ORDER = ["특별", "자산", "수익률", "보유", "거래", "활동", "수집"];
const MAX_REWARD_CASH = 100_000_000;

const digits = (value: string) => value.replace(/[^0-9]/g, "");
const won = (value: number) => `${count(value)}원`;

function stateOf(row: Row, now = Date.now()): State {
  if (!row.active) return "off";
  if (row.starts_at && new Date(String(row.starts_at)).getTime() > now) return "scheduled";
  if (row.ends_at && new Date(String(row.ends_at)).getTime() <= now) return "ended";
  if (row.max_uses !== null && row.max_uses !== undefined && num(row.used_count) >= num(row.max_uses)) return "full";
  return "live";
}

const splitTitles = (value: unknown) => String(value ?? "").split(",").map((id) => id.trim()).filter(Boolean);

function draftFrom(row: Row): Draft {
  return {
    code: String(row.code ?? ""),
    rewardCash: num(row.reward_cash) ? String(num(row.reward_cash)) : "",
    titleIds: splitTitles(row.title_ids),
    maxUses: row.max_uses === null || row.max_uses === undefined ? "" : String(row.max_uses),
    startsAt: toLocalInput(row.starts_at),
    endsAt: toLocalInput(row.ends_at),
    active: Boolean(row.active),
    memo: String(row.memo ?? ""),
  };
}

/** 보상 한 줄 요약. 목록과 미리보기가 같이 쓴다. */
function rewardSummary(cash: number, titleIds: string[], names: Map<string, string>) {
  const parts = [];
  if (cash > 0) parts.push(`현금 ${won(cash)}`);
  if (titleIds.length) parts.push(`칭호 ${titleIds.map((id) => names.get(id) ?? id).join(", ")}`);
  return parts.length ? parts.join(" + ") : "보상 없음";
}

/**
 * 프로모션 코드 관리. 코드마다 현금과 칭호 보상, 사용 인원, 기간을 정한다.
 * 한 계정은 코드 하나를 한 번만 쓸 수 있고, 현금 보상은 운용계좌가 아니라 개인 지갑으로 들어간다.
 */
export function AdminPromos() {
  const [rows, setRows] = useState<Row[]>([]);
  const [catalog, setCatalog] = useState<CatalogTitle[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [usage, setUsage] = useState<Row[] | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await apiFetch<{ codes: Row[]; titles: CatalogTitle[] }>("/api/admin/promos");
      setRows(result.codes);
      setCatalog(result.titles);
      setError("");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "프로모션 코드를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    setUsage(null);
    if (!editing) return undefined;
    const controller = new AbortController();
    apiFetch<Row[]>(`/api/admin/promos/${encodeURIComponent(editing)}/redemptions`, { signal: controller.signal })
      .then(setUsage)
      .catch(() => { if (!controller.signal.aborted) setUsage([]); });
    return () => controller.abort();
  }, [editing, rows]);

  const names = new Map(catalog.map((title) => [title.id, title.name]));
  const groups = CATEGORY_ORDER.map((category) => ({ category, titles: catalog.filter((title) => title.category === category) })).filter((group) => group.titles.length);

  const startNew = () => { setEditing(null); setDraft(EMPTY); setReason(""); setMessage(""); };
  const edit = (row: Row) => { setEditing(String(row.code)); setDraft(draftFrom(row)); setReason(""); setMessage(""); };
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((current) => ({ ...current, [key]: value }));
  const toggleTitle = (id: string) => set("titleIds", draft.titleIds.includes(id) ? draft.titleIds.filter((item) => item !== id) : [...draft.titleIds, id]);

  const cash = Number(draft.rewardCash || 0);
  const codeError = !editing && draft.code && !/^[A-Za-z0-9_-]{2,40}$/.test(draft.code) ? "코드는 영문·숫자와 _ - 로 2~40자입니다." : "";
  const cashError = cash > MAX_REWARD_CASH ? "현금 보상은 1억원까지입니다." : "";
  const usesError = draft.maxUses !== "" && (Number(draft.maxUses) < 1 || Number(draft.maxUses) > 1_000_000) ? "인원 제한은 1~1,000,000명입니다." : "";
  const timeError = draft.startsAt && draft.endsAt && draft.endsAt <= draft.startsAt ? "끝나는 시각은 시작 시각보다 뒤여야 합니다." : "";
  const rewardError = cash === 0 && draft.titleIds.length === 0 ? "현금이나 칭호 중 하나는 골라 주세요." : "";
  const valid = draft.code.trim() && reason.trim() && !codeError && !cashError && !usesError && !timeError && !rewardError;

  const save = async () => {
    if (!valid) return;
    setBusy(true);
    setMessage("");
    try {
      const saved = await apiFetch<Row>(editing ? `/api/admin/promos/${encodeURIComponent(editing)}` : "/api/admin/promos", {
        method: editing ? "PUT" : "POST",
        body: JSON.stringify({
          code: draft.code.trim(),
          rewardCash: cash,
          titleIds: draft.titleIds,
          maxUses: draft.maxUses === "" ? null : Number(draft.maxUses),
          startsAt: toInstant(draft.startsAt),
          endsAt: toInstant(draft.endsAt),
          active: draft.active,
          memo: draft.memo.trim(),
          reason: reason.trim(),
        }),
      });
      setMessage(editing ? "코드를 고쳤습니다. 이미 받은 사람의 보상은 그대로입니다." : `${String(saved.code)} 코드를 만들었습니다.`);
      setEditing(String(saved.code));
      setDraft(draftFrom(saved));
      setReason("");
      await load();
    } catch (failure) {
      setMessage(failure instanceof Error ? failure.message : "코드를 저장하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  const limitText = draft.maxUses ? `선착순 ${count(Number(draft.maxUses))}명` : "인원 제한 없음";
  const periodText = draft.startsAt || draft.endsAt
    ? `${draft.startsAt ? draft.startsAt.replace("T", " ") : "지금"}부터 ${draft.endsAt ? draft.endsAt.replace("T", " ") : "계속"}`
    : "기간 제한 없음";

  return (
    <Panel id="admin-promos" title="프로모션 코드" meta={loading ? "불러오는 중" : `${rows.length}개`} level={2}>
      <div className="notice-admin">
        <div className="notice-editor">
          <div className="notice-list-head">
            <h3 className="member-title">코드 목록</h3>
            <button type="button" className="text-button" onClick={startNew} aria-pressed={editing === null}>새 코드 만들기</button>
          </div>
          {error && <p className="account-message" role="alert">{error}</p>}
          {rows.length > 0 ? (
            <ul className="notice-list promo-list">
              {rows.map((row) => {
                const state = stateOf(row);
                const code = String(row.code);
                return (
                  <li key={code}>
                    <button type="button" className={`notice-list-item${editing === code ? " is-selected" : ""}`} aria-current={editing === code ? "true" : undefined} onClick={() => edit(row)}>
                      <span className={`notice-state is-${state === "live" ? "live" : state}`}>{STATE_LABEL[state]}</span>
                      <span className="promo-list-main">
                        <span className="notice-list-title promo-code-text">{code}</span>
                        <span className="promo-list-reward">{rewardSummary(num(row.reward_cash), splitTitles(row.title_ids), names)}</span>
                      </span>
                      <span className="notice-list-time num">
                        {count(num(row.used_count))}{row.max_uses !== null && row.max_uses !== undefined ? ` / ${count(num(row.max_uses))}` : ""}명 사용
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          ) : !loading && !error && <p className="member-empty">아직 코드가 없습니다. 첫 코드를 만들어 보세요.</p>}

          <form className="account-form notice-form" onSubmit={(event) => { event.preventDefault(); void save(); }}>
            <h3 className="member-title">{editing ? `${editing} 고치기` : "새 코드"}</h3>
            <label htmlFor="promo-admin-code">코드</label>
            <input id="promo-admin-code" value={draft.code} maxLength={40} placeholder="예: launch2026" autoCapitalize="none" spellCheck={false}
              disabled={Boolean(editing)} aria-describedby="promo-admin-code-hint" onChange={(event) => set("code", event.target.value.replace(/\s/g, ""))} />
            <p className="account-hint" id="promo-admin-code-hint">{editing ? "이미 쓴 사람의 기록이 이 코드에 묶여 있어 코드는 바꿀 수 없습니다." : "대소문자는 구분하지 않습니다."}</p>
            {codeError && <p className="admin-form-message" role="alert">{codeError}</p>}

            <label htmlFor="promo-admin-cash">현금 보상 (원)</label>
            <input id="promo-admin-cash" inputMode="numeric" value={draft.rewardCash} placeholder="0" aria-describedby="promo-admin-cash-hint"
              onChange={(event) => set("rewardCash", digits(event.target.value).slice(0, 10))} />
            <p className="account-hint" id="promo-admin-cash-hint">{cash > 0 ? `${won(cash)}을 줍니다. ` : "비우면 현금은 주지 않습니다. "}받은 현금은 회원의 내 지갑으로 들어갑니다.</p>
            {cashError && <p className="admin-form-message" role="alert">{cashError}</p>}

            <fieldset className="promo-titles">
              <legend>칭호 보상 <span className="num">{draft.titleIds.length ? `${draft.titleIds.length}개 선택` : "선택 안 함"}</span></legend>
              {groups.map((group) => (
                <div key={group.category} className="promo-title-group">
                  <p className="promo-title-category">{group.category}</p>
                  <div className="promo-title-options">
                    {group.titles.map((title) => (
                      <label key={title.id} className={`promo-title-option${draft.titleIds.includes(title.id) ? " is-picked" : ""}`}>
                        <input type="checkbox" checked={draft.titleIds.includes(title.id)} onChange={() => toggleTitle(title.id)} />
                        {title.name}
                      </label>
                    ))}
                  </div>
                </div>
              ))}
            </fieldset>
            {rewardError && draft.code && <p className="admin-form-message" role="alert">{rewardError}</p>}

            <label htmlFor="promo-admin-uses">사용 인원 제한</label>
            <input id="promo-admin-uses" inputMode="numeric" value={draft.maxUses} placeholder="비우면 제한 없음" onChange={(event) => set("maxUses", digits(event.target.value).slice(0, 7))} />
            {usesError && <p className="admin-form-message" role="alert">{usesError}</p>}
            <div className="admin-form-pair">
              <div className="account-field">
                <label htmlFor="promo-admin-start">시작 (비우면 바로)</label>
                <input id="promo-admin-start" type="datetime-local" value={draft.startsAt} onChange={(event) => set("startsAt", event.target.value)} />
              </div>
              <div className="account-field">
                <label htmlFor="promo-admin-end">끝 (비우면 계속)</label>
                <input id="promo-admin-end" type="datetime-local" value={draft.endsAt} min={draft.startsAt || undefined} onChange={(event) => set("endsAt", event.target.value)} />
              </div>
            </div>
            {timeError && <p className="admin-form-message" role="alert">{timeError}</p>}
            <label className="notice-check">
              <input type="checkbox" checked={draft.active} onChange={(event) => set("active", event.target.checked)} />
              코드 켜기
            </label>

            <label htmlFor="promo-admin-memo">메모 (관리자만 봄)</label>
            <input id="promo-admin-memo" value={draft.memo} maxLength={200} placeholder="예: 10월 방송 시청자용" onChange={(event) => set("memo", event.target.value)} />
            <label htmlFor="promo-admin-reason">사유 (감사 기록에 남음)</label>
            <input id="promo-admin-reason" value={reason} maxLength={300} placeholder={editing ? "예: 기간 연장" : "예: 오픈 이벤트"} onChange={(event) => setReason(event.target.value)} />
            <div className="notice-actions">
              <button type="submit" className="account-button is-primary" disabled={busy || !valid}>{editing ? "변경 저장" : "코드 만들기"}</button>
            </div>
            {message && <p className="admin-form-message" role="status">{message}</p>}
          </form>
        </div>

        <aside className="notice-preview promo-preview" aria-label="코드를 입력한 사용자가 받는 것">
          <p className="notice-preview-label">코드를 입력한 사람은 한 번만 이렇게 받습니다.</p>
          <dl className="promo-preview-card">
            <div>
              <dt>코드</dt>
              <dd className={draft.code.trim() ? "promo-code-text" : "promo-preview-empty"}>{draft.code.trim().toLowerCase() || "아직 코드가 없습니다"}</dd>
            </div>
            <div>
              <dt>보상</dt>
              <dd>{rewardSummary(cash, draft.titleIds, names)}</dd>
            </div>
            <div>
              <dt>조건</dt>
              <dd>{limitText}, {periodText}{draft.active ? "" : ", 지금은 꺼져 있음"}</dd>
            </div>
          </dl>
          {editing && (
            <section className="promo-usage" aria-labelledby="promo-usage-title">
              <h4 id="promo-usage-title" className="member-title">최근 사용</h4>
              {usage === null ? <p className="member-empty" role="status">불러오는 중</p> : usage.length === 0 ? <p className="member-empty">아직 쓴 사람이 없습니다.</p> : (
                <ul className="promo-usage-list">
                  {usage.map((entry) => (
                    <li key={`${num(entry.user_id)}`}>
                      <span>{String(entry.nickname)}</span>
                      <span className="num">{adminTime(entry.redeemed_at)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          )}
        </aside>
      </div>
    </Panel>
  );
}
