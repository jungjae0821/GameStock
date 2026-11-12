import type { BlockProgram, Project, ProjectStatus, Source } from "../../lib/research";
import { EXAMPLE_BLOCKS, EXAMPLE_SCRIPT, blocksToScript, normalizeProgram, parseBlocks } from "./program";

/* 퀀트 전략 편집 초안과 그 변환·검사. */
export type Language = "SNSCRIPT" | "BLOCKS";
export type Draft = {
  name: string; stockCode: string; range: string; language: Language;
  script: string; blocks: BlockProgram; maxOrders: string; maxNotional: string;
};

export const STATUS_LABEL: Record<ProjectStatus, string> = { PAUSED: "중지됨", ACTIVE: "실행 중", RUNNING: "주문 처리 중", PAUSING: "중지하는 중" };
export const digits = (text: string) => text.replace(/[^0-9]/g, "");

export function blankDraft(stockCode: string): Draft {
  return { name: "새 전략", stockCode, range: "1h", language: "SNSCRIPT", script: EXAMPLE_SCRIPT, blocks: EXAMPLE_BLOCKS, maxOrders: "10", maxNotional: "1000000" };
}

export function draftOf(project: Project): Draft {
  const blocks = project.language === "BLOCKS" ? parseBlocks(project.source) ?? normalizeProgram(project.program) : normalizeProgram(project.program);
  return {
    name: project.name, stockCode: project.stockCode, range: project.range, language: project.language,
    script: project.language === "SNSCRIPT" ? project.source : blocksToScript(blocks), blocks,
    maxOrders: String(project.maxOrders), maxNotional: String(project.maxNotional),
  };
}

export const sourceOf = (draft: Draft): Source => draft.language === "SNSCRIPT" ? { language: "SNSCRIPT", source: draft.script } : { language: "BLOCKS", blocks: draft.blocks };
/** 편집 여부 비교용. 지금 고른 언어의 소스만 본다. */
export const signature = (draft: Draft) => JSON.stringify([draft.name.trim(), draft.stockCode, draft.range, sourceOf(draft), draft.maxOrders, draft.maxNotional]);

export function limitsIssue(draft: Draft): string {
  const orders = Number(draft.maxOrders);
  const notional = Number(draft.maxNotional);
  if (!draft.name.trim() || [...draft.name.trim()].length > 80) return "이름은 1~80자로 지어 주세요.";
  if (!draft.maxOrders || orders < 1 || orders > 100) return "주문 횟수 한도는 1~100회예요.";
  if (!draft.maxNotional || notional < 1 || notional > 1e12) return "주문 금액 한도는 1원~1조 원이에요.";
  return "";
}
