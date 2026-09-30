// Explicit production smoke using synthetic fixtures only. Never writes publisher tokens.
// LIVE_BASE=https://baseballmaster.cc/livestreaming/novideo node test/deployment.mjs
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { snapshot } from './fixture.mjs';
const base = process.env.LIVE_BASE;
assert.ok(base, 'Set LIVE_BASE explicitly');
const token = randomBytes(32).toString('hex');
let code;
const checks = [];
const endpoint = () => `${base}/api/sessions${code ? '/' + code : ''}`;
async function write(method, body, authorization = token) {
  return fetch(endpoint(), { method, headers: { 'Content-Type': 'application/json', ...(authorization ? { Authorization: `Bearer ${authorization}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
}
async function read() { return fetch(endpoint()); }
try {
  const state = snapshot(); state.gameID = randomUUID();
  const created = await write('POST', { requestID: randomUUID(), createdAt: Date.now(), snapshot: state });
  assert.equal(created.status, 201); code = (await created.json()).code;
  const publicRead = await read(); assert.equal(publicRead.status, 200);
  assert.match(publicRead.headers.get('cache-control'), /no-store/);
  assert.equal(publicRead.headers.get('referrer-policy'), 'no-referrer');
  assert.match(publicRead.headers.get('x-robots-tag'), /noindex/);
  const envelope = await publicRead.json(); assert.deepEqual(envelope.snapshot, state);
  assert.ok(!JSON.stringify(envelope).includes(token));
  checks.push('HTTPS publish/read and no-store/no-referrer/noindex');
  assert.equal((await write('PUT', { ...state, revision: 2 }, null)).status, 401);
  assert.equal((await write('DELETE', undefined, randomBytes(32).toString('hex'))).status, 403);
  assert.deepEqual((await (await read()).json()).snapshot, state);
  checks.push('anonymous and wrong-token writes rejected');
  const burst = await Promise.all(Array.from({ length: 250 }, async () => {
    const response = await read(); assert.equal(response.status, 200); return response.json();
  }));
  assert.ok(burst.every(value => value.snapshot.revision === state.revision));
  checks.push('250 concurrent public reads');
  if (process.env.LIVE_RESTART_SSH_HOST) {
    await promisify(execFile)('ssh', ['-o', 'BatchMode=yes', process.env.LIVE_RESTART_SSH_HOST, 'systemctl restart baseballmaster-live.service'], { timeout: 20_000 });
    for (let attempt = 0; attempt < 15; attempt++) {
      const health = await fetch(`${base}/health`);
      if (health.ok) break;
      await new Promise(resolve => setTimeout(resolve, 500));
    }
    assert.deepEqual((await (await read()).json()).snapshot, state);
    checks.push('service process restart preserves current link');
  }
  state.revision++; state.isFinal = true; state.batterOrder = null;
  state.endedAt = Date.now() - 3_600_000 + 8000;
  assert.equal((await write('PUT', state)).status, 200);
  const deadline = (await (await read()).json()).expiresAt;
  assert.ok(Math.abs(deadline - (state.endedAt + 3_600_000)) < 1);
  // A heartbeat must not extend the final-match deadline.
  assert.equal((await write('POST')).status, 200);
  assert.equal((await (await read()).json()).expiresAt, deadline);
  await new Promise(resolve => setTimeout(resolve, Math.max(0, deadline - Date.now()) + 1500));
  assert.equal((await read()).status, 404);
  assert.equal((await fetch(`${base}/${code}`)).status, 410);
  code = undefined;
  checks.push('final deadline fixed; expired API 404 and viewer 410');
  for (const path of ['/TODO.md', '/.git/config', '/server.mjs', '/live.sqlite', '/livestreaming/novideo/live.sqlite']) {
    assert.equal((await fetch(new URL(path, base))).status, 404);
  }
  checks.push('internal files and database not public');
  console.log(JSON.stringify({ passed: true, checks }, null, 2));
} finally {
  if (code) await write('DELETE');
}
