import { onAuthStateChanged, type User } from "firebase/auth";
import { useEffect, useState, type FormEvent } from "react";
import { firebaseAuth } from "../lib/firebase";
import { redeemPromoCode } from "../lib/titles";

/** 프로모션 코드 입력 상태와 제출. 기존 화면과 새 화면이 같이 쓴다. */
export function usePromoCode() {
  const [user, setUser] = useState<User | null>(firebaseAuth.currentUser);
  const [code, setCode] = useState("");
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<{ text: string; ok: boolean } | null>(null);

  useEffect(() => onAuthStateChanged(firebaseAuth, (current) => {
    setUser(current);
    setMessage(null);
  }), []);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const trimmed = code.trim();
    if (!user || pending || !trimmed) return;
    setPending(true);
    setMessage(null);
    try {
      const status = await redeemPromoCode(trimmed);
      const named = status.newlyAwarded.map((title) => title.name);
      const parts = [
        status.rewardCash > 0 ? `내 지갑에 ${status.rewardCash.toLocaleString("ko-KR")}원이 들어왔어요.` : "",
        named.length > 0 ? `칭호 ${named.join(", ")}을(를) 받았어요.` : "",
      ].filter(Boolean);
      setMessage({ text: parts.length > 0 ? parts.join(" ") : "프로모션 코드를 사용했어요.", ok: true });
      if (status.rewardCash > 0) window.dispatchEvent(new Event("gamestock:career"));
      setCode("");
    } catch (failure) {
      setMessage({ text: failure instanceof Error ? failure.message : "프로모션 코드를 사용하지 못했습니다.", ok: false });
    } finally {
      setPending(false);
    }
  };

  return { user, code, setCode, pending, message, submit };
}

