import { firebaseAuth } from "./firebase";
import endpoints from "../../endpoints.json";

const configuredApiBase = import.meta.env.VITE_API_BASE_URL?.trim();
// 운영 주소는 저장소 루트 endpoints.json 한 곳에서만 정한다.
const productionApiBase = endpoints.api;
const localApiBase = "http://localhost:8081";

// A full market simulation can briefly hold a quote request while bots trade.
// Give the selected backend time to answer without switching databases.
const API_TIMEOUT_MS = 15_000;

// Reads and mutations always belong to the same explicitly selected environment.
export const apiBaseUrl = (configuredApiBase || (import.meta.env.PROD ? productionApiBase : localApiBase)).replace(/\/$/, "");
export const currentApiBaseUrl = () => apiBaseUrl;

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

type ApiRequestInit = RequestInit & { auth?: boolean };

/** 공개 조회는 인증 갱신에 의존하지 않는다. 계좌·주문 요청은 기본적으로 토큰을 첨부한다. */
export async function apiFetch<T>(path: string, options: ApiRequestInit = {}): Promise<T> {
  const { auth = true, ...init } = options;
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");

  const user = firebaseAuth.currentUser;
  if (auth && user) {
    let tokenTimeout: number | undefined;
    try {
      const token = await Promise.race([
        user.getIdToken(),
        new Promise<never>((_, reject) => {
          tokenTimeout = window.setTimeout(() => reject(new Error("로그인 연결이 지연되고 있습니다. 잠시 후 다시 로그인해 주세요.")), API_TIMEOUT_MS);
        }),
      ]);
      headers.set("Authorization", `Bearer ${token}`);
    } finally {
      window.clearTimeout(tokenTimeout);
    }
  }

    const controller = new AbortController();
    const abortFromCaller = () => controller.abort();
    if (init.signal?.aborted) throw new DOMException("요청이 취소되었습니다.", "AbortError");
    const timeoutId = window.setTimeout(() => controller.abort(), API_TIMEOUT_MS);
    if (init.signal) {
      init.signal.addEventListener("abort", abortFromCaller, { once: true });
    }
    try {
      const response = await fetch(`${apiBaseUrl}${path}`, { ...init, headers, signal: controller.signal });
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
      if (timedOut) throw new Error("서버 응답이 지연되고 있습니다. 주문 내역을 확인한 뒤 다시 시도해 주세요.");
      throw error;
    } finally {
      window.clearTimeout(timeoutId);
      init.signal?.removeEventListener("abort", abortFromCaller);
    }
}
