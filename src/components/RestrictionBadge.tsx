import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useMarket } from "../market/MarketProvider";
import { LISTING_BY_CODE } from "../market/universe";
import { kstDateTime } from "../market/format";

/** Shared by the website and app WebView; portal avoids table overflow clipping. */
export function RestrictionBadge({ code }: { code: string }) {
  const restriction = useMarket().quotes[code]?.restriction;
  const [hovered, setHovered] = useState(false);
  const [pinned, setPinned] = useState(false);
  const [position, setPosition] = useState({ left: 8, top: 8 });
  const [now, setNow] = useState(Date.now());
  const button = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const closeTimer = useRef<number | undefined>(undefined);
  const id = useId();
  const open = Boolean(restriction && (hovered || pinned));
  const key = restriction ? `${restriction.kind}:${restriction.phase}:${restriction.startedAt}` : "";

  useEffect(() => {
    setHovered(false);
    setPinned(false);
    return () => window.clearTimeout(closeTimer.current);
  }, [key]);

  useEffect(() => {
    if (!open) return;
    const locate = () => {
      const rect = button.current?.getBoundingClientRect();
      if (!rect) return;
      const width = Math.min(340, window.innerWidth - 16);
      const height = panel.current?.offsetHeight ?? 260;
      const below = rect.bottom + 8;
      setPosition({
        left: Math.max(8, Math.min(rect.left, window.innerWidth - width - 8)),
        top: Math.max(8, below + height <= window.innerHeight - 8 ? below : rect.top - height - 8),
      });
    };
    const outside = (event: PointerEvent) => {
      if (button.current?.contains(event.target as Node) || panel.current?.contains(event.target as Node)) return;
      setPinned(false);
      setHovered(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setPinned(false);
      setHovered(false);
      button.current?.focus();
    };
    locate();
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    window.addEventListener("resize", locate);
    window.addEventListener("scroll", locate, true);
    document.addEventListener("pointerdown", outside);
    document.addEventListener("keydown", escape);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("resize", locate);
      window.removeEventListener("scroll", locate, true);
      document.removeEventListener("pointerdown", outside);
      document.removeEventListener("keydown", escape);
    };
  }, [open]);

  if (!restriction) return null;
  const keepOpen = () => { window.clearTimeout(closeTimer.current); };
  const leave = () => {
    keepOpen();
    closeTimer.current = window.setTimeout(() => setHovered(false), 160);
  };
  const remaining = Math.max(0, Math.ceil((Date.parse(restriction.endsAt) - now) / 1000));
  const remainingText = remaining > 0 ? `${Math.floor(remaining / 60)}분 ${remaining % 60}초 남음` : "서버에서 재개 처리 중";
  const name = LISTING_BY_CODE[code]?.name ?? code;
  const isCircuit = restriction.kind === "CIRCUIT_BREAKER";
  const label = `${restriction.label}${isCircuit && restriction.phase === "AUCTION" ? " · 단일가" : ""}`;

  return <>
    <button
      ref={button}
      type="button"
      className={`restriction-badge${isCircuit ? " is-circuit" : " is-vi"}`}
      aria-label={`${name} ${label} 제한 설명`}
      aria-expanded={open}
      aria-controls={open ? id : undefined}
      aria-haspopup="dialog"
      onPointerEnter={(event) => {
        if (event.pointerType !== "mouse" || document.documentElement.dataset.appShell === "mobile") return;
        keepOpen();
        setNow(Date.now());
        setHovered(true);
      }}
      onPointerLeave={leave}
      onClick={(event) => {
        event.preventDefault();
        event.stopPropagation();
        keepOpen();
        setHovered(false);
        setPinned((value) => !value);
        setNow(Date.now());
      }}
    >{label}</button>
    {open && createPortal(
      <div ref={panel} id={id} className="restriction-popover" role="dialog" aria-labelledby={`${id}-title`}
        style={position} onPointerEnter={keepOpen} onPointerLeave={leave}>
        <div className="restriction-popover-head">
          <strong id={`${id}-title`}>{isCircuit ? `서킷브레이커 ${restriction.level}단계 · 시장 전체` : `${restriction.label} · ${name}`}</strong>
          <button type="button" aria-label="제한 설명 닫기" onClick={() => { setHovered(false); setPinned(false); button.current?.focus(); }}>×</button>
        </div>
        <p>{restriction.reason}</p>
        <p><strong>거래 제한</strong><br />{restriction.effect}</p>
        <p className="restriction-countdown">{remainingText}</p>
        <p className="restriction-time">{restriction.phase === "HALTED" ? "단일가 접수 시작" : restriction.phase === "CLOSED" ? "다음 거래일 시작" : "단일가 체결 예정"}: {kstDateTime(Date.parse(restriction.endsAt))}</p>
      </div>, document.body,
    )}
  </>;
}
