import { useRef, useState } from "react";
import type { FocusEvent, PointerEvent } from "react";

/**
 * 값은 실시간으로 갱신하되 순위는 사용자가 읽는 동안 고정한다.
 * 2초마다 줄이 바뀌면 클릭 대상이 손가락 밑에서 달아난다.
 */
export function useHeldOrder<T>(live: T[], hold: boolean): T[] {
  const held = useRef(live);
  if (!hold) held.current = live;
  return hold ? held.current : live;
}

type HoldHandlers = {
  onPointerEnter: (event: PointerEvent) => void;
  onPointerLeave: (event: PointerEvent) => void;
  onFocusCapture: (event: FocusEvent) => void;
  onBlurCapture: (event: FocusEvent) => void;
};

/** 포인터가 머무르거나 포커스가 안에 있는 동안 순위를 고정한다. */
export function useHoldWhilePointing(): [boolean, HoldHandlers] {
  const [hold, setHold] = useState(false);
  return [
    hold,
    {
      onPointerEnter: () => setHold(true),
      onPointerLeave: () => setHold(false),
      onFocusCapture: () => setHold(true),
      onBlurCapture: () => setHold(false),
    },
  ];
}
