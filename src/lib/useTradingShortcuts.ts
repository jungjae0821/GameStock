import { useEffect, useRef } from "react";
import type { RefObject } from "react";

export type TradingShortcutHandlers = {
  onBuy: () => void;
  onSell: () => void;
  onCancel: (all: boolean) => void;
  onMarket: () => void;
  onMovePrice: (direction: "up" | "down") => void;
  onQuantityPreset: (preset: number) => void;
};

function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || target.matches("input, textarea, select, [contenteditable=\"true\"]");
}

/** 포커스가 주문창 안에 있을 때만 활성화한다. 입력창의 기본 키 동작은 보존한다. */
export function useTradingShortcuts(handlers: TradingShortcutHandlers, scope: RefObject<HTMLElement | null>, disabled = false): void {
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (disabled || event.defaultPrevented) return;
      if (!(event.target instanceof Node) || !scope.current?.contains(event.target)) return;
      if (!scope.current.contains(document.activeElement)) return;
      if (isEditableTarget(event.target) || event.ctrlKey || event.metaKey || event.altKey) return;

      // 한글 IME는 같은 키를 ㅠ/ㄴ/ㅊ 또는 Process로 전달한다.
      // 편집 영역은 위에서 제외했으므로 주문 단축키는 물리 키로 판단한다.
      const physicalKeys: Record<string, string> = { KeyB: "b", KeyS: "s", KeyC: "c", KeyM: "m" };
      const key = physicalKeys[event.code] ?? event.key.toLowerCase();
      if (event.isComposing && !physicalKeys[event.code]) return;
      if (key === "c") {
        if (event.repeat) return;
        event.preventDefault();
        handlersRef.current.onCancel(event.shiftKey);
        return;
      }
      if (event.shiftKey) return;

      if (key === "arrowup" || key === "arrowdown") {
        event.preventDefault();
        handlersRef.current.onMovePrice(key === "arrowup" ? "up" : "down");
        return;
      }
      if (key === "b" || key === "s" || key === "m") {
        if (event.repeat) return;
        event.preventDefault();
        if (key === "b") handlersRef.current.onBuy();
        if (key === "s") handlersRef.current.onSell();
        if (key === "m") handlersRef.current.onMarket();
        return;
      }
      if (/^[1-4]$/.test(event.key)) {
        if (event.repeat) return;
        event.preventDefault();
        handlersRef.current.onQuantityPreset(Number(event.key));
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [scope, disabled]);
}
