import { useRef, type KeyboardEvent } from "react";
import type { Diagnostic } from "../../lib/research";

export const SCRIPT_MAX_LENGTH = 32_768;

/** snScript 편집칸. 줄 번호와 오류 줄 표시만 둔 일반 텍스트 편집기다. */
export function ScriptEditor({ value, onChange, diagnostics, id }: { value: string; onChange: (value: string) => void; diagnostics: Diagnostic[]; id: string }) {
  const gutter = useRef<HTMLDivElement>(null);
  const area = useRef<HTMLTextAreaElement>(null);
  const lines = value.split("\n").length;
  const errorLines = new Set(diagnostics.map((item) => item.line));

  /* Tab은 공백 두 칸을 넣는다. Esc 뒤 Tab은 다음 칸으로 초점을 넘긴다(키보드 함정 방지). */
  const escaped = useRef(false);
  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Escape") { escaped.current = true; return; }
    if (event.key !== "Tab" || event.shiftKey || escaped.current) { escaped.current = false; return; }
    event.preventDefault();
    const element = event.currentTarget;
    const { selectionStart, selectionEnd } = element;
    const next = `${value.slice(0, selectionStart)}  ${value.slice(selectionEnd)}`;
    onChange(next);
    window.requestAnimationFrame(() => { element.selectionStart = element.selectionEnd = selectionStart + 2; });
  };

  /** 진단 위치로 커서를 옮긴다. */
  const jump = (diagnostic: Diagnostic) => {
    const element = area.current;
    if (!element) return;
    const rows = value.split("\n");
    let offset = 0;
    for (let index = 0; index < Math.min(diagnostic.line - 1, rows.length); index += 1) offset += rows[index].length + 1;
    offset += Math.max(0, diagnostic.column - 1);
    element.focus();
    element.setSelectionRange(offset, offset);
  };

  return (
    <div className="qs-editor">
      <div className="qs-frame">
        <div className="qs-gutter nx-num" ref={gutter} aria-hidden="true">
          {Array.from({ length: lines }, (_, index) => <span key={index} className={errorLines.has(index + 1) ? "is-error" : undefined}>{index + 1}</span>)}
        </div>
        <textarea id={id} ref={area} className="qs-input" value={value} spellCheck={false} autoCapitalize="off" autoComplete="off" autoCorrect="off"
          wrap="off" maxLength={SCRIPT_MAX_LENGTH} aria-invalid={diagnostics.length > 0 || undefined} aria-describedby={`${id}-diagnostics`}
          onKeyDown={onKeyDown} onChange={(event) => onChange(event.target.value)}
          onScroll={(event) => { if (gutter.current) gutter.current.scrollTop = event.currentTarget.scrollTop; }} />
      </div>
      <div id={`${id}-diagnostics`} className="qs-diagnostics" aria-live="polite">
        {diagnostics.map((item, index) => (
          <button key={index} type="button" className="qs-diagnostic" onClick={() => jump(item)}>
            <span className="nx-num">{item.line}:{item.column}</span> {item.message}
          </button>
        ))}
      </div>
      <p className="nx-field-note">
        <span className="nx-num">{value.length.toLocaleString("ko-KR")}/{SCRIPT_MAX_LENGTH.toLocaleString("ko-KR")}</span>자 · Tab 들여쓰기, Esc 다음 Tab으로 편집칸 나가기
      </p>
    </div>
  );
}
