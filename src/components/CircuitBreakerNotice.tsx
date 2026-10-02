import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useMarket } from "../market/MarketProvider";
import { kstDateTime } from "../market/format";
import type { TradingRestriction } from "../market/types";

// Keep acknowledgements across home/market navigation, but announce each new phase.
const acknowledged = new Set<string>();

export function CircuitBreakerNotice() {
  const restriction = useMarket().marketRestriction;
  const [dismissed, setDismissed] = useState<string | null>(null);
  if (restriction?.kind !== "CIRCUIT_BREAKER") return null;
  const key = `${restriction.startedAt}:${restriction.level}:${restriction.phase}`;
  if (dismissed === key || acknowledged.has(key)) return null;
  return <NoticeDialog key={key} restriction={restriction} onClose={() => {
    acknowledged.add(key);
    setDismissed(key);
  }} />;
}

function NoticeDialog({ restriction, onClose }: { restriction: TradingRestriction; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    element.showModal();
    return () => {
      element.close();
      document.body.style.overflow = previousOverflow;
      previousFocus?.focus({ preventScroll: true });
    };
  }, []);

  const phase = restriction.phase === "AUCTION" ? "단일가 접수 중" : restriction.phase === "CLOSED" ? "오늘 거래 종료" : "거래 일시 중단";
  const next = restriction.phase === "HALTED" ? "단일가 접수 시작 예정" : restriction.phase === "CLOSED" ? "다음 거래일 시작" : "단일가 체결 예정";
  return createPortal(
    <dialog ref={dialog} className="circuit-notice" aria-labelledby="circuit-notice-title" aria-describedby="circuit-notice-effect"
      onCancel={(event) => { event.preventDefault(); onClose(); }}>
      <div className="circuit-notice-content">
        <p className="circuit-notice-kicker">시장 전체 · 거래 제한 공지</p>
        <h2 id="circuit-notice-title">서킷브레이커 {restriction.level}단계</h2>
        <p className="circuit-notice-phase">{phase}</p>
        <p>{restriction.reason}</p>
        <p id="circuit-notice-effect">{restriction.effect}</p>
        <p className="restriction-time">{next}<br />{kstDateTime(Date.parse(restriction.endsAt))} (한국시간)</p>
        <button type="button" className="circuit-notice-confirm" onClick={onClose} autoFocus>확인했어요</button>
      </div>
    </dialog>, document.body,
  );
}
