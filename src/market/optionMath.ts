import type { DerivativeSnapshot } from "./derivatives";

/*
 * 서버 DerivativePricing.java와 같은 식. 계산기·체인의 델타·확률은 화면에서만 쓰는 추정값이고,
 * 주문·청산 금액은 언제나 서버가 정한다.
 */
const YEAR_SECONDS = 365 * 24 * 60 * 60;
export const CONTRACT_MULTIPLIER = 1_000;
export const OPTION_FEE_RATE = 0.003;

/** 표준정규분포 누적확률. 아브라모비츠-스테군 근사(서버와 같은 계수). */
export function normalCdf(x: number): number {
  const t = 1 / (1 + 0.2316419 * Math.abs(x));
  const density = 0.3989422804014327 * Math.exp(-x * x / 2);
  const tail = density * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
  return x >= 0 ? 1 - tail : tail;
}

export function intrinsic(spot: number, strike: number, call: boolean): number {
  return call ? Math.max(0, spot - strike) : Math.max(0, strike - spot);
}

/** 남은 초가 0 이하이면 만기 내재가치, 아니면 무위험 이자율 0의 블랙-숄즈 가격(지수 포인트). */
export function optionPoints(spot: number, strike: number, secondsLeft: number, volatility: number, call: boolean): number {
  if (spot <= 0 || strike <= 0) return 0;
  if (secondsLeft <= 0) return intrinsic(spot, strike, call);
  const t = Math.max(1, secondsLeft) / YEAR_SECONDS;
  const vol = Math.max(0.01, volatility);
  const root = vol * Math.sqrt(t);
  const d1 = (Math.log(spot / strike) + 0.5 * vol * vol * t) / root;
  const d2 = d1 - root;
  const value = call
    ? spot * normalCdf(d1) - strike * normalCdf(d2)
    : strike * normalCdf(-d2) - spot * normalCdf(-d1);
  return Math.max(0, value);
}

/** 지수 1포인트가 움직일 때 1계약 가격이 움직이는 포인트. */
export function optionDelta(spot: number, strike: number, secondsLeft: number, volatility: number, call: boolean): number {
  // 서버 DerivativePricing.optionDelta처럼 만기가 지났거나 가격이 0 이하면 0이다(log(0)으로 NaN이 나오지 않게).
  if (!(spot > 0) || !(strike > 0) || !(secondsLeft > 0)) return 0;
  const t = Math.max(1, secondsLeft) / YEAR_SECONDS;
  const root = Math.max(0.01, volatility) * Math.sqrt(t);
  const d1 = (Math.log(spot / strike) + 0.5 * root * root) / root;
  return call ? normalCdf(d1) : normalCdf(d1) - 1;
}

/** 만기 지수가 목표값 너머(콜은 위, 풋은 아래)로 끝날 모형상 확률. 이자율 0 로그정규 가정. */
export function probabilityBeyond(spot: number, target: number, secondsLeft: number, volatility: number, above: boolean): number {
  if (target <= 0) return above ? 1 : 0;
  if (secondsLeft <= 0) return above ? (spot > target ? 1 : 0) : (spot < target ? 1 : 0);
  const root = Math.max(0.01, volatility) * Math.sqrt(Math.max(1, secondsLeft) / YEAR_SECONDS);
  const z = (Math.log(spot / target) - 0.5 * root * root) / root;
  return above ? normalCdf(z) : normalCdf(-z);
}

/** 서버의 1계약 원화 프리미엄. 최소 10원. */
export function premiumWon(points: number): number {
  return Math.max(10, Math.round(points * CONTRACT_MULTIPLIER));
}

export function optionFee(amount: number): number {
  return Math.max(0, Math.ceil(amount * OPTION_FEE_RATE));
}

/**
 * 서버는 변동성을 내려주지 않지만 모든 계약을 같은 변동성으로 매긴다.
 * 시간가치가 가장 큰(가장 긴 만기의 ATM) 계약 가격에서 거꾸로 풀면 서버 값과 같아진다.
 */
export function impliedVolatility(snapshot: DerivativeSnapshot): number {
  const reference = snapshot.options.reduce<DerivativeSnapshot["options"][number] | null>((best, contract) => {
    const timeValue = contract.mark - intrinsic(snapshot.indexValue, contract.strike, contract.type === "CALL");
    if (contract.secondsLeft <= 0 || timeValue <= 0) return best;
    const bestValue = best ? best.mark - intrinsic(snapshot.indexValue, best.strike, best.type === "CALL") : -1;
    return timeValue > bestValue ? contract : best;
  }, null);
  if (!reference) return 0.8;
  const call = reference.type === "CALL";
  let low = 0.01;
  let high = 3;
  for (let step = 0; step < 60; step += 1) {
    const middle = (low + high) / 2;
    if (optionPoints(snapshot.indexValue, reference.strike, reference.secondsLeft, middle, call) > reference.mark) high = middle;
    else low = middle;
  }
  return (low + high) / 2;
}

export interface OptionLeg {
  call: boolean;
  strike: number;
  expiryEpoch: number;
  quantity: number;
  /** 이 포지션에 들인 돈(원). 새 주문은 프리미엄 + 수수료, 보유 포지션은 서버 평가 기준인 진입 프리미엄. */
  cost: number;
}

/** 새로 살 때 즉시 빠지는 현금: 프리미엄 합계 + 수수료 0.3%. */
export function entryCost(premium: number, quantity: number): number {
  const total = premium * quantity;
  return total + optionFee(total);
}

/**
 * 그 시각·지수에서 정리했을 때 받는 현금.
 * 만기 전 청산은 서버처럼 최소 10원 프리미엄에서 수수료 0.3%를 떼고, 만기 결제는 내재가치를 수수료 없이 받는다.
 */
export function legExitValue(leg: OptionLeg, spot: number, atEpoch: number, volatility: number): number {
  const secondsLeft = leg.expiryEpoch - atEpoch;
  if (secondsLeft <= 0) return Math.round(intrinsic(spot, leg.strike, leg.call) * CONTRACT_MULTIPLIER) * leg.quantity;
  const proceeds = premiumWon(optionPoints(spot, leg.strike, secondsLeft, volatility, leg.call)) * leg.quantity;
  return proceeds - optionFee(proceeds);
}

/** 그 시각에 본전이 되는 지수. 콜은 지수에 대해 증가, 풋은 감소라 이분법으로 찾는다. */
export function legBreakeven(leg: OptionLeg, atEpoch: number, volatility: number): number | null {
  const profit = (spot: number) => legExitValue(leg, spot, atEpoch, volatility) - leg.cost;
  let low = leg.strike * 0.5;
  let high = leg.strike * 1.5;
  if (leg.call ? profit(high) < 0 : profit(low) < 0) return null;
  for (let step = 0; step < 50; step += 1) {
    const middle = (low + high) / 2;
    const ahead = profit(middle) >= 0;
    if (leg.call ? ahead : !ahead) high = middle;
    else low = middle;
  }
  return (low + high) / 2;
}
