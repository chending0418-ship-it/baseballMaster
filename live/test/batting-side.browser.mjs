import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { createLiveServer, BASE } from '../server.mjs';
import { uiSnapshot } from './fixture.mjs';

const require = createRequire(import.meta.url);
const modulePath = process.env.PLAYWRIGHT_MODULE || 'playwright';
const { chromium } = require(modulePath);
const { expect } = require(modulePath + '/test');
const app = createLiveServer();
await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
const url = `http://127.0.0.1:${app.server.address().port}${BASE}`;
const token = randomBytes(32).toString('hex');
const state = uiSnapshot();
let browser, code;

async function publish(method, body) {
  const response = await fetch(url + '/api/sessions' + (code ? '/' + code : ''), {
    method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  assert.ok(response.ok, `HTTP ${response.status}`);
  return response.json();
}

try {
  browser = await chromium.launch({ executablePath: process.env.CHROME_PATH });
  const viewer = await browser.newPage({ viewport: { width: 375, height: 812 } });
  const overlay = await browser.newPage({ viewport: { width: 600, height: 108 } });
  const pages = [viewer, overlay];
  const errors = [];
  for (const page of pages) page.on('pageerror', error => errors.push(error.message));
  const current = state.entries.find(entry => entry.appearanceID === state.currentAppearanceID);
  const awayBatter = state.away.lineup[3].player;
  const homeBatter = state.home.lineup[5].player;
  state.home.lineup[0].player.number = awayBatter.number;

  // The game is in the visitors' half, but the publisher supplied the home leadoff hitter.
  state.batter = state.home.lineup[0].player;
  state.batterOrder = 1;
  state.nextBatters = state.home.lineup.slice(1, 3).map(({ player, order }) => ({ player, order }));
  code = (await publish('POST', { requestID: randomUUID(), createdAt: Date.now(), snapshot: state })).code;
  await Promise.all(pages.map(page => page.clock.install()));
  await Promise.all([viewer.goto(url + '/' + code), overlay.goto(url + '/overlay?view=batter#temporary/' + code)]);
  await expect(viewer.locator('#phase')).toHaveText('3 局上');
  console.log('Top half rendered:', await viewer.locator('#batter').textContent(), await viewer.locator('#batter-label').textContent());
  await expect(viewer.locator('#batter')).toHaveText(`#${awayBatter.number} ${awayBatter.name}`);
  await expect(viewer.locator('#batter-label')).toHaveText('当前打者 · 第 4 棒');
  await expect(viewer.locator('#next-batters')).toContainText(state.away.lineup[4].player.name);
  await expect(overlay.locator('#batter-name')).toHaveText(awayBatter.name);
  await expect(overlay.locator('#batter-order')).toHaveText('第 4 棒');

  // Bottom half, again with the wrong team's leadoff hitter in the top-level fields.
  Object.assign(state, { isTop: false, batter: state.away.lineup[0].player, batterOrder: 1, revision: 2 });
  Object.assign(current, { isTop: false, player: homeBatter, order: 6 });
  await publish('PUT', state);
  await Promise.all(pages.map(page => page.clock.runFor(10_100)));
  await expect(viewer.locator('#phase')).toHaveText('3 局下');
  await expect(viewer.locator('#batter')).toHaveText(`#${homeBatter.number} ${homeBatter.name}`);
  await expect(viewer.locator('#batter-label')).toHaveText('当前打者 · 第 6 棒');
  await expect(overlay.locator('#batter-name')).toHaveText(homeBatter.name);
  await viewer.reload();
  await expect(viewer.locator('#batter-label')).toHaveText('当前打者 · 第 6 棒');

  // With no usable current appearance, the other team's name stays off both surfaces.
  Object.assign(state, { currentAppearanceID: null, revision: 3 });
  await publish('PUT', state);
  await Promise.all(pages.map(page => page.clock.runFor(10_100)));
  await expect(viewer.locator('#batter')).toHaveText('—');
  await expect(overlay.locator('#batter')).toBeHidden();
  assert.deepEqual(errors, []);
  console.log('Current batter verified for both halves, reconnect, and unknown batter.');
} finally {
  await browser?.close();
  app.server.closeAllConnections();
  await new Promise(resolve => app.server.close(resolve));
}
