import type { BinaryOp, BlockProgram, Expr } from "../../lib/research";

/*
 * BlockQuant 블록과 snScript 소스를 오가는 변환. 두 형식은 서버에서 같은 Program으로 컴파일된다.
 * 연산자 우선순위는 QuantCompiler.Parser와 같다: 단항 > 곱/나눗셈 > 덧/뺄셈 > 비교 > and > or, 이항은 왼쪽 결합.
 */

export const FIELDS = ["open", "high", "low", "close", "volume"] as const;
export const FIELD_LABEL: Record<(typeof FIELDS)[number], string> = { open: "시가", high: "고가", low: "저가", close: "종가", volume: "체결 건수" };

export const ARITHMETIC: BinaryOp[] = ["+", "-", "*", "/"];
export const COMPARISON: BinaryOp[] = [">", ">=", "<", "<=", "==", "!="];
export const LOGIC: BinaryOp[] = ["and", "or"];
export const CROSS: BinaryOp[] = ["crossover", "crossunder"];
export const BINARY = new Set<string>([...ARITHMETIC, ...COMPARISON, ...LOGIC, ...CROSS]);

export const EXAMPLE_SCRIPT = `// 종가 기준 이동평균 교차
let fast = ta.sma(close, 5)
let slow = ta.ema(close, 20)
plot fastLine = fast
plot slowLine = slow
plot rsiLine = ta.rsi(close, 14)
buy when ta.crossover(fast, slow) quantity 2
sell when ta.crossunder(fast, slow) quantity 2
`;

export const EXAMPLE_BLOCKS: BlockProgram = {
  version: 1,
  bindings: [
    { name: "fast", expression: { op: "sma", period: 5, args: [{ op: "ref", name: "close" }] } },
    { name: "slow", expression: { op: "ema", period: 20, args: [{ op: "ref", name: "close" }] } },
  ],
  plots: [{ name: "fastLine", expression: { op: "ref", name: "fast" } }],
  rules: [
    { side: "BUY", quantity: 2, condition: { op: "crossover", args: [{ op: "ref", name: "fast" }, { op: "ref", name: "slow" }] } },
    { side: "SELL", quantity: 2, condition: { op: "crossunder", args: [{ op: "ref", name: "fast" }, { op: "ref", name: "slow" }] } },
  ],
};

const PRECEDENCE: Record<string, number> = {
  or: 1, and: 2, ">": 3, ">=": 3, "<": 3, "<=": 3, "==": 3, "!=": 3, "+": 4, "-": 4, "*": 5, "/": 5,
};
const precedence = (expr: Expr) => PRECEDENCE[expr.op] ?? 6;

/** 소스 문법은 지수 표기를 받지 않는다. */
function numberText(value: number): string {
  const text = String(Math.abs(value));
  const plain = /e/i.test(text) ? Math.abs(value).toFixed(12).replace(/\.?0+$/, "") : text;
  return value < 0 ? `-${plain}` : plain;
}

export function exprToScript(expr: Expr): string {
  switch (expr.op) {
    case "number": return numberText(expr.value);
    case "ref": return expr.name;
    case "sma": case "ema": case "rsi": case "stddev": return `ta.${expr.op}(${exprToScript(expr.args[0])}, ${expr.period})`;
    case "atr": return `ta.atr(${expr.period})`;
    case "abs": return `abs(${exprToScript(expr.args[0])})`;
    case "crossover": case "crossunder": return `ta.${expr.op}(${exprToScript(expr.args[0])}, ${exprToScript(expr.args[1])})`;
    case "not": case "neg": {
      const inner = exprToScript(expr.args[0]);
      const wrapped = precedence(expr.args[0]) < 6 || (expr.args[0].op === "number" && expr.args[0].value < 0) ? `(${inner})` : inner;
      return expr.op === "not" ? `not ${wrapped}` : `-${wrapped}`;
    }
    default: {
      const own = precedence(expr);
      const [left, right] = expr.args;
      const leftText = precedence(left) < own ? `(${exprToScript(left)})` : exprToScript(left);
      const rightText = precedence(right) <= own ? `(${exprToScript(right)})` : exprToScript(right);
      return `${leftText} ${expr.op} ${rightText}`;
    }
  }
}

export function blocksToScript(program: BlockProgram): string {
  const lines: string[] = [];
  for (const binding of program.bindings) lines.push(`let ${binding.name} = ${exprToScript(binding.expression)}`);
  for (const plot of program.plots) lines.push(`plot ${plot.name} = ${exprToScript(plot.expression)}`);
  for (const rule of program.rules) lines.push(`${rule.side === "BUY" ? "buy" : "sell"} when ${exprToScript(rule.condition)} quantity ${rule.quantity}`);
  return `${lines.join("\n")}\n`;
}

type LooseExpr = { op: string; value?: number | null; name?: string | null; period?: number | null; args?: LooseExpr[] | null };

/** 컴파일된 Program은 미사용 필드가 null이다. 블록 편집기가 쓰는 모양으로 정리한다. */
export function normalizeExpr(raw: LooseExpr): Expr {
  const args = (raw.args ?? []).map(normalizeExpr);
  switch (raw.op) {
    case "number": return { op: "number", value: raw.value ?? 0 };
    case "ref": return { op: "ref", name: raw.name ?? "close" };
    case "sma": case "ema": case "rsi": case "stddev": return { op: raw.op, period: raw.period ?? 14, args: [args[0] ?? { op: "ref", name: "close" }] };
    case "atr": return { op: "atr", period: raw.period ?? 14, args: [] };
    case "abs": case "not": case "neg": return { op: raw.op, args: [args[0] ?? { op: "number", value: 0 }] };
    default: return { op: raw.op as BinaryOp, args: [args[0] ?? { op: "number", value: 0 }, args[1] ?? { op: "number", value: 0 }] };
  }
}

export function normalizeProgram(raw: unknown): BlockProgram {
  const value = (raw ?? {}) as { bindings?: Array<{ name: string; expression: LooseExpr }>; plots?: Array<{ name: string; expression: LooseExpr }>; rules?: Array<{ side: string; quantity: number; condition: LooseExpr }> };
  return {
    version: 1,
    bindings: (value.bindings ?? []).map((item) => ({ name: item.name, expression: normalizeExpr(item.expression) })),
    plots: (value.plots ?? []).map((item) => ({ name: item.name, expression: normalizeExpr(item.expression) })),
    rules: (value.rules ?? []).map((item) => ({ side: item.side === "SELL" ? "SELL" : "BUY", quantity: item.quantity, condition: normalizeExpr(item.condition) })),
  };
}

/** 저장된 BLOCKS 소스(JSON)를 되살린다. 깨졌으면 null. */
export function parseBlocks(source: string): BlockProgram | null {
  try { return normalizeProgram(JSON.parse(source)); } catch { return null; }
}

export function countNodes(expr: Expr): number {
  return 1 + ("args" in expr ? expr.args.reduce((sum, child) => sum + countNodes(child), 0) : 0);
}
