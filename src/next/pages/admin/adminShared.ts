import { useCallback, useRef, useState } from "react";
import { apiFetch } from "../../../lib/api";

/** 관리자 API가 내려주는 DB 행. 열 이름은 DB 그대로(snake_case)다. */
export type Row = Record<string, unknown>;

export const PAGE_SIZE = 50;

export const ORDER_STATUS: Record<string, string> = {
  OPEN: "미체결",
  PARTIAL: "부분 체결",
  FILLED: "체결 완료",
  CANCELLED: "취소",
};

export const count = (value: number) => value.toLocaleString("ko-KR");

/** 서버 시각(ISO 문자열 또는 epoch ms)을 한국 시간으로 표기한다. */
export function adminTime(value: unknown): string {
  if (value === null || value === undefined || value === "") return "-";
  const date = new Date(typeof value === "number" ? value : String(value));
  return Number.isNaN(date.getTime())
    ? String(value)
    : date.toLocaleString("ko-KR", { timeZone: "Asia/Seoul", dateStyle: "short", timeStyle: "medium", hour12: false });
}

/** 서버 시각 → datetime-local 값(한국 시간). */
export function toLocalInput(value: unknown) {
  if (!value) return "";
  const date = new Date(typeof value === "number" ? value : String(value));
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleString("sv-SE", { timeZone: "Asia/Seoul" }).slice(0, 16).replace(" ", "T");
}
/** datetime-local 값(한국 시간) → ISO. 비어 있으면 null. */
export const toInstant = (value: string) => (value ? new Date(`${value}:00+09:00`).toISOString() : null);

export const num = (value: unknown) => (typeof value === "number" ? value : Number(value ?? 0));

/**
 * id 내림차순 목록을 before 커서로 이어 받는다. 필터가 바뀌면 처음부터
 * 다시 읽고, 받은 행이 한 쪽보다 적으면 끝으로 본다.
 */
export function useCursorList(path: string) {
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState("");
  const latest = useRef(0);

  const load = useCallback(async (params: URLSearchParams, append: boolean, before = 0) => {
    const request = ++latest.current;
    setLoading(true);
    setError("");
    // 조건이나 기록 종류가 바뀌면 이전 결과를 바로 지운다. 다른 종류의 행이 잠깐 섞여 보이지 않게 한다.
    if (!append) setRows([]);
    params.set("limit", String(PAGE_SIZE));
    if (before > 0) params.set("before", String(before));
    else params.delete("before");
    try {
      const page = await apiFetch<Row[]>(`${path}?${params}`);
      // 조건을 연달아 바꾸면 늦게 도착한 이전 응답은 버린다.
      if (request !== latest.current) return;
      setRows((current) => (append ? [...current, ...page] : page));
      setDone(page.length < PAGE_SIZE);
    } catch (failure) {
      if (request !== latest.current) return;
      if (!append) setRows([]);
      setError(failure instanceof Error ? failure.message : "목록을 불러오지 못했습니다.");
    } finally {
      if (request === latest.current) setLoading(false);
    }
  }, [path]);

  const lastId = rows.length ? num(rows[rows.length - 1].id) : 0;
  return { rows, loading, done, error, load, lastId };
}
