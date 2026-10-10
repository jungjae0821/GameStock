import { useEffect, useState } from "react";
import { Panel } from "../components/Panel";
import { checkTitles, titleLabel, type TitleStatus, type UserTitle } from "../lib/titles";

const CATEGORY_ORDER = ["자산", "수익률", "보유", "거래", "활동", "수집", "주간"];
const dateFormatter = new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeZone: "Asia/Seoul" });

function TitleCard({ title }: { title: UserTitle }) {
  const date = title.weekly ? title.updatedAt : title.acquiredAt;
  return (
    <li className={`title-card${title.owned ? " is-owned" : ""}${title.weekly ? " is-weekly" : ""}`}>
      <span className="title-badge" aria-hidden="true">{title.owned ? "★" : "☆"}</span>
      <div className="title-copy">
        <h3 className="title-name">{titleLabel(title)}</h3>
        <p className="title-condition">{title.description}</p>
        <p className="title-state">
          {title.owned
            ? `${title.weekly ? "최근 지급" : "획득"} ${date ? dateFormatter.format(new Date(date)) : ""}`
            : title.weekly ? "주간 순위에 들면 1관왕부터 시작" : "미획득 · 1회 한정 자동 지급"}
        </p>
      </div>
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

  const groups = status
    ? CATEGORY_ORDER.map((category) => ({ category, titles: status.titles.filter((title) => title.category === category) }))
        .filter((group) => group.titles.length > 0)
    : [];

  return (
    <div className="page-stack">
      <h1 className="page-title">칭호</h1>
      {error ? (
        <p className="empty is-inline">{error}</p>
      ) : !status ? (
        <p className="empty is-inline">칭호를 확인하는 중…</p>
      ) : (
        <>
          <p className="title-summary">
            보유 칭호 <strong className="num">{status.ownedCount}</strong> / <span className="num">{status.totalCount}</span>
          </p>
          {groups.map((group) => {
            const owned = group.titles.filter((title) => title.owned).length;
            return (
              <Panel key={group.category} id={`titles-${group.category}`} title={`${group.category} 칭호`} meta={`${owned}/${group.titles.length}`}>
                <ul className="title-grid">
                  {group.titles.map((title) => <TitleCard key={title.id} title={title} />)}
                </ul>
              </Panel>
            );
          })}
        </>
      )}
    </div>
  );
}
