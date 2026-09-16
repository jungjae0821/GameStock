import type { Listing, NewsSource } from "./types";

interface Template {
  source: NewsSource;
  direction: 1 | -1;
  title: (listing: Listing, pick: (values: number[]) => number) => string;
}

/**
 * 소식은 모두 모의다. 실제 보도·공지가 아니므로 출처를 실제 매체명이 아니라
 * 정보 성격(업데이트 노트, 스토어 지표 등)으로 표기한다.
 */
const POSITIVE: Template[] = [
  { source: "업데이트 노트", direction: 1, title: (l) => `${l.detail} 업데이트 적용` },
  { source: "커뮤니티 집계", direction: 1, title: () => `동시 접속자 수 3일 연속 상승` },
  { source: "스토어 지표", direction: 1, title: (_l, pick) => `매출 순위 ${pick([2, 3, 4, 5])}계단 상승` },
  { source: "미디어 보도", direction: 1, title: (l) => `${l.detail} 신규 공개` },
  { source: "스토어 지표", direction: 1, title: () => `최근 30일 리뷰 평점 상승` },
];

const NEGATIVE: Template[] = [
  { source: "퍼블리셔 공지", direction: -1, title: () => `서버 점검 4시간 예정` },
  { source: "커뮤니티 집계", direction: -1, title: () => `동시 접속자 수 2주 만에 최저` },
  { source: "스토어 지표", direction: -1, title: (_l, pick) => `매출 순위 ${pick([3, 5, 7, 9])}계단 하락` },
  { source: "퍼블리셔 공지", direction: -1, title: (l) => `${l.detail} 일정 연기 공지` },
  { source: "커뮤니티 집계", direction: -1, title: (l) => `${l.detail} 밸런스 지적 확산` },
];

export interface GeneratedNews {
  source: NewsSource;
  direction: 1 | -1;
  title: string;
  /** 틱당 가격 충격 크기 */
  shock: number;
}

export function generateNews(listing: Listing, rng: () => number): GeneratedNews {
  const pick = (values: number[]) => values[Math.min(values.length - 1, Math.floor(rng() * values.length))];
  const positive = rng() < 0.55;
  const pool = positive ? POSITIVE : NEGATIVE;
  const template = pool[Math.min(pool.length - 1, Math.floor(rng() * pool.length))];
  const magnitude = 0.01 + rng() * 0.02;
  return {
    source: template.source,
    direction: template.direction,
    title: `${listing.name} ${template.title(listing, pick)}`.replace(/\s+/g, " "),
    shock: magnitude,
  };
}
