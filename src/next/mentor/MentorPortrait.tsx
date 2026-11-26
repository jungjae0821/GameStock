import { useEffect, useState, useSyncExternalStore } from "react";

/*
 * 슈엔 일러스트. 전신 그림 한 장(/characters/shuen.webp)을 잘라서 큰 대화창에는 상반신(bust),
 * 작은 OJT 팝업에는 얼굴(face)만 보여 준다. 그림을 못 읽으면 브랜드 마크로 대신하고,
 * 한 번 실패하면 다른 곳에서도 다시 받으려 하지 않는다.
 */
export const SHUEN_ILLUSTRATION = "/characters/shuen.webp";
const FALLBACK = "/brand/shuen-mark.png";

let missing = false;
const listeners = new Set<() => void>();
const markMissing = () => {
  if (missing) return;
  missing = true;
  for (const listener of listeners) listener();
};
const useMissing = () => useSyncExternalStore(
  (listener) => { listeners.add(listener); return () => listeners.delete(listener); },
  () => missing,
  () => missing,
);

type Props = { variant: "bust" | "face"; talking?: boolean };

export function MentorPortrait({ variant, talking = false }: Props) {
  const failed = useMissing();
  return (
    <span className={`mentor-portrait is-${variant}${talking ? " is-talking" : ""}${failed ? " is-fallback" : ""}`} aria-hidden="true">
      {failed
        ? <img src={FALLBACK} alt="" />
        : <img src={SHUEN_ILLUSTRATION} alt="" decoding="async" onError={markMissing} />}
    </span>
  );
}

const reducedMotion = () => typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

/**
 * 대사를 한 글자씩 보여 준다. 움직임을 줄이도록 설정했으면 바로 다 보여 준다.
 * skip()을 부르면 남은 글자를 한꺼번에 채운다.
 */
export function useTypewriter(text: string, speedMs = 26): { shown: string; done: boolean; skip: () => void } {
  const letters = Array.from(text);
  const [count, setCount] = useState(() => (reducedMotion() ? letters.length : 0));
  const [source, setSource] = useState(text);
  if (source !== text) {
    setSource(text);
    setCount(reducedMotion() ? letters.length : 0);
  }
  const total = letters.length;
  useEffect(() => {
    if (count >= total) return;
    const timer = window.setTimeout(() => setCount((current) => Math.min(total, current + 1)), speedMs);
    return () => window.clearTimeout(timer);
  }, [count, total, speedMs]);
  return { shown: letters.slice(0, count).join(""), done: count >= total, skip: () => setCount(total) };
}
