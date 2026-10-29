import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { ApiError } from "../../lib/api";
import {
  CHART_RANGES, MAX_PROJECTS, RUN_PAGE, compileQuant, createProject, deleteProject, failure, getProject, isAbort, listProjects, listRuns,
  quantCapabilities, rangeLabel, replaceProject, setProjectState,
  type BlockProgram, type Capabilities, type Compilation, type Project, type ProjectSave, type ProjectStatus, type Run, type Source,
} from "../../lib/research";
import { compactWon, kstDateTime, serverTimestamp, won } from "../../market/format";
import { useMarket } from "../../market/MarketProvider";
import { LISTING_BY_CODE } from "../../market/universe";
import { QUANT_PATH, navigate } from "../../router";
import { AnimatedNav } from "../AnimatedNav";
import { BacktestPanel } from "../quant/BacktestPanel";
import { BlockEditor } from "../quant/BlockEditor";
import { EXAMPLE_BLOCKS, EXAMPLE_SCRIPT, blocksToScript, normalizeProgram, parseBlocks } from "../quant/program";
import { SCRIPT_MAX_LENGTH, ScriptEditor } from "../quant/ScriptEditor";

type Language = "SNSCRIPT" | "BLOCKS";
type Draft = {
  name: string; stockCode: string; range: string; language: Language;
  script: string; blocks: BlockProgram; maxOrders: string; maxNotional: string;
};

const STATUS_LABEL: Record<ProjectStatus, string> = { PAUSED: "중지됨", ACTIVE: "실행 중", RUNNING: "주문 처리 중", PAUSING: "중지하는 중" };
const RUN_LABEL: Record<Run["status"], string> = { SUBMITTING: "접수 중", ACCEPTED: "접수됨", REJECTED: "거절", UNKNOWN: "확인 필요" };
const COMPILE_DELAY_MS = 400;
const LIVE_POLL_MS = 5_000;
const digits = (text: string) => text.replace(/[^0-9]/g, "");

function blankDraft(stockCode: string): Draft {
  return { name: "새 전략", stockCode, range: "1h", language: "SNSCRIPT", script: EXAMPLE_SCRIPT, blocks: EXAMPLE_BLOCKS, maxOrders: "10", maxNotional: "1000000" };
}

function draftOf(project: Project): Draft {
  const blocks = project.language === "BLOCKS" ? parseBlocks(project.source) ?? normalizeProgram(project.program) : normalizeProgram(project.program);
  return {
    name: project.name, stockCode: project.stockCode, range: project.range, language: project.language,
    script: project.language === "SNSCRIPT" ? project.source : blocksToScript(blocks), blocks,
    maxOrders: String(project.maxOrders), maxNotional: String(project.maxNotional),
  };
}

const sourceOf = (draft: Draft): Source => draft.language === "SNSCRIPT" ? { language: "SNSCRIPT", source: draft.script } : { language: "BLOCKS", blocks: draft.blocks };
/** 편집 여부 비교용. 지금 고른 언어의 소스만 본다. */
const signature = (draft: Draft) => JSON.stringify([draft.name.trim(), draft.stockCode, draft.range, sourceOf(draft), draft.maxOrders, draft.maxNotional]);

function limitsIssue(draft: Draft): string {
  const orders = Number(draft.maxOrders);
  const notional = Number(draft.maxNotional);
  if (!draft.name.trim() || [...draft.name.trim()].length > 80) return "이름은 1~80자로 지어 주세요.";
  if (!draft.maxOrders || orders < 1 || orders > 100) return "주문 횟수 한도는 1~100회예요.";
  if (!draft.maxNotional || notional < 1 || notional > 1e12) return "주문 금액 한도는 1원~1조 원이에요.";
  return "";
}

export function NxQuantPage({ id }: { id: number | null }) {
  const snapshot = useMarket();
  const formId = useId();
  const defaultCode = snapshot.codes[0] ?? "";
  const [capabilities, setCapabilities] = useState<Capabilities | null>(null);
  const [projects, setProjects] = useState<Project[] | null>(null);
  const [listError, setListError] = useState("");
  const [project, setProject] = useState<Project | null>(null);
  const [loadError, setLoadError] = useState("");
  const [draft, setDraft] = useState<Draft>(() => blankDraft(defaultCode));
  const [savedSignature, setSavedSignature] = useState<string | null>(null);
  const [compilation, setCompilation] = useState<Compilation | null>(null);
  const [compileError, setCompileError] = useState("");
  const [compiling, setCompiling] = useState(false);
  const [busy, setBusy] = useState<"save" | "state" | "delete" | null>(null);
  const [message, setMessage] = useState<{ text: string; ok: boolean } | null>(null);
  const [conflict, setConflict] = useState(false);
  const [editorKey, setEditorKey] = useState(0);

  const dirty = savedSignature === null || signature(draft) !== savedSignature;
  const code = useMemo(() => sourceOf(draft), [draft]);

  /* 공개 계약 정보. 실패해도 기본 범위로 편집할 수 있다. */
  useEffect(() => {
    const controller = new AbortController();
    quantCapabilities(controller.signal).then(setCapabilities).catch(() => undefined);
    return () => controller.abort();
  }, []);

  const refreshList = useCallback(async (signal?: AbortSignal) => {
    try {
      setProjects(await listProjects(signal));
      setListError("");
    } catch (reason) {
      if (!isAbort(reason)) setListError(failure(reason, "전략 목록을 불러오지 못했어요."));
    }
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    void refreshList(controller.signal);
    return () => controller.abort();
  }, [refreshList]);

  const adopt = useCallback((next: Project) => {
    const nextDraft = draftOf(next);
    setProject(next);
    setDraft(nextDraft);
    setSavedSignature(signature(nextDraft));
    setEditorKey((key) => key + 1);
    setConflict(false);
    setProjects((current) => current && [next, ...current.filter((item) => item.id !== next.id)].sort((a, b) => serverTimestamp(b.updatedAt) - serverTimestamp(a.updatedAt)));
  }, []);

  /* 주소의 전략을 연다. 없으면 새 초안이다. */
  useEffect(() => {
    /* 새 전략을 저장하고 주소만 바뀐 경우다. 저장 결과 문구를 남긴다. */
    if (id !== null && project?.id === id) return;
    setMessage(null);
    setLoadError("");
    setConflict(false);
    if (id === null) {
      setProject(null);
      setDraft(blankDraft(defaultCode));
      setSavedSignature(null);
      setEditorKey((key) => key + 1);
      return;
    }
    const controller = new AbortController();
    setProject(null);
    getProject(id, controller.signal).then(adopt).catch((reason: unknown) => {
      if (!isAbort(reason)) setLoadError(failure(reason, "전략을 불러오지 못했어요."));
    });
    return () => controller.abort();
    // 같은 전략을 다시 열 때는 편집 중인 초안을 지킨다. id가 바뀔 때만 다시 읽는다.
  }, [id]);

  /* 새 초안을 연 뒤 시세가 늦게 오면 종목을 채운다. */
  useEffect(() => {
    if (!draft.stockCode && defaultCode) setDraft((current) => ({ ...current, stockCode: defaultCode }));
  }, [defaultCode, draft.stockCode]);

  /* 편집이 멈추면 컴파일한다. 이전 요청은 취소한다. */
  const codeKey = JSON.stringify(code);
  useEffect(() => {
    const controller = new AbortController();
    const timer = window.setTimeout(async () => {
      setCompiling(true);
      try {
        setCompilation(await compileQuant(code, controller.signal));
        setCompileError("");
      } catch (reason) {
        if (isAbort(reason)) return;
        setCompilation(null);
        setCompileError(failure(reason, "컴파일하지 못했어요."));
      } finally {
        if (!controller.signal.aborted) setCompiling(false);
      }
    }, COMPILE_DELAY_MS);
    return () => { window.clearTimeout(timer); controller.abort(); };
    // codeKey가 code의 내용을 담는다. 같은 소스로 다시 그릴 때는 부르지 않는다.
  }, [codeKey]);

  /* 저장하지 않은 편집이 있으면 창을 닫기 전에 묻는다. */
  useEffect(() => {
    if (!dirty || savedSignature === null) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty, savedSignature]);

  const open = (to: number | null) => {
    if (to === id) return;
    if (dirty && savedSignature !== null && !window.confirm("저장하지 않은 편집이 있어요. 버리고 이동할까요?")) return;
    navigate(QUANT_PATH(to));
  };

  const update = (patch: Partial<Draft>) => setDraft((current) => ({ ...current, ...patch }));

  const switchLanguage = (language: Language) => {
    if (language === draft.language) return;
    if (language === "SNSCRIPT") {
      update({ language, script: blocksToScript(draft.blocks) });
    } else {
      if (!compilation?.valid || !compilation.program) { setMessage({ text: "컴파일 오류가 없어야 블록으로 바꿀 수 있어요.", ok: false }); return; }
      update({ language, blocks: normalizeProgram(compilation.program) });
    }
    setEditorKey((key) => key + 1);
    setMessage(null);
  };

  const payload = (revision?: number): ProjectSave => ({
    name: draft.name.trim(), stockCode: draft.stockCode, range: draft.range, code,
    maxOrders: Number(draft.maxOrders), maxNotional: Number(draft.maxNotional), ...(revision === undefined ? {} : { revision }),
  });

  const save = async (revision = project?.revision) => {
    if (busy || issue) return;
    setBusy("save");
    setMessage(null);
    try {
      if (project) {
        adopt(await replaceProject(project.id, payload(revision)));
        setMessage({ text: "저장했어요.", ok: true });
      } else {
        const created = await createProject(payload());
        adopt(created);
        setMessage({ text: "저장했어요. 자동매매는 직접 시작하기 전까지 꺼져 있어요.", ok: true });
        navigate(QUANT_PATH(created.id), { replace: true });
      }
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 409) setConflict(true);
      setMessage({ text: failure(reason, "저장하지 못했어요."), ok: false });
    } finally {
      setBusy(null);
    }
  };

  /* 다른 탭에서 먼저 저장했다. 최신 revision을 받아 내 편집으로 덮어쓰거나, 최신 버전으로 바꾼다. */
  const resolveConflict = async (keepMine: boolean) => {
    if (!project) return;
    try {
      const latest = await getProject(project.id);
      if (keepMine) {
        setProject(latest);
        if (latest.status !== "PAUSED") { setMessage({ text: "다른 곳에서 자동매매를 켰어요. 중지한 뒤 저장해 주세요.", ok: false }); setConflict(false); return; }
        await save(latest.revision);
      } else {
        adopt(latest);
        setMessage({ text: "최신 버전을 불러왔어요.", ok: true });
      }
    } catch (reason) {
      setMessage({ text: failure(reason, "최신 버전을 불러오지 못했어요."), ok: false });
    }
  };

  const toggle = async (active: boolean) => {
    if (!project || busy) return;
    if (active && !window.confirm(
      `「${project.name}」 자동매매를 시작할까요?\n\n${LISTING_BY_CODE[project.stockCode]?.name ?? project.stockCode} 종목에 실제 지정가 주문이 나가요. `
      + `주문 ${project.maxOrders - project.ordersUsed}회, ${won(Math.max(0, project.maxNotional - project.notionalUsed))}까지 남아 있어요. `
      + "체결되지 않은 주문은 자동으로 취소되지 않아요.")) return;
    setBusy("state");
    setMessage(null);
    try {
      const next = await setProjectState(project.id, active, project.revision);
      setProject(next);
      setProjects((current) => current && current.map((item) => item.id === next.id ? next : item));
      setMessage({ text: active ? "자동매매를 시작했어요. 완성된 캔들마다 조건을 확인해요." : "중지를 요청했어요. 이미 접수한 주문은 취소되지 않아요.", ok: true });
    } catch (reason) {
      setMessage({ text: failure(reason, active ? "자동매매를 시작하지 못했어요." : "중지하지 못했어요."), ok: false });
      getProject(project.id).then(setProject).catch(() => undefined);
    } finally {
      setBusy(null);
    }
  };

  const remove = async () => {
    if (!project || busy || !window.confirm(`「${project.name}」 전략과 실행 기록을 모두 지울까요? 되돌릴 수 없어요.`)) return;
    setBusy("delete");
    try {
      await deleteProject(project.id);
      setProjects((current) => current && current.filter((item) => item.id !== project.id));
      setSavedSignature(null);
      navigate(QUANT_PATH(null), { replace: true });
    } catch (reason) {
      setMessage({ text: failure(reason, "지우지 못했어요."), ok: false });
    } finally {
      setBusy(null);
    }
  };

  const issue = limitsIssue(draft);
  const paused = !project || project.status === "PAUSED";
  const compiled = compilation?.valid === true;
  const atLimit = !project && (projects?.length ?? 0) >= (capabilities?.maxProjects ?? MAX_PROJECTS);
  const ranges = capabilities ? CHART_RANGES.filter((item) => item.value in capabilities.ranges) : CHART_RANGES;
  const stockCodes = snapshot.codes.includes(draft.stockCode) || !draft.stockCode ? snapshot.codes : [draft.stockCode, ...snapshot.codes];
  const scriptTooLong = draft.language === "SNSCRIPT" && draft.script.length > SCRIPT_MAX_LENGTH;
  const saveBlocked = !paused ? "자동매매를 중지한 뒤 저장할 수 있어요." : atLimit ? `전략은 ${capabilities?.maxProjects ?? MAX_PROJECTS}개까지 만들 수 있어요.` : issue || (scriptTooLong ? "소스가 너무 길어요." : "");

  return (
    <div className="nx-screen qx-page">
      <header className="nx-screen-head">
        <div>
          <h1 className="nx-page-title">퀀트 전략</h1>
          <p className="nx-screen-sub">블록이나 snScript로 매매 규칙을 만들고 지난 시세로 시험해 보세요. 자동매매를 켜면 완성된 캔들마다 조건을 확인해 지정가 주문을 내요.</p>
        </div>
      </header>

      <div className="qx-layout">
        <nav className="qx-projects" aria-labelledby={`${formId}-projects`}>
          <header>
            <h2 id={`${formId}-projects`}>내 전략 <span className="nx-num nx-muted">{projects?.length ?? 0}/{capabilities?.maxProjects ?? MAX_PROJECTS}</span></h2>
            <button type="button" className="nx-btn is-small" onClick={() => open(null)} aria-current={id === null ? "page" : undefined}>새 전략</button>
          </header>
          {listError && <p className="nx-msg is-error" role="alert">{listError} <button type="button" className="nx-link-button" onClick={() => void refreshList()}>다시 시도</button></p>}
          {!projects && !listError && <div className="nx-skeleton" role="status" aria-label="전략 목록을 불러오는 중"><span /><span /></div>}
          {projects?.length === 0 && <p className="nx-muted qx-projects-empty">아직 저장한 전략이 없어요.</p>}
          <ul>
            {projects?.map((item) => (
              <li key={item.id}>
                <button type="button" className={`qx-project${item.id === id ? " is-active" : ""}`} aria-current={item.id === id ? "page" : undefined} onClick={() => open(item.id)}>
                  <strong>{item.name}</strong>
                  <span className="nx-muted">{LISTING_BY_CODE[item.stockCode]?.name ?? item.stockCode} · {rangeLabel(item.range)}봉 · {item.language === "BLOCKS" ? "블록" : "snScript"}</span>
                  <span className={`qx-status is-${item.status.toLowerCase()}`}>{STATUS_LABEL[item.status]}</span>
                </button>
              </li>
            ))}
          </ul>
        </nav>

        <div className="qx-main">
          {loadError && <p className="nx-note is-error" role="alert">{loadError} <button type="button" className="nx-link-button" onClick={() => navigate(QUANT_PATH(null))}>새 전략 만들기</button></p>}
          {id !== null && !project && !loadError ? <div className="nx-skeleton" role="status" aria-label="전략을 불러오는 중"><span /><span /><span /></div> : !loadError && <>
            <section className="nx-panel qx-settings" aria-labelledby={`${formId}-settings`}>
              <header className="nx-panel-head">
                <h2 id={`${formId}-settings`} className="nx-panel-title">{project ? "전략 편집" : "새 전략"}</h2>
                {project && <span className={`qx-status is-${project.status.toLowerCase()}`}>{STATUS_LABEL[project.status]} · r{project.revision}</span>}
              </header>
              <div className="qx-fields">
                <div className="nx-field qx-field-name">
                  <label htmlFor={`${formId}-name`}>이름</label>
                  <input id={`${formId}-name`} className="nx-input" value={draft.name} maxLength={80} onChange={(event) => update({ name: event.target.value })} />
                </div>
                <div className="nx-field">
                  <label htmlFor={`${formId}-stock`}>종목</label>
                  <select id={`${formId}-stock`} className="nx-select" value={draft.stockCode} onChange={(event) => update({ stockCode: event.target.value })}>
                    {stockCodes.map((stock) => <option key={stock} value={stock}>{LISTING_BY_CODE[stock]?.name ?? stock} · {stock}</option>)}
                  </select>
                </div>
                <div className="nx-field">
                  <label htmlFor={`${formId}-range`}>캔들</label>
                  <select id={`${formId}-range`} className="nx-select" value={draft.range} onChange={(event) => update({ range: event.target.value })}>
                    {ranges.map((item) => <option key={item.value} value={item.value}>{item.label}봉</option>)}
                  </select>
                </div>
                <div className="nx-field">
                  <label htmlFor={`${formId}-orders`}>주문 횟수 한도</label>
                  <div className="nx-stepper is-plain">
                    <input id={`${formId}-orders`} className="nx-num" inputMode="numeric" value={draft.maxOrders} onChange={(event) => update({ maxOrders: digits(event.target.value).slice(0, 3) })} />
                    <span className="nx-stepper-unit">회</span>
                  </div>
                </div>
                <div className="nx-field">
                  <label htmlFor={`${formId}-notional`}>주문 금액 한도</label>
                  <div className="nx-stepper is-plain">
                    <input id={`${formId}-notional`} className="nx-num" inputMode="numeric" value={draft.maxNotional ? Number(draft.maxNotional).toLocaleString("ko-KR") : ""}
                      onChange={(event) => update({ maxNotional: digits(event.target.value).slice(0, 13) })} />
                    <span className="nx-stepper-unit">원</span>
                  </div>
                </div>
              </div>
              <p className="nx-field-note">한도는 매수·매도 주문을 낸 횟수와 「지정가 × 수량」을 모두 더해 세요. 실패하거나 체결되지 않아도 줄어들지 않고, 중지·편집해도 초기화되지 않아요.</p>
            </section>

            <section className="nx-panel qx-code" aria-labelledby={`${formId}-code`}>
              <header className="nx-panel-head">
                <h2 id={`${formId}-code`} className="nx-panel-title">규칙</h2>
                <AnimatedNav as="div" fill activeKey={draft.language} className="nx-seg" role="group" aria-label="편집 방식">
                  <button type="button" className={draft.language === "BLOCKS" ? "is-active" : undefined} aria-pressed={draft.language === "BLOCKS"} onClick={() => switchLanguage("BLOCKS")}>블록</button>
                  <button type="button" className={draft.language === "SNSCRIPT" ? "is-active" : undefined} aria-pressed={draft.language === "SNSCRIPT"} onClick={() => switchLanguage("SNSCRIPT")}>snScript</button>
                </AnimatedNav>
              </header>
              {draft.language === "BLOCKS"
                ? <BlockEditor key={editorKey} program={draft.blocks} onChange={(blocks) => update({ blocks })} />
                : <>
                    <label htmlFor={`${formId}-script`} className="vh">snScript 소스</label>
                    <ScriptEditor id={`${formId}-script`} value={draft.script} onChange={(script) => update({ script })} diagnostics={compilation?.valid === false ? compilation.diagnostics : []} />
                    <details className="qx-help">
                      <summary>snScript 문법</summary>
                      <ul>
                        <li><code>let 이름 = 식</code> · <code>plot 이름 = 식</code> · <code>buy when 조건 quantity 수량</code> · <code>sell when 조건 quantity 수량</code></li>
                        <li>시계열: <code>open high low close volume</code>(volume은 체결 건수)</li>
                        <li>함수: <code>ta.sma(식, 기간)</code> <code>ta.ema</code> <code>ta.rsi</code> <code>ta.stddev</code> <code>ta.atr(기간)</code> <code>ta.crossover(a, b)</code> <code>ta.crossunder(a, b)</code> <code>abs(식)</code></li>
                        <li>연산: <code>+ - * /</code> <code>&gt; &gt;= &lt; &lt;= == !=</code> <code>and or not</code>, 괄호. 한 문장은 한 줄에 쓰고 <code>//</code>로 주석을 달아요.</li>
                        <li>기간 2~500, 수량 1~1,000주, 변수 32개·그래프 8개·규칙 8개까지.</li>
                      </ul>
                    </details>
                  </>}
              <p className={`qx-compile${compilation && !compilation.valid ? " is-error" : ""}`} role="status">
                {compiling ? "확인하는 중…"
                  : compileError ? compileError
                  : !compilation ? ""
                  : compilation.valid ? `문제없어요 · 변수 ${compilation.program?.bindings.length ?? 0} · 그래프 ${compilation.program?.plots.length ?? 0} · 규칙 ${compilation.program?.rules.length ?? 0}`
                  : `오류 ${compilation.diagnostics.length}개 · ${compilation.diagnostics[0]?.message ?? ""}`}
              </p>
            </section>

            <div className="qx-savebar">
              {message && <p className={`nx-msg ${message.ok ? "is-success" : "is-error"}`} role={message.ok ? "status" : "alert"}>{message.text}</p>}
              {conflict && (
                <div className="qx-conflict">
                  <button type="button" className="nx-btn is-small" onClick={() => void resolveConflict(true)}>내 편집으로 저장</button>
                  <button type="button" className="nx-btn is-small" onClick={() => void resolveConflict(false)}>최신 버전 불러오기</button>
                </div>
              )}
              {saveBlocked && <p className="nx-field-hint is-error">{saveBlocked}</p>}
              <div className="qx-actions">
                {project && <button type="button" className="nx-btn is-danger" disabled={!paused || busy !== null} onClick={() => void remove()}
                  title={paused ? undefined : "중지한 전략만 지울 수 있어요"}>지우기</button>}
                <button type="button" className="nx-btn is-primary" disabled={busy !== null || !dirty || !compiled || Boolean(saveBlocked)} onClick={() => void save()}>
                  {busy === "save" ? "저장하는 중…" : project ? (dirty ? "저장" : "저장됨") : "전략 저장"}
                </button>
              </div>
            </div>

            <BacktestPanel stockCode={draft.stockCode} range={draft.range} code={compiled ? code : null} ready={compiled} />

            {project && <LivePanel project={project} dirty={dirty} busy={busy === "state"} onToggle={(active) => void toggle(active)} onProject={(next) => {
              setProject(next);
              setProjects((current) => current && current.map((item) => item.id === next.id ? next : item));
            }} />}
          </>}
        </div>
      </div>
    </div>
  );
}

/** 자동매매 상태·한도 사용량·실행 기록. 켜져 있는 동안 상태와 기록을 주기적으로 다시 받는다. */
function LivePanel({ project, dirty, busy, onToggle, onProject }: {
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
