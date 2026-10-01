import { useEffect } from "react";
import { signInWithCustomToken } from "firebase/auth";
import { firebaseAuth } from "../lib/firebase";
import { openLoginPrompt } from "../lib/auth";

declare global {
  interface Window {
    ReactNativeWebView?: { postMessage(message: string): void };
  }
}

/** Auth events are independent of the reference's navigation markup. */
export function AuthBridge() {
  useEffect(() => {
    const nativeAuth = (event: Event) => {
      const detail = (event as CustomEvent<{ type: string; customToken?: string }>).detail;
      if (detail?.type !== "FIREBASE_CUSTOM_TOKEN" || !detail.customToken) return;
      void signInWithCustomToken(firebaseAuth, detail.customToken).catch(() => {
        window.dispatchEvent(new CustomEvent("gamestock-login-error", {
          detail: "앱 로그인을 완료하지 못했습니다. 다시 시도해 주세요.",
        }));
      });
    };
    const requestLogin = () => { if (!firebaseAuth.currentUser) openLoginPrompt(); };
    window.addEventListener("gamestock-native-auth", nativeAuth);
    window.addEventListener("gamestock-request-login", requestLogin);
    return () => {
      window.removeEventListener("gamestock-native-auth", nativeAuth);
      window.removeEventListener("gamestock-request-login", requestLogin);
    };
  }, []);
  return null;
}
