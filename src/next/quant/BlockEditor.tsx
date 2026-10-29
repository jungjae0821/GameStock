import { useEffect, useId, useState, type ReactNode } from "react";
import type { BinaryOp, BlockProgram, Expr, Rule } from "../../lib/research";
import { BINARY, COMPARISON, CROSS, FIELD_LABEL, FIELDS, LOGIC, countNodes } from "./program";

/*
 * 블록 편집기. 식 하나가 블록 하나이고, 블록 종류를 바꾸면 안쪽 블록은 되도록 그대로 옮겨 담는다.
 * 블록 색은 분류(값·지표·계산·비교·논리)를 나타낸다. 저장 모양은 docs/research-backend.md의 BlockProgram이다.
 */

type Kind = "number" | "ref" | Expr["op"];
type Group = "value" | "indicator" | "math" | "compare" | "logic";

const KIND_GROUPS: Array<{ label: string; group: Group; kinds: Array<[Kind, string]> }> = [
  { label: "값", group: "value", kinds: [["number", "숫자"], ["ref", "시계열 · 변수"]] },
  { label: "지표", group: "indicator", kinds: [["sma", "단순 이동평균"], ["ema", "지수 이동평균"], ["rsi", "RSI"], ["stddev", "표준편차"], ["atr", "ATR"]] },
  { label: "계산", group: "math", kinds: [["+", "더하기 +"], ["-", "빼기 −"], ["*", "곱하기 ×"], ["/", "나누기 ÷"], ["abs", "절댓값"], ["neg", "부호 바꾸기"]] },
  { label: "비교", group: "compare", kinds: [[">", "보다 큼 >"], [">=", "크거나 같음 ≥"], ["<", "보다 작음 <"], ["<=", "작거나 같음 ≤"], ["==", "같음 ="], ["!=", "다름 ≠"]] },
  { label: "논리", group: "logic", kinds: [["and", "그리고"], ["or", "또는"], ["not", "아님"], ["crossover", "위로 교차"], ["crossunder", "아래로 교차"]] },
];
const GROUP_OF = new Map<string, Group>(KIND_GROUPS.flatMap((item) => item.kinds.map(([kind]) => [kind, item.group] as [string, Group])));

const LIMITS = { bindings: 32, plots: 8, rules: 8, nodes: 256, depth: 24 };
const CLOSE: Expr = { op: "ref", name: "close" };
const NAME_PATTERN = /^[A-Za-z_][A-Za-z0-9_]{0,31}$/;
const RESERVED = new Set(["let", "plot", "buy", "sell", "when", "quantity", "and", "or", "not", "true", "false", ...FIELDS]);

const childrenOf = (expr: Expr): Expr[] => ("args" in expr ? expr.args : []);

/** 블록 종류를 바꿀 때 기존 블록을 재료로 새 블록을 만든다. */
function convert(expr: Expr, kind: Kind): Expr {
  const kids = childrenOf(expr);
  const first = kids[0] ?? (expr.op === "number" || expr.op === "ref" ? expr : CLOSE);
  const period = "period" in expr ? expr.period : 14;
  switch (kind) {
    case "number": return { op: "number", value: expr.op === "number" ? expr.value : 0 };
    case "ref": return expr.op === "ref" ? expr : CLOSE;
    case "sma": case "ema": case "rsi": case "stddev": return { op: kind, period, args: [first] };
    case "atr": return { op: "atr", period, args: [] };
    case "abs": case "neg": case "not": return { op: kind, args: [first] };
    default: {
      const binary = kind as BinaryOp;
      const fallback: Expr = COMPARISON.includes(binary) || LOGIC.includes(binary) || CROSS.includes(binary) ? CLOSE : { op: "number", value: 1 };
      return { op: binary, args: [first, kids[1] ?? (kids[0] ? fallback : { op: "number", value: 0 })] };
    }
  }
}

function KindSelect({ expr, onChange, label }: { expr: Expr; onChange: (expr: Expr) => void; label: string }) {
  return (
    <select className="qb-kind" value={expr.op} aria-label={`${label} 블록 종류`} onChange={(event) => onChange(convert(expr, event.target.value as Kind))}>
      {KIND_GROUPS.map((group) => (
        <optgroup key={group.label} label={group.label}>
          {group.kinds.map(([kind, text]) => <option key={kind} value={kind}>{text}</option>)}
        </optgroup>
      ))}
    </select>
  );
}

/** 입력 중인 글자는 그대로 두고, 바깥에서 값이 바뀌면(블록 종류 변경·불러오기) 따라간다. */
function useDraft(value: number): [string, (text: string) => void] {
  const [text, setText] = useState(String(value));
  useEffect(() => { setText((current) => (Number(current) === value ? current : String(value))); }, [value]);
  return [text, setText];
}

function PeriodInput({ value, onChange, label }: { value: number; onChange: (period: number) => void; label: string }) {
  const [text, setText] = useDraft(value);
  const invalid = !(Number.isInteger(Number(text)) && Number(text) >= 2 && Number(text) <= 500);
  return (
    <input className="qb-number qb-period nx-num" inputMode="numeric" value={text} aria-label={`${label} 기간(2~500)`} aria-invalid={invalid || undefined}
      title="기간 2~500" size={3}
      onChange={(event) => {
        const next = event.target.value.replace(/[^0-9]/g, "").slice(0, 3);
        setText(next);
        if (next) onChange(Number(next));
      }}
      onBlur={() => setText(String(value))} />
  );
}

function NumberInput({ value, onChange, label }: { value: number; onChange: (value: number) => void; label: string }) {
  const [text, setText] = useDraft(value);
  const parsed = Number(text);
  const invalid = text === "" || !Number.isFinite(parsed) || Math.abs(parsed) > 1e12;
  return (
    <input className="qb-number nx-num" inputMode="decimal" value={text} aria-label={label} aria-invalid={invalid || undefined} size={Math.max(2, text.length)}
      onChange={(event) => {
        const next = event.target.value.replace(/[^0-9.\-]/g, "");
        setText(next);
        const number = Number(next);
        if (next !== "" && next !== "-" && Number.isFinite(number)) onChange(number);
      }}
      onBlur={() => setText(String(value))} />
  );
}

/** 식 블록 하나. 안쪽 블록은 같은 컴포넌트로 다시 그린다. */
function ExprBlock({ expr, onChange, names, label, depth = 0 }: { expr: Expr; onChange: (expr: Expr) => void; names: string[]; label: string; depth?: number }) {
  const group = GROUP_OF.get(expr.op) ?? "value";
  const child = (index: number, next: Expr) => {
    const args = [...childrenOf(expr)];
    args[index] = next;
    onChange({ ...expr, args } as Expr);
  };
  const inner = (index: number, part: string) => childrenOf(expr)[index] && (
    <ExprBlock expr={childrenOf(expr)[index]} onChange={(next) => child(index, next)} names={names} label={`${label} ${part}`} depth={depth + 1} />
  );
  const tooDeep = depth >= LIMITS.depth - 1;

  let body: ReactNode;
  if (expr.op === "number") {
    body = <><KindSelect expr={expr} onChange={onChange} label={label} /><NumberInput value={expr.value} label={`${label} 숫자`} onChange={(value) => onChange({ op: "number", value })} /></>;
  } else if (expr.op === "ref") {
    const known = (FIELDS as readonly string[]).includes(expr.name) || names.includes(expr.name);
    body = <>
      <KindSelect expr={expr} onChange={onChange} label={label} />
      <select className="qb-ref" value={expr.name} aria-label={`${label} 참조`} aria-invalid={!known || undefined} onChange={(event) => onChange({ op: "ref", name: event.target.value })}>
        <optgroup label="시계열">{FIELDS.map((field) => <option key={field} value={field}>{FIELD_LABEL[field]} · {field}</option>)}</optgroup>
        {names.length > 0 && <optgroup label="변수">{names.map((name) => <option key={name} value={name}>{name}</option>)}</optgroup>}
        {!known && <option value={expr.name}>{expr.name} (없는 이름)</option>}
      </select>
    </>;
  } else if (expr.op === "atr") {
    body = <><KindSelect expr={expr} onChange={onChange} label={label} /><span className="qb-text">기간</span><PeriodInput value={expr.period} label={label} onChange={(period) => onChange({ ...expr, period })} /></>;
  } else if (expr.op === "sma" || expr.op === "ema" || expr.op === "rsi" || expr.op === "stddev") {
    body = <><KindSelect expr={expr} onChange={onChange} label={label} />{inner(0, "대상")}<span className="qb-text">기간</span><PeriodInput value={expr.period} label={label} onChange={(period) => onChange({ ...expr, period })} /></>;
  } else if (expr.op === "abs" || expr.op === "neg" || expr.op === "not") {
    body = <><KindSelect expr={expr} onChange={onChange} label={label} />{inner(0, "대상")}</>;
  } else if (CROSS.includes(expr.op)) {
    body = <>{inner(0, "기준")}<KindSelect expr={expr} onChange={onChange} label={label} />{inner(1, "상대")}</>;
  } else {
    body = <>{inner(0, "왼쪽")}<KindSelect expr={expr} onChange={onChange} label={label} />{inner(1, "오른쪽")}</>;
  }

  return (
    <span className={`qb-block is-${group}${BINARY.has(expr.op) ? " is-binary" : ""}`} data-depth={depth}>
      {body}
      {tooDeep && childrenOf(expr).length > 0 && <span className="qb-warn" role="note">중첩 한도에 가까워요</span>}
    </span>
  );
}

function RowTools({ index, count, onMove, onRemove, label }: { index: number; count: number; onMove: (to: number) => void; onRemove: () => void; label: string }) {
  return (
    <span className="qb-row-tools">
      <button type="button" className="qb-tool" disabled={index === 0} aria-label={`${label} 위로`} onClick={() => onMove(index - 1)}>↑</button>
      <button type="button" className="qb-tool" disabled={index === count - 1} aria-label={`${label} 아래로`} onClick={() => onMove(index + 1)}>↓</button>
      <button type="button" className="qb-tool" aria-label={`${label} 지우기`} onClick={onRemove}>×</button>
    </span>
  );
}

function move<T>(items: T[], from: number, to: number): T[] {
  const next = [...items];
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item);
  return next;
}

function nameIssue(name: string, taken: string[]): string {
  if (!NAME_PATTERN.test(name)) return "영문·숫자·밑줄 32자 이내, 숫자로 시작할 수 없어요.";
  if (RESERVED.has(name)) return "예약어나 기본 시계열 이름은 쓸 수 없어요.";
  if (taken.includes(name)) return "이미 쓰는 이름이에요.";
  return "";
}

function NameInput({ value, onChange, taken, label }: { value: string; onChange: (name: string) => void; taken: string[]; label: string }) {
  const id = useId();
  const issue = nameIssue(value, taken);
  return (
    <span className="qb-name">
      <input id={id} className="qb-name-input" value={value} aria-label={label} aria-invalid={Boolean(issue) || undefined} aria-describedby={issue ? `${id}-issue` : undefined}
        spellCheck={false} autoComplete="off" onChange={(event) => onChange(event.target.value.replace(/[^A-Za-z0-9_]/g, "").slice(0, 32))} />
      {issue && <span id={`${id}-issue`} className="qb-issue">{issue}</span>}
    </span>
  );
}

function freshName(prefix: string, taken: string[]) {
  let index = 1;
  while (taken.includes(`${prefix}${index}`)) index += 1;
  return `${prefix}${index}`;
}

/** 변수 → 그래프 → 매매 규칙 순서로 위에서 아래로 읽힌다. 변수는 자기보다 위에서 만든 변수만 쓸 수 있다. */
export function BlockEditor({ program, onChange }: { program: BlockProgram; onChange: (program: BlockProgram) => void }) {
  const bindingNames = program.bindings.map((item) => item.name);
  const plotNames = program.plots.map((item) => item.name);
  const nodes = [...program.bindings, ...program.plots].reduce((sum, item) => sum + countNodes(item.expression), 0)
    + program.rules.reduce((sum, rule) => sum + countNodes(rule.condition), 0);
  const size = JSON.stringify(program).length;
  const set = (patch: Partial<BlockProgram>) => onChange({ ...program, ...patch });
  const lastName = bindingNames.at(-1);

  const setRule = (index: number, rule: Rule) => set({ rules: program.rules.map((item, at) => at === index ? rule : item) });

  return (
    <div className="qb-editor">
      <section className="qb-section" aria-labelledby="qb-bindings">
        <header>
          <h3 id="qb-bindings">변수 <span className="nx-num nx-muted">{program.bindings.length}/{LIMITS.bindings}</span></h3>
          <button type="button" className="nx-btn is-small" disabled={program.bindings.length >= LIMITS.bindings}
            onClick={() => set({ bindings: [...program.bindings, { name: freshName("v", bindingNames), expression: { op: "sma", period: 20, args: [CLOSE] } }] })}>변수 추가</button>
        </header>
        {program.bindings.length === 0 && <p className="nx-muted qb-empty">이동평균처럼 여러 번 쓰는 값을 변수로 만들어 두세요.</p>}
        <ol className="qb-rows">
          {program.bindings.map((binding, index) => {
            const before = bindingNames.slice(0, index);
            return (
              <li key={index} className="qb-row">
                <span className="qb-keyword">변수</span>
                <NameInput value={binding.name} label={`변수 ${index + 1} 이름`} taken={before}
                  onChange={(name) => set({ bindings: program.bindings.map((item, at) => at === index ? { ...item, name } : item) })} />
                <span className="qb-keyword">=</span>
                <ExprBlock expr={binding.expression} names={before} label={`변수 ${binding.name}`}
                  onChange={(expression) => set({ bindings: program.bindings.map((item, at) => at === index ? { ...item, expression } : item) })} />
                <RowTools index={index} count={program.bindings.length} label={`변수 ${binding.name}`}
                  onMove={(to) => set({ bindings: move(program.bindings, index, to) })}
                  onRemove={() => set({ bindings: program.bindings.filter((_, at) => at !== index) })} />
              </li>
            );
          })}
        </ol>
      </section>

      <section className="qb-section" aria-labelledby="qb-plots">
        <header>
          <h3 id="qb-plots">그래프 <span className="nx-num nx-muted">{program.plots.length}/{LIMITS.plots}</span></h3>
          <button type="button" className="nx-btn is-small" disabled={program.plots.length >= LIMITS.plots}
            onClick={() => set({ plots: [...program.plots, { name: freshName("line", plotNames), expression: lastName ? { op: "ref", name: lastName } : CLOSE }] })}>그래프 추가</button>
        </header>
        <p className="nx-muted qb-empty">그래프는 백테스트 차트에 선으로 그려져요. 그래프 이름은 변수가 아니라서 다른 블록에서 쓸 수 없어요.</p>
        <ol className="qb-rows">
          {program.plots.map((plot, index) => (
            <li key={index} className="qb-row">
              <span className="qb-keyword">그래프</span>
              <NameInput value={plot.name} label={`그래프 ${index + 1} 이름`} taken={plotNames.filter((_, at) => at !== index)}
                onChange={(name) => set({ plots: program.plots.map((item, at) => at === index ? { ...item, name } : item) })} />
              <span className="qb-keyword">=</span>
              <ExprBlock expr={plot.expression} names={bindingNames} label={`그래프 ${plot.name}`}
                onChange={(expression) => set({ plots: program.plots.map((item, at) => at === index ? { ...item, expression } : item) })} />
              <RowTools index={index} count={program.plots.length} label={`그래프 ${plot.name}`}
                onMove={(to) => set({ plots: move(program.plots, index, to) })}
                onRemove={() => set({ plots: program.plots.filter((_, at) => at !== index) })} />
            </li>
          ))}
        </ol>
      </section>

      <section className="qb-section" aria-labelledby="qb-rules">
        <header>
          <h3 id="qb-rules">매매 규칙 <span className="nx-num nx-muted">{program.rules.length}/{LIMITS.rules}</span></h3>
          <button type="button" className="nx-btn is-small" disabled={program.rules.length >= LIMITS.rules}
            onClick={() => set({ rules: [...program.rules, { side: "BUY", quantity: 1, condition: { op: "crossover", args: [CLOSE, lastName ? { op: "ref", name: lastName } : { op: "ref", name: "open" }] } }] })}>규칙 추가</button>
        </header>
        <p className="nx-muted qb-empty">캔들마다 위에서부터 처음 참이 된 규칙 하나만 주문해요. 순서가 우선순위예요.</p>
        <ol className="qb-rows">
          {program.rules.map((rule, index) => (
            <li key={index} className={`qb-row is-${rule.side === "BUY" ? "buy" : "sell"}`}>
              <select className="qb-side" value={rule.side} aria-label={`규칙 ${index + 1} 방향`} onChange={(event) => setRule(index, { ...rule, side: event.target.value as Rule["side"] })}>
                <option value="BUY">매수</option>
                <option value="SELL">매도</option>
              </select>
              <span className="qb-keyword">조건</span>
              <ExprBlock expr={rule.condition} names={bindingNames} label={`규칙 ${index + 1} 조건`} onChange={(condition) => setRule(index, { ...rule, condition })} />
              <span className="qb-keyword">수량</span>
              <span className="qb-qty">
                <QuantityInput value={rule.quantity} label={`규칙 ${index + 1} 수량`} onChange={(quantity) => setRule(index, { ...rule, quantity })} />
                <span className="qb-text">주</span>
              </span>
              <RowTools index={index} count={program.rules.length} label={`규칙 ${index + 1}`}
                onMove={(to) => set({ rules: move(program.rules, index, to) })}
                onRemove={() => set({ rules: program.rules.filter((_, at) => at !== index) })} />
            </li>
          ))}
        </ol>
      </section>
      <p className={`nx-field-note qb-usage${nodes > LIMITS.nodes || size > 32_768 ? " is-over" : ""}`}>
        블록 <span className="nx-num">{nodes}/{LIMITS.nodes}</span> · 저장 크기 <span className="nx-num">{size.toLocaleString("ko-KR")}/32,768</span>자
      </p>
    </div>
  );
}

function QuantityInput({ value, onChange, label }: { value: number; onChange: (value: number) => void; label: string }) {
  const [text, setText] = useDraft(value);
  const invalid = !(Number.isInteger(Number(text)) && Number(text) >= 1 && Number(text) <= 1000);
  return (
    <input className="qb-number nx-num" inputMode="numeric" value={text} aria-label={`${label}(1~1,000주)`} aria-invalid={invalid || undefined} size={4}
      onChange={(event) => {
        const next = event.target.value.replace(/[^0-9]/g, "").slice(0, 4);
        setText(next);
        if (next) onChange(Number(next));
      }}
      onBlur={() => setText(String(value))} />
  );
}
