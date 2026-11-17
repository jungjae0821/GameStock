import test from "node:test";
import assert from "node:assert/strict";
// @ts-expect-error 앱 파일은 타입 선언이 없는 JS다.
import { navigationTarget } from "../mobile/src/web-origin.js";

const app = "https://syuenasset.xyz";

test("앱 화면 이동과 하위 프레임은 WebView에서 연다", () => {
  assert.equal(navigationTarget("https://syuenasset.xyz/market/UMA?app-shell=1", app), "app");
  assert.equal(navigationTarget("about:blank", app), "app");
  // Firebase 로그인 같은 하위 프레임은 출처와 상관없이 막지 않는다.
  assert.equal(navigationTarget("https://gamestock-20994.firebaseapp.com/__/auth/iframe", app, false), "app");
});

test("다른 사이트로 가는 최상위 이동은 시스템 브라우저로 넘긴다", () => {
  assert.equal(navigationTarget("https://news.example.com/article/1", app), "external");
  assert.equal(navigationTarget("http://syuenasset.xyz/", app), "external");
  assert.equal(navigationTarget("https://user:pw@syuenasset.xyz/", app), "external");
  assert.equal(navigationTarget("mailto:help@example.com", app), "external");
});

test("알 수 없는 스킴과 잘못된 주소는 열지 않는다", () => {
  assert.equal(navigationTarget("javascript:alert(1)", app), "block");
  assert.equal(navigationTarget("intent://scan/#Intent;scheme=zxing;end", app), "block");
  assert.equal(navigationTarget("not a url", app), "block");
});
