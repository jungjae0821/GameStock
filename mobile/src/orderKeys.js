import * as Crypto from 'expo-crypto';

/** 결과를 모르는 주문을 이 시간 안에 똑같이 다시 보내면 같은 키를 붙인다. 웹 src/market/orderRequestKey.ts와 같은 규칙. */
export const ORDER_KEY_REUSE_MS = 120_000;

/**
 * 주문 Idempotency-Key를 고른다. 응답이 늦어 결과를 모르는 주문을 다시 보내면 같은 키를 보내
 * 서버가 한 번만 실행하게 한다. 성공했거나 서버가 응답한 주문 뒤에는 새 키를 쓴다.
 */
export function createOrderKeys(newKey = () => Crypto.randomUUID(), now = Date.now) {
  let pending = null;
  return {
    keyFor({ code, side, qty, orderType, price }) {
      const fingerprint = [code, side, qty, orderType ?? 'MARKET', price ?? ''].join('|');
      if (pending && pending.fingerprint === fingerprint && now() - pending.at < ORDER_KEY_REUSE_MS) return pending.key;
      pending = { fingerprint, key: newKey(), at: now() };
      return pending.key;
    },
    settle() { pending = null; },
  };
}
