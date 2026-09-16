import { useEffect, useRef } from "react";
import { prefersReducedMotion } from "../lib/motion";
import { won } from "../market/format";

interface Props {
  value: number;
  /** 값을 문자열로 바꾸는 포맷터. 기본은 원화 표기. */
  format?: (value: number) => string;
  className?: string;
  /**
   * flash: 값이 바뀔 때 배경과 글자색이 함께 번쩍인다(표).
   * digits: 값이 바뀐 자리만 밝아진다(전광판). 그대로 있는 자리는 건드리지
   *   않으므로 실제로 움직인 자리만 눈에 들어온다.
   */
  motion?: "flash" | "digits";
}

/**
 * 값이 바뀔 때만 움직인다. 세 방식 모두 상승·하락을 색과 위치로 알리는
 * 피드백이며 장식용 루프가 아니다. 감소 모드에서는 애니메이션 없이 값만
 * 갱신되고 최종 상태는 같다.
 */
export function PriceCell({ value, format = won, className, motion = "flash" }: Props) {
  const ref = useRef<HTMLSpanElement>(null);
  const previous = useRef(value);
  const previousText = useRef<string | null>(null);
  const text = format(value);
  const direction = value > previous.current ? "up" : value < previous.current ? "down" : "none";

  useEffect(() => {
    const element = ref.current;
    const before = previous.current;
    previous.current = value;
    previousText.current = text;
    if (!element || before === value || prefersReducedMotion()) return;
    /* 자리별 강조는 board.css 애니메이션이 담당한다. 여기서는 아무것도 하지 않는다. */
    if (motion === "digits") return;
    const up = value > before;
    const styles = getComputedStyle(element);
    const read = (name: string, fallback: string) => styles.getPropertyValue(name).trim() || fallback;
    element.animate(
      [
        {
          color: read(up ? "--tick-up" : "--tick-down", styles.color),
          backgroundColor: read(up ? "--tick-up-bg" : "--tick-down-bg", "transparent"),
        },
        { color: styles.color, backgroundColor: "transparent" },
      ],
      { duration: 460, easing: "ease-out" },
    );
  }, [value, text, motion]);

  const name = className ? `price ${className}` : "price";

  if (motion === "digits") {
    /*
     * 자리 수가 같으면 글자가 바뀐 자리만, 달라지면 전체를 한 번 밝힌다.
     * key에 글자를 넣어 바뀐 자리만 다시 마운트되게 하고, 방향이 뒤집혀도
     * 멈춰 있던 자리는 애니메이션을 다시 시작하지 않는다.
     */
    const prior = previousText.current;
    const whole = prior === null || prior.length !== text.length;
    return (
      <span ref={ref} className={name}>
        {Array.from(text, (char, index) => {
          const moved = direction !== "none" && (whole || char !== prior[index]);
          return (
            <span key={`${index}:${char}`} className={moved ? `digit is-${direction}` : "digit"}>
              {char}
            </span>
          );
        })}
      </span>
    );
  }

  return <span ref={ref} className={name}>{text}</span>;
}
