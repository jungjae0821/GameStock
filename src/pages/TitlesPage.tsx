import { useEffect, useState } from "react";
import { Panel } from "../components/Panel";
import { checkTitles, equipTitle, type TitleStatus, type UserTitle } from "../lib/titles";

const CATEGORY_ORDER = ["자산", "수익률", "보유", "거래", "활동", "수집", "주간"];
const dateFormatter = new Intl.DateTimeFormat("ko-KR", { year: "2-digit", month: "2-digit", day: "2-digit", timeZone: "Asia/Seoul" });

function LockIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" focusable="false">
      <rect x="5" y="10.5" width="14" height="10" rx="2" fill="currentColor" />
      <path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      <circle cx="12" cy="15.5" r="1.4" fill="var(--paper)" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" focusable="false">
      <path d="M5 12.5l4.2 4.2L19 7" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

type RowProps = { title: UserTitle; pending: boolean; onToggle: (title: UserTitle) => void };

function TitleRow({ title, pending, onToggle }: RowProps) {
  const date = title.weekly ? title.updatedAt : title.acquiredAt;
  const className = `title-row${title.owned ? " is-owned" : " is-locked"}${title.weekly ? " is-weekly" : ""}${title.equipped ? " is-equipped" : ""}`;
  const body = (
    <>
      {/* 라디오처럼 "고를 수 있는 항목"임을 알리는 표시. 장착하면 안쪽이 채워진다. */}
      <span className="title-dot" aria-hidden="true" />
      <span className="title-copy">
        <span className="title-name">
          {title.name}
          {title.weekly && title.owned && <span className="title-crown num">{title.count}관왕</span>}
          {title.equipped && <span className="title-equipped">장착됨</span>}
        </span>
        <span className="title-condition">{title.description}</span>
      </span>
      {title.owned ? (
        <span className="title-seal">
          <span className="title-seal-mark"><CheckIcon /> 획득</span>
          {date && <span className="title-seal-date num">{dateFormatter.format(new Date(date))}</span>}
        </span>
      ) : (
        <span className="title-lock" role="img" aria-label="미획득">
          <LockIcon />
        </span>
      )}
    </>
  );
  return (
    <li className="title-item">
      {title.owned ? (
        <button
          type="button"
          className={className}
          aria-pressed={title.equipped}
          title={title.equipped ? "누르면 장착을 해제해요" : "누르면 이 칭호를 장착해요"}
          disabled={pending}
          onClick={() => onToggle(title)}
        >
          {body}
        </button>
      ) : (
        <div className={className}>{body}</div>
      )}
    </li>
  );
}

export function TitlesPage() {
  const [status, setStatus] = useState<TitleStatus | null>(null);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const [notice, setNotice] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    checkTitles(controller.signal)
      .then((next) => {
        setStatus(next);
        setError("");
      })
      .catch(() => {
        if (!controller.signal.aborted) setError("칭호 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
      });
    return () => controller.abort();
  }, []);

  // 이미 장착한 칭호를 다시 누르면 장착을 해제한다.
  const toggle = async (title: UserTitle) => {
    if (pending) return;
    setPending(true);
    setNotice("");
    try {
      setStatus(await equipTitle(title.equipped ? null : title.id));
    } catch (failure) {
      setNotice(failure instanceof Error ? failure.message : "칭호를 장착하지 못했습니다.");
    } finally {
      setPending(false);
    }
  };

  const groups = status
    ? CATEGORY_ORDER.map((category) => ({ category, titles: status.titles.filter((title) => title.category === category) }))
        .filter((group) => group.titles.length > 0)
    : [];
  const equipped = status?.titles.find((title) => title.equipped);
  const percent = status ? Math.round((status.ownedCount / Math.max(1, status.totalCount)) * 100) : 0;

  return (
    <div className="page-stack is-compact titles-page">
      <h1 className="page-title">칭호</h1>
      {error ? (
        <p className="empty is-inline">{error}</p>
      ) : !status ? (
        <p className="empty is-inline">칭호를 확인하는 중…</p>
      ) : (
        <>
          <section className="title-progress" aria-label="칭호 수집 현황">
            <div className="title-progress-count">
              <span className="title-progress-label">
                수집한 칭호
                {equipped && <> · 장착 중 <strong className="title-progress-equipped">{equipped.name}</strong></>}
              </span>
              <span className="title-progress-value num">
                <strong>{status.ownedCount}</strong>
                <span> / {status.totalCount}</span>
              </span>
            </div>
            <div className="title-progress-track" role="progressbar" aria-valuemin={0} aria-valuemax={status.totalCount} aria-valuenow={status.ownedCount} aria-label={`${percent}% 수집`}>
              <span className="title-progress-fill" style={{ width: `${percent}%` }} />
            </div>
            <p className="title-progress-note">획득한 칭호를 누르면 장착돼요. 조건을 달성하면 자동으로 지급되고, 주간 수익률 칭호는 매주 월요일에 지급됩니다.</p>
            {notice && <p className="title-progress-error" role="alert">{notice}</p>}
          </section>
          {groups.map((group) => {
            const owned = group.titles.filter((title) => title.owned).length;
            return (
              <Panel key={group.category} id={`titles-${group.category}`} title={`${group.category} 칭호`} meta={`${owned}/${group.titles.length}`}>
                <ul className="title-list">
                  {group.titles.map((title) => <TitleRow key={title.id} title={title} pending={pending} onToggle={toggle} />)}
                </ul>
              </Panel>
            );
          })}
        </>
      )}
    </div>
  );
}
