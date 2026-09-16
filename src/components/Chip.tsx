import type { ReactNode } from "react";

/* 칩은 값의 상태에만 쓴다. 구획·출처 표시 같은 장식 자리에는 만들지 않는다. */
export type Tone = "up" | "down" | "flat";

export function rateTone(ratio: number): Tone {
  if (ratio > 0) return "up";
  if (ratio < 0) return "down";
  return "flat";
}

interface Props {
  tone?: Tone;
  solid?: boolean;
  className?: string;
  children: ReactNode;
}

export function Chip({ tone = "flat", solid = false, className, children }: Props) {
  const name = solid ? `is-solid-${tone}` : `is-${tone}`;
  return <span className={`chip ${name}${className ? ` ${className}` : ""}`}>{children}</span>;
}
