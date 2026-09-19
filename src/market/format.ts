const wonFormatter = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });
const KST_TIME_ZONE = "Asia/Seoul";
const kstClockFormatter = new Intl.DateTimeFormat("en-GB", {
  timeZone: KST_TIME_ZONE,
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hour12: false,
});
const kstMinuteFormatter = new Intl.DateTimeFormat("en-GB", {
  timeZone: KST_TIME_ZONE,
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});
const kstDateFormatter = new Intl.DateTimeFormat("en-CA", {
  timeZone: KST_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

/** 서버가 시간대 없이 내려주는 레거시 ISO 값을 UTC로 안전하게 해석한다. */
export function serverTimestamp(value: string): number {
  const normalized = value.trim();
  if (!normalized) return Number.NaN;
  const hasOffset = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(normalized);
  return Date.parse(hasOffset ? normalized : `${normalized}Z`);
}

/** ₩25,710 */
export function won(value: number): string {
  const sign = value < 0 ? "-" : "";
  return `${sign}₩${wonFormatter.format(Math.abs(Math.round(value)))}`;
}

/** +₩1,200 / -₩300 / ₩0 */
export function signedWon(value: number): string {
  const rounded = Math.round(value);
  if (rounded === 0) return won(0);
  return `${rounded > 0 ? "+" : "-"}₩${wonFormatter.format(Math.abs(rounded))}`;
}

/** 비율 0.0097 → "+0.97%". 등락 계산은 모두 비율로 다루고 표시에서만 백분율로 바꾼다. */
export function rate(ratio: number, digits = 2): string {
  const percent = Number((ratio * 100).toFixed(digits));
  if (percent === 0) return `${(0).toFixed(digits)}%`;
  return `${percent > 0 ? "+" : "-"}${Math.abs(percent).toFixed(digits)}%`;
}

/** 비율 0.0097 → "0.97%" — 부호 없이 */
export function plainRate(ratio: number, digits = 2): string {
  return `${(ratio * 100).toFixed(digits)}%`;
}

/** 큰 금액을 조·억·만 단위로 줄여 쓴다. 거래대금처럼 자릿수가 긴 값에 쓴다. */
export function compactWon(value: number): string {
  const abs = Math.abs(value);
  const sign = value < 0 ? "-" : "";
  if (abs >= 1_000_000_000_000) return `${sign}₩${(abs / 1_000_000_000_000).toFixed(2)}조`;
  if (abs >= 100_000_000) return `${sign}₩${(abs / 100_000_000).toFixed(1)}억`;
  if (abs >= 10_000) return `${sign}₩${wonFormatter.format(Math.round(abs / 10_000))}만`;
  return won(value);
}

/** 상승 ▲ / 하락 ▼ / 보합 − */
export function trendArrow(value: number): string {
  if (value > 0) return "▲";
  if (value < 0) return "▼";
  return "−";
}

/** 122,550주 / 12.3만주 — 거래량은 만 단위부터 소수 한 자리로 표시한다. */
export function shares(value: number): string {
  if (value >= 100_000_000) return `${(value / 100_000_000).toFixed(1)}억주`;
  if (value >= 10_000) return `${(value / 10_000).toFixed(1)}만주`;
  return `${wonFormatter.format(Math.round(value))}주`;
}

/** 1,024.31 — 지수 표기 */
export function indexValue(value: number): string {
  return value.toLocaleString("ko-KR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** +24.31 / -8.20 — 지수 변화량 */
export function signedNumber(value: number, digits = 2): string {
  const rounded = Number(value.toFixed(digits));
  if (rounded === 0) return (0).toFixed(digits);
  return `${rounded > 0 ? "+" : "-"}${Math.abs(rounded).toFixed(digits)}`;
}

/** 12:04:31 */
export function clock(at: number): string {
  return kstClockFormatter.format(new Date(at));
}

/** 12:04 */
export function minutes(at: number): string {
  return kstMinuteFormatter.format(new Date(at));
}

/** 방금 / 42초 전 / 3분 전 / 12:04 */
export function since(at: number, now: number): string {
  const seconds = Math.max(0, Math.round((now - at) / 1000));
  if (seconds < 5) return "방금";
  if (seconds < 60) return `${seconds}초 전`;
  const minute = Math.floor(seconds / 60);
  if (minute < 60) return `${minute}분 전`;
  return minutes(at);
}

/** 오늘 / 어제 / 9월 15일 */
export function dayLabel(at: number, now: number): string {
  const day = new Date(at);
  const today = new Date(now);
  const dateKey = (date: Date) => {
    const parts = Object.fromEntries(kstDateFormatter.formatToParts(date).map((part) => [part.type, part.value]));
    return `${parts.year}-${parts.month}-${parts.day}`;
  };
  const startOfDay = (date: Date) => Date.parse(`${dateKey(date)}T00:00:00+09:00`);
  const diffDays = Math.round((startOfDay(today) - startOfDay(day)) / 86_400_000);
  if (diffDays === 0) return "오늘";
  if (diffDays === 1) return "어제";
  const [, month, dateValue] = dateKey(day).split("-").map(Number);
  return `${month}월 ${dateValue}일`;
}

/** 2초 → "2초" */
export function tickLabel(ms: number): string {
  const seconds = ms / 1000;
  return `${Number.isInteger(seconds) ? seconds : seconds.toFixed(1)}초`;
}
