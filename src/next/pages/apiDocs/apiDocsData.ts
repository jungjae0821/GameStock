import { apiBaseUrl } from "../../../lib/api";

/* API 문서 화면의 엔드포인트 표, 주문 예시, 오류 표, 목차. */
export const BASE = apiBaseUrl;
export const SPEC_URL = `${BASE}/api/openapi/openapi.json`;

export type Method = "GET" | "POST" | "DELETE";

export type Endpoint = {
  method: Method;
  path: string;
  auth: "공개" | "READ / TRADE" | "TRADE" | "Firebase";
  description: string;
};

export const QUOTE_ENDPOINTS: Endpoint[] = [
  { method: "GET", path: "/api/openapi/v1/quotes", auth: "공개", description: "모든 주식·ETF·선물·옵션 스냅샷" },
  { method: "GET", path: "/api/openapi/v1/stocks", auth: "공개", description: "주식 목록과 현재가" },
  { method: "GET", path: "/api/openapi/v1/stocks/{symbol}", auth: "공개", description: "주식 한 종목의 현재가" },
  { method: "GET", path: "/api/openapi/v1/stocks/{symbol}/orderbook", auth: "공개", description: "주식 매수·매도 호가" },
  { method: "GET", path: "/api/openapi/v1/stocks/{symbol}/candles?range=1d", auth: "공개", description: "주식 OHLC 차트" },
  { method: "GET", path: "/api/openapi/v1/etfs", auth: "공개", description: "ETF NAV·구성 종목·최근 표본" },
  { method: "GET", path: "/api/openapi/v1/futures", auth: "공개", description: "FUTIDX 선물 가격·승수·레버리지" },
  { method: "GET", path: "/api/openapi/v1/options?cycleId=1H&type=CALL", auth: "공개", description: "옵션 체인; 필터는 생략 가능" },
];

export const KEY_ENDPOINTS: Endpoint[] = [
  { method: "POST", path: "/api/openapi/keys", auth: "Firebase", description: "API 키 발급; 토큰 원문은 이 응답에서 한 번만" },
  { method: "GET", path: "/api/openapi/keys", auth: "Firebase", description: "키 목록; 원문 없이 id·이름·접두사·권한·만기" },
  { method: "DELETE", path: "/api/openapi/keys/{keyId}", auth: "Firebase", description: "본인 키 폐기" },
  { method: "GET", path: "/api/openapi/openapi.json", auth: "공개", description: "OpenAPI 3.0.3 명세" },
];

export const ACCOUNT_ENDPOINTS: Endpoint[] = [
  { method: "GET", path: "/api/openapi/v1/account", auth: "READ / TRADE", description: "주식·파생 계좌 요약" },
  { method: "GET", path: "/api/openapi/v1/positions", auth: "READ / TRADE", description: "보유 ETF·선물·옵션 포지션 전체" },
  { method: "GET", path: "/api/openapi/v1/trades", auth: "READ / TRADE", description: "ETF·선물·옵션 최근 거래 20건" },
  { method: "GET", path: "/api/openapi/v1/orders?symbol=GI&limit=50", auth: "READ / TRADE", description: "주식 미체결 주문 목록" },
  { method: "GET", path: "/api/openapi/v1/orders/{orderId}", auth: "READ / TRADE", description: "본인 주식 주문의 상태·잔여 수량" },
  { method: "GET", path: "/api/openapi/v1/orders/history?symbol=GI", auth: "READ / TRADE", description: "종목별 주식 주문 이력 최근 20건" },
  { method: "GET", path: "/api/openapi/v1/requests/{requestId}", auth: "READ / TRADE", description: "완료된 요청의 원래 응답" },
];

export const TRADE_ENDPOINTS: Endpoint[] = [
  { method: "POST", path: "/api/openapi/v1/orders", auth: "TRADE", description: "주식·ETF·선물·옵션 신규 주문" },
  { method: "DELETE", path: "/api/openapi/v1/orders/{orderId}", auth: "TRADE", description: "주식 미체결 주문 취소" },
  { method: "POST", path: "/api/openapi/v1/positions/{positionId}/close", auth: "TRADE", description: "ETF·선물·옵션 부분/전량 청산" },
];

export const ORDER_EXAMPLES = [
  {
    id: "stock",
    label: "주식",
    body: `{"product":"STOCK","symbol":"GI","side":"BUY","quantity":1,"orderType":"MARKET"}`,
    note: "지정가는 orderType을 LIMIT으로 바꾸고 price를 더합니다. 주식만 지정가를 지원하며 1~1,000주를 주문할 수 있습니다. price는 호가 단위와 가격제한폭을 지켜야 합니다.",
  },
  {
    id: "etf",
    label: "ETF",
    body: `{"product":"ETF","symbol":"ETFIDX","side":"BUY","quantity":2}`,
    note: "현재 NAV로 체결됩니다. 매도도 같은 형식에 side만 SELL로 바꿉니다.",
  },
  {
    id: "future",
    label: "선물",
    body: `{"product":"FUTURE","symbol":"FUTIDX","side":"BUY","quantity":1,"leverage":5}`,
    note: "레버리지는 2·5·10배이며 생략하면 5배입니다. SELL은 롱 청산이 아니라 새 숏 진입입니다.",
  },
  {
    id: "option",
    label: "옵션",
    body: `{"product":"OPTION","side":"BUY","quantity":1,"optionType":"CALL","cycleId":"1H","strike":1000,"expiryEpoch":1792659600}`,
    note: "값은 /options 응답의 최신 type·cycleId·strike·expiryEpoch로 바꿉니다. 만기가 바뀌면 자동 매수하지 않고 거절합니다.",
  },
] as const;

export const ERROR_ROWS = [
  ["400", "INVALID_REQUEST", "필수값·수량·상품 조합·만기·주문 가능 잔액 등 검증 실패"],
  ["401", "UNAUTHORIZED", "인증 누락, 잘못된 키, 만료·폐기된 키"],
  ["403", "FORBIDDEN", "조회 전용 키의 거래, 계정 정지, 관리자 거래, 직급이 안 되는 데스크, 손실한도·재교육 중 매수·신규 진입"],
  ["404", "NOT_FOUND", "종목·본인 주문·포지션·완료 요청 없음"],
  ["409", "CONFLICT", "요청 키 충돌 또는 활성 API 키 개수 초과"],
  ["429", "RATE_LIMITED", "호출 한도 초과; Retry-After 초 뒤 재시도"],
  ["500 · 503", "—", "처리 오류·일시 불가; 새 요청 키를 만들지 말고 요청 결과 확인"],
] as const;

export const TOC = [
  ["quick-start", "API 키 발급"],
  ["auth", "인증과 키"],
  ["quotes", "시세 조회"],
  ["orders", "주문하기"],
  ["account", "계좌·주문 조회"],
  ["close", "취소·청산"],
  ["idempotency", "중복 방지"],
  ["errors", "오류·호출 한도"],
  ["tools", "명세와 도구"],
] as const;
