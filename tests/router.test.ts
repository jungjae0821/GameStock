import test from "node:test";
import assert from "node:assert/strict";
import { isReturnablePath } from "../src/router";

test("로그인 뒤 라우터가 여는 화면이면 어디든 돌아간다", () => {
  for (const path of ["/", "/market", "/market/UMA", "/market/etf/GAME", "/trade/UMA", "/news/12", "/career", "/attendance",
    "/billboard", "/quant", "/quant/3", "/talk/UMA", "/tap", "/admin/users", "/derivatives/options", "/heatmap", "/loan"]) {
    assert.equal(isReturnablePath(path), true, path);
  }
});

test("로그인 화면·없는 주소·다른 사이트로는 돌아가지 않는다", () => {
  for (const path of ["/login", "/login/email", "/nope", "/billboard/extra", "/quant/abc", "/api-docs/x", "//evil.example/path", "evil", ""]) {
    assert.equal(isReturnablePath(path), false, path);
  }
});
