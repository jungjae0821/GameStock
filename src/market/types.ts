export interface DailyMissionStatus {
  /** 보상 지급 여부를 판단하는 한국시간 날짜(YYYY-MM-DD). */
  missionDate: string;
  resetsAt: string;
  serverTime: string;
  /** 서버가 오늘 배정한 미션 ID. 화면은 이 목록을 그대로 렌더링한다. */
  availableMissionIds: string[];
  completedMissionIds: string[];
}

export interface LoanEntry {
  id: number;
  kind: "BORROW" | "REPAY" | "INTEREST" | "LIQUIDATION";
  amount: number;
  balanceAfter: number;
  createdAt: string;
}

/** 서버가 계산한 대출 상태. 한도와 담보비율은 매 조회 시점의 시세 기준이다. */
export interface LoanStatus {
  balance: number;
  limit: number;
  available: number;
  grossAsset: number;
  netAsset: number;
  cash: number;
  /** 총 자산 ÷ 대출 잔액. 대출이 없으면 null. */
  collateralRatio: number | null;
  warning: boolean;
  borrowBlocked: boolean;
  limitRatio: number;
  dailyInterestRate: number;
  warningRatio: number;
  liquidationRatio: number;
  minAmount: number;
  history: LoanEntry[];
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

/** 서버 게임주 지수(지수 ETF NAV). 날이 바뀌어도 이어지고, prevClose는 전날 종가다. */
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
  /** Deterministic virtual market-size inputs exposed by the backend. */
  activeUsers?: number;
  marketCap?: number;
  movementWeight?: number;
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
  | "공식 발표"
  | "퍼블리셔 공지"
  | "커뮤니티 집계"
  | "스토어 지표"
  | "미디어 보도";

/** 뉴스 화면에 노출하는 출처. 나머지는 내부 시뮬레이션 신호로만 남긴다. */
export const VISIBLE_NEWS_SOURCES: NewsSource[] = ["공식 발표", "미디어 보도"];

export interface NewsItem {
  id: number;
  at: number;
  code: string;
  source: NewsSource;
  title: string;
  /** 공식 발표의 원문. 화면에서 생략 없이 펼쳐 보인다. */
  description?: string;
  /** 서버가 원문 기사에서 미리 뽑아 둔 첫 문단. 있으면 본문 패널이 따로 묻지 않는다. */
  excerpt?: string[];
  /** Google News 링크를 푼 언론사 원문 주소. */
  articleUrl?: string;
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
  availableQty?: number;
  marketValue?: number;
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
  assetValue?: number;
  totalAsset?: number;
  positions: Record<string, Position>;
  fills: Fill[];
  /** 실현 손익. 매도 체결에서만 갱신된다. */
  realized: number;
  /** 출석 보상으로 지급된 현금. 투자 수익률에서는 제외한다. */
  attendanceRewardCash?: number;
  /** 미션 보상으로 지급된 현금. 투자 수익률에서는 제외한다. */
  missionRewardCash?: number;
  /** 갚지 않은 모의 대출 잔액(쌓인 이자 포함). 순자산과 손익에서 뺀다. */
  loanBalance?: number;
  /** ETF·선물·옵션 포지션의 평가액. 현금과 별도로 총자산에 더한다. */
  derivativeAssetValue?: number;
  /** 청산·만기로 확정된 파생 손익. */
  derivativeRealizedProfitLoss?: number;
}

export interface MarketSnapshot {
  portfolioError?: string;
  marketRestriction?: TradingRestriction | null;
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
