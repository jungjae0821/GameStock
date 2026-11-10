import test from "node:test";
import assert from "node:assert/strict";
import { clock, dayLabel, fillTime, indexValue, kstDateTime, minutes, signedWon, since, won } from "../src/market/format";
import { optionDelta } from "../src/market/optionMath";
import { takeSourceLink } from "../src/lib/sourceLink";

const HOUR = 3_600;

test("금액은 반올림한 뒤 부호를 정해 -₩0이 나오지 않는다", () => {
  assert.equal(won(-0.4), "₩0");
  assert.equal(won(-0.5), "₩0");
  assert.equal(won(-0.6), "-₩1");
  assert.equal(won(25_710.4), "₩25,710");
  assert.equal(won(-1_200), "-₩1,200");
  assert.equal(signedWon(-0.4), "₩0");
  assert.equal(signedWon(1_200), "+₩1,200");
  assert.equal(signedWon(-300), "-₩300");
});

test("숫자가 아닌 금액·지수는 NaN 대신 0으로 보인다", () => {
  for (const value of [Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]) {
    assert.equal(won(value), "₩0");
    assert.equal(signedWon(value), "₩0");
    assert.equal(indexValue(value), "0.00");
  }
  assert.equal(indexValue(1_024.314), "1,024.31");
});

test("읽지 못한 시각은 오류 없이 빈 글자가 된다", () => {
  const now = Date.parse("2026-11-10T03:00:00Z");
  for (const at of [Number.NaN, Date.parse("not a date")]) {
    assert.equal(clock(at), "");
    assert.equal(minutes(at), "");
    assert.equal(kstDateTime(at), "");
    assert.equal(dayLabel(at, now), "");
    assert.equal(since(at, now), "");
    assert.equal(fillTime(at, now), "");
  }
  assert.equal(clock(now), "12:00:00");
  assert.equal(fillTime(now, now), "12:00:00");
});

test("옵션 델타는 서버처럼 만기 후·가격 0 이하에서 0이다", () => {
  assert.equal(optionDelta(110, 100, 0, 0.3, true), 0);
  assert.equal(optionDelta(90, 100, -5, 0.3, false), 0);
  assert.equal(optionDelta(0, 100, HOUR, 0.3, true), 0);
  assert.equal(optionDelta(100, 0, HOUR, 0.3, false), 0);
  assert.equal(optionDelta(Number.NaN, 100, HOUR, 0.3, true), 0);
  const call = optionDelta(100, 100, 30 * 24 * HOUR, 0.3, true);
  const put = optionDelta(100, 100, 30 * 24 * HOUR, 0.3, false);
  assert.ok(call > 0.5 && call < 0.6, `call ${call}`);
  assert.ok(Math.abs(call - put - 1) < 1e-12, "콜-풋 델타 차이는 1");
});

test("출처 주소는 뒤에 붙은 태그를 포함하지 않는다", () => {
  assert.deepEqual(takeSourceLink("<p>본문</p><p>출처: https://x.com/a?b=1</p>"), {
    text: "<p>본문</p><p></p>",
    link: "https://x.com/a?b=1",
  });
  assert.equal(takeSourceLink("본문\n출처: https://news.example/1 다음").link, "https://news.example/1");
  assert.equal(takeSourceLink("출처: https://a.example/1\n출처: https://b.example/2").link, "https://a.example/1");
  assert.deepEqual(takeSourceLink("출처 없음"), { text: "출처 없음", link: null });
});
