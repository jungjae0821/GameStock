/*
 * 파생 상품 미리 보기 식. react-native 없이 읽히도록 derivatives.js에서 떼어 냈다(테스트가 웹과 직접 비교한다).
 * 옵션 식은 웹 src/market/optionMath.ts, 카운트다운은 웹 src/market/derivatives.ts와 같다.
 * 주문·청산 금액은 서버가 정하고, 여기 식은 주문 전 미리 보기에만 쓴다(서버 DerivativePricing.java와 같은 식).
 */

/** 만기까지 남은 시간. mm:ss, h:mm:ss, n일 hh:mm. */
export function countdown(expiryEpoch, now) {
  const seconds = Math.max(0, Math.floor((expiryEpoch * 1000 - now) / 1000));
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3_600);
  const minutes = Math.floor((seconds % 3_600) / 60);
  const rest = seconds % 60;
  const pad = value => String(value).padStart(2, '0');
  if (days > 0) return `${days}일 ${pad(hours)}:${pad(minutes)}`;
  if (hours > 0) return `${hours}:${pad(minutes)}:${pad(rest)}`;
  return `${minutes}:${pad(rest)}`;
}

/** 지수·NAV 표시. 소수 둘째 자리. */
export const indexText = value => (Number(value) || 0).toLocaleString('ko-KR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const YEAR_SECONDS = 365 * 24 * 60 * 60;
export const CONTRACT_MULTIPLIER = 1_000;
export const OPTION_FEE_RATE = 0.003;
export const FUTURE_FEE_RATE = 0.0002;
export const ETF_FEE_RATE = 0.001;

/** 표준정규분포 누적확률. 아브라모비츠-스테군 근사(서버와 같은 계수). */
export function normalCdf(x) {
  const t = 1 / (1 + 0.2316419 * Math.abs(x));
  const density = 0.3989422804014327 * Math.exp(-x * x / 2);
  const tail = density * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
  return x >= 0 ? 1 - tail : tail;
}

export const intrinsic = (spot, strike, call) => (call ? Math.max(0, spot - strike) : Math.max(0, strike - spot));

/** 남은 초가 0 이하이면 만기 내재가치, 아니면 무위험 이자율 0의 블랙-숄즈 가격(지수 포인트). */
export function optionPoints(spot, strike, secondsLeft, volatility, call) {
  if (spot <= 0 || strike <= 0) return 0;
  if (secondsLeft <= 0) return intrinsic(spot, strike, call);
  const t = Math.max(1, secondsLeft) / YEAR_SECONDS;
  const vol = Math.max(0.01, volatility);
  const root = vol * Math.sqrt(t);
  const d1 = (Math.log(spot / strike) + 0.5 * vol * vol * t) / root;
  const d2 = d1 - root;
  const value = call ? spot * normalCdf(d1) - strike * normalCdf(d2) : strike * normalCdf(-d2) - spot * normalCdf(-d1);
  return Math.max(0, value);
}

/**
 * 지수 1포인트가 움직일 때 1계약 가격이 움직이는 포인트. 서버 DerivativePricing.optionDelta와 같이 만기가 지난 계약은 0이다.
 * 웹 optionMath.optionDelta는 만기 뒤 1/0/−1을 돌려줘 서버와 다르다. 앱은 서버를 따른다.
 */
export function optionDelta(spot, strike, secondsLeft, volatility, call) {
  if (!(spot > 0) || !(strike > 0) || !(secondsLeft > 0)) return 0;
  const t = Math.max(1, secondsLeft) / YEAR_SECONDS;
  const root = Math.max(0.01, volatility) * Math.sqrt(t);
  const d1 = (Math.log(spot / strike) + 0.5 * root * root) / root;
  return call ? normalCdf(d1) : normalCdf(d1) - 1;
}

export const optionFee = amount => Math.max(0, Math.ceil(amount * OPTION_FEE_RATE));
/** 서버의 1계약 원화 프리미엄. 최소 10원. */
export const premiumWon = points => Math.max(10, Math.round(points * CONTRACT_MULTIPLIER));

/** 서버는 변동성을 내려주지 않지만 모든 계약을 같은 변동성으로 매긴다. 시간가치가 가장 큰 계약에서 거꾸로 푼다. */
export function impliedVolatility(snapshot) {
  const valueOf = contract => contract.mark - intrinsic(snapshot.indexValue, contract.strike, contract.type === 'CALL');
  const reference = snapshot.options.reduce((best, contract) => {
    if (contract.secondsLeft <= 0 || valueOf(contract) <= 0) return best;
    return !best || valueOf(contract) > valueOf(best) ? contract : best;
  }, null);
  if (!reference) return 0.8;
  const call = reference.type === 'CALL';
  let low = 0.01;
  let high = 3;
  for (let step = 0; step < 60; step += 1) {
    const middle = (low + high) / 2;
    if (optionPoints(snapshot.indexValue, reference.strike, reference.secondsLeft, middle, call) > reference.mark) high = middle;
    else low = middle;
  }
  return (low + high) / 2;
}

/** 그 시각·지수에서 정리했을 때 받는 현금. 만기 결제는 내재가치를 수수료 없이, 그 전 청산은 수수료 0.3%를 뗀다. */
export function legExitValue(leg, spot, atEpoch, volatility) {
  const secondsLeft = leg.expiryEpoch - atEpoch;
  if (secondsLeft <= 0) return Math.round(intrinsic(spot, leg.strike, leg.call) * CONTRACT_MULTIPLIER) * leg.quantity;
  const proceeds = premiumWon(optionPoints(spot, leg.strike, secondsLeft, volatility, leg.call)) * leg.quantity;
  return proceeds - optionFee(proceeds);
}

/** 선물 1건의 명목가·증거금·수수료. 서버와 같이 증거금과 수수료는 올림이다. */
export function futureCost(future, leverage, quantity) {
  const notional = Math.round(future.price * future.multiplier * quantity);
  return { notional, margin: Math.ceil(notional / leverage), fee: Math.ceil(notional * FUTURE_FEE_RATE) };
}

/** 평가액이 증거금의 유지 비율까지 줄어드는 지수. 서버 강제청산 조건을 거꾸로 푼 값이다. */
export function liquidationIndex(entry, margin, quantity, long, future) {
  const room = (margin * (1 - future.maintenanceRate)) / (future.multiplier * quantity);
  return Math.max(0, long ? entry - room : entry + room);
}
