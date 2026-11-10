import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

/*
 * 앱(mobile/src, 일반 JS)이 웹(src)의 순수 계산을 복사해 쓴다. 같은 입력에 같은 결과가 나오는지 표로 비교한다.
 * react-native를 읽지 않는 앱 파일만 가져온다. 웹 파일 중 api.ts(import.meta.env·firebase)를 읽는 것은
 * 노드에서 열 수 없어, 칭호 색표는 소스 글자에서 꺼내 비교한다.
 */

import * as webIndicators from "../src/lib/indicators";
import * as webFormat from "../src/market/format";
import { classifyNewsSource as webClassify } from "../src/market/newsSource";
import { LISTING_BY_CODE, nextTickPrice, previousTickPrice, roundToTick, tickSize } from "../src/market/universe";
import { affordableQuantity, orderFee } from "../src/market/orderSizing";
import * as webOption from "../src/market/optionMath";
import type { DerivativeSnapshot } from "../src/market/derivatives";

// @ts-expect-error 앱 파일은 타입 선언이 없는 JS다.
import * as appIndicators from "../mobile/src/indicators.js";
// @ts-expect-error 앱 파일은 타입 선언이 없는 JS다.
import * as appFormat from "../mobile/src/format.js";
// @ts-expect-error 앱 파일은 타입 선언이 없는 JS다.
import { classifyNewsSource as appClassify, hostOf as appHostOf } from "../mobile/src/newsSource.js";
// @ts-expect-error 앱 파일은 타입 선언이 없는 JS다.
import { titleColor as appTitleColor } from "../mobile/src/titles.js";
// @ts-expect-error 앱 파일은 타입 선언이 없는 JS다.
import * as appTrading from "../mobile/src/trading.js";
// @ts-expect-error 앱 파일은 타입 선언이 없는 JS다.
import * as appOption from "../mobile/src/derivativeMath.js";

/* ───────── 기술 지표 ───────── */

const wave = (length: number, seed: number) => Array.from({ length }, (_, index) => {
  const close = 10_000 + Math.round(Math.sin(index / 3 + seed) * 400 + index * 7 * (seed % 2 ? 1 : -1));
  return { high: close + 30 + (index % 5) * 4, low: close - 25 - (index % 3) * 6, close };
});
const flat = Array.from({ length: 30 }, () => ({ high: 5000, low: 5000, close: 5000 }));
const barSets: Record<string, Array<{ high: number; low: number; close: number }>> = {
  empty: [],
  one: [{ high: 101, low: 99, close: 100 }],
  short: wave(5, 1),
  medium: wave(40, 2),
  long: wave(120, 3),
  flat,
  zero: Array.from({ length: 20 }, () => ({ high: 0, low: 0, close: 0 })),
  negative: wave(30, 4).map((bar) => ({ high: -bar.low, low: -bar.high, close: -bar.close })),
};
const specs = (["SMA", "EMA", "RSI", "ATR", "BBANDS", "MACD"] as const).flatMap((type) =>
  [1, 2, 5, 14, 20, 200].map((period) => ({ id: `${type}-${period}`, type, period })));

test("기술 지표: 모든 지표·기간·봉 묶음에서 앱과 웹이 같은 값을 낸다", () => {
  for (const [name, bars] of Object.entries(barSets)) {
    for (const spec of specs) {
      assert.deepEqual(appIndicators.computeIndicator(spec, bars), webIndicators.computeIndicator(spec, bars), `${name} ${spec.id}`);
    }
  }
});

test("기술 지표: sma·ema·rsi 단독 호출과 가운데 빈 값이 있는 ema도 같다", () => {
  const closes = wave(60, 5).map((bar) => bar.close);
  for (const period of [1, 3, 9, 26, 100]) {
    assert.deepEqual(appIndicators.sma(closes, period), webIndicators.sma(closes, period), `sma ${period}`);
    assert.deepEqual(appIndicators.rsi(closes, period), webIndicators.rsi(closes, period), `rsi ${period}`);
    const gapped = closes.map((value, index) => (index % 17 === 4 ? null : value));
    assert.deepEqual(appIndicators.ema(gapped, period), webIndicators.ema(gapped, period), `ema ${period}`);
  }
});

/* ───────── 표시 형식 ───────── */

// 앱은 금액을 "1,234원", 부호 붙은 음수를 '−'(U+2212)로 그린다. 글자 모양만 웹 꼴로 바꿔 숫자 규칙을 비교한다.
const asWebWon = (text: string) => text.replace(/−/g, "-").replace(/^([+-]?)(.*)원$/, "$1₩$2");
const asWebSign = (text: string) => text.replace(/−/g, "-");

const amounts = [0, 1, -1, 0.5, 1.5, 2.5, -1.5, -2.5, 0.49, -0.6, 999.5, 1234, -1234, 1234.4, -1234.6, 25_710, 1_000_000, -98_765_432.1, 1e12 + 0.5];
const ratios = [0, 0.0001, 0.00004, 0.00005, 0.0097, -0.0097, 0.012345, -0.012345, 0.1, -0.3, 1, 2.5, -0.999, 0.000049, 0.00995];

test("원화: 반올림과 부호가 웹 won·signedWon과 같다", () => {
  for (const value of amounts) {
    assert.equal(asWebWon(appFormat.won(value)), webFormat.won(value), `won(${value})`);
    assert.equal(asWebWon(appFormat.signedWon(value)), webFormat.signedWon(value), `signedWon(${value})`);
  }
});

test("백분율: 반올림 뒤 부호를 정하는 웹 rate와 같다", () => {
  for (const ratio of [...ratios, ...ratios.map((value) => -value), -0.00001, -0.000049]) {
    assert.equal(asWebSign(appFormat.signedPercent(ratio)), webFormat.rate(ratio), `signedPercent(${ratio})`);
  }
});

test("지수 표기: 웹 indexValue와 같다", () => {
  for (const value of [0, 1, -1, 1024.314, 1024.315, -8.2, 0.005, -0.004, 123_456.789, 1e9]) {
    assert.equal(appFormat.indexValue(value), webFormat.indexValue(value), `indexValue(${value})`);
    assert.equal(appOption.indexText(value), webFormat.indexValue(value), `indexText(${value})`);
  }
});

const isoSamples = [
  "2026-10-22T03:04:05Z", "2026-10-22T03:04:05.123Z", "2026-10-22T03:04:05", "2026-10-22T15:59:59",
  "2026-10-22T16:00:00", "2026-10-22T23:30:00+09:00", "2026-10-22T00:00:00-0500", " 2026-01-01T00:00:00 ",
  "2026-12-31T14:59:59.999", "", "   ", "not a date",
];

test("서버 시각: 시간대가 빠진 값을 UTC로 읽는 웹 serverTimestamp와 같다", () => {
  for (const iso of isoSamples) assert.equal(appFormat.serverTimestamp(iso), webFormat.serverTimestamp(iso), `serverTimestamp(${JSON.stringify(iso)})`);
});

test("체결 시각: 기기 시간대와 상관없이 웹 clock처럼 한국 시간이다", () => {
  for (const iso of isoSamples) {
    const at = webFormat.serverTimestamp(iso);
    if (!Number.isFinite(at)) continue;
    assert.equal(appFormat.clockTime(iso), webFormat.clock(at), `clockTime(${JSON.stringify(iso)})`);
    assert.equal(appFormat.clockTime(at), webFormat.clock(at), `clockTime(${at})`);
  }
});

test("앱은 웹이 그대로 드러내는 -0·NaN을 0으로 읽는다(웹의 결함은 따르지 않는다)", () => {
  // 웹 won(-0.4)은 "-₩0", won(NaN)은 "₩NaN"이다. 앱은 부호 없는 0으로 보인다.
  assert.equal(webFormat.won(-0.4), "-₩0");
  assert.equal(appFormat.won(-0.4), "0원");
  assert.equal(appFormat.price(-0.4), "0");
  assert.equal(appFormat.signedWon(-0.4), "0원");
  assert.equal(appFormat.won(Number.NaN), "0원");
  assert.equal(appFormat.signedPercent(Number.NaN), "0.00%");
  assert.equal(appFormat.clockTime("not a date"), "");
  assert.equal(appFormat.relativeTime("not a date"), "");
});

test("상대 시각: 시간대가 빠진 서버 값도 UTC로 읽는다", () => {
  const now = Date.parse("2026-10-22T03:10:00Z");
  assert.equal(appFormat.relativeTime("2026-10-22T03:07:00", now), "3분 전");
  assert.equal(appFormat.relativeTime("2026-10-22T12:07:00+09:00", now), "3분 전");
});

/* ───────── 뉴스 출처 분류 ───────── */

const names: Record<string, string> = Object.fromEntries(Object.entries(LISTING_BY_CODE).map(([code, listing]) => [code, listing.name]));
const someCode = Object.keys(LISTING_BY_CODE)[0];
const someName = names[someCode];

const newsEvents = [
  { title: "기사 제목 - 연합뉴스", stockCode: someCode },
  { title: "기사 제목", stockCode: someCode, source: "공식 발표" },
  { title: "원신 업데이트 총정리 - 게임메카", stockCode: someCode },
  { title: "7.2 패치노트 - 디스이즈게임", stockCode: someCode },
  { title: "정기 점검 안내", stockCode: someCode },
  { title: "Version 3.1 Patch Notes - 루리웹", stockCode: someCode },
  { title: "새 캐릭터 공개 - Genshin Impact", stockCode: someCode },
  { title: "새 캐릭터 공개 - 넥슨 ", stockCode: someCode },
  { title: `${someName} | 특별 방송 예고 - YouTube`, stockCode: someCode },
  { title: `캐릭터 PV | ${someName}`, stockCode: someCode },
  { title: `캐릭터 PV | ${someName}`, stockCode: "없는코드" },
  { title: "", stockCode: someCode },
  ...[
    "https://www.hoyoverse.com/ko-kr/news/1", "https://HOYOLAB.COM/article/2", "https://hoyoverse.com.evil.example/x",
    "https://nothoyoverse.com/x", "https://user:pw@nexon.com/news", "https://nexon.com:8443/a?b#c",
    "http://game.naver.com/lounge", "https://naver.com/game", "https://news.google.com/rss/articles/abc",
    "  https://www.nexon.com/news  ", "https:\\\\www.nexon.com\\news", "https:nexon.com/news", "https://nex\non.com/a",
    "nexon.com/news", "mailto:help@nexon.com", "", null, undefined, "https://exa mple.com",
  ].map((articleUrl) => ({ title: "일반 기사 - 매체", stockCode: someCode, articleUrl })),
];

test("뉴스 출처: 주소·게시처·제목 꼴로 나누는 결과가 웹과 같다", () => {
  for (const event of newsEvents) {
    assert.equal(appClassify(event, names), webClassify(event), JSON.stringify(event));
  }
});

test("뉴스 출처: URL hostname이 없는 런타임의 정규식 대안도 웹 URL 해석과 같은 호스트를 낸다", () => {
  const urls = [
    "https://www.hoyoverse.com/ko-kr/news/1", "https://HOYOLAB.COM/article/2", "https://user:pw@nexon.com/news",
    "https://nexon.com:8443/a?b#c", "http://game.naver.com/lounge", "https://hoyoverse.com.evil.example/x",
    "  https://www.nexon.com/news  ", "https:\\\\www.nexon.com\\news", "https:nexon.com/news", "https://nex\non.com/a",
    "https://a.b?x", "https://a.b#x", "nexon.com/news", "mailto:help@nexon.com", "",
  ];
  const webHost = (url: string) => {
    try { return url ? new URL(url).hostname.toLowerCase() : ""; } catch { return ""; }
  };
  for (const url of urls) {
    assert.equal(appHostOf(url, false), webHost(url), `pattern ${JSON.stringify(url)}`);
    assert.equal(appHostOf(url, true), webHost(url), `URL ${JSON.stringify(url)}`);
  }
});

/* ───────── 칭호 색 ───────── */

test("칭호 색: 웹 TITLE_COLORS의 모든 칭호와 n관왕 꼬리에서 같은 색을 낸다", () => {
  const source = readFileSync(new URL("../src/lib/titles.ts", import.meta.url), "utf8");
  const block = source.slice(source.indexOf("const TITLE_COLORS"), source.indexOf("};", source.indexOf("const TITLE_COLORS")));
  const entries = [...block.matchAll(/"([^"]+)":\s*"(#[0-9a-fA-F]{3,8})"/g)].map((match) => [match[1], match[2]] as const);
  assert.ok(entries.length > 20, "웹 색표를 읽지 못했다");
  for (const [label, color] of entries) {
    assert.equal(appTitleColor(label), color, label);
    assert.equal(appTitleColor(`${label} 3관왕`), color, `${label} 3관왕`);
  }
  assert.equal(appTitleColor("없는 칭호"), undefined);
  assert.equal(appTitleColor(""), undefined);
});

/* ───────── 호가·수수료 ───────── */

test("호가 단위·수수료·최대 수량이 웹과 같다", () => {
  const prices = [0, 0.4, 1, 1.5, 999, 1999, 1999.6, 2000, 2001, 4995, 4999, 5000, 19_990, 19_995, 20_000, 49_950, 50_000, 199_900, 200_000, 499_500, 500_000, 1_234_567];
  for (const price of prices) {
    assert.equal(appTrading.tickSize(price), tickSize(price), `tickSize(${price})`);
    assert.equal(appTrading.roundToTick(price), roundToTick(price), `roundToTick(${price})`);
    assert.equal(appTrading.nextTickPrice(price), nextTickPrice(price), `nextTickPrice(${price})`);
    assert.equal(appTrading.previousTickPrice(price), previousTickPrice(price), `previousTickPrice(${price})`);
  }
  for (const gross of [-10, 0, 1, 499, 500, 1499, 1500, 25_710, 1_000_000]) assert.equal(appTrading.orderFee(gross), orderFee(gross), `orderFee(${gross})`);
  for (const [cash, price] of [[0, 100], [-5, 100], [1_000_000, 25_710], [1_000_000, 0], [Number.NaN, 100], [100, Number.POSITIVE_INFINITY], [1001, 1000], [1_000_000_000, 1]]) {
    assert.equal(appTrading.affordableQuantity(cash, price), affordableQuantity(cash, price), `affordableQuantity(${cash}, ${price})`);
  }
  assert.equal(appTrading.affordableQuantity(1_000_000, 100, 5), affordableQuantity(1_000_000, 100, 5));
});

/* ───────── 옵션 ───────── */

const spots = [0, -10, 800, 990, 1000, 1010, 1300];
const strikes = [0, 900, 1000, 1100];
const seconds = [-60, 0, 0.5, 1, 600, 3600, 86_400 * 3];
const vols = [0, 0.005, 0.3, 0.8, 2.5];

test("옵션 가격·내재가치·정규분포가 웹 optionMath와 같다", () => {
  for (const x of [-8, -2.5, -0.1, 0, 0.1, 1.96, 8]) assert.equal(appOption.normalCdf(x), webOption.normalCdf(x), `normalCdf(${x})`);
  for (const spot of spots) for (const strike of strikes) for (const call of [true, false]) {
    assert.equal(appOption.intrinsic(spot, strike, call), webOption.intrinsic(spot, strike, call));
    for (const left of seconds) for (const vol of vols) {
      assert.equal(appOption.optionPoints(spot, strike, left, vol, call), webOption.optionPoints(spot, strike, left, vol, call), `optionPoints(${spot}, ${strike}, ${left}, ${vol}, ${call})`);
    }
  }
  for (const points of [0, 0.004, 0.0105, 1.2345, 37.5]) assert.equal(appOption.premiumWon(points), webOption.premiumWon(points));
  for (const amount of [-100, 0, 1, 333, 334, 10_000]) assert.equal(appOption.optionFee(amount), webOption.optionFee(amount));
});

test("옵션 델타: 만기 전·양수 가격에서는 웹과 같다", () => {
  for (const spot of spots.filter((value) => value > 0)) for (const strike of strikes.filter((value) => value > 0)) {
    for (const left of seconds.filter((value) => value > 0)) for (const vol of vols) for (const call of [true, false]) {
      assert.equal(appOption.optionDelta(spot, strike, left, vol, call), webOption.optionDelta(spot, strike, left, vol, call), `optionDelta(${spot}, ${strike}, ${left}, ${vol}, ${call})`);
    }
  }
});

test("옵션 델타: 만기가 지났거나 가격이 0 이하면 앱은 서버 DerivativePricing처럼 0이다(웹은 1/0/−1)", () => {
  assert.equal(appOption.optionDelta(1100, 1000, 0, 0.8, true), 0);
  assert.equal(appOption.optionDelta(900, 1000, -5, 0.8, false), 0);
  assert.equal(appOption.optionDelta(0, 1000, 600, 0.8, true), 0);
  assert.equal(webOption.optionDelta(1100, 1000, 0, 0.8, true), 1);
});

test("내재 변동성과 정리 금액이 웹과 같다", () => {
  const contract = (type: "CALL" | "PUT", strike: number, mark: number, secondsLeft: number) =>
    ({ id: `${type}${strike}`, cycleId: "1H", type, strike, mark, premium: 0, breakeven: 0, expiryAt: "", expiryEpoch: 0, secondsLeft });
  const snapshots = [
    { indexValue: 1000, options: [] },
    { indexValue: 1000, options: [contract("CALL", 1000, 0, 600)] },
    { indexValue: 1000, options: [contract("CALL", 900, 100, 600), contract("PUT", 1100, 100, 600)] },
    { indexValue: 1000, options: [contract("CALL", 1000, 12.5, 3600), contract("PUT", 1000, 12.5, 3600), contract("CALL", 1050, 3, 600)] },
    { indexValue: 1012, options: [contract("PUT", 1000, 8, 86_400), contract("CALL", 1000, 40, -1), contract("CALL", 1100, 2, 86_400)] },
  ] as unknown as DerivativeSnapshot[];
  for (const snapshot of snapshots) assert.equal(appOption.impliedVolatility(snapshot), webOption.impliedVolatility(snapshot));

  const legs = [
    { call: true, strike: 1000, expiryEpoch: 10_000, quantity: 1, cost: 15_000 },
    { call: false, strike: 1000, expiryEpoch: 10_000, quantity: 3, cost: 9_000 },
    { call: true, strike: 1100, expiryEpoch: 10_000, quantity: 2, cost: 100 },
  ];
  for (const leg of legs) for (const spot of [0, 900, 1000, 1100.4, 1250]) for (const at of [5_000, 9_999, 10_000, 12_000]) for (const vol of [0.01, 0.8]) {
    assert.equal(appOption.legExitValue(leg, spot, at, vol), webOption.legExitValue(leg, spot, at, vol), `legExitValue(${JSON.stringify(leg)}, ${spot}, ${at}, ${vol})`);
  }
});
