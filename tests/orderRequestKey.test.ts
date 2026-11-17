import test from "node:test";
import assert from "node:assert/strict";
import { createOrderKeys, ORDER_KEY_REUSE_MS } from "../src/market/orderRequestKey";

function keys() {
  let serial = 0;
  let time = 0;
  const orderKeys = createOrderKeys(() => `key-${++serial}`, () => time);
  return { orderKeys, advance: (ms: number) => { time += ms; } };
}

const buy = { code: "UMA", side: "buy", qty: 3, orderType: "LIMIT", price: 10_000 };

test("결과를 모르는 주문을 똑같이 다시 보내면 같은 키를 쓴다", () => {
  const { orderKeys } = keys();
  assert.equal(orderKeys.keyFor(buy), "key-1");
  assert.equal(orderKeys.keyFor({ ...buy }), "key-1");
});

test("결과가 확정되면 같은 주문도 새 키로 보낸다", () => {
  const { orderKeys } = keys();
  orderKeys.keyFor(buy);
  orderKeys.settle();
  assert.equal(orderKeys.keyFor(buy), "key-2");
});

test("내용이 다른 주문은 새 키를 받는다", () => {
  const { orderKeys } = keys();
  orderKeys.keyFor(buy);
  assert.equal(orderKeys.keyFor({ ...buy, qty: 4 }), "key-2");
  assert.equal(orderKeys.keyFor({ ...buy, price: 10_100 }), "key-3");
  assert.equal(orderKeys.keyFor({ ...buy, side: "sell" }), "key-4");
});

test("오래 지난 주문은 다시 보내도 새 주문으로 본다", () => {
  const { orderKeys, advance } = keys();
  orderKeys.keyFor(buy);
  advance(ORDER_KEY_REUSE_MS);
  assert.equal(orderKeys.keyFor(buy), "key-2");
});
