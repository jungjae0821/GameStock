import { request } from './auth';

// 종목 채팅과 지표 차트 서버 계약. 웹 src/lib/research.ts와 같다(docs/research-backend.md).
// 시간은 UTC ISO 문자열이고, 글은 일반 텍스트로만 그린다.

const stock = code => `/api/stocks/${encodeURIComponent(code)}`;

/** 서버 시각에 시간대가 빠져 있으면 UTC로 읽는다. 웹 serverTimestamp와 같다. */
export function serverTime(value) {
  const text = String(value ?? '').trim();
  if (!text) return Number.NaN;
  return Date.parse(/(?:Z|[+-]\d{2}:?\d{2})$/i.test(text) ? text : `${text}Z`);
}

// 종목 채팅

export const CHAT_MAX_LENGTH = 1000;

/** before와 after는 함께 보낼 수 없다. 둘 다 없으면 최신 목록이다. */
export function loadChat(code, cursor = {}, limit = 50) {
  const query = [`limit=${limit}`];
  if (cursor.before !== undefined) query.push(`before=${cursor.before}`);
  else if (cursor.after !== undefined) query.push(`after=${cursor.after}`);
  return request(`${stock(code)}/community/chat?${query.join('&')}`, { auth: false });
}

export const sendChat = (code, body) => request(`${stock(code)}/community/chat`, { method: 'POST', body: { body } });
export const deleteChat = (code, id) => request(`${stock(code)}/community/entries/${id}`, { method: 'DELETE' });

// 지표 차트

// 키는 서버의 range 값, 이름은 캔들 한 개의 간격이다.
export const CHART_RANGES = [
  { key: '1h', label: '1분' }, { key: '6h', label: '5분' }, { key: '12h', label: '10분' },
  { key: '1d', label: '30분' }, { key: '3d', label: '1시간' }, { key: '1w', label: '2시간' },
  { key: '2w', label: '6시간' }, { key: '1m', label: '일' }, { key: '1y', label: '주' },
];

export const analyzeChart = (code, range, indicators) =>
  request(`${stock(code)}/chart-analysis`, { method: 'POST', body: { range, indicators }, auth: false });

/** 서버가 문구 없이 상태만 돌려줄 때 쓰는 기본 문구. */
const STATUS_TEXT = {
  401: '로그인이 필요해요.',
  403: '이 요청을 할 수 있는 권한이 없어요.',
  404: '찾을 수 없어요. 이미 지워졌을 수 있어요.',
  413: '내용이 너무 길어요.',
  429: '요청이 많아요. 잠시 후 다시 시도해 주세요.',
};

export function failure(error, fallback) {
  if (error?.status && STATUS_TEXT[error.status] && /^요청을 처리하지 못했어요/.test(error.message)) return STATUS_TEXT[error.status];
  return error?.status ? error.message : fallback;
}

// 종목토론 글. 종목마다 글과 한 단계 답글, 좋아요가 있다. 로그인했으면 내 글과 좋아요 여부도 온다.
export const loadTalk = (code, before = 0) => request(`${stock(code)}/talk${before ? `?before=${before}` : ''}`);
export const writeTalk = (code, body, parentId = null) => request(`${stock(code)}/talk`, { method: 'POST', body: { body, parentId } });
export const deleteTalk = id => request(`/api/talk/${id}`, { method: 'DELETE' });
export const likeTalk = id => request(`/api/talk/${id}/like`, { method: 'POST' });
