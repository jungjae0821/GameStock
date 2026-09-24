import type { Listing, NewsSource } from "./types";

interface Template {
  source: NewsSource;
  direction: 1 | -1;
  title: (listing: Listing, pick: (values: number[]) => number) => string;
}

/**
 * 소식은 모두 모의다. 실제 보도·공지가 아니므로 출처를 실제 매체명이 아니라
 * 노출하기로 한 정보 성격으로 표기한다.
 */
const POSITIVE: Template[] = [
  { source: "업데이트 노트", direction: 1, title: (l) => `${l.detail} 업데이트 적용` },
  { source: "미디어 보도", direction: 1, title: (l) => `${l.detail} 신규 공개` },
];

const NEGATIVE: Template[] = [
  { source: "업데이트 노트", direction: -1, title: (l) => `${l.detail} 업데이트 지연` },
  { source: "미디어 보도", direction: -1, title: (l) => `${l.detail} 기대감 둔화` },
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
