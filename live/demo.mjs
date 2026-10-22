// Synthetic data only. The publishing token stays in this process and is never printed or saved.
import { randomBytes, randomUUID } from 'node:crypto';
import { createInterface } from 'node:readline';
import { snapshot } from './test/fixture.mjs';
const base = (process.env.LIVE_BASE || 'http://127.0.0.1:8088/baseballmaster/live').replace(/\/$/, '');
const token = randomBytes(32).toString('hex');
let state = snapshot(), code, timer;
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
    state.isFinal = true; state.endedAt = Date.now(); await publish();
    await close();
    if ((await fetch(base + '/api/sessions/' + code)).status !== 404) throw new Error('Deletion failed');
    console.log('PASS: create → read → update → undo → finish → delete');
  } else {
    console.log(`示例观赛网址：${base}/${code}`);
    console.log('命令：ball / undo / finish / reopen / expire（15 秒后删除）/ quit');
    timer = setInterval(() => send('POST').catch(() => {}), 10000);
    const input = createInterface({ input: process.stdin });
    for await (const line of input) {
      const command = line.trim();
      if (command === 'quit') { await close(); input.close(); break; }
      if (command === 'ball') { state.balls = 2; state.entries.at(-1).summary = '陈昊：坏球（2 坏 2 好）'; }
      else if (command === 'undo') { state.balls = 1; state.entries.at(-1).summary = '陈昊：界外球（1 坏 2 好）'; }
      else if (command === 'finish' || command === 'expire') { state.isFinal = true; state.endedAt = Date.now() - (command === 'expire' ? 3_585_000 : 0); }
      else if (command === 'reopen') { state.isFinal = false; state.endedAt = null; }
      else continue;
      await publish(); console.log('示例已更新');
    }
    await close();
  }
} catch (error) { console.error('示例检查失败：' + error.message); await close(); process.exitCode = 1; }
