/** 결과를 모르는 주문을 이 시간 안에 똑같이 다시 보내면 같은 키를 붙인다. 서버는 키를 하루 동안 기억한다. */
export const ORDER_KEY_REUSE_MS = 120_000;

export type OrderKeyRequest = { code: string; side: string; qty: number; orderType?: string; price?: number };

/**
 * 주문 Idempotency-Key를 고른다.
 *
 * 응답이 늦어 화면이 기다리기를 포기한 주문은 서버에서 체결됐을 수도 있다. 사용자가 같은 주문을
 * 다시 누르면 같은 키를 보내 서버가 한 번만 실행하게 한다. 성공했거나 서버가 응답한 주문은
 * 결과가 확정됐으므로 다음 주문은 새 키를 쓴다. 같은 주문을 일부러 한 번 더 내는 경우도 그대로 된다.
 */
export function createOrderKeys(newKey: () => string = () => crypto.randomUUID(), now: () => number = Date.now) {
  let pending: { fingerprint: string; key: string; at: number } | null = null;
  return {
    keyFor(request: OrderKeyRequest): string {
      const fingerprint = [request.code, request.side, request.qty, request.orderType ?? "MARKET", request.price ?? ""].join("|");
      if (pending && pending.fingerprint === fingerprint && now() - pending.at < ORDER_KEY_REUSE_MS) return pending.key;
      pending = { fingerprint, key: newKey(), at: now() };
      return pending.key;
    },
    /** 결과가 확정됐다(성공했거나 서버가 응답했다). */
    settle(): void {
      pending = null;
    },
  };
}
