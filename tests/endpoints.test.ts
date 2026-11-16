import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

/*
 * 운영 API 주소는 endpoints.json 한 곳에서 정한다. 웹과 배포 워크플로는 그 파일을 직접 읽지만,
 * 정적 설정·앱·문서는 주소를 글자로 들고 있다. 주소를 바꿀 때 빠뜨린 곳이 없도록 여기서 맞춰 본다.
 */

const api = new URL((JSON.parse(readFileSync("endpoints.json", "utf8")) as { api: string }).api);
const read = (path: string) => readFileSync(path, "utf8");

test("직접 읽지 못하는 설정들이 endpoints.json의 API 주소를 쓴다", () => {
  const csp = (JSON.parse(read("firebase.json")) as { hosting: { headers: { headers: { key: string; value: string }[] }[] } })
    .hosting.headers.flatMap((rule) => rule.headers)
    .filter((header) => header.key.startsWith("Content-Security-Policy"))
    .map((header) => header.value)
    .join(" ");
  // CSP가 API 연결을 허용하지 않으면 주소를 바꾼 뒤 화면이 서버에 붙지 못한다.
  if (/connect-src/.test(csp)) {
    assert.match(csp, new RegExp(`connect-src[^;]*https://${api.host.replaceAll(".", "\\.")}`), "firebase.json CSP connect-src에 https API 주소가 필요합니다.");
    assert.match(csp, new RegExp(`connect-src[^;]*wss://${api.host.replaceAll(".", "\\.")}`), "firebase.json CSP connect-src에 wss API 주소가 필요합니다.");
  }

  const monitor = read("backend/ops/monitor-targets.json");
  assert.ok(monitor.includes(`https://${api.host}/api/health`), "monitor-targets.json이 API 상태 주소를 감시해야 합니다.");
  assert.ok(monitor.includes(`wss://${api.host}/ws/stream`), "monitor-targets.json이 웹소켓 주소를 감시해야 합니다.");

  assert.ok(read("mobile/src/config.js").includes(`"${api.origin}"`), "mobile/src/config.js의 기본 API 주소가 endpoints.json과 달라요.");
});

test("저장소 어디에도 예전 API 주소가 남아 있지 않다", () => {
  const files = execFileSync("git", ["ls-files"], { encoding: "utf8" })
    .split("\n")
    .filter((file) => file && !/package-lock\.json$|\.(png|jpe?g|webp|gif|ico|ttf|woff2?|otf|jar|zip)$/i.test(file));
  // 같은 이름 규칙(gamestock-api.*)이나 IP 기반 임시 도메인(sslip.io)을 쓰는 다른 주소가 있으면 갈아끼우다 빠뜨린 것이다.
  const pattern = /(?:gamestock-api\.[a-z0-9.-]+|[a-z0-9.-]+\.sslip\.io)/gi;
  const stale: string[] = [];
  for (const file of files) {
    let text: string;
    try { text = read(file); } catch { continue; }
    text.split("\n").forEach((line, index) => {
      for (const match of line.matchAll(pattern)) {
        if (match[0].toLowerCase() !== api.host.toLowerCase()) stale.push(`${file}:${index + 1} ${match[0]}`);
      }
    });
  }
  assert.deepEqual(stale, [], `endpoints.json(${api.host})과 다른 API 주소가 남아 있어요.`);
});
