import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createLiveServer, BASE, HOUR } from '../server.mjs';
import { snapshot } from './fixture.mjs';

async function setup(t, options = {}) {
  let clock = Date.now();
  const app = createLiveServer({ now: () => clock, ...options });
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${app.server.address().port}`;
  t.after(() => new Promise(resolve => app.server.close(resolve)));
  const token = randomBytes(32).toString('hex');
  async function request(method, path = '/api/sessions', body, secret = token) {
    const r = await fetch(origin + BASE + path, { method, headers: { ...(secret ? { Authorization: 'Bearer ' + secret } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
    return { status: r.status, headers: r.headers, data: r.headers.get('content-type')?.includes('json') ? await r.json() : await r.text() };
  }
  const create = async (s = snapshot(), extra = {}) => request('POST', undefined, { requestID: randomUUID(), createdAt: clock, snapshot: s, ...extra });
  return { ...app, origin, request, create, token, now: () => clock, advance: ms => { clock += ms; } };
}
test('creation, conditional reads, updates, heartbeat and no public write permission', async t => {
  const a = await setup(t); const created = await a.create(); assert.equal(created.status, 201);
  const path = '/api/sessions/' + created.data.code;
  const read = await a.request('GET', path, undefined, null);
  assert.equal(read.data.snapshot.batter.number, '00'); assert.equal(read.headers.get('cache-control'), 'no-store');
  assert.equal(JSON.stringify(read.data).includes(a.token), false);
  assert.equal((await a.request('PUT', path, snapshot(2), null)).status, 401);
  assert.equal((await a.request('DELETE', path, undefined, 'f'.repeat(64))).status, 403);
  assert.equal((await a.request('GET', path + '?since=1')).data.unchanged, true);
  a.advance(10000); const heartbeat = await a.request('POST', path);
  assert.equal(heartbeat.data.revision, 1); assert.equal(heartbeat.data.lastSeen, a.now());
  const next = snapshot(2); next.balls = 2;
  assert.equal((await a.request('PUT', path, next)).status, 200);
  assert.equal((await a.request('GET', path)).data.snapshot.balls, 2);
});
test('idempotency, out-of-order requests and atomic undo remove original records', async t => {
  const a = await setup(t); const requestID = randomUUID(); const created = await a.create(snapshot(), { requestID });
  assert.equal((await a.create(snapshot(), { requestID })).data.code, created.data.code); assert.equal(a.count(), 1);
  const path = '/api/sessions/' + created.data.code;
  const updated = snapshot(2); updated.entries.pop(); updated.balls = 0;
  assert.equal((await a.request('PUT', path, updated)).status, 200);
  assert.equal((await a.request('PUT', path, updated)).status, 200);
  assert.equal((await a.request('PUT', path, snapshot())).status, 409);
  assert.equal((await a.request('PUT', path, snapshot(2))).status, 409);
  const read = (await a.request('GET', path)).data.snapshot;
  assert.equal(read.entries.length, 3); assert.equal(read.balls, 0); assert.equal(read.revision, 2);
});
test('batting order follows corrected snapshots, undo and v1 snapshots without the optional field', async t => {
  const a = await setup(t); const c = await a.create(); const path = '/api/sessions/' + c.data.code;
  assert.equal((await a.request('GET', path)).data.snapshot.batterOrder, 4);
  const corrected = snapshot(2); corrected.batterOrder = 7; corrected.batter.name = '更正打者'; corrected.batter.number = '00/0';
  assert.equal((await a.request('PUT', path, corrected)).status, 200);
  assert.deepEqual((await a.request('GET', path)).data.snapshot, corrected);
  assert.equal((await a.request('PUT', path, snapshot(3))).status, 200);
  assert.equal((await a.request('GET', path)).data.snapshot.batterOrder, 4);
  const legacy = snapshot(4); delete legacy.batterOrder;
  assert.equal((await a.request('PUT', path, legacy)).status, 200);
  assert.deepEqual((await a.request('GET', path)).data.snapshot, legacy);
  const unknown = snapshot(5); unknown.batterOrder = null;
  assert.equal((await a.request('PUT', path, unknown)).status, 200);
});
test('invalid batting orders cannot replace the last valid public snapshot', async t => {
  const a = await setup(t); const c = await a.create(); const path = '/api/sessions/' + c.data.code;
  for (const value of [0, -1, 1.5, '4', true, 1000]) {
    assert.equal((await a.request('PUT', path, { ...snapshot(2), batterOrder: value })).status, 400);
  }
  assert.equal((await a.request('PUT', path, { ...snapshot(2), batter: null })).status, 400);
  assert.equal((await a.request('PUT', path, { ...snapshot(2), isFinal: true, endedAt: a.now() })).status, 400);
  assert.deepEqual((await a.request('GET', path)).data.snapshot, snapshot());
  const final = { ...snapshot(2), isFinal: true, endedAt: a.now(), batterOrder: null };
  assert.equal((await a.request('PUT', path, final)).status, 200);
  assert.equal((await a.request('GET', path)).data.snapshot.batterOrder, null);
});
test('completion expires exactly one hour after end, revisions/heartbeats cannot extend it or resurrect it', async t => {
  const a = await setup(t); const c = await a.create(); const path = '/api/sessions/' + c.data.code;
  const final = snapshot(2); final.isFinal = true; final.batterOrder = null; final.endedAt = a.now() - 10000;
  const end = await a.request('PUT', path, final); const expiry = end.data.expiresAt;
  a.advance(20000); final.revision = 3; final.endedAt = a.now();
  assert.equal((await a.request('PUT', path, final)).data.expiresAt, expiry);
  assert.equal((await a.request('POST', path)).data.expiresAt, expiry);
  a.advance(expiry - a.now()); a.sweep(); assert.equal(a.count(), 0);
  assert.equal((await a.request('GET', path)).status, 404);
  assert.equal((await a.request('PUT', path, snapshot(4))).status, 410);
  assert.equal((await a.request('POST', path)).status, 410);
  assert.equal((await a.request('GET', '/' + c.data.code)).status, 410);
});
test('reopening within one hour retains URL; offline idle session expires without client cooperation', async t => {
  const a = await setup(t); const c = await a.create(); const path = '/api/sessions/' + c.data.code;
  const final = snapshot(2); final.isFinal = true; final.batterOrder = null; final.endedAt = a.now(); await a.request('PUT', path, final);
  a.advance(120000); assert.equal((await a.request('PUT', path, snapshot(3))).data.expiresAt, a.now() + HOUR);
  a.advance(HOUR); a.sweep(); assert.equal(a.count(), 0); assert.equal((await a.request('GET', path)).status, 404);
});
test('late terminal upload immediately deletes; manual deletion including lost creation response is idempotent', async t => {
  const a = await setup(t); const requestID = randomUUID(); const c = await a.create(snapshot(), { requestID }); const path = '/api/sessions/' + c.data.code;
  const final = snapshot(2); final.isFinal = true; final.batterOrder = null; final.endedAt = a.now() - HOUR;
  assert.equal((await a.request('PUT', path, final)).status, 410); assert.equal(a.count(), 0);
  await a.create(snapshot(), { requestID: randomUUID() });
  const lostID = randomUUID(); const lost = await a.create(snapshot(), { requestID: lostID });
  assert.equal((await a.request('DELETE', undefined, { requestID: lostID })).status, 200);
  assert.equal((await a.request('DELETE', undefined, { requestID: lostID })).status, 200);
  assert.equal((await a.request('GET', '/api/sessions/' + lost.data.code)).status, 404);
});
test('invalid schema, body limits, old creation, capacity and create rate limit', async t => {
  const a = await setup(t, { maxSessions: 1 });
  assert.equal((await a.create({ ...snapshot(), teams: ['private roster'] })).status, 400);
  assert.equal((await a.create(snapshot(), { createdAt: a.now() - HOUR })).status, 400);
  const invalid = snapshot(); invalid.home.runs = 900; assert.equal((await a.create(invalid)).status, 400);
  assert.equal((await a.create({ ...snapshot(), notice: 'x'.repeat(2 * 1024 * 1024) })).status, 413);
  assert.equal((await a.create()).status, 201); assert.equal((await a.create()).status, 503);
  const b = await setup(t, { createLimit: 1 }); await b.create(); assert.equal((await b.create()).status, 429);
});
test('startup removes expired disk content and secure_delete clears payload bytes', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'bm-live-')); t.after(() => rmSync(directory, { recursive: true, force: true }));
  const dbPath = join(directory, 'live.sqlite'); const a = await setup(t, { dbPath });
  const s = snapshot(); s.home.name = 'UNIQUE_PAYLOAD_ERASE_CHECK'; await a.create(s);
  assert.equal(readFileSync(dbPath).includes('UNIQUE_PAYLOAD_ERASE_CHECK'), true);
  // Separate service instance simulates a restart after the deadline; no requests needed to trigger expiry.
  const b = createLiveServer({ dbPath, now: () => a.now() + HOUR });
  assert.equal(b.count(), 0); assert.equal(readFileSync(dbPath).includes('UNIQUE_PAYLOAD_ERASE_CHECK'), false);
  b.server.emit('close');
});
test('base path, nested viewer, security headers and script routes', async t => {
  const a = await setup(t); const c = await a.create();
  for (const p of ['', '/', '/' + c.data.code, '/app.js', '/style.css']) assert.equal((await a.request('GET', p)).status, 200);
  assert.equal((await a.request('GET', '/api/sessions')).status, 404); // No game directory.
  assert.equal((await a.request('GET', '/../../server.mjs')).status, 404);
  assert.match((await a.request('GET', '/' + c.data.code)).headers.get('content-security-policy'), /frame-ancestors 'none'/);
});
test('five concurrent games / 250 spectators with conditional refresh and writes', async t => {
  const a = await setup(t); const codes = [];
  for (let i = 0; i < 5; i++) codes.push((await a.create({ ...snapshot(), gameID: 'game-' + i })).data.code);
  const begin = performance.now();
  const results = await Promise.all(Array.from({ length: 250 }, (_, i) => a.request('GET', '/api/sessions/' + codes[i % 5] + '?since=1')));
  assert.ok(results.every(r => r.status === 200 && r.data.unchanged));
  const writes = await Promise.all(codes.map((code, i) => a.request('PUT', '/api/sessions/' + code, { ...snapshot(2), gameID: 'game-' + i })));
  assert.ok(writes.every(r => r.status === 200 && r.data.revision === 2));
  t.diagnostic(`250 simultaneous conditional reads: ${Math.round(performance.now() - begin)} ms; errors: 0`);
});

test('long game snapshot stays atomic and strictly rejects nested/private fields', async t => {
  const a = await setup(t);
  const long = snapshot();
  long.entries = Array.from({ length: 1000 }, (_, i) => ({ ...long.entries[0], id: 'pa-' + i, appearanceID: 'pa-' + i, summary: '长比赛逐打席记录 ' + i }));
  const c = await a.create(long); assert.equal(c.status, 201);
  const path = '/api/sessions/' + c.data.code;
  assert.equal((await a.request('GET', path)).data.snapshot.entries.length, 1000);
  const invalid = structuredClone(long); invalid.revision = 2; invalid.batter.privateEmail = 'not-for-publishing';
  assert.equal((await a.request('PUT', path, invalid)).status, 400);
  assert.equal((await a.request('GET', path)).data.revision, 1);
  const other = { ...long, revision: 2, gameID: 'another-game' };
  assert.equal((await a.request('PUT', path, other)).status, 409);
});

test('slow overlapping upload cannot overwrite a newer revision or revive a deleted row', async t => {
  const { request: httpRequest } = await import('node:http');
  const a = await setup(t); const c = await a.create(); const path = '/api/sessions/' + c.data.code;
  async function slowUpload(during) {
    const payload = JSON.stringify(snapshot(2));
    let outgoing;
    const response = new Promise((resolve, reject) => {
      outgoing = httpRequest(a.origin + BASE + path, { method: 'PUT', headers: { Authorization: 'Bearer ' + a.token, 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) } }, r => { r.resume(); r.on('end', () => resolve(r.statusCode)); });
      outgoing.on('error', reject); outgoing.write(payload.slice(0, 20));
    });
    await new Promise(resolve => setTimeout(resolve, 10));
    await during(); outgoing.end(payload.slice(20)); return response;
  }
  assert.equal(await slowUpload(() => a.request('PUT', path, snapshot(3))), 409);
  assert.equal((await a.request('GET', path)).data.revision, 3);
  assert.equal(await slowUpload(() => a.request('DELETE', path)), 410);
  assert.equal((await a.request('GET', path)).status, 404);
});
