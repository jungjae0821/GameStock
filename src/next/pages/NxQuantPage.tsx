import { useCallback, useEffect, useId, useMemo, useState } from "react";
import { ApiError } from "../../lib/api";
import {
  CHART_RANGES, MAX_PROJECTS, compileQuant, createProject, deleteProject, failure, getProject, isAbort, listProjects,
  quantCapabilities, replaceProject, setProjectState,
  type Capabilities, type Compilation, type Project, type ProjectSave,
} from "../../lib/research";
import { serverTimestamp, won } from "../../market/format";
import { useMarket } from "../../market/MarketProvider";
import { LISTING_BY_CODE } from "../../market/universe";
import { QUANT_PATH, navigate } from "../../router";
import { BacktestPanel } from "../quant/BacktestPanel";
import { blankDraft, draftOf, limitsIssue, signature, sourceOf, type Draft, type Language } from "../quant/draft";
import { LivePanel } from "../quant/LivePanel";
import { ProjectList } from "../quant/ProjectList";
import { blocksToScript, normalizeProgram } from "../quant/program";
import { RuleEditor } from "../quant/RuleEditor";
import { SCRIPT_MAX_LENGTH } from "../quant/ScriptEditor";
import { StrategySettings } from "../quant/StrategySettings";
import { UnlockNotice } from "../engagement/UnlockNotice";
import { DeskIntro } from "../mentor/DeskIntro";

const COMPILE_DELAY_MS = 400;

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

      <UnlockNotice feature="QUANT" />
      <DeskIntro desk="QUANT" />

      <div className="qx-layout">
        <ProjectList formId={formId} id={id} projects={projects} listError={listError} capabilities={capabilities}
          onOpen={open} onRetry={() => void refreshList()} />

        <div className="qx-main">
          {loadError && <p className="nx-note is-error" role="alert">{loadError} <button type="button" className="nx-link-button" onClick={() => navigate(QUANT_PATH(null))}>새 전략 만들기</button></p>}
          {id !== null && !project && !loadError ? <div className="nx-skeleton" role="status" aria-label="전략을 불러오는 중"><span /><span /><span /></div> : !loadError && <>
            <StrategySettings formId={formId} project={project} draft={draft} update={update} stockCodes={stockCodes} ranges={ranges} />

            <RuleEditor formId={formId} draft={draft} update={update} onLanguage={switchLanguage} editorKey={editorKey}
              compilation={compilation} compiling={compiling} compileError={compileError} />

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
