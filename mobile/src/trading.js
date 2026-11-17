// 웹 src/market/universe.ts, orderSizing.ts와 같은 규칙. 서버도 같은 호가 단위와 수수료로 검사한다.

/** 한국 거래소 호가 단위. */
export function tickSize(price) {
  if (price < 2000) return 1;
  if (price < 5000) return 5;
  if (price < 20000) return 10;
  if (price < 50000) return 50;
  if (price < 200000) return 100;
  if (price < 500000) return 500;
  return 1000;
}

export const roundToTick = price => {
  const size = tickSize(price);
  return Math.round(price / size) * size;
};

export function nextTickPrice(price) {
  const candidate = Math.max(1, Math.floor(price) + 1);
  const size = tickSize(candidate);
  return Math.max(1, Math.ceil(candidate / size) * size);
}

export function previousTickPrice(price) {
  const candidate = Math.max(1, Math.ceil(price) - 1);
  const size = tickSize(candidate);
  return Math.max(1, Math.floor(candidate / size) * size);
}

/** 사용자가 참여한 체결의 수수료 0.1%. 원 단위로 반올림하고 최소 1원. */
export const orderFee = gross => (gross > 0 ? Math.max(1, Math.round(gross / 1000)) : 0);

export const MAX_ORDER_QUANTITY = 1000;

/** 수수료까지 낼 수 있는 최대 매수 수량. 한 번에 1,000주까지. */
export function affordableQuantity(cash, price, cap = MAX_ORDER_QUANTITY) {
  if (!Number.isFinite(cash) || !Number.isFinite(price) || cash <= 0 || price <= 0) return 0;
  let low = 0;
  let high = Math.max(0, Math.min(cap, Math.floor(cash / price)));
  while (low < high) {
    const quantity = Math.ceil((low + high) / 2);
    const gross = quantity * price;
    if (gross + orderFee(gross) <= cash) low = quantity;
    else high = quantity - 1;
  }
  return low;
}

/** 지정가가 오늘의 가격 제한폭 밖인가. 웹 src/market/orderSizing.ts와 같은 규칙. 제한폭을 모르면 막지 않는다. */
export function outsidePriceBand(price, limitDown, limitUp) {
  return Boolean((limitUp && price > limitUp) || (limitDown && price < limitDown));
}

/**
 * 지금 팔 수 있는 수량. 서버가 대기 중인 매도 주문과 미정산분을 뺀 값(availableQuantity)을 주면 그 값을,
 * 예전 서버 응답이면 보유 수량을 쓴다. 웹 src/market/orderSizing.ts와 같은 규칙.
 */
export function sellableQuantity(position, cap = MAX_ORDER_QUANTITY) {
  const value = position?.availableQuantity ?? position?.quantity ?? 0;
  return Number.isFinite(value) ? Math.max(0, Math.min(cap, Math.floor(value))) : 0;
}
