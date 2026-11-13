import { useEffect, useRef, useState } from "react";

const EVENT_NAME = "gamestock:mission-reward";
const numberFormatter = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });

export function announceMissionReward(amount: number): void {
  window.dispatchEvent(new CustomEvent<{ amount: number }>(EVENT_NAME, { detail: { amount } }));
  // 보상은 내 지갑으로 들어간다. 지갑을 보여 주는 커리어 화면이 다시 읽게 알린다.
  window.dispatchEvent(new Event("gamestock:career"));
}

export function MissionRewardToast() {
  const [amount, setAmount] = useState<number | null>(null);
  const timer = useRef<number | null>(null);

  useEffect(() => {
    const handleReward = (event: Event) => {
      const detail = (event as CustomEvent<{ amount?: number }>).detail;
      if (!detail || typeof detail.amount !== "number") return;
      setAmount(detail.amount);
      if (timer.current !== null) window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => {
        setAmount(null);
        timer.current = null;
      }, 3600);
    };
    window.addEventListener(EVENT_NAME, handleReward);
    return () => {
      window.removeEventListener(EVENT_NAME, handleReward);
      if (timer.current !== null) window.clearTimeout(timer.current);
    };
  }, []);

  if (amount === null) return null;
  return (
    <div className="mission-toast" role="status" aria-live="polite">
      미션 완료! 내 지갑에 {numberFormatter.format(amount)}원이 들어왔어요.
    </div>
  );
}
