import test from "node:test";
import assert from "node:assert/strict";
import { mobileReturnTarget } from "../src/lib/mobileReturn";

const challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
const query = (values: Record<string, string>) => new URLSearchParams({ mobileAuth: "1", state: "s1", codeChallenge: challenge, ...values });

test("앱 스킴 주소와 PKCE 값이 있으면 앱으로 돌려보낸다", () => {
  assert.deepEqual(mobileReturnTarget(query({ returnUri: "gamestock://auth/callback" }), false), {
    uri: "gamestock://auth/callback", state: "s1", codeChallenge: challenge,
  });
});

test("운영에서는 Expo Go 주소로 로그인 코드를 보내지 않는다", () => {
  assert.equal(mobileReturnTarget(query({ returnUri: "exp://attacker.example:8081/--/auth/callback" }), false), null);
  assert.ok(mobileReturnTarget(query({ returnUri: "exp://192.168.0.2:8081/--/auth/callback" }), true));
});

test("다른 스킴이나 PKCE 값이 없는 요청은 받지 않는다", () => {
  assert.equal(mobileReturnTarget(query({ returnUri: "https://attacker.example/cb" }), true), null);
  assert.equal(mobileReturnTarget(query({ returnUri: "javascript:alert(1)" }), true), null);
  assert.equal(mobileReturnTarget(new URLSearchParams({ mobileAuth: "1", state: "s1", returnUri: "gamestock://auth/callback" }), true), null);
  assert.equal(mobileReturnTarget(query({ returnUri: "gamestock://auth/callback", codeChallenge: "short" }), true), null);
  assert.equal(mobileReturnTarget(query({ returnUri: "gamestock://auth/callback", state: "" }), true), null);
});

test("앱이 연 요청이 아니면 고르지 않는다", () => {
  assert.equal(mobileReturnTarget(new URLSearchParams({ returnUri: "gamestock://auth/callback" }), true), null);
});
