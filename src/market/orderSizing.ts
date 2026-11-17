/** Match the backend's 0.1% fee, rounded to the nearest won (minimum one). */
export function orderFee(gross: number): number {
  return gross > 0 ? Math.max(1, Math.round(gross / 1000)) : 0;
}

export function affordableQuantity(cash: number, price: number, cap = 1000): number {
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

/** 지정가가 오늘의 가격 제한폭 밖인가. 서버가 거절하는 가격을 화면에서 먼저 막는다. 제한폭을 모르면 막지 않는다. */
export function outsidePriceBand(price: number, limitDown?: number, limitUp?: number): boolean {
  return Boolean((limitUp && price > limitUp) || (limitDown && price < limitDown));
}

/**
 * 지금 팔 수 있는 수량. 서버가 대기 중인 매도 주문과 미정산분을 뺀 값(availableQuantity)을 주면 그 값을,
 * 예전 서버 응답이면 보유 수량을 쓴다. 한 번에 1,000주까지.
 */
export function sellableQuantity(position?: { quantity?: number; availableQuantity?: number } | null, cap = 1000): number {
  const value = position?.availableQuantity ?? position?.quantity ?? 0;
  return Number.isFinite(value) ? Math.max(0, Math.min(cap, Math.floor(value))) : 0;
}
