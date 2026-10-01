export interface DailyMissionStatus {
  /** 보상 지급 여부를 판단하는 한국시간 날짜(YYYY-MM-DD). */
  missionDate: string;
  resetsAt: string;
  serverTime: string;
  completedMissionIds: string[];
}

export interface Listing {
  /** 2~3자 종목 코드. 표에서는 모노그램 배지로, URL에서는 식별자로 쓴다. */
  code: string;
  name: string;
  publisher: string;
  genre: string;
  /** 세션 시작 시 전일 종가 */
  prevClose: number;
  /** 거래량 계수. 클수록 틱당 체결량이 많다. */
  activity: number;
  /** 소식 문구에 쓰는 해당 종목의 구체 명사. */
  detail: string;
}

/** 체결 한 건. */
interface TradePrint {
  at: number;
  price: number;
  qty: number;
  side: "buy" | "sell";
}

/** 호가 한 단계. */
export interface BookLevel {
  price: number;
  qty: number;
}

/** 서버 지수: 한국시간 거래일 기준 1000, 전체 종목 수익률을 동일가중 평균한다. */
export interface MarketIndex {
  value: number;
  prevClose: number;
  series: number[];
  tradingDate?: string;
}

export interface TradingRestriction {
  kind: "DYNAMIC_VI" | "STATIC_VI" | "CIRCUIT_BREAKER";
  label: string;
  phase: "AUCTION" | "HALTED" | "CLOSED";
  level: number;
  reason: string;
  effect: string;
  startedAt: string;
  endsAt: string;
  limitOrdersAllowed: boolean;
  marketOrdersAllowed: boolean;
}

export interface Quote {
  code: string;
  restriction?: TradingRestriction | null;
  price: number;
  prevClose: number;
  open: number;
  high: number;
  low: number;
  /** 세션 누적 체결량(주) */
  volume: number;
  /** 세션 누적 체결 건수 */
  trades: number;
  /** 세션 시작부터의 가격 경로. 최근 구간만 유지한다. */
  series: number[];
  limitUp: number;
  limitDown: number;
  /** 체결강도 계산용 매수·매도 누적 수량 */
  buyVolume: number;
  sellVolume: number;
  /** 최근 체결 흐름(최신순) */
  prints: TradePrint[];
  /** 매도 호가 5단계(위가 비색 가격) */
  asks: BookLevel[];
  /** 매수 호가 5단계(위가 비색 가격) */
  bids: BookLevel[];
}

export type { TradePrint };

export type NewsSource =
  | "업데이트 노트"
  | "퍼블리셔 공지"
  | "커뮤니티 집계"
  | "스토어 지표"
  | "미디어 보도";

/** 뉴스 화면에 노출하는 출처. 나머지는 내부 시뮬레이션 신호로만 남긴다. */
export const VISIBLE_NEWS_SOURCES: NewsSource[] = ["업데이트 노트", "미디어 보도"];

export interface NewsItem {
  id: number;
  at: number;
  code: string;
  source: NewsSource;
  title: string;
  /** 공식 업데이트 노트의 원문. 화면에서 생략 없이 펼쳐 보인다. */
  description?: string;
  /** 발행 시점의 시세. 이후 가격 변화로 시세 반영률을 계산한다. */
  priceAtPublish: number;
  /** 서버가 계산한 발행 시점 대비 현재가 변화율. 비율(0.0012 = 0.12%)로 저장한다. */
  priceChangeRatio?: number;
  /** 소식이 시세에 미치는 방향. */
  direction: 1 | -1;
}

export interface Position {
  code: string;
  qty: number;
  avgCost: number;
}

export interface Fill {
  id: number;
  at: number;
  code: string;
  side: "buy" | "sell";
  qty: number;
  price: number;
}

export interface Portfolio {
  cash: number;
  positions: Record<string, Position>;
  fills: Fill[];
  /** 실현 손익. 매도 체결에서만 갱신된다. */
  realized: number;
  /** 출석 보상으로 지급된 현금. 투자 수익률에서는 제외한다. */
  attendanceRewardCash?: number;
}

export interface MarketSnapshot {
  quotes: Record<string, Quote>;
  index: MarketIndex;
  codes: string[];
  news: NewsItem[];
  portfolio: Portfolio;
  watch: string[];
  updatedAt: number;
  tickMs: number;
}

export type OrderRequest = {
  code: string;
  side: "buy" | "sell";
  qty: number;
  orderType?: "MARKET" | "LIMIT";
  price?: number;
};

export type OrderResult =
  | { ok: true; fill: Fill; message: string }
  | { ok: false; message: string };
