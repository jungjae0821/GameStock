import { useEffect, useId, useRef, useState } from "react";
import type { IndicatorType } from "../../lib/indicators";

export const INDICATOR_TYPES: Array<{ type: IndicatorType; label: string; period: number; note: string; pane: "overlay" | "oscillator" }> = [
  { type: "SMA", label: "단순 이동평균", period: 20, note: "종가 평균", pane: "overlay" },
  { type: "EMA", label: "지수 이동평균", period: 20, note: "최근 값에 가중", pane: "overlay" },
  { type: "BBANDS", label: "볼린저 밴드", period: 20, note: "평균 ± 2표준편차", pane: "overlay" },
  { type: "RSI", label: "RSI", period: 14, note: "70 과열, 30 침체", pane: "oscillator" },
  { type: "MACD", label: "MACD", period: 26, note: "12/26/9 고정", pane: "oscillator" },
  { type: "ATR", label: "ATR", period: 14, note: "평균 변동폭", pane: "oscillator" },
];
export const MAX_INDICATORS = 12;
/** 아래 칸으로 그리는 지표 수. 셋 이상이면 차트가 호가·주문 내역을 화면 밖으로 밀어낸다. */
export const MAX_OSCILLATORS = 2;

/** 지표 고르기. 종류를 고르고 기간을 정해 추가한다. 차트 위에 한 단계 밝은 면으로 뜬다. */
export function IndicatorMenu({ open, onOpenChange, count, oscillators, onAdd }: {
  open: boolean; onOpenChange: (open: boolean) => void; count: number; oscillators: number;
  onAdd: (type: IndicatorType, period: number) => void;
}) {
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const firstOption = useRef<HTMLButtonElement>(null);
  const [type, setType] = useState<IndicatorType>("SMA");
  const [period, setPeriod] = useState("20");
  const fixed = type === "MACD";
  const value = fixed ? 26 : Number(period);
  const periodError = !fixed && period !== "" && (!Number.isInteger(value) || value < 2 || value > 500) ? "기간은 2에서 500 사이 정수로 넣어 주세요." : "";
  const full = count >= MAX_INDICATORS;
  const panesFull = INDICATOR_TYPES.find((item) => item.type === type)?.pane === "oscillator" && oscillators >= MAX_OSCILLATORS;
  const blocked = full || panesFull || Boolean(periodError) || (!fixed && period === "");
  const hint = periodError
    || (full ? `지표는 ${MAX_INDICATORS}개까지 켤 수 있어요. 범례의 ×로 하나를 끈 뒤 추가해 주세요.` : "")
    || (panesFull ? `RSI·MACD·ATR은 아래 칸으로 ${MAX_OSCILLATORS}개까지 그려요. 하나를 끈 뒤 추가해 주세요.` : "")
    || `${count}/${MAX_INDICATORS}개 켜짐`;

  const close = () => { onOpenChange(false); trigger.current?.focus(); };

  useEffect(() => {
    if (!open) return;
    firstOption.current?.focus();
    const onPointer = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) onOpenChange(false); };
    document.addEventListener("pointerdown", onPointer);
    return () => document.removeEventListener("pointerdown", onPointer);
  }, [open, onOpenChange]);

  const pick = (next: IndicatorType) => {
    setType(next);
    setPeriod(String(INDICATOR_TYPES.find((item) => item.type === next)?.period ?? 20));
  };

  return (
    <div className="nx-study-menu-root" ref={root} onKeyDown={(event) => { if (open && event.key === "Escape") { event.stopPropagation(); close(); } }}>
      <button ref={trigger} type="button" className="nx-study-menu-trigger" aria-expanded={open} aria-controls={id} onClick={() => onOpenChange(!open)}>
        <span className="nx-study-fx" aria-hidden="true">fx</span>지표 <span className="nx-num nx-muted">{count}</span>
      </button>
      {open && (
        <div id={id} className="nx-study-menu" role="dialog" aria-label="지표 추가">
          <div className="nx-study-types" role="radiogroup" aria-label="지표 종류">
            {INDICATOR_TYPES.map((item, index) => (
              <button key={item.type} ref={index === 0 ? firstOption : undefined} type="button" role="radio" aria-checked={type === item.type}
                className={type === item.type ? "is-active" : undefined} onClick={() => pick(item.type)}>
                <span>{item.label}</span>
                <span className="nx-muted">{item.note}</span>
              </button>
            ))}
          </div>
          <form className="nx-study-add" noValidate onSubmit={(event) => {
            event.preventDefault();
            if (blocked) return;
            onAdd(type, value);
            close();
          }}>
            <label htmlFor={`${id}-period`}>기간</label>
            <input id={`${id}-period`} className="nx-input nx-num" inputMode="numeric" value={fixed ? "12·26·9" : period} disabled={fixed}
              aria-invalid={Boolean(periodError)} aria-describedby={`${id}-hint`} onChange={(event) => setPeriod(event.target.value.replace(/[^0-9]/g, ""))} />
            <span className="nx-muted">캔들</span>
            <button type="submit" className="nx-btn is-primary is-small" disabled={blocked}>추가</button>
            <p id={`${id}-hint`} className={`nx-field-hint${periodError ? " is-error" : ""}`}>{hint}</p>
          </form>
        </div>
      )}
    </div>
  );
}
