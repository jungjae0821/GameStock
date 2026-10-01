import { LoginCard } from "../components/LoginCard";
import type { LoginMethod } from "../router";

/** /login 경로로 직접 들어왔을 때의 로그인 페이지. 모달과 같은 카드를 쓴다. */
export function LoginPage({ method }: { method: LoginMethod }) {
  return (
    <section className="login-page" aria-labelledby="login-title">
      <LoginCard method={method} />
    </section>
  );
}
