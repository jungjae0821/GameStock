// 웹 src/market/format.ts와 같은 반올림·부호·시간대 규칙을 쓴다(tests/mobile-parity.test.ts가 지킨다).
// 화면 모양은 앱 방식대로 둔다: 금액은 "1,234원", 부호 붙은 값의 음수 부호는 '−'(U+2212)다.
const number = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 });
const index = new Intl.NumberFormat('ko-KR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** 반올림한 원 단위 정수. 숫자가 아니면 0이고, -0.4처럼 0으로 반올림되면 부호 없는 0이다. */
const roundWon = value => Math.round(Number(value) || 0) || 0;

/** 서버가 시간대 없이 내려주는 레거시 ISO 값을 UTC로 읽는다. 웹 serverTimestamp와 같다. */
export function serverTimestamp(value) {
  const normalized = String(value ?? '').trim();
  if (!normalized) return Number.NaN;
  const hasOffset = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(normalized);
  return Date.parse(hasOffset ? normalized : `${normalized}Z`);
}

export const won = value => `${number.format(roundWon(value))}원`;
export const price = value => number.format(roundWon(value));
export const indexValue = value => index.format(Number(value) || 0);

/** 비율(0.0123)을 부호 있는 백분율로. 웹 rate처럼 소수 둘째 자리로 반올림한 뒤 부호를 정하므로 0.00%에는 부호가 없다. */
export const signedPercent = ratio => {
  const percent = Number(((Number(ratio) || 0) * 100).toFixed(2));
  const sign = percent > 0 ? '+' : percent < 0 ? '−' : '';
  return `${sign}${Math.abs(percent).toFixed(2)}%`;
};

export const signedWon = amount => {
  const value = roundWon(amount);
  const sign = value > 0 ? '+' : value < 0 ? '−' : '';
  return `${sign}${number.format(Math.abs(value))}원`;
};

export const relativeTime = (iso, now = Date.now()) => {
  const at = serverTimestamp(iso);
  if (!Number.isFinite(at)) return '';
  const minutes = Math.max(0, Math.round((now - at) / 60000));
  if (minutes < 1) return '방금';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.round(hours / 24)}일 전`;
};

/** RSS 제목 끝의 " - 매체명"은 이유 문장에서 뺀다. */
export const cleanTitle = title => String(title || '').replace(/\s+-\s+[^-]{1,30}$/, '').trim();

/** RSS 설명의 HTML과 끝의 "출처: URL"을 걷어 낸 요약. 없으면 null. */
export const newsSummary = description => {
  const text = String(description || '')
    .replace(/\s*출처:\s*https?:\/\/\S+\s*$/i, '')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/gi, ' ').replace(/&amp;/gi, '&').replace(/&quot;/gi, '"').replace(/&#39;/g, "'")
    .replace(/\s+/g, ' ')
    .trim();
  return text || null;
};

/** 원문 기사 주소. articleUrl이 없으면 설명 끝의 "출처: URL"에서 찾는다. */
export const newsSourceUrl = news => {
  if (news?.articleUrl) return news.articleUrl;
  const match = String(news?.description || '').match(/출처:\s*(https?:\/\/[^\s<]+)/i);
  return match ? match[1] : null;
};

const KST_OFFSET_MS = 9 * 60 * 60_000;

/** 체결 시각 12:04:31. 웹 clock처럼 기기 시간대와 상관없이 한국 시간이다(한국은 일광 절약 시간이 없다). */
export const clockTime = iso => {
  const ms = typeof iso === 'number' ? iso : serverTimestamp(iso);
  if (!Number.isFinite(ms)) return '';
  const at = new Date(ms + KST_OFFSET_MS);
  const pad = value => String(value).padStart(2, '0');
  return `${pad(at.getUTCHours())}:${pad(at.getUTCMinutes())}:${pad(at.getUTCSeconds())}`;
};
