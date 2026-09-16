/**
 * 작업하던 프로젝트를 통째로 압축해 건네줄 수 있게 묶는다.
 *
 *   npm run export  →  export/ssokdex.zip
 *
 * 받는 사람은 풀고 `npm install` 한 번이면 `npm run dev`로 띄울 수 있다.
 * node_modules·빌드 산출물·에디터 찌꺼기는 넣지 않는다. zip 명령이 없는
 * 환경(윈도우 기본 Git Bash 등)에서도 돌도록 zip을 직접 쓴다.
 */
import { readdirSync, readFileSync, statSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { join, resolve, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateRawSync, inflateRawSync } from "node:zlib";

const root = resolve(fileURLToPath(new URL("..", import.meta.url)));
const targetDir = join(root, "export");
const targetFile = join(targetDir, "ssokdex.zip");
/** 압축 안에서의 최상위 폴더. 풀면 이 폴더 하나가 생긴다. */
const topFolder = "ssokdex";

const SKIP_DIRS = new Set(["node_modules", "dist", "export", ".git", ".freebuff", ".idea", ".vscode"]);
const SKIP_FILES = /(?:^|[\\/])(?:\.DS_Store|Thumbs\.db)$|\.tsbuildinfo$/;

/* ---------------------------------------------------------------- zip 쓰기 */

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function dosStamp(mtime) {
  const d = new Date(mtime);
  const year = Math.max(1980, d.getFullYear());
  return {
    time: (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1),
    date: ((year - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate(),
  };
}

function collect(dir, found = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) continue;
      collect(join(dir, entry.name), found);
      continue;
    }
    const path = join(dir, entry.name);
    if (SKIP_FILES.test(path)) continue;
    found.push(path);
  }
  return found;
}

function buildZip(files) {
  const chunks = [];
  const central = [];
  let offset = 0;

  for (const path of files) {
    const name = `${topFolder}/${relative(root, path).split(sep).join("/")}`;
    const raw = readFileSync(path);
    const deflated = deflateRawSync(raw, { level: 9 });
    /* 이미 압축된 자산은 그대로 넣는 편이 작다. */
    const stored = deflated.length >= raw.length;
    const body = stored ? raw : deflated;
    const { time, date } = dosStamp(statSync(path).mtimeMs);
    const nameBytes = Buffer.from(name, "utf8");
    const crc = crc32(raw);
    /* 0x0800: 파일 이름이 UTF-8이라는 표시. 한글 경로가 깨지지 않게. */
    const flags = 0x0800;

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(flags, 6);
    local.writeUInt16LE(stored ? 0 : 8, 8);
    local.writeUInt16LE(time, 10);
    local.writeUInt16LE(date, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(raw.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    local.writeUInt16LE(0, 28);

    chunks.push(local, nameBytes, body);

    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50, 0);
    entry.writeUInt16LE(20, 4);
    entry.writeUInt16LE(20, 6);
    entry.writeUInt16LE(flags, 8);
    entry.writeUInt16LE(stored ? 0 : 8, 10);
    entry.writeUInt16LE(time, 12);
    entry.writeUInt16LE(date, 14);
    entry.writeUInt32LE(crc, 16);
    entry.writeUInt32LE(body.length, 20);
    entry.writeUInt32LE(raw.length, 24);
    entry.writeUInt16LE(nameBytes.length, 28);
    entry.writeUInt16LE(0, 30);
    entry.writeUInt16LE(0, 32);
    entry.writeUInt16LE(0, 34);
    entry.writeUInt16LE(0, 36);
    entry.writeUInt32LE(0o644 << 16, 38);
    entry.writeUInt32LE(offset, 42);
    central.push(entry, nameBytes);

    offset += local.length + nameBytes.length + body.length;
  }

  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);

  return Buffer.concat([...chunks, directory, end]);
}

/* ------------------------------------------------------------- 만든 것 확인 */

/** 만든 zip을 다시 읽어 목록과 내용이 맞는지 본다. */
function verify(zip) {
  const endOffset = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (endOffset < 0) throw new Error("zip 끝 레코드를 찾지 못했습니다.");
  const count = zip.readUInt16LE(endOffset + 10);
  let cursor = zip.readUInt32LE(endOffset + 16);
  const names = [];

  for (let i = 0; i < count; i += 1) {
    if (zip.readUInt32LE(cursor) !== 0x02014b50) throw new Error(`${i}번째 항목 헤더가 어긋났습니다.`);
    const method = zip.readUInt16LE(cursor + 10);
    const size = zip.readUInt32LE(cursor + 24);
    const nameLength = zip.readUInt16LE(cursor + 28);
    const extraLength = zip.readUInt16LE(cursor + 30);
    const commentLength = zip.readUInt16LE(cursor + 32);
    const localOffset = zip.readUInt32LE(cursor + 42);
    const name = zip.subarray(cursor + 46, cursor + 46 + nameLength).toString("utf8");

    /* 본문을 실제로 풀어 길이가 맞는지까지 확인한다. */
    const localNameLength = zip.readUInt16LE(localOffset + 26);
    const localExtraLength = zip.readUInt16LE(localOffset + 28);
    const dataStart = localOffset + 30 + localNameLength + localExtraLength;
    const compSize = zip.readUInt32LE(localOffset + 18);
    const body = zip.subarray(dataStart, dataStart + compSize);
    const unpacked = method === 0 ? body : inflateRawSync(body);
    if (unpacked.length !== size) throw new Error(`${name} 내용 길이가 맞지 않습니다.`);

    names.push(name);
    cursor += 46 + nameLength + extraLength + commentLength;
  }

  return names;
}

function formatSize(bytes) {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(2)}MB` : `${Math.round(bytes / 1024)}KB`;
}

const files = collect(root);
if (files.length === 0) throw new Error("압축할 파일이 없습니다.");

const zip = buildZip(files);
const names = verify(zip);

mkdirSync(targetDir, { recursive: true });
writeFileSync(targetFile, zip);

console.log(`압축 완료: ${targetFile} (${formatSize(zip.length)} · 파일 ${names.length}개)`);
console.log(`최상위 폴더: ${topFolder}/`);
console.log(`받는 사람: 풀고 \`npm install\` → \`npm run dev\``);
