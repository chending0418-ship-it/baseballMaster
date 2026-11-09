// Synthetic data only. The publishing token stays in this process and is never printed or saved.
import { randomBytes, randomUUID } from 'node:crypto';
import { createInterface } from 'node:readline';
import { snapshot, uiSnapshot } from './test/fixture.mjs';
const base = (process.env.LIVE_BASE || 'http://127.0.0.1:8088/livestreaming/novideo').replace(/\/$/, '');
const token = randomBytes(32).toString('hex');
const overlay = process.argv.includes('--overlay');
let state = overlay ? uiSnapshot() : snapshot(), code, timer;
if (overlay) state.bases = state.bases.slice(0, 1);
async function send(method, payload, suffix = code ? '/' + code : '') {
  const response = await fetch(base + '/api/sessions' + suffix, { method, headers: { Authorization: 'Bearer ' + token, ...(payload ? { 'Content-Type': 'application/json' } : {}) }, body: payload ? JSON.stringify(payload) : undefined });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}
async function publish() { state.revision++; await send('PUT', state); }
async function close() { clearInterval(timer); if (code) await send('DELETE').catch(() => {}); }
process.on('SIGINT', async () => { await close(); process.exit(0); });
try {
  code = (await send('POST', { requestID: randomUUID(), createdAt: Date.now(), snapshot: state })).code;
  if (process.argv.includes('--smoke')) {
    state.balls = 2; await publish();
    const read = await (await fetch(base + '/api/sessions/' + code)).json();
    if (read.snapshot.balls !== 2) throw new Error('Update was not visible');
    state.balls = 1; await publish();
    state.isFinal = true; state.batterOrder = null; state.endedAt = Date.now(); await publish();
    await close();
    if ((await fetch(base + '/api/sessions/' + code)).status !== 404) throw new Error('Deletion failed');
    console.log('PASS: create → read → update → undo → finish → delete');
  } else {
    console.log(`示例观赛网址：${base}/${code}`);
    if (overlay) {
      for (const [view, label, size] of [['score', '比分＋投手', '640×116'], ['batter', '当前打者', '600×108'],
        ['lineup&team=away', '客队阵容', '440×540'], ['lineup&team=home', '主队阵容', '440×540']]) {
        console.log(`${label}（${size}）：${base}/overlay?view=${view}#temporary/${code}`);
      }
    }
    console.log('命令：ball / undo / finish / reopen / expire（15 秒后删除）/ quit' + (overlay ? ' / batter / pitcher' : ''));
    timer = setInterval(() => send('POST').catch(() => {}), 10000);
    const input = createInterface({ input: process.stdin });
    const history = [];
    for await (const line of input) {
      const command = line.trim();
      if (command === 'quit') { await close(); input.close(); break; }
      const before = overlay ? structuredClone(state) : null;
      if (overlay && command === 'undo') {
        if (!history.length) continue;
        state = { ...history.pop(), revision: state.revision };
      } else if (overlay && command === 'ball' && !state.isFinal) {
        state.strikes = Math.min(state.strikes + 1, 2);
        state.pitchCount++;
      } else if (overlay && command === 'batter' && !state.isFinal && state.currentAppearanceID) {
        const next = state.away.lineup.find(row => row.order === 5);
        const appearance = state.entries.find(entry => entry.id === state.currentAppearanceID);
        Object.assign(appearance, { status: 'completed', label: '1B', summary: '陈昊：一垒安打' });
        state.pitchCount++;
        state.batter = next.player; state.batterOrder = next.order;
        state.batterStats = { atBats: 1, hits: 1, isComplete: true };
        state.currentAppearanceID = null; state.balls = 0; state.strikes = 0;
        state.bases = [{ ...state.bases[0], base: 3 }, { base: 1, player: state.away.lineup[3].player }];
      } else if (overlay && command === 'pitcher' && !state.isFinal) {
        state.pitcher = { id: 'pitcher-relief', name: '许洋', number: '26' };
        state.pitchCount = 0;
        state.home.lineup[0].player = state.pitcher;
        state.home.pitcherID = state.pitcher.id;
      } else if (!overlay && command === 'ball') { state.balls = 2; state.entries.at(-1).summary = '陈昊：坏球（2 坏 2 好）'; }
      else if (command === 'undo') { state.balls = 1; state.entries.at(-1).summary = '陈昊：界外球（1 坏 2 好）'; }
      else if (command === 'finish' || command === 'expire') { state.isFinal = true; state.batterOrder = null; state.endedAt = Date.now() - (command === 'expire' ? 3_585_000 : 0); }
      else if (command === 'reopen') { state.isFinal = false; state.batterOrder = overlay ? state.away.lineup.find(row => row.player.id === state.batter?.id)?.order ?? null : 4; state.endedAt = null; }
      else continue;
      await publish();
      if (overlay && command !== 'undo') history.push(before);
      console.log('示例已更新');
    }
    await close();
  }
} catch (error) { console.error('示例检查失败：' + error.message); await close(); process.exitCode = 1; }
