import { useLayoutEffect, useRef } from "react";
import type { Quote, TradePrint } from "../../market/types";

export const TAPE_LIMIT = 12;
/** 테이프 칸 폭과 간격(px). CSS의 .nx-tape-chip 폭·.nx-tape-strip 간격과 같아야 밀려나는 거리가 맞는다. */
const TAPE_CHIP = 72;
const TAPE_GAP = 6;
const SLIDE_MS = 320;
const SLIDE_EASING = "cubic-bezier(0.16, 1, 0.3, 1)";

/** 같은 방향으로 이어진 최근 체결 수. 흐름이 한쪽으로 쏠리는 정도를 센다. */
function combo(prints: TradePrint[]): { side: "buy" | "sell"; count: number } | null {
  const first = prints[0];
  if (!first) return null;
  let count = 0;
  for (const print of prints) {
    if (print.side !== first.side) break;
    count += 1;
  }
  return { side: first.side, count };
}

/**
 * 같은 시각·가격·수량의 체결이 연달아 올 수 있다. 뒤(오래된 쪽)부터 번호를 매겨
 * 새 체결이 앞에 붙어도 기존 체결의 키는 그대로 두고, 새 체결만 새 키를 받게 한다.
 */
export function printKeys(prints: TradePrint[]): string[] {
  const seen = new Map<string, number>();
  const keys = new Array<string>(prints.length);
  for (let index = prints.length - 1; index >= 0; index -= 1) {
    const print = prints[index];
    const base = `${print.at}-${print.price}-${print.qty}-${print.side}`;
    const count = seen.get(base) ?? 0;
    seen.set(base, count + 1);
    keys[index] = `${base}#${count}`;
  }
  return keys;
}

type Arrival = { code: string; signature: string; fresh: string[] };

/**
 * 직전 렌더와 비교해 이번에 새로 들어온 체결 키를 고른다. 체결 목록이 그대로인 다시 그리기에서는
 * 앞서 고른 묶음을 유지해 진행 중인 움직임을 끊지 않는다. 종목을 바꾼 첫 렌더는 새 체결이 없다.
 */
export function useArrival(code: string, keys: string[]): Arrival {
  const arrival = useRef<Arrival | null>(null);
  const signature = keys.join(",");
  const previous = arrival.current;
  if (!previous || previous.code !== code) {
    arrival.current = { code, signature, fresh: [] };
  } else if (previous.signature !== signature) {
    const known = new Set(previous.signature.split(","));
    arrival.current = { code, signature, fresh: keys.filter((key) => !known.has(key)) };
  }
  return arrival.current!;
}

/**
 * 호가 아래의 체결 테이프. 새 체결 묶음은 왼쪽 끝에 붙고, 앞선 체결은 그만큼 오른쪽으로 밀려나며 옅어진다.
 * 칸마다 따로 움직이지 않고 띠 전체를 한 번에 밀어 칸끼리 겹치지 않게 한다.
 */
export function PrintTape({ quote, keys, arrival }: { quote: Quote; keys: string[]; arrival: Arrival }) {
  const strip = useRef<HTMLDivElement>(null);
  const prints = quote.prints.slice(0, TAPE_LIMIT);
  const streak = combo(quote.prints);
  const fresh = new Set(arrival.fresh);
  const shift = arrival.fresh.length * (TAPE_CHIP + TAPE_GAP);

  useLayoutEffect(() => {
    const element = strip.current;
    if (!element) return;
    for (const running of element.getAnimations()) running.cancel();
    if (shift <= 0 || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    element.animate([{ transform: `translateX(${-shift}px)` }, { transform: "translateX(0)" }], { duration: SLIDE_MS, easing: SLIDE_EASING });
  }, [arrival, shift]);

  return (
    <div className="nx-tape">
      <p className="nx-tape-streak" aria-live="off">
        {streak && streak.count > 1 ? <>
          <strong className={`nx-num is-${streak.side === "buy" ? "up" : "down"}`}>{streak.count}</strong>
          <span>연속 {streak.side === "buy" ? "매수" : "매도"}</span>
        </> : <span>{prints[0] ? `${prints[0].side === "buy" ? "매수" : "매도"} 체결` : "체결 대기"}</span>}
      </p>
      <div className="nx-tape-track" role="img" aria-label={`최근 체결 ${prints.length}건`}>
        <div className="nx-tape-strip" ref={strip} aria-hidden="true">
          {prints.map((print, index) => (
            <span key={keys[index]} className={`nx-tape-chip nx-num is-${print.side}${fresh.has(keys[index]) ? " is-fresh" : ""}`}
              style={{ opacity: Math.max(0.35, 1 - index * 0.07) }}>
              {print.qty.toLocaleString("ko-KR")}<small>주</small>
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}
