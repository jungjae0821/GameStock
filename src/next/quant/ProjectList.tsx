import { MAX_PROJECTS, rangeLabel, type Capabilities, type Project } from "../../lib/research";
import { LISTING_BY_CODE } from "../../market/universe";
import { STATUS_LABEL } from "./draft";

/** 내 전략 목록. 고르면 그 전략을 열고, "새 전략"은 빈 초안을 연다. */
export function ProjectList({ formId, id, projects, listError, capabilities, onOpen, onRetry }: {
  formId: string; id: number | null; projects: Project[] | null; listError: string; capabilities: Capabilities | null;
  onOpen: (id: number | null) => void; onRetry: () => void;
}) {
  return (
    <nav className="qx-projects" aria-labelledby={`${formId}-projects`}>
      <header>
        <h2 id={`${formId}-projects`}>내 전략 <span className="nx-num nx-muted">{projects?.length ?? 0}/{capabilities?.maxProjects ?? MAX_PROJECTS}</span></h2>
        <button type="button" className="nx-btn is-small" onClick={() => onOpen(null)} aria-current={id === null ? "page" : undefined}>새 전략</button>
      </header>
      {listError && <p className="nx-msg is-error" role="alert">{listError} <button type="button" className="nx-link-button" onClick={onRetry}>다시 시도</button></p>}
      {!projects && !listError && <div className="nx-skeleton" role="status" aria-label="전략 목록을 불러오는 중"><span /><span /></div>}
      {projects?.length === 0 && <p className="nx-muted qx-projects-empty">아직 저장한 전략이 없어요.</p>}
      <ul>
        {projects?.map((item) => (
          <li key={item.id}>
            <button type="button" className={`qx-project${item.id === id ? " is-active" : ""}`} aria-current={item.id === id ? "page" : undefined} onClick={() => onOpen(item.id)}>
              <strong>{item.name}</strong>
              <span className="nx-muted">{LISTING_BY_CODE[item.stockCode]?.name ?? item.stockCode} · {rangeLabel(item.range)}봉 · {item.language === "BLOCKS" ? "블록" : "snScript"}</span>
              <span className={`qx-status is-${item.status.toLowerCase()}`}>{STATUS_LABEL[item.status]}</span>
            </button>
          </li>
        ))}
      </ul>
    </nav>
  );
}
