import { firebaseAuth } from "./firebase";

const configuredApiBase = import.meta.env.VITE_API_BASE_URL?.trim();
const productionApiBase = "https://gamestock-production.up.railway.app";
const localApiBase = "http://localhost:8081";
const isLocalBase = (value: string) => /^(https?:\/\/)?(localhost|127\.0\.0\.1)(:\d+)?\/?$/i.test(value);

const API_TIMEOUT_MS = 4_000;

// 개발 화면은 로컬 백엔드를 먼저 보고, 로컬 서버가 꺼져 있으면 Railway로
// 재시도한다. 배포 화면은 Railway를 먼저 보되 사용자의 로컬 백엔드도
// 마지막 후보로 남겨 둔다. 이렇게 하면 어느 화면을 열었는지에 따라
// API 주소가 하나로 고정되지 않는다.
const preferredApiBase = import.meta.env.PROD
  ? (configuredApiBase && !isLocalBase(configuredApiBase) ? configuredApiBase : productionApiBase)
  : (configuredApiBase || localApiBase);
const API_BASES = [...new Set(
  [preferredApiBase, productionApiBase, localApiBase]
    .map((base) => base.replace(/\/$/, "")),
)];

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

/** Spring Boot API 공통 호출기. 로그인된 경우 Firebase ID 토큰을 자동 첨부한다. */
export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");

  const user = firebaseAuth.currentUser;
  if (user) headers.set("Authorization", `Bearer ${await user.getIdToken()}`);

  let lastNetworkError: unknown;
  for (const base of API_BASES) {
    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => controller.abort(), API_TIMEOUT_MS);
    const abortFromCaller = () => controller.abort();
    if (init.signal) {
      if (init.signal.aborted) throw new DOMException("요청이 취소되었습니다.", "AbortError");
      init.signal.addEventListener("abort", abortFromCaller, { once: true });
    }
    try {
      const response = await fetch(`${base}${path}`, { ...init, headers, signal: controller.signal });
      if (response.ok) {
        if (response.status === 204) return undefined as T;
        return (await response.json()) as T;
      }

      let message = `API 요청 실패 (${response.status})`;
      try {
        const body = (await response.json()) as { message?: string };
        if (body.message) message = body.message;
      } catch {
        /* JSON이 아닌 오류 응답은 기본 문구를 사용한다. */
      }
      // 서버가 응답했다면 인증·권한·검증 오류일 수 있으므로 다른 서버에
      // 같은 주문이나 변경 요청을 재전송하지 않는다.
      throw new ApiError(response.status, message);
    } catch (error) {
      const timedOut = controller.signal.aborted && !init.signal?.aborted;
      const networkFailure = error instanceof TypeError || timedOut;
      if (!networkFailure || init.signal?.aborted) throw error;
      lastNetworkError = error;
      // 연결 거부·CORS·네트워크 단절처럼 서버에 도달하지 못한 경우만
      // 다음 API 후보로 넘긴다.
    } finally {
      window.clearTimeout(timeoutId);
      init.signal?.removeEventListener("abort", abortFromCaller);
    }
  }

  throw lastNetworkError instanceof Error
    ? lastNetworkError
    : new Error("연결 가능한 백엔드가 없습니다.");
}

export const apiBaseUrl = API_BASES[0];
