// Rendering-only check against an isolated local service; snapshots must remain intact.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createLiveServer, BASE } from '../server.mjs';
import { uiSnapshot } from './fixture.mjs';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const output = new URL('../../output/validation/3.1.1/live-contact-wording/', import.meta.url);
mkdirSync(output, { recursive: true });
const { server } = createLiveServer();
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const credential = randomBytes(32).toString('hex');
async function request(method, path, body) {
  const response = await fetch(origin + BASE + path, { method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${credential}` },
    body: body ? JSON.stringify(body) : undefined });
  const result = await response.json(); assert.ok(response.ok, JSON.stringify(result)); return result;
}
const snapshot = uiSnapshot();
snapshot.entries[0].summary = '陈昊：一垒安打；击球观察：平飞 · 点选去向；跑者：推进到二垒';
snapshot.entries[0].details = [
  { id: 'point', text: snapshot.entries[0].summary },
  { id: 'old-point', text: '击球观察：高飞 · 点选位置（已复核修订）' },
  { id: 'region', text: '击球观察：地滚 · 内野中间；防守路线 6-4-3' },
  { id: 'note', text: '备注：点选位置' }
];
const browser = await chromium.launch({ channel: 'chrome' }), errors = [], passed = [];
try {
  const session = await request('POST', '/api/sessions', { requestID: randomUUID(), createdAt: Date.now(), snapshot });
  for (const width of [393, 1280]) for (const colorScheme of ['light', 'dark']) {
    const context = await browser.newContext({ viewport: { width, height: 852 }, colorScheme });
    const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${origin}${BASE}/${session.code}`);
    const entry = page.locator('#entry-pa-1'); await entry.waitFor();
    assert.equal(await entry.locator('.summary').innerText(), '一垒安打；击球观察：平飞；跑者：推进到二垒');
    await entry.locator('summary').click();
    const details = await entry.locator('li > span').allTextContents();
    assert.deepEqual(details, [
      '陈昊：一垒安打；击球观察：平飞；跑者：推进到二垒',
      '击球观察：高飞（已复核修订）',
      '击球观察：地滚 · 内野中间；防守路线 6-4-3',
      '备注：点选位置'
    ]);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    const name = `${width}-${colorScheme}`; passed.push(name);
    await entry.screenshot({ path: new URL(name + '.png', output).pathname });
    await context.close();
  }
  assert.deepEqual((await request('GET', '/api/sessions/' + session.code)).snapshot, snapshot);
  assert.deepEqual(errors, []);
  writeFileSync(new URL('results.json', output), JSON.stringify({ passed, errors, originalSnapshotPreserved: true }, null, 2));
  console.log('PASS: four Chromium viewport/theme combinations, summary/detail wording and original snapshot preservation.');
} finally {
  await browser.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
}
