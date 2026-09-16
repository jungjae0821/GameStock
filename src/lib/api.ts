import { firebaseAuth } from "./firebase";

const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8081").replace(/\/$/, "");

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

  const response = await fetch(`${API_BASE}${path}`, { ...init, headers });
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
  throw new ApiError(response.status, message);
}

export const apiBaseUrl = API_BASE;
