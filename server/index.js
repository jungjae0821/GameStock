import { createServer } from "node:http";
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { extname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const distDir = resolve(fileURLToPath(new URL("../dist", import.meta.url)));
const port = Number(process.env.PORT ?? 4173);

const mimeTypes = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".ico": "image/x-icon",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".map": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".txt": "text/plain; charset=utf-8",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
};

const immutable = /-[A-Za-z0-9_]{8,}\./;

async function resolveFile(pathname) {
  const candidate = normalize(join(distDir, decodeURIComponent(pathname)));
  if (!candidate.startsWith(distDir)) return null;
  try {
    const info = await stat(candidate);
    if (!info.isFile()) return null;
    return { path: candidate, size: info.size };
  } catch {
    return null;
  }
}

function send(res, status, headers, body) {
  res.writeHead(status, headers);
  if (body === null || body === undefined) {
    res.end();
    return;
  }
  if (typeof body === "string" || Buffer.isBuffer(body)) {
    res.end(body);
    return;
  }
  /* 파일은 스트림으로 흘려보낸다. res.end(stream)은 거부된다. */
  body.on("error", () => res.destroy());
  body.pipe(res);
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", "http://localhost");
  if (req.method !== "GET" && req.method !== "HEAD") {
    send(res, 405, { allow: "GET, HEAD" });
    return;
  }
  if (url.pathname === "/healthz") {
    send(res, 200, { "content-type": "text/plain; charset=utf-8" }, "ok");
    return;
  }

  const asset = await resolveFile(url.pathname);
  if (asset) {
    const type = mimeTypes[extname(asset.path)] ?? "application/octet-stream";
    const cache = immutable.test(asset.path)
      ? "public, max-age=31536000, immutable"
      : "public, max-age=300";
    send(
      res,
      200,
      { "content-type": type, "content-length": asset.size, "cache-control": cache },
      req.method === "HEAD" ? null : createReadStream(asset.path),
    );
    return;
  }

  const fallback = await resolveFile("/index.html");
  if (!fallback) {
    send(res, 503, { "content-type": "text/plain; charset=utf-8" }, "dist/ not found. Run npm run build.");
    return;
  }
  send(
    res,
    200,
    { "content-type": "text/html; charset=utf-8", "cache-control": "no-cache" },
    req.method === "HEAD" ? null : createReadStream(fallback.path),
  );
});

server.listen(port, "0.0.0.0", () => {
  console.log(`씹덕주식 프론트엔드: http://0.0.0.0:${port} (dist: ${distDir})`);
});
