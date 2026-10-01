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
  { source: "업데이트 노트", direction: 1, title: (l) => `${l.detail} 업데이트 배포` },
  { source: "업데이트 노트", direction: 1, title: (l) => `${l.detail} 정식 적용` },
  { source: "업데이트 노트", direction: 1, title: () => `시즌 이벤트 개시` },
  { source: "업데이트 노트", direction: 1, title: () => `신규 콘텐츠 예고 공개` },
  { source: "업데이트 노트", direction: 1, title: (l) => `${l.detail} 콜라보레이션 발표` },
  { source: "미디어 보도", direction: 1, title: (l) => `${l.detail} 호평 이어가` },
  { source: "미디어 보도", direction: 1, title: () => `동시접속 반등 포착` },
  { source: "미디어 보도", direction: 1, title: () => `매출 전망 상향 조정` },
  { source: "미디어 보도", direction: 1, title: () => `해외 시장 공략 본격화` },
  { source: "미디어 보도", direction: 1, title: (l) => `${l.detail} 화제성 이어져` },
];

const NEGATIVE: Template[] = [
  { source: "업데이트 노트", direction: -1, title: (l) => `${l.detail} 업데이트 연기` },
  { source: "업데이트 노트", direction: -1, title: () => `긴급 점검 실시` },
  { source: "업데이트 노트", direction: -1, title: (l) => `${l.detail} 핫픽스 진행` },
  { source: "업데이트 노트", direction: -1, title: () => `서버 안정화 작업 착수` },
  { source: "업데이트 노트", direction: -1, title: (l) => `${l.detail} 밸런스 재조정 예고` },
  { source: "미디어 보도", direction: -1, title: (l) => `${l.detail} 일정 지연 우려 확산` },
  { source: "미디어 보도", direction: -1, title: () => `유저 반발 확산` },
  { source: "미디어 보도", direction: -1, title: () => `경쟁작 공세에 밀려` },
  { source: "미디어 보도", direction: -1, title: () => `매출 전망 하향 조정` },
  { source: "미디어 보도", direction: -1, title: (l) => `${l.detail} 이슈 식은 조짐` },
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
