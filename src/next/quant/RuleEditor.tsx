import type { Compilation } from "../../lib/research";
import { AnimatedNav } from "../AnimatedNav";
import { BlockEditor } from "./BlockEditor";
import { ScriptEditor } from "./ScriptEditor";
import type { Draft, Language } from "./draft";

/** 블록·snScript 편집기와 컴파일 결과. */
export function RuleEditor({ formId, draft, update, onLanguage, editorKey, compilation, compiling, compileError }: {
  formId: string; draft: Draft; update: (patch: Partial<Draft>) => void; onLanguage: (language: Language) => void;
  editorKey: number; compilation: Compilation | null; compiling: boolean; compileError: string;
}) {
  return (
    <section className="nx-panel qx-code" aria-labelledby={`${formId}-code`}>
      <header className="nx-panel-head">
        <h2 id={`${formId}-code`} className="nx-panel-title">규칙</h2>
        <AnimatedNav as="div" fill activeKey={draft.language} className="nx-seg" role="group" aria-label="편집 방식">
          <button type="button" className={draft.language === "BLOCKS" ? "is-active" : undefined} aria-pressed={draft.language === "BLOCKS"} onClick={() => onLanguage("BLOCKS")}>블록</button>
          <button type="button" className={draft.language === "SNSCRIPT" ? "is-active" : undefined} aria-pressed={draft.language === "SNSCRIPT"} onClick={() => onLanguage("SNSCRIPT")}>snScript</button>
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
  );
}
