import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useMarket } from "../market/MarketProvider";
import { kstDateTime } from "../market/format";
import type { TradingRestriction } from "../market/types";

// Keep acknowledgements across home/market navigation, but announce each new phase.
const acknowledged = new Set<string>();
const HIDE_UNTIL_KEY = "gamestock-circuit-level3-hide-until";
const KST_OFFSET = 9 * 60 * 60 * 1000;
const DAY = 24 * 60 * 60 * 1000;

function readHiddenUntil() {
  try { return Number(localStorage.getItem(HIDE_UNTIL_KEY)) || 0; }
  catch { return 0; }
}

export function CircuitBreakerNotice() {
  const restriction = useMarket().marketRestriction;
  const [dismissed, setDismissed] = useState<string | null>(null);
  const [hiddenUntil, setHiddenUntil] = useState(readHiddenUntil);
  useEffect(() => {
    if (hiddenUntil <= Date.now()) return;
    const timer = window.setTimeout(() => {
      setHiddenUntil(0);
      try { localStorage.removeItem(HIDE_UNTIL_KEY); } catch { /* Storage can be disabled. */ }
    }, Math.min(hiddenUntil - Date.now() + 1, DAY));
    return () => window.clearTimeout(timer);
  }, [hiddenUntil]);
  if (restriction?.kind !== "CIRCUIT_BREAKER") return null;
  if (restriction.level === 3 && hiddenUntil > Date.now()) return null;
  const key = `${restriction.startedAt}:${restriction.level}:${restriction.phase}`;
  if (dismissed === key || acknowledged.has(key)) return null;
  const close = () => {
    acknowledged.add(key);
    setDismissed(key);
  };
  return <NoticeDialog key={key} restriction={restriction} onClose={close} onConfirm={() => {
    if (restriction.level !== 3) { close(); return; }
    const until = (Math.floor((Date.now() + KST_OFFSET) / DAY) + 1) * DAY - KST_OFFSET;
    try { localStorage.setItem(HIDE_UNTIL_KEY, String(until)); } catch { /* Keep the in-memory preference. */ }
    setHiddenUntil(until);
  }} />;
}

function NoticeDialog({ restriction, onClose, onConfirm }: { restriction: TradingRestriction; onClose: () => void; onConfirm: () => void }) {
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
        <button type="button" className="circuit-notice-confirm" onClick={onConfirm} autoFocus>{restriction.level === 3 ? "오늘하루 보지않기" : "확인했어요"}</button>
      </div>
    </dialog>, document.body,
  );
}
