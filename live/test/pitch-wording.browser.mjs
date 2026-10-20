// PLAYWRIGHT_MODULE=/absolute/path/to/playwright node live/test/pitch-wording.browser.mjs
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createLiveServer, BASE as LIVE } from '../server.mjs';
import { createCollaborationServer, BASE as CLOUD } from '../../collaboration/server.mjs';
import { fixture } from '../../collaboration/test/match-fixture.mjs';
import { uiSnapshot } from './fixture.mjs';

const require = createRequire(import.meta.url);
const modulePath = process.env.PLAYWRIGHT_MODULE || 'playwright';
const { webkit } = require(modulePath), { expect } = require(modulePath + '/test');
const output = new URL('../../output/validation/3.0/pitch-record-wording/browser-new-only/', import.meta.url);
mkdirSync(output, { recursive: true });
const live = createLiveServer(), cloud = createCollaborationServer({ allowLocalViewer: true });
for (const app of [live, cloud]) await new Promise(r => app.server.listen(0, '127.0.0.1', r));
const origin = app => `http://127.0.0.1:${app.server.address().port}`;
const key = randomBytes(32).toString('base64url'), liveKey = randomBytes(32).toString('hex');
async function request(app, method, path, body) {
  const response = await fetch(origin(app) + path, { method,
    headers: { 'Content-Type': 'application/json', 'X-BM-Protocol': '1', Authorization: `Bearer ${app === live ? liveKey : key}` },
    body: body ? JSON.stringify(body) : undefined });
  const result = await response.json(); assert.ok(response.ok, JSON.stringify(result)); return result;
}
const command = (method, path, body) => request(cloud, method, CLOUD + path, body ? { operationID: randomUUID(), ...body } : undefined);
const state = uiSnapshot();
state.entries[0].summary = '陈昊：好球 - 看（1 坏 1 好）';
state.entries[0].label = 'C';
state.entries[2].summary = '周子墨：好球 - 挥空（1 坏 2 好）';
state.entries[2].label = 'S';
state.entries[3].summary = '陈昊：界外（1 坏 2 好）';
state.entries[3].details = ['坏球（1 坏 0 好）', '好球 - 看（1 坏 1 好）', '好球 - 挥空（1 坏 2 好）', '界外（1 坏 2 好）', '看振（1 坏 1 好）']
  .map((text, i) => ({ id: 'pitch-' + i, text: '陈昊：' + text }));
const browser = await webkit.launch(), errors = [], results = [];
try {
  const session = await request(live, 'POST', LIVE + '/api/sessions', { requestID: randomUUID(), createdAt: Date.now(), snapshot: state });
  await command('POST', '/devices', { label: 'iPhone 17 Pro' });
  const team = await command('POST', '/teams', { name: '青岛海浪', nickname: '教练' });
  const match = fixture(); match.projection = { ...structuredClone(state), gameID: match.payload.id };
  for (const k of ['inning', 'isTop', 'balls', 'strikes', 'outs']) match.payload.state[k] = state[k];
  for (const side of ['home', 'away']) {
    match.payload.state[side + 'Team'].name = state[side].name;
    match.payload.state[side + 'RunsByInning'] = [...state[side].innings];
    match.payload.state[side + 'Hits'] = state[side].hits; match.payload.state[side + 'Errors'] = state[side].errors;
  }
  const game = await command('POST', `/teams/${team.teamID}/games`, match), path = `/teams/${team.teamID}/games/${game.id}`;
  const opened = await command('POST', path + '/live', { enabled: true });
  match.payload.status = 1; match.payload.state.isFinal = true;
  Object.assign(match.projection, { isFinal: true, endedAt: Date.now(), batterOrder: null, currentAppearanceID: null });
  await command('PUT', path, { ...match, baseRevision: 1, writerEpoch: 1 });
  await command('POST', path + '/access', { mode: 'public', baseAccessVersion: 1 });
  for (const [name, url] of [
    ['existing-live', `${origin(live)}${LIVE}/${session.code}`],
    ['shared-live', `${origin(cloud)}/watch/#live/${opened.liveCode}`],
    ['shared-history', `${origin(cloud)}/watch/#${game.viewCode}`]
  ]) {
    const context = await browser.newContext({ viewport: { width: 402, height: 874 }, colorScheme: 'dark' });
    const page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
    await page.goto(url); await expect(page.locator('#match')).toBeVisible();
    await expect(page.locator('#entry-pa-1 .summary')).toHaveText('好球 - 看（1 坏 1 好）');
    await expect(page.locator('#entry-pa-7 .summary')).toHaveText('好球 - 挥空（1 坏 2 好）');
    await expect(page.locator('#entry-pa-2 .summary')).toHaveText('挥棒三振出局，三出局。');
    await page.locator('#entry-pa-8 summary').click();
    await expect(page.locator('#entry-pa-8 li > span')).toHaveText([
      '陈昊：坏球（1 坏 0 好）', '陈昊：好球 - 看（1 坏 1 好）', '陈昊：好球 - 挥空（1 坏 2 好）', '陈昊：界外（1 坏 2 好）', '陈昊：看振（1 坏 1 好）'
    ]);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: new URL(name + '.png', output).pathname, fullPage: true });
    results.push(name); await context.close();
  }
  assert.deepEqual((await request(live, 'GET', LIVE + '/api/sessions/' + session.code)).snapshot, state);
  assert.deepEqual((await command('GET', path)).projection.snapshot, match.projection);
  assert.deepEqual(errors, []);
  writeFileSync(new URL('results.json', output), JSON.stringify({ passed: results, viewport: [402, 874], errors, originalSnapshotsPreserved: true }, null, 2));
  console.log('PASS: existing live, shared live and shared history; new wording displayed directly, old wording unchanged, strikeout results and stored snapshots.');
} finally {
  await browser.close();
  for (const app of [live, cloud]) { app.server.closeAllConnections(); await new Promise(r => app.server.close(r)); }
  cloud.store.close();
}
