import type { Listing } from "./types";

/**
 * 상장 종목. 게임명·퍼블리셔는 실제 제품 정보, 가격은 전부 모의 시세다.
 * detail 은 소식 문구를 종목별로 구체화하기 위한 명사다.
 */
export const LISTINGS: Listing[] = [
  // Spring Boot/MySQL의 기존 종목 코드와 동일하게 GOV를 사용한다.
  { code: "GOV", name: "승리의 여신: 니케", publisher: "시프트업", genre: "RPG", prevClose: 25710, activity: 2.6, detail: "2부 12장" },
  { code: "UMA", name: "우마무스메 프리티더비", publisher: "사이게임즈", genre: "육성", prevClose: 12600, activity: 2.2, detail: "신규 육성 시나리오" },
  { code: "BA", name: "블루 아카이브", publisher: "넥슨게임즈", genre: "RPG", prevClose: 9370, activity: 2.4, detail: "메인 스토리 2부" },
  { code: "GI", name: "원신", publisher: "호요버스", genre: "오픈월드", prevClose: 31900, activity: 3.0, detail: "신규 지역" },
  { code: "SR", name: "붕괴: 스타레일", publisher: "호요버스", genre: "RPG", prevClose: 22480, activity: 2.7, detail: "개척 임무" },
  { code: "ZZZ", name: "젠레스 존 제로", publisher: "호요버스", genre: "액션", prevClose: 27150, activity: 2.1, detail: "신규 요원" },
  { code: "AK", name: "명일방주", publisher: "하이퍼그리프", genre: "전략", prevClose: 18240, activity: 1.8, detail: "신규 오퍼레이터" },
  { code: "WH", name: "명조: 워더링 웨이브", publisher: "쿠로게임즈", genre: "액션", prevClose: 13700, activity: 1.7, detail: "공명자 에코" },
  { code: "PX", name: "페르소나5: 더 팬텀 X", publisher: "아틀러스", genre: "RPG", prevClose: 15300, activity: 1.4, detail: "궁전 스토리" },
  { code: "LT", name: "림버스 컴퍼니", publisher: "프로젝트문", genre: "RPG", prevClose: 7860, activity: 1.3, detail: "거울 던전" },
  { code: "ES", name: "에픽세븐", publisher: "스마일게이트", genre: "RPG", prevClose: 6480, activity: 1.2, detail: "월광 영웅" },
  { code: "MH", name: "몬스터헌터 와일즈", publisher: "캡콤", genre: "액션", prevClose: 38500, activity: 2.5, detail: "신규 몬스터" },
  { code: "EL", name: "엘든 링", publisher: "프롬소프트웨어", genre: "액션 RPG", prevClose: 42000, activity: 2.0, detail: "보스 밸런스" },
  { code: "PW", name: "팰월드", publisher: "포켓페어", genre: "생존", prevClose: 19800, activity: 1.9, detail: "신규 팰" },
  { code: "SD", name: "스타듀 밸리", publisher: "컨선드에이프", genre: "시뮬레이션", prevClose: 5600, activity: 0.9, detail: "신규 지역" },
];

export const LISTING_BY_CODE: Record<string, Listing> = Object.fromEntries(
  LISTINGS.map((listing) => [listing.code, listing]),
);

/**
 * 한국 거래소 호가 단위. 모의 시세도 같은 단위로 맞춰야 표가 읽힌다.
 */
export function tickSize(price: number): number {
  if (price < 2000) return 1;
  if (price < 5000) return 5;
  if (price < 20000) return 10;
  if (price < 50000) return 50;
  if (price < 200000) return 100;
  if (price < 500000) return 500;
  return 1000;
}

/** 가격제한폭 ±30%. 실제 시장 규칙을 모의 시세에도 적용한다. */
export const DAILY_LIMIT = 0.3;

export function roundToTick(price: number): number {
  const size = tickSize(price);
  return Math.round(price / size) * size;
}
