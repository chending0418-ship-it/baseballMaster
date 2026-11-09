// Optional local browser regression. PLAYWRIGHT_MODULE and CHROME_PATH can point to local installations.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { createLiveServer, BASE } from '../server.mjs';
import { uiSnapshot } from './fixture.mjs';
import { createCollaborationServer } from '../../collaboration/server.mjs';
import { fixture } from '../../collaboration/test/match-fixture.mjs';

const require = createRequire(import.meta.url);
const modulePath = process.env.PLAYWRIGHT_MODULE || 'playwright';
const { chromium } = require(modulePath);
const { expect } = require(modulePath + '/test');
const output = resolve(process.env.LIVE_QA_OUTPUT || '../output/validation/obs-overlay');
mkdirSync(output, { recursive: true });
const temporary = createLiveServer();
const shared = createCollaborationServer();
const listen = async ({ server }) => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return 'http://127.0.0.1:' + server.address().port;
};
const shutdown = ({ server }) => {
  server.closeAllConnections();
  return new Promise(resolve => server.close(resolve));
};
const origin = await listen(temporary);
const sharedOrigin = await listen(shared);
let browser;
try {
  browser = await chromium.launch({ executablePath: process.env.CHROME_PATH });
  const errors = [];
  const sourcePath = origin + BASE + '/overlay';
  const source = async (view, width, height, fragment, query = '') => {
    const page = await browser.newPage({ viewport: { width, height } });
    page.on('pageerror', error => errors.push(error.message));
    await page.clock.install();
    await page.route('**/api/v3/live/**', async route => {
      const path = new URL(route.request().url()).pathname;
      await route.fulfill({ response: await route.fetch({ url: sharedOrigin + path }) });
    });
    await page.goto(sourcePath + '?view=' + view + query + '#' + fragment);
    return page;
  };
  const key = randomBytes(32).toString('hex');
  const state = uiSnapshot();
  let code;
  const publish = async (method, body) => {
    const response = await fetch(origin + BASE + '/api/sessions' + (code ? '/' + code : ''), {
      method, headers: { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined
    });
    assert.ok(response.ok, 'Temporary service returned ' + response.status);
    return response.json();
  };
  code = (await publish('POST', { requestID: randomUUID(), createdAt: Date.now(), snapshot: state })).code;
  const fragment = 'temporary/' + code;
  const score = await source('score', 640, 116, fragment);
  const batter = await source('batter', 600, 108, fragment);
  const lineup = await source('lineup', 440, 540, fragment, '&team=home');
  const awayLineup = await source('lineup', 440, 540, fragment, '&team=away');
  const both = await source('lineup', 896, 540, fragment, '&team=both');
  const preview = await source('lineup', 600, 540, fragment, '&team=both&preview=1');
  const views = [[score, 'score', 640, 116], [batter, 'batter', 600, 108], [lineup, 'lineup', 440, 540],
    [awayLineup, 'lineup', 440, 540], [both, 'lineup', 896, 540]];
  const pages = views.map(([page]) => page);
  const loc = (page, id) => page.locator('#' + id);
  const tick = pages => Promise.all(pages.map(page => page.clock.runFor(5100)));
  async function revision(page, id, number) {
    await expect(loc(page, id)).toHaveAttribute('data-revision', String(number));
  }
  async function update(change) {
    change(state);
    state.revision++;
    await publish('PUT', state);
    await tick(pages);
    await Promise.all(views.map(([page, id]) => revision(page, id, state.revision)));
  }

  for (const [page, id, width, height] of views) {
    await revision(page, id, state.revision);
    assert.deepEqual(await loc(page, id).boundingBox(), { x: 0, y: 0, width, height });
    await expect(page.locator('body > section:visible')).toHaveCount(1);
    assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), 'rgba(0, 0, 0, 0)');
    await page.screenshot({ path: resolve(output, (page === both ? 'lineup-both' : page === awayLineup ? 'lineup-away' : id) + '.png'), omitBackground: true });
  }
  await expect(loc(score, 'pitch-strikes')).toBeEmpty();
  await expect(loc(score, 'pitch-strikeouts')).toBeEmpty();
  await expect(loc(batter, 'appearances')).toHaveText('一垒安打');
  await expect(lineup.locator('.lineup-rows').locator('li')).toHaveCount(9);
  await expect(lineup.locator('.lineup-team')).toHaveText(state.home.name);
  await expect(awayLineup.locator('.lineup-team')).toHaveText(state.away.name);
  await expect(awayLineup.locator('.lineup-rows').locator('li')).toHaveCount(9);
  await expect(both.locator('.lineup[data-team="away"] .lineup-team')).toHaveText(state.away.name);
  await expect(both.locator('.lineup[data-team="home"] .lineup-team')).toHaveText(state.home.name);
  assert.deepEqual(await both.locator('.lineup[data-team="away"]').boundingBox(), { x: 0, y: 0, width: 440, height: 540 });
  assert.deepEqual(await both.locator('.lineup[data-team="home"]').boundingBox(), { x: 456, y: 0, width: 440, height: 540 });
  await expect(loc(preview, 'lineup')).toBeVisible();
  const previewBox = await loc(preview, 'lineup').boundingBox();
  assert.ok(previewBox.width <= 600 && previewBox.width > 590);

  await update(s => { s.pitchCount++; s.strikes++; });
  await expect(loc(score, 'count')).toHaveText('2–2');
  await expect(loc(score, 'pitches')).toHaveText('本场 29 球');
  await update(s => {
    s.batter = { ...s.nextBatters[0].player, name: '很长的打者姓名'.repeat(10) };
    s.batterOrder = 5;
    s.batterStats = { hits: 1, atBats: 1, isComplete: true };
    s.balls = 0; s.strikes = 0; s.currentAppearanceID = null;
    s.bases[1].base = 3;
    s.away.innings[2]++; s.away.runs++;
  });
  await expect(loc(score, 'away-runs')).toHaveText('3');
  await expect(loc(score, 'bases').locator('.occupied')).toHaveCount(2);
  await expect(loc(score, 'bases').locator('[data-base="3"]')).toHaveClass('occupied');
  await expect(loc(batter, 'batter-name')).toHaveText(state.batter.name);
  await expect(loc(batter, 'appearances')).toHaveText('二垒安打');
  const previousPitcher = state.pitcher;
  await update(s => {
    s.pitcher = { id: 'reliever', name: '很长的替补投手姓名'.repeat(10), number: '26' };
    s.pitchCount = 0;
    s.home.lineup[0].player = s.pitcher;
  });
  await expect(loc(score, 'pitcher-name')).toHaveText(state.pitcher.name);
  await expect(loc(score, 'pitches')).toHaveText('本场 0 球');
  await expect(lineup.locator('.lineup-rows').locator('li').first()).toContainText(state.pitcher.name);
  await expect(both.locator('[data-team="home"] li').first()).toContainText(state.pitcher.name);
  await expect(both.locator('[data-team="away"] li').first()).toContainText(state.away.lineup[0].player.name);
  await expect(awayLineup.locator('.lineup-rows li').first()).toContainText(state.away.lineup[0].player.name);
  for (const [page, id, width, height] of views) {
    assert.deepEqual(await loc(page, id).boundingBox(), { x: 0, y: 0, width, height });
  }
  for (const [page, id] of [[score, 'pitcher-name'], [batter, 'batter-name']]) {
    assert.equal(await loc(page, id).evaluate(el => el.scrollWidth > el.clientWidth && getComputedStyle(el).textOverflow === 'ellipsis'), true);
  }

  await update(s => { s.pitcher = previousPitcher; s.pitchCount = 29; s.home.lineup[0].player = previousPitcher; });
  await expect(loc(score, 'pitcher-name')).toHaveText(previousPitcher.name);
  await update(s => { s.isFinal = true; s.endedAt = Date.now(); s.batterOrder = null; });
  await expect(loc(score, 'inning')).toHaveText('终场');
  await expect(loc(batter, 'batter')).toBeHidden();
  await publish('DELETE');
  await tick(pages);
  await Promise.all(views.map(([page, id]) => expect(loc(page, id)).toBeHidden()));

  const deviceKey = randomBytes(32).toString('base64url');
  const call = async (path, body) => {
    const response = await fetch(sharedOrigin + '/api/v3' + path, {
      method: 'POST', headers: { Authorization: 'Bearer ' + deviceKey, 'Content-Type': 'application/json', 'X-BM-Protocol': '1' },
      body: JSON.stringify({ operationID: randomUUID(), ...body })
    });
    assert.ok(response.ok, 'Shared service returned ' + response.status);
    return response.json();
  };
  await call('/devices', { label: '本地转播测试' });
  const { teamID } = await call('/teams', { name: '测试球队', nickname: '记录员' });
  const match = fixture();
  const playerID = randomUUID();
  match.payload.state.homeTeam.players.push({ id: playerID, chineseName: '陈航', englishName: '', numbers: [18] });
  match.payload.state.pitching = [playerID, { pitches: 62, strikes: 38, strikeouts: 3, runs: 0, earnedRuns: 0 }];
  match.projection.pitcher = { id: playerID, name: '陈航', number: '18' };
  match.projection.pitchCount = 62;
  const game = await call('/teams/' + teamID + '/games', match);
  const livePath = '/teams/' + teamID + '/games/' + game.id + '/live';
  const { liveCode } = await call(livePath, { enabled: true });
  const sharedScore = await source('score', 640, 116, 'shared/' + liveCode);
  await expect(loc(sharedScore, 'pitches')).toHaveText('本场 62 球');
  await expect(loc(sharedScore, 'pitch-strikes')).toHaveText('38 好球');
  await expect(loc(sharedScore, 'pitch-strikeouts')).toHaveText('3 三振');
  await call(livePath, { enabled: false });
  await tick([sharedScore]);
  await expect(loc(sharedScore, 'score')).toBeHidden();
  assert.deepEqual(errors, []);
  console.log('PASS: four independent transparent sources, fixed bounds, scoring, batter, pitcher, lineup, undo, final and revocation');
} finally {
  await browser?.close();
  await Promise.all([shutdown(temporary), shutdown(shared)]);
  shared.store.close();
}
