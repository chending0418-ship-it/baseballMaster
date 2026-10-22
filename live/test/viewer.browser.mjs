// Optional local browser regression. Core service tests do not require Playwright.
// PLAYWRIGHT_MODULE=/absolute/path/to/playwright node test/viewer.browser.mjs
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createLiveServer, BASE } from '../server.mjs';
import { snapshot, uiSnapshot } from './fixture.mjs';

const require = createRequire(import.meta.url);
const modulePath = process.env.PLAYWRIGHT_MODULE || 'playwright';
const { webkit } = require(modulePath);
const { expect } = require(modulePath + '/test');
const output = resolve(process.env.LIVE_QA_OUTPUT || '../output/validation/2.2/live-ui/production-browser');
mkdirSync(output, { recursive: true });
const app = process.env.LIVE_BROWSER_ORIGIN ? null : createLiveServer({ createLimit: 10000 });
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
    let state = uiSnapshot(), code;
    async function send(method, body) {
      const response = await fetch(`${origin}${BASE}/api/sessions${code ? '/' + code : ''}`, {
        method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: body ? JSON.stringify(body) : undefined
      });
      assert.ok(response.ok, `HTTP ${response.status}`);
      return response.json();
    }
    async function settle() { await expect.poll(() => page.evaluate(() => inFlight)).toBe(false); }
    async function update(change) {
      await settle();
      change(state); state.revision++;
      await send('PUT', state);
      await page.clock.runFor(10_100);
      await expect.poll(() => page.evaluate(() => revision)).toBe(state.revision);
      await settle();
    }
    try {
      code = (await send('POST', { requestID: randomUUID(), createdAt: Date.now(), snapshot: state })).code;
      await page.clock.install();
      await page.goto(`${origin}${BASE}/${code}`);
      await expect(page).toHaveTitle(`${state.away.name} vs ${state.home.name}｜文字直播`);
      await expect(page.locator('meta[property="og:title"]')).toHaveAttribute('content', `${state.away.name} vs ${state.home.name}｜文字直播`);
      await expect(page.locator('#batter-label')).toHaveText('当前打者 · 第 4 棒');
      await expect(page.locator('#batter')).toHaveText('#12 陈昊');
      await expect(page.locator('#pitch-count')).toHaveText('已投 28 球');
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      const scoreboard = await page.locator('.scoreboard').boundingBox();
      assert.ok(scoreboard.height < 390, `Scoreboard should be compact: ${scoreboard.height}px`);
      await expect(page.locator('#batter-stats')).toHaveText('本场 2 打数 · 1 安打');
      await expect(page.locator('#rules')).toContainText('半局 6 分换边');
      await expect(page.locator('#entry-pa-8 .summary')).toHaveText('2 坏 1 好 · 1 出局');
      await expect(page.locator('#entry-pa-1 summary .entry-bases')).toHaveAttribute('aria-label', '一垒有人');
      await expect(page.locator('#entry-pa-2 summary .entry-bases')).toHaveAttribute('aria-label', '垒上无人');
      await expect(page.locator('#entry-pa-8 summary .entry-bases')).toHaveAttribute('aria-label', '一、二垒有人');
      const rows = page.locator('#innings-body tr');
      await expect(rows.nth(0).locator('td').nth(2)).toHaveText('0');
      await expect(rows.nth(1).locator('td').nth(2)).toHaveText('-');
      await expect(rows.nth(0).locator('td').nth(3)).toHaveText('-');
      const alignment = await page.evaluate(() => {
        const a = document.getElementById('next-batters'), b = document.getElementById('synced');
        const x = a.getBoundingClientRect(), y = b.getBoundingClientRect();
        return { fontA: getComputedStyle(a).fontSize, fontB: getComputedStyle(b).fontSize,
          sameRow: Math.abs(x.bottom-y.bottom)<2, overlap: x.right>y.left+1 };
      });
      assert.equal(alignment.fontA, '11px'); assert.equal(alignment.fontB, '11px');
      assert.ok(alignment.sameRow && !alignment.overlap, JSON.stringify(alignment));
      await page.screenshot({ path: `${output}/${name}.png`, fullPage: true });
      await page.locator('#entry-pa-8 summary').click();
      await expect(page.locator('#entry-pa-8 li .entry-bases').nth(0)).toHaveAttribute('aria-label', '二垒有人');
      await page.locator('#entry-pa-8 summary').click();

      await page.locator('#open-lineups').click();
      await expect(page.locator('#lineup-view')).toBeVisible();
      await expect(page.locator('#roster-body tr')).toHaveCount(9);
      await page.locator('#home-tab').click();
      await expect(page.locator('#lineup-pitcher')).toContainText('#23 王星野');
      await page.screenshot({ path: `${output}/${name}-lineups.png`, fullPage: true });
      await update(s => { s.home.lineup[0].player.name = '更新投手'; s.pitcher.name = '更新投手'; });
      await expect(page.locator('#lineup-pitcher')).toContainText('更新投手');
      await page.locator('#away-tab').click();
      await expect(page.locator('#roster-body .roster-now')).toHaveText('打击中');
      await page.locator('#home-tab').click();
      await page.locator('#home-tab').press('ArrowLeft');
      await expect(page.locator('#away-tab')).toHaveAttribute('aria-selected', 'true');
      await page.locator('#close-lineups').click();
      await expect(page.locator('#live-view')).toBeVisible();

      // The clock is stored elapsed time, not time since the web page was opened.
      await update(s => { s.clock.runningSince = null; s.clock.elapsedSeconds = 1578; });
      const paused = await page.locator('#game-time').textContent();
      assert.ok(paused.includes('26 分 18 秒 · 计时暂停'));
      await page.clock.runFor(20_000);
      await expect(page.locator('#game-time')).toHaveText(paused);
      await page.screenshot({ path: `${output}/${name}-paused.png`, fullPage: true });
      await settle();
      const synced = await page.locator('#synced').textContent();
      await page.route('**/api/sessions/**', route => route.abort());
      await page.clock.runFor(10_100);
      await expect(page.locator('#connection')).toContainText('网络暂不可用');
      await expect(page.locator('#synced')).toHaveText(synced);
      await page.unroute('**/api/sessions/**');
      await page.clock.runFor(10_100);
      await expect(page.locator('#connection')).toBeHidden();

      await update(s => { s.hasStarted = false; s.clock = { startedAt: null, runningSince: null, elapsedSeconds: 0 };
        s.away.playedInnings.fill(false); s.home.playedInnings.fill(false); });
      await expect(page.locator('#phase')).toHaveText('未开始');
      await expect(page.locator('#matchup')).toBeHidden();
      await expect(page.locator('#game-time')).toHaveText('尚未开赛');
      await expect(rows.nth(0).locator('td').nth(0)).toHaveText('-');
      await page.screenshot({ path: `${output}/${name}-pregame.png`, fullPage: true });
      await update(s => { const r = s.revision; Object.assign(s, uiSnapshot(r)); });


      await update(s => {
        s.away.name = '客队 & <队名>'; s.home.name = '主队 甲';
        s.batterOrder = 5; s.batter.name = '更正打者'; s.batter.number = '00/0';
        // A longer game lets the desktop viewport exercise the historical-reading state too.
        s.entries.unshift(...Array.from({ length: 5 }, (_, i) => ({ ...s.entries[0], id: `history-${i}`, appearanceID: `history-${i}` })));
      });
      await expect(page).toHaveTitle('客队 & <队名> vs 主队 甲｜文字直播');
      await expect(page.locator('#batter-label')).toHaveText('当前打者 · 第 5 棒');
      await expect(page.locator('#batter')).toHaveText('#00/0 更正打者');
      await expect(page.locator('.entry')).toHaveCount(state.entries.length);

      // Reading an expanded historical entry holds the old snapshot until the user accepts it.
      await page.locator('#entry-pa-8 summary').click();
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
      assert.ok(await page.evaluate(() => window.scrollY > 400));
      await update(s => { s.batterOrder = 6; });
      await expect(page.locator('#updates')).toBeVisible();
      await expect(page.locator('#batter-label')).toHaveText('当前打者 · 第 5 棒');
      await page.locator('#updates').click();
      await expect(page.locator('#batter-label')).toHaveText('当前打者 · 第 6 棒');
      await expect(page.locator('#entry-pa-8')).toHaveAttribute('open', '');
      await page.locator('#current').click();
      await page.clock.runFor(1000);
      await page.evaluate(() => window.scrollTo(0, 0));

      // Undo removes an obsolete entry; redoing restores the same identity once.
      const removed = state.entries.pop();
      await update(s => { s.batterOrder = 4; s.batter.name = '陈昊'; s.batter.number = '12'; });
      await expect(page.locator('#batter-label')).toHaveText('当前打者 · 第 4 棒');
      await expect(page.locator('#entry-pa-8')).toHaveCount(0);
      await update(s => { s.entries.push(removed); s.mode = 'coachPitch'; s.pitchCount = null; s.pitchLimit = 6; });
      await expect(page.locator('#entry-pa-8')).toHaveCount(1);
      await expect(page.locator('#pitcher-label')).toHaveText('P 位守备');
      await expect(page.locator('#pitch-count')).toHaveText('本打席 3 / 6 球');

      await page.screenshot({ path: `${output}/${name}-coach.png`, fullPage: true });
      // A missed publisher heartbeat is visible without changing its last successful sync.
      await page.route('**/api/sessions/**', async route => {
        const response = await route.fetch(); const body = await response.json();
        body.lastSeen = body.serverTime - 60_000;
        await route.fulfill({ response, json: body });
      });
      await page.clock.runFor(10_100);
      await expect(page.locator('#connection')).toContainText('记分设备暂未同步');
      await page.unroute('**/api/sessions/**');
      await page.clock.runFor(10_100);
      await expect(page.locator('#connection')).toBeHidden();

      await update(s => { const r = s.revision; Object.assign(s, uiSnapshot(r));
        s.away.name = '中国青少年棒球公开赛北京远征联合代表队';
        s.home.name = 'ShanghaiInternationalBaseballAcademyFalcons';
        s.away.shortName = s.away.name; s.home.shortName = s.home.name;
        for (const side of [s.away, s.home]) { side.innings.push(0,0,0); side.playedInnings.push(false,false,false); }
        s.batter.name = 'ChristopherAlexanderMontgomery';
        s.pitcher.name = '欧阳上官司马长姓名投手';
        s.nextBatters[0].player.name = 'ChristopherAlexanderMontgomery';
        s.nextBatters[1].player.name = '上官欧阳司马诸葛长姓名球员';
        s.batterStats.isComplete = false;
      });
      await expect(page.locator('#batter')).toContainText('ChristopherAlexanderMontgomery');
      await expect(page.locator('#batter-stats')).toContainText('待确认');
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      assert.equal(await page.evaluate(() => {
        const footer = document.querySelector('.score-footer').getBoundingClientRect();
        return [...document.querySelectorAll('#next-batters span')].some(e => e.getBoundingClientRect().right > footer.right + 1);
      }), false, 'Long upcoming names must stay inside the scoreboard');
      await page.screenshot({ path: `${output}/${name}-long-names-extra-innings.png`, fullPage: true });
      await page.evaluate(() => window.scrollTo(0, 0));
      if (await page.locator('#updates').isVisible()) await page.locator('#updates').click();
      await page.clock.runFor(1000);
      await update(s => { const r = s.revision; Object.assign(s, uiSnapshot(r)); });
      // All eight base occupancies retain the right-hand diagram and accurate text.
      for (const occupied of [[], [1], [2], [3], [1,2], [1,3], [2,3], [1,2,3]]) {
        await update(s => { s.entries[0].situation.bases = occupied; });
        await expect(page.locator('#entry-pa-1 summary .occupied')).toHaveCount(occupied.length);
      }
      await update(s => { delete s.batterOrder; });
      await expect(page.locator('#batter-label')).toHaveText('当前打者');
      await expect(page.locator('#batter')).toHaveText('#12 陈昊');
      await update(s => { s.isFinal = true; s.endedAt = Date.now(); s.batterOrder = null; });
      await expect(page.locator('#batter-label')).toHaveText('当前打者');
      await expect(page.locator('#batter')).toHaveText('—');
      await expect(page.locator('#phase')).toHaveText('终场');
      await expect(page.locator('#matchup')).toBeHidden();
      await page.clock.runFor(20_000);
      const finishedTime = await page.locator('#game-time').textContent();
      await page.clock.runFor(10_000);
      await expect(page.locator('#game-time')).toHaveText(finishedTime);
      await page.screenshot({ path: `${output}/${name}-final.png`, fullPage: true });
      // Existing 2.1 publishers remain readable without fabricated stats or history states.
      await update(s => { const r = s.revision; Object.keys(s).forEach(k => delete s[k]); Object.assign(s, snapshot(r)); });
      await expect(page.locator('#batter-stats')).toHaveText('本场表现未记录');
      await expect(page.locator('#entry-pa-1 summary .entry-bases')).toHaveText('垒况未记录');
      await page.locator('#open-lineups').click();
      await expect(page.locator('#lineup-empty')).toHaveText('此场直播未提供当前阵容');
      await page.locator('#close-lineups').click();
      await send('DELETE');
      code = undefined;
      await page.clock.runFor(10_100);
      await expect(page.locator('#match')).toBeHidden();
      await expect(page).toHaveTitle('文字直播 · BaseballMaster');
      assert.deepEqual(errors, []);
      results.push({ name, width, height, colorScheme, scoreboardHeight: scoreboard.height, passed: true });
      console.log(`PASS ${name}: scoreboard/footer, historical bases, lineups, pause, offline/reconnect, stale heartbeat, pregame, correction, history, undo/redo, coach, long names/12 innings, eight base states, legacy, finish, close`);
    } finally {
      if (code) await send('DELETE');
      await context.close();
    }
  }
  const context = await browser.newContext({ viewport: { width: 375, height: 812 } });
  const page = await context.newPage();
  await page.goto(origin + BASE + '/');
  await expect(page.locator('#gate')).toBeVisible();
  await page.locator('#code').fill('INVALID');
  await page.locator('#code-form button').click();
  await expect(page.locator('#message')).toHaveText('请输入完整的 24 位场次串码。');
  const invalid = await page.goto(origin + BASE + '/INVALID');
  assert.equal(invalid.status(), 404);
  await expect(page.locator('body')).toContainText('页面不存在');
  await page.goto(origin + BASE + '/AAAAAAAAAAAAAAAAAAAAAAAA');
  await expect(page.locator('#message')).toContainText('直播已关闭');
  await expect(page.locator('#match')).toBeHidden();
  await context.close();
  results.push({ name: 'gate-invalid-expired', passed: true });
  writeFileSync(`${output}/results.json`, JSON.stringify(results, null, 2) + '\n');
} finally {
  await browser.close();
  if (app) await new Promise(resolve => app.server.close(resolve));
}
