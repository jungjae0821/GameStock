import { useEffect, useRef, useState } from "react";
import { useAuthUser } from "../lib/auth";
import { checkTitles, onTitlesAwarded, titleLabel } from "../lib/titles";

/** 총 자산·보유 수량처럼 순간적으로 달성하는 조건도 놓치지 않도록 자주 확인한다. */
const CHECK_INTERVAL_MS = 20_000;

/** 로그인한 동안 칭호 조건을 주기적으로 확인하고, 새로 받은 칭호를 알린다. */
export function TitleWatcher() {
  const { user } = useAuthUser();
  const [queue, setQueue] = useState<string[]>([]);
  const timer = useRef<number | null>(null);

  useEffect(() => onTitlesAwarded((titles) => {
    setQueue((current) => [...current, ...titles.map(titleLabel)]);
  }), []);

  useEffect(() => {
    if (!user) return;
    const controller = new AbortController();
    let checking = false;
    const run = async () => {
      if (checking || document.visibilityState !== "visible") return;
      checking = true;
      try {
        await checkTitles(controller.signal);
      } catch {
        /* 다음 주기에 다시 확인한다. */
      } finally {
        checking = false;
      }
    };
    void run();
    const poll = window.setInterval(() => void run(), CHECK_INTERVAL_MS);
    const resume = () => void run();
    document.addEventListener("visibilitychange", resume);
    return () => {
      controller.abort();
      window.clearInterval(poll);
      document.removeEventListener("visibilitychange", resume);
    };
  }, [user?.uid]);

  // 여러 칭호를 한 번에 받으면 하나씩 차례로 보여 준다.
  useEffect(() => {
    if (queue.length === 0 || timer.current !== null) return;
    timer.current = window.setTimeout(() => {
      timer.current = null;
      setQueue((current) => current.slice(1));
    }, 3600);
  }, [queue]);

  useEffect(() => () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
  }, []);

  if (queue.length === 0) return null;
  return (
    <div className="mission-toast title-toast" role="status" aria-live="polite">
      새 칭호 획득! [{queue[0]}]
    </div>
  );
}
