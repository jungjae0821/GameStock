import { useEffect, useState } from "react";
import { Panel } from "../components/Panel";
import { checkTitles, type TitleStatus, type UserTitle } from "../lib/titles";

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

function TitleRow({ title, index }: { title: UserTitle; index: number }) {
  const date = title.weekly ? title.updatedAt : title.acquiredAt;
  return (
    <li className={`title-row${title.owned ? " is-owned" : " is-locked"}${title.weekly ? " is-weekly" : ""}`}>
      <span className="title-index num" aria-hidden="true">{String(index).padStart(2, "0")}</span>
      <div className="title-copy">
        <h3 className="title-name">
          {title.name}
          {title.weekly && title.owned && <span className="title-crown num">{title.count}관왕</span>}
        </h3>
        <p className="title-condition">{title.description}</p>
      </div>
      {title.owned ? (
        <span className="title-seal" aria-label={`획득${date ? ` ${dateFormatter.format(new Date(date))}` : ""}`}>
          <span className="title-seal-mark"><CheckIcon /> 획득</span>
          {date && <span className="title-seal-date num">{dateFormatter.format(new Date(date))}</span>}
        </span>
      ) : (
        <span className="title-lock" role="img" aria-label="미획득">
          <LockIcon />
        </span>
      )}
    </li>
  );
}

export function TitlesPage() {
  const [status, setStatus] = useState<TitleStatus | null>(null);
  const [error, setError] = useState("");

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

  // 번호는 카테고리를 넘어 1부터 이어 붙여, 전체 도감처럼 읽히게 한다.
  let counter = 0;
  const groups = status
    ? CATEGORY_ORDER.map((category) => ({
        category,
        titles: status.titles.filter((title) => title.category === category).map((title) => ({ title, index: ++counter })),
      })).filter((group) => group.titles.length > 0)
    : [];
  const percent = status ? Math.round((status.ownedCount / Math.max(1, status.totalCount)) * 100) : 0;

  return (
    <div className="page-stack is-narrow titles-page">
      <h1 className="page-title">칭호</h1>
      {error ? (
        <p className="empty is-inline">{error}</p>
      ) : !status ? (
        <p className="empty is-inline">칭호를 확인하는 중…</p>
      ) : (
        <>
          <section className="title-progress" aria-label="칭호 수집 현황">
            <div className="title-progress-count">
              <span className="title-progress-label">수집한 칭호</span>
              <span className="title-progress-value num">
                <strong>{status.ownedCount}</strong>
                <span> / {status.totalCount}</span>
              </span>
            </div>
            <div className="title-progress-track" role="progressbar" aria-valuemin={0} aria-valuemax={status.totalCount} aria-valuenow={status.ownedCount} aria-label={`${percent}% 수집`}>
              <span className="title-progress-fill" style={{ width: `${percent}%` }} />
            </div>
            <p className="title-progress-note">조건을 달성하면 자동으로 지급돼요. 주간 수익률 칭호는 매주 월요일에 지급됩니다.</p>
          </section>
          {groups.map((group) => {
            const owned = group.titles.filter(({ title }) => title.owned).length;
            return (
              <Panel key={group.category} id={`titles-${group.category}`} title={`${group.category} 칭호`} meta={`${owned}/${group.titles.length}`}>
                <ul className="title-list">
                  {group.titles.map(({ title, index }) => <TitleRow key={title.id} title={title} index={index} />)}
                </ul>
              </Panel>
            );
          })}
        </>
      )}
    </div>
  );
}
