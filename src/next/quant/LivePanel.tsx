import { useCallback, useEffect, useId, useRef, useState } from "react";
import { RUN_PAGE, failure, getProject, isAbort, listRuns, type Project, type Run } from "../../lib/research";
import { compactWon, kstDateTime, serverTimestamp, won } from "../../market/format";
import { STATUS_LABEL } from "./draft";

const RUN_LABEL: Record<Run["status"], string> = { SUBMITTING: "접수 중", ACCEPTED: "접수됨", REJECTED: "거절", UNKNOWN: "확인 필요" };
const LIVE_POLL_MS = 5_000;

/** 자동매매 상태·한도 사용량·실행 기록. 켜져 있는 동안 상태와 기록을 주기적으로 다시 받는다. */
export function LivePanel({ project, dirty, busy, onToggle, onProject }: {
  project: Project; dirty: boolean; busy: boolean; onToggle: (active: boolean) => void; onProject: (project: Project) => void;
}) {
  const id = useId();
  const [runs, setRuns] = useState<Run[] | null>(null);
  const [more, setMore] = useState(false);
  const [error, setError] = useState("");
  const [loadingMore, setLoadingMore] = useState(false);
  const live = project.status !== "PAUSED";
  const onProjectRef = useRef(onProject);
  onProjectRef.current = onProject;

  const firstPage = useCallback(async (signal?: AbortSignal) => {
    const page = await listRuns(project.id, undefined, signal);
    /* 앞서 더 불러온 기록은 남기고 최신 페이지만 바꾼다. */
    setRuns((current) => {
      if (!current) return page;
      const oldest = page.at(-1)?.id ?? Number.POSITIVE_INFINITY;
      return [...page, ...current.filter((run) => run.id < oldest)];
    });
    setMore((current) => current || page.length >= RUN_PAGE);
    setError("");
  }, [project.id]);

  useEffect(() => {
    setRuns(null);
    setMore(false);
  }, [project.id]);

  useEffect(() => {
    const controller = new AbortController();
    firstPage(controller.signal).catch((reason: unknown) => { if (!isAbort(reason)) setError(failure(reason, "실행 기록을 불러오지 못했어요.")); });
    if (!live) return () => controller.abort();
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void firstPage(controller.signal).catch(() => undefined);
      getProject(project.id, controller.signal).then((next) => onProjectRef.current(next)).catch(() => undefined);
    }, LIVE_POLL_MS);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, [firstPage, live, project.id]);

  const older = async () => {
    const last = runs?.at(-1);
    if (!last) return;
    setLoadingMore(true);
    try {
      const page = await listRuns(project.id, last.id);
      setRuns((current) => [...(current ?? []), ...page.filter((run) => !current?.some((item) => item.id === run.id))]);
      setMore(page.length >= RUN_PAGE);
    } catch (reason) {
      setError(failure(reason, "기록을 더 불러오지 못했어요."));
    } finally {
      setLoadingMore(false);
    }
  };

  const unknown = runs?.some((run) => run.status === "UNKNOWN" || run.status === "SUBMITTING");
  const orderShare = Math.min(1, project.ordersUsed / Math.max(1, project.maxOrders));
  const notionalShare = Math.min(1, project.notionalUsed / Math.max(1, project.maxNotional));

  return (
    <section className="nx-panel qx-live" aria-labelledby={`${id}-title`}>
      <header className="nx-panel-head">
        <h2 id={`${id}-title`} className="nx-panel-title">자동매매</h2>
        <span className={`qx-status is-${project.status.toLowerCase()}`} role="status">{STATUS_LABEL[project.status]}</span>
      </header>
      <div className="qx-live-head">
        <dl className="qx-usage">
          <div>
            <dt>주문 횟수</dt>
            <dd><span className="nx-num">{project.ordersUsed}/{project.maxOrders}회</span><span className="qx-meter" aria-hidden="true"><span style={{ width: `${orderShare * 100}%` }} /></span></dd>
          </div>
          <div>
            <dt>주문 금액</dt>
            <dd><span className="nx-num">{compactWon(project.notionalUsed)} / {compactWon(project.maxNotional)}</span><span className="qx-meter" aria-hidden="true"><span style={{ width: `${notionalShare * 100}%` }} /></span></dd>
          </div>
          <div><dt>마지막 확인 캔들</dt><dd className="nx-num">{project.lastBar ? kstDateTime(serverTimestamp(project.lastBar)) : "–"}</dd></div>
        </dl>
        {project.status === "PAUSED"
          ? <button type="button" className="nx-btn is-buy" disabled={busy || dirty} onClick={() => onToggle(true)} title={dirty ? "저장한 뒤 시작할 수 있어요" : undefined}>{busy ? "시작하는 중…" : "자동매매 시작"}</button>
          : <button type="button" className="nx-btn" disabled={busy || project.status === "PAUSING"} onClick={() => onToggle(false)}>{project.status === "PAUSING" ? "중지하는 중…" : busy ? "중지하는 중…" : "중지"}</button>}
      </div>
      {dirty && project.status === "PAUSED" && <p className="nx-field-hint">저장하지 않은 편집이 있어요. 저장한 규칙으로만 자동매매를 시작할 수 있어요.</p>}
      {unknown && <p className="nx-note is-guard" role="alert">처리 결과를 확인하지 못한 주문이 있어요. 주문 내역에서 접수 여부를 확인한 뒤 다시 시작해 주세요. 자동으로 다시 주문하지 않아요.</p>}
      <ul className="qx-live-rules">
        <li>완성된 캔들만 보고, 처음 참이 된 규칙 하나만 주문해요.</li>
        <li>매수는 현재가를 호가 단위로 내린 값, 매도는 올린 값으로 지정가 주문을 내요. 미체결 주문은 자동으로 취소되지 않아요.</li>
        <li>로그아웃해도 서버에서 계속 실행돼요. 「접수됨」은 체결 완료가 아니라 주문 접수가 됐다는 뜻이에요.</li>
      </ul>
      {error && <p className="nx-msg is-error" role="alert">{error}</p>}
      {runs === null ? (!error && <div className="nx-skeleton" role="status" aria-label="실행 기록을 불러오는 중"><span /><span /></div>)
        : runs.length === 0 ? <p className="nx-empty-state">아직 실행 기록이 없어요.</p> : (
        <div className="nx-scroll-x">
          <table className="nx-grid-table qx-runs">
            <caption className="vh">자동매매 실행 기록</caption>
            <thead><tr><th scope="col">캔들</th><th scope="col">구분</th><th scope="col">수량</th><th scope="col">지정가</th><th scope="col">상태</th><th scope="col">내용</th></tr></thead>
            <tbody>
              {runs.map((run) => (
                <tr key={run.id}>
                  <th scope="row" className="nx-num">{kstDateTime(serverTimestamp(run.barTime))}</th>
                  <td><span className={`nx-side is-${run.side === "BUY" ? "buy" : "sell"}`}>{run.side === "BUY" ? "매수" : "매도"}</span></td>
                  <td className="nx-num">{run.quantity.toLocaleString("ko-KR")}주</td>
                  <td className="nx-num">{won(run.limitPrice)}</td>
                  <td><span className={`qx-run is-${run.status.toLowerCase()}`}>{RUN_LABEL[run.status]}</span></td>
                  <td className="qx-run-message">{run.message}{run.orderId !== null && <span className="nx-muted nx-num"> · 주문 #{run.orderId}</span>}{run.revision !== project.revision && <span className="nx-muted"> · r{run.revision}</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {more && <button type="button" className="nx-link-button" disabled={loadingMore} onClick={() => void older()}>{loadingMore ? "불러오는 중…" : "이전 기록 더 보기"}</button>}
    </section>
  );
}
