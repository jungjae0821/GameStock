import { onAuthStateChanged, type User } from "firebase/auth";
import { useEffect, useState, type FormEvent } from "react";
import { Panel } from "./Panel";
import { firebaseAuth } from "../lib/firebase";
import { redeemPromoCode } from "../lib/titles";

/** 프로모션 코드를 입력해 특별 칭호를 받는다. */
export function PromoCodePanel() {
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
      const special = status.newlyAwarded.filter((title) => title.category === "특별").map((title) => title.name);
      setMessage({ text: special.length > 0 ? `특별 칭호 ${special.join(", ")}을(를) 획득했어요.` : "프로모션 코드를 사용했어요.", ok: true });
      setCode("");
    } catch (failure) {
      setMessage({ text: failure instanceof Error ? failure.message : "프로모션 코드를 사용하지 못했습니다.", ok: false });
    } finally {
      setPending(false);
    }
  };

  return (
    <Panel id="promo-code" title="프로모션 코드">
      <form className="account-form promo-form" onSubmit={submit}>
        <label htmlFor="promo-code-input">코드를 입력하면 보상을 받을 수 있어요.</label>
        <div className="account-input-row">
          <input
            id="promo-code-input"
            value={code}
            onChange={(event) => setCode(event.target.value)}
            placeholder={user ? "프로모션 코드 입력" : "로그인 후 입력할 수 있어요"}
            autoComplete="off"
            spellCheck={false}
            maxLength={40}
            disabled={!user || pending}
          />
          <button type="submit" className="account-button is-primary" disabled={!user || pending || !code.trim()}>
            {pending ? "확인 중…" : "사용"}
          </button>
        </div>
        {message && (
          <p className={`account-hint promo-message${message.ok ? " is-ok" : " is-error"}`} role={message.ok ? "status" : "alert"}>
            {message.text}
          </p>
        )}
      </form>
    </Panel>
  );
}
