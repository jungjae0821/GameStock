import { useEffect, useRef, useState } from "react";
import { closeLoginPrompt, useLoginPrompt } from "../lib/auth";
import { LoginCard } from "./LoginCard";
import type { LoginMethod } from "../router";

/*
 * 로그인이 필요한 동작(관심등록, 주문, 마이페이지 등)을 만나면 화면 이동 대신
 * 현재 화면 위에 띄우는 로그인 모달. /login 페이지와 같은 카드를 재사용한다.
 */
export function LoginModal() {
  const prompt = useLoginPrompt();
  const [method, setMethod] = useState<LoginMethod>("choose");
  const cardRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!prompt) return;
    setMethod("choose");
    requestAnimationFrame(() => cardRef.current?.focus({ preventScroll: true }));
  }, [prompt]);

  useEffect(() => {
    if (!prompt) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") closeLoginPrompt();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [prompt]);

  if (!prompt) return null;

  return (
    <div className="login-modal">
      <div className="login-modal-backdrop" onClick={closeLoginPrompt} />
      <div className="login-modal-card" role="dialog" aria-modal="true" aria-label="로그인" tabIndex={-1} ref={cardRef}>
        <LoginCard method={method} next={prompt.next} onDone={closeLoginPrompt} onMethodChange={setMethod} />
      </div>
    </div>
  );
}
