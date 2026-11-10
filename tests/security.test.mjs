import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, symlink, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { execFileSync } from 'node:child_process';
import vm from 'node:vm';
import { createStaticServer } from '../server/index.js';
import { collect, exportablePath } from '../scripts/export-zip.mjs';

// Windows는 개발자 모드나 관리자 권한이 없으면 심볼릭 링크를 만들 수 없다(EPERM).
// 그때는 테스트를 건너뛰고, 링크를 만들 수 있는 환경(Linux CI 등)에서만 검사한다.
async function symlinkOrSkip(t, target, path) {
  try {
    await symlink(target, path);
    return true;
  } catch (error) {
    if (process.platform !== 'win32' || error.code !== 'EPERM') throw error;
    t.skip('심볼릭 링크 생성 권한 없음(Windows 개발자 모드를 켜면 실행됨)');
    return false;
  }
}

test('malformed requests cannot stop the server or expose sibling directories/symlinks', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'gamestock-http-test-'));
  const root = join(dir, 'dist');
  await mkdir(root);
  await mkdir(join(dir, 'dist-private'));
  await writeFile(join(root, 'index.html'), 'APP');
  await writeFile(join(dir, 'dist-private', 'private.txt'), 'PRIVATE');
  if (!await symlinkOrSkip(t, join(dir, 'dist-private', 'private.txt'), join(root, 'linked.txt'))) {
    await rm(dir, { recursive: true, force: true });
    return;
  }
  const server = createStaticServer(root).listen(0, '127.0.0.1');
  try {
    await once(server, 'listening');
    const base = `http://127.0.0.1:${server.address().port}`;
    for (const url of ['/%', '/%E0%A4%A', '/%GG']) assert.equal((await fetch(base + url)).status, 400);
    for (const url of ['/..%2fdist-private/private.txt', '/linked.txt']) assert.notEqual(await (await fetch(base + url)).text(), 'PRIVATE');
    assert.equal(await (await fetch(base + '/trade/UMA')).text(), 'APP');
    assert.equal((await fetch(base + '/healthz')).status, 200);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    await rm(dir, { recursive: true, force: true });
  }
});

test('export excludes secrets, untracked files and symlinks before reading any content', async t => {
  for (const name of ['.env', '.env.local', 'backend/secrets/firebase-admin.json', '.aws/config', 'logs/debug.log', 'private.key', 'serviceAccount.json', '../escape'])
    assert.equal(exportablePath(name), false, name);
  const dir = await mkdtemp(join(tmpdir(), 'gamestock-export-test-'));
  try {
    execFileSync('git', ['init', '-q', dir]);
    await writeFile(join(dir, 'source.ts'), 'export const x = 1;');
    await writeFile(join(dir, 'local.json'), 'untracked');
    if (!await symlinkOrSkip(t, 'local.json', join(dir, 'linked.json'))) return;
    execFileSync('git', ['-C', dir, 'add', 'source.ts', 'linked.json']);
    assert.deepEqual(collect(dir), [join(dir, 'source.ts')]);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('native auth bridge rejects foreign origins even if navigation races injection', async () => {
  const source = await readFile(new URL('../mobile/src/web-origin.js', import.meta.url), 'utf8');
  const { isTrustedAppUrl, authInjection } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
  const app = 'https://app.example';
  assert.equal(isTrustedAppUrl(app + '/trade/UMA', app), true);
  for (const url of ['https://app.example.evil.test', 'http://app.example', 'https://app.example@evil.test', 'javascript:alert(1)', 'about:blank'])
    assert.equal(isTrustedAppUrl(url, app), false, url);
  for (const origin of [app, 'https://evil.test']) {
    const events = [];
    const window = { location: { origin }, dispatchEvent: event => events.push(event) };
    window.top = window;
    vm.runInNewContext(authInjection({ customToken: 'TEST' }, app), { window, CustomEvent: class { constructor(type, init) { Object.assign(this, { type, ...init }); } } });
    assert.equal(events.length, origin === app ? 1 : 0);
  }
});
