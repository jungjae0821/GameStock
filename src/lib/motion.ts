const query = typeof window === "undefined" ? null : window.matchMedia("(prefers-reduced-motion: reduce)");
let reduced = query?.matches ?? false;

query?.addEventListener("change", (event) => {
  reduced = event.matches;
});

/** 틱 플래시 같은 명령형 애니메이션은 이 값을 확인하고 건너뛴다. */
export function prefersReducedMotion(): boolean {
  return reduced;
}
