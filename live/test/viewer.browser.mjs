// Optional local browser regression. Core service tests do not require Playwright.
// PLAYWRIGHT_MODULE=/absolute/path/to/playwright node test/viewer.browser.mjs
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createLiveServer, BASE } from '../server.mjs';
import { snapshot } from './fixture.mjs';

const require = createRequire(import.meta.url);
const modulePath = process.env.PLAYWRIGHT_MODULE || 'playwright';
const { webkit } = require(modulePath);
const { expect } = require(modulePath + '/test');
const output = resolve(process.env.LIVE_QA_OUTPUT || '../output/validation/2.1/live-browser');
mkdirSync(output, { recursive: true });
const app = process.env.LIVE_BROWSER_ORIGIN ? null : createLiveServer();
if (app) await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
const origin = process.env.LIVE_BROWSER_ORIGIN || `http://127.0.0.1:${app.server.address().port}`;
const browser = await webkit.launch();
const results = [];
try {
  for (const [name, width, height, colorScheme] of [
    ['mobile-light', 375, 812, 'light'], ['mobile-dark', 375, 812, 'dark'],
    ['mobile-narrow', 320, 812, 'light'], ['desktop', 1440, 1000, 'light']
  ]) {
    const context = await browser.newContext({ viewport: { width, height }, colorScheme });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const token = randomBytes(32).toString('hex');
    let state = snapshot(), code;
    async function send(method, body) {
      const response = await fetch(`${origin}${BASE}/api/sessions${code ? '/' + code : ''}`, {
        method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: body ? JSON.stringify(body) : undefined
      });
      assert.ok(response.ok, `HTTP ${response.status}`);
      return response.json();
    }
    async function update(change) {
      change(state); state.revision++;
      await send('PUT', state);
      await page.clock.runFor(10_100);
    }
    try {
      code = (await send('POST', { requestID: randomUUID(), createdAt: Date.now(), snapshot: state })).code;
      await page.clock.install();
      await page.goto(`${origin}${BASE}/${code}`);
      await expect(page.locator('#batter-label')).toHaveText('第 4 棒 · 打者');
      await expect(page.locator('#batter')).toHaveText('陈昊 · #00');
      await expect(page.locator('#pitch-count')).toHaveText('本场已投 28 球');
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      const scoreboard = await page.locator('.scoreboard').boundingBox();
      assert.ok(scoreboard.height < 360, `Scoreboard should be compact: ${scoreboard.height}px`);
      await page.screenshot({ path: `${output}/${name}.png`, fullPage: true });

      await update(s => {
        s.batterOrder = 5; s.batter.name = '更正打者'; s.batter.number = '00/0';
        // A longer game lets the desktop viewport exercise the historical-reading state too.
        s.entries.unshift(...Array.from({ length: 5 }, (_, i) => ({ ...s.entries[0], id: `history-${i}`, appearanceID: `history-${i}` })));
      });
      await expect(page.locator('#batter-label')).toHaveText('第 5 棒 · 打者');
      await expect(page.locator('#batter')).toHaveText('更正打者 · #00/0');
      await expect(page.locator('.entry')).toHaveCount(state.entries.length);

      // Reading an expanded historical entry holds the old snapshot until the user accepts it.
      await page.locator('#entry-pa-8 summary').click();
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
      assert.ok(await page.evaluate(() => window.scrollY > 400));
      await update(s => { s.batterOrder = 6; });
      await expect(page.locator('#updates')).toBeVisible();
      await expect(page.locator('#batter-label')).toHaveText('第 5 棒 · 打者');
      await page.locator('#updates').click();
      await expect(page.locator('#batter-label')).toHaveText('第 6 棒 · 打者');
      await expect(page.locator('#entry-pa-8')).toHaveAttribute('open', '');
      await page.locator('#current').click();
      await page.clock.runFor(1000);
      await page.evaluate(() => window.scrollTo(0, 0));

      // Undo removes an obsolete entry; redoing restores the same identity once.
      const removed = state.entries.pop();
      await update(s => { s.batterOrder = 4; s.batter.name = '陈昊'; s.batter.number = '00'; });
      await expect(page.locator('#batter-label')).toHaveText('第 4 棒 · 打者');
      await expect(page.locator('#entry-pa-8')).toHaveCount(0);
      await update(s => { s.entries.push(removed); s.mode = 'coachPitch'; s.pitchCount = null; s.pitchLimit = 6; });
      await expect(page.locator('#entry-pa-8')).toHaveCount(1);
      await expect(page.locator('#pitcher-label')).toHaveText('P 位守备');
      await expect(page.locator('#pitch-count')).toHaveText('本打席 3 / 6 球');

      await update(s => { delete s.batterOrder; });
      await expect(page.locator('#batter-label')).toHaveText('打者');
      await expect(page.locator('#batter')).toHaveText('陈昊 · #00');
      await update(s => { s.isFinal = true; s.endedAt = Date.now(); s.batterOrder = null; });
      await expect(page.locator('#batter-label')).toHaveText('打者');
      await expect(page.locator('#batter')).toHaveText('—');
      await expect(page.locator('#phase')).toHaveText('比赛已结束');
      await send('DELETE');
      code = undefined;
      await page.clock.runFor(10_100);
      await expect(page.locator('#match')).toBeHidden();
      assert.deepEqual(errors, []);
      results.push({ name, width, height, colorScheme, scoreboardHeight: scoreboard.height, passed: true });
      console.log(`PASS ${name}: order, correction, history, undo/redo, coach, legacy, finish, close`);
    } finally {
      if (code) await send('DELETE');
      await context.close();
    }
  }
  writeFileSync(`${output}/results.json`, JSON.stringify(results, null, 2) + '\n');
} finally {
  await browser.close();
  if (app) await new Promise(resolve => app.server.close(resolve));
}
