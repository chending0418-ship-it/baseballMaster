import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createCollaborationServer, CollaborationStore, BASE } from '../server.mjs';
import { uiSnapshot } from '../../live/test/fixture.mjs';

test('stable team sources start empty, follow one match, switch explicitly and stay separate from text live', async t => {
  let now = Date.now();
  const app = createCollaborationServer({ now: () => now });
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    app.server.closeAllConnections();
    await new Promise(resolve => app.server.close(resolve));
    app.store.close();
  });
  const origin = 'http://127.0.0.1:' + app.server.address().port;
  async function request(method, path, body, key) {
    const headers = { 'X-BM-Protocol': '1', 'Content-Type': 'application/json' };
    if (key) headers.Authorization = 'Bearer ' + key;
    const res = await fetch(origin + BASE + path, { method, headers, body: body && JSON.stringify(body) });
    return { status: res.status, data: await res.json() };
  }
  const token = () => randomBytes(32).toString('base64url');
  const admin = token();
  const other = token();
  const op = body => ({ operationID: randomUUID(), ...body });
  await request('POST', '/devices', { label: 'admin' }, admin);
  await request('POST', '/devices', { label: 'other' }, other);
  const { data: { teamID } } = await request('POST', '/teams', op({ name: '测试队', nickname: '记录员' }), admin);
  const route = '/teams/' + teamID + '/broadcast';
  const access = () => request('GET', route, undefined, admin);
  assert.equal((await request('GET', route, undefined, other)).status, 404);
  const { data: { code } } = await access();
  const read = (suffix = '') => request('GET', '/broadcast/' + code + suffix);
  assert.equal((await read()).data.active, false);
  assert.equal((await access()).data.code, code);

  const a = uiSnapshot(), b = uiSnapshot();
  a.gameID = randomUUID(); b.gameID = randomUUID();
  const upload = (snapshot, extra = {}) => request('PUT', route, { snapshot, ...extra }, admin);
  assert.equal((await upload({ ...a, hasStarted: false })).status, 409);
  assert.equal((await upload(a, { pitching: { strikes: 17, strikeouts: 2 } })).data.selected, true);
  const first = (await read()).data;
  assert.equal(first.snapshot.gameID, a.gameID);
  assert.equal(first.box.pitching[0].values.strikes, 17);
  assert.equal((await request('GET', '/live/' + code)).status, 404);
  assert.equal((await read('?since=' + encodeURIComponent(first.revision))).data.snapshot, undefined);
  assert.equal((await upload({ ...a, strikes: 2 })).status, 409);
  assert.equal((await upload({ ...a, revision: 2, strikes: 2 })).data.selected, true);
  assert.equal((await read()).data.snapshot.strikes, 2);
  assert.equal((await upload(b)).data.selected, false);
  assert.equal((await upload(b, { expectedGameID: randomUUID() })).data.selected, false);
  assert.equal((await upload(b, { expectedGameID: a.gameID.toUpperCase() })).data.selected, true);
  assert.equal((await read()).data.snapshot.gameID, b.gameID);
  assert.equal((await upload({ ...a, revision: 3 })).data.selected, false);

  const final = { ...b, revision: 2, batterOrder: null, isFinal: true, endedAt: now };
  assert.equal((await upload(final)).data.selected, true);
  now += 59_000;
  assert.equal((await upload({ ...final, revision: 3 })).data.selected, true);
  assert.equal((await read()).data.snapshot.isFinal, true);
  now += 1001;
  assert.equal((await read()).data.active, false);
  assert.equal((await access()).data.code, code);
  assert.equal((await upload({ ...a, revision: 4 })).data.selected, false);
  const c = { ...a, gameID: randomUUID() };
  assert.equal((await upload(c)).data.selected, true);

  const { data: rotated } = await request('POST', route + '/rotate', { expectedCode: code }, admin);
  assert.notEqual(rotated.code, code);
  assert.equal((await read()).status, 404);
  assert.equal((await request('GET', '/broadcast/' + rotated.code)).data.snapshot.gameID, c.gameID);
});

test('restart keeps the selected match and its revision while the live payload stays temporary', t => {
  const dir = mkdtempSync(join(tmpdir(), 'team-broadcast-'));
  t.after(() => rmSync(dir, { force: true, recursive: true }));
  const database = join(dir, 'team.sqlite');
  const key = randomBytes(32).toString('base64url');
  let now = Date.now();
  const options = { database, now: () => now };
  const old = { ...uiSnapshot(), gameID: randomUUID() };
  const selected = { ...old, gameID: randomUUID(), revision: 2, balls: 1 };
  const next = { ...old, gameID: randomUUID() };
  const first = new CollaborationStore(options);
  let code, teamID, deviceID, cursor;
  try {
    ({ deviceID } = first.register(key, { label: '测试设备' }));
    ({ teamID } = first.createTeam(deviceID, { operationID: randomUUID(), name: '固定链接球队', nickname: '记录员' }));
    ({ code } = first.broadcasts.sources(teamID, deviceID));
    assert.equal(first.broadcasts.publish(teamID, deviceID, { snapshot: old }).selected, true);
    assert.equal(first.broadcasts.publish(teamID, deviceID, { snapshot: selected, expectedGameID: old.gameID }).selected, true);
    cursor = first.broadcasts.read(code).revision;
  } finally { first.close(); }

  const reopened = new CollaborationStore(options);
  try {
    assert.deepEqual(reopened.broadcasts.sources(teamID, deviceID), { code, activeGameID: selected.gameID, activeMatch: null });
    assert.equal(reopened.broadcasts.read(code).active, false);
    const put = (snapshot, fields = {}) => reopened.broadcasts.publish(teamID, deviceID, { snapshot, ...fields });
    assert.equal(put(old).selected, false);
    assert.equal(put(next).selected, false);
    assert.equal(put(old, { expectedGameID: old.gameID }).selected, false);
    assert.throws(() => put({ ...selected, revision: 1 }), error => error.code === 'broadcast_revision');
    assert.throws(() => put({ ...selected, balls: 0 }), error => error.code === 'broadcast_revision');
    assert.equal(reopened.broadcasts.read(code).active, false);
    assert.equal(put(selected).selected, true);
    const resumed = reopened.broadcasts.read(code, cursor);
    assert.notEqual(resumed.revision, cursor);
    assert.equal(resumed.snapshot.gameID, selected.gameID);
    assert.equal(resumed.snapshot.balls, 1);

    const final = { ...selected, revision: 3, batterOrder: null, isFinal: true, endedAt: now };
    assert.equal(put(final).selected, true);
  } finally { reopened.close(); }

  now += 59_000;
  const afterFinal = new CollaborationStore(options);
  try {
    const put = snapshot => afterFinal.broadcasts.publish(teamID, deviceID, { snapshot });
    const final = { ...selected, revision: 3, batterOrder: null, isFinal: true, endedAt: now - 59_000 };
    assert.equal(put(final).selected, true);
    now += 1_001;
    assert.equal(afterFinal.broadcasts.read(code).active, false);
    assert.equal(put(old).selected, false);
    assert.equal(put(next).selected, true);
    assert.equal(afterFinal.broadcasts.publish(teamID, deviceID, { snapshot: old, expectedGameID: next.gameID }).selected, true);
    assert.equal(afterFinal.broadcasts.read(code).snapshot.gameID, old.gameID);
  } finally { afterFinal.close(); }
});

test('pre-upgrade links wait for explicit selection instead of airing the first reconnecting match', t => {
  const dir = mkdtempSync(join(tmpdir(), 'team-broadcast-legacy-'));
  t.after(() => rmSync(dir, { force: true, recursive: true }));
  const database = join(dir, 'team.sqlite');
  const legacy = new CollaborationStore({ database });
  let code, teamID, deviceID;
  try {
    ({ deviceID } = legacy.register(randomBytes(32).toString('base64url'), { label: '旧版设备' }));
    ({ teamID } = legacy.createTeam(deviceID, { operationID: randomUUID(), name: '原有球队', nickname: '记录员' }));
    ({ code } = legacy.broadcasts.sources(teamID, deviceID));
    legacy.db.exec(`DROP TABLE team_broadcast_selection; DROP TABLE team_broadcast_displaced;
      DROP TABLE team_broadcast_confirmation;
      DELETE FROM metadata WHERE key='teamBroadcastSelectionV1';`);
  } finally { legacy.close(); }

  const upgraded = new CollaborationStore({ database });
  try {
    const old = { ...uiSnapshot(), gameID: randomUUID() };
    const selected = { ...old, gameID: randomUUID() };
    assert.equal(upgraded.broadcasts.sources(teamID, deviceID).code, code);
    assert.equal(upgraded.broadcasts.publish(teamID, deviceID, { snapshot: old }).selected, false);
    assert.equal(upgraded.broadcasts.read(code).active, false);
    assert.equal(upgraded.broadcasts.publish(teamID, deviceID, { snapshot: selected, expectedGameID: null }).selected, true);
    assert.equal(upgraded.broadcasts.read(code).snapshot.gameID, selected.gameID);
    assert.equal(upgraded.broadcasts.publish(teamID, deviceID, { snapshot: selected }).selected, true);
  } finally { upgraded.close(); }
});
