// 앱(mobile, 일반 JS + JSX)은 타입 검사·린트가 없어 문법 오류가 있어도 CI가 잡지 못했다.
// 앱 소스를 모두 JSX로 파싱해 깨진 파일이 있으면 실패한다. 웹과 같은 계산은 tests/mobile-parity.test.ts가 비교한다.
import { readdir, readFile } from "node:fs/promises";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { transform } from "esbuild";

const root = fileURLToPath(new URL("../mobile/", import.meta.url));

async function* sources(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.name === "node_modules" || entry.name.startsWith(".")) continue;
    const path = join(dir, entry.name);
    if (entry.isDirectory()) yield* sources(path);
    else if (/\.(js|jsx)$/.test(entry.name)) yield path;
  }
}

const files = [join(root, "App.js"), join(root, "app.config.js")];
for await (const file of sources(join(root, "src"))) files.push(file);

let failed = 0;
for (const file of files) {
  try {
    await transform(await readFile(file, "utf8"), { loader: "jsx", sourcefile: relative(root, file) });
  } catch (error) {
    failed += 1;
    console.error(error.message);
  }
}
console.log(`앱 소스 ${files.length}개 중 ${files.length - failed}개 통과`);
if (failed > 0) process.exit(1);
