import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { CollaborationStore, createCollaborationServer, BASE } from '../server.mjs';
import { fixture } from './match-fixture.mjs';
const secret = () => randomBytes(32).toString('base64url');
const op = fields => ({ operationID: randomUUID(), ...fields });
const player = (name = '同名队员', number = '00') => ({ id: randomUUID(), sourceID: randomUUID(), chineseName: name, englishName: '', numbers: [number], archived: false });
const rejected = (f, code) => assert.throws(f, error => error.code === code);
function setup(t) {
  const store = new CollaborationStore(); t.after(() => store.close());
  const admin = store.register(secret(), { label: 'admin' }).deviceID;
  const team = store.createTeam(admin, op({ name: '批量球队', nickname: '管理员' })).teamID;
  function member(role) {
    const device = store.register(secret(), { label: role }).deviceID, token = secret();
    store.invite(team, admin, op({ token, role })); store.redeem(device, op({ token, nickname: role })); return device;
  }
  const read = () => store.roster.read(team, admin).team;
  const batch = players => op({ kind: 'players', baseRevision: read().revision, players });
  return { store, admin, team, member, read, batch };
}

test('one atomic batch appends distinct identities and preserves 0/00, old roster and match payload', t => {
  const a = setup(t), original = player('原队员', '0');
  a.store.roster.update(a.team, a.admin, op({ kind: 'player', baseRevision: a.read().revision, player: original }));
  const saved = a.store.games.create(a.team, a.admin, op(fixture(true)));
  const beforeGame = a.store.games.document(a.team, saved.id, a.admin), before = a.read();
  const additions = [player('同名队员', '0'), player('同名队员', '00')], body = a.batch(additions);
  const result = a.store.roster.update(a.team, a.admin, body);
  assert.equal(result.team.revision, before.revision + 1);
  assert.deepEqual(result.team.players, [...before.players, ...additions]);
  assert.deepEqual(a.store.games.document(a.team, saved.id, a.admin), beforeGame);
  assert.deepEqual(a.store.roster.update(a.team, a.admin, body), result);
  assert.equal(a.read().players.length, 3);
  rejected(() => a.store.roster.update(a.team, a.admin, { ...body, players: [player()] }), 'operation_conflict');
  assert.equal(a.store.db.prepare("SELECT count(*) AS n FROM audit WHERE action='playersImported'").get().n, 1);
});

test('admin and scorer may import; members, strangers and revoked edit roles cannot replay success', t => {
  const a = setup(t), scorer = a.member('scorer'), member = a.member('member');
  const body = a.batch([player()]);
  for (const actor of [member]) rejected(() => a.store.roster.update(a.team, actor, body), 'forbidden');
  const outsider = a.store.register(secret(), { label: '外队' }).deviceID;
  rejected(() => a.store.roster.update(a.team, outsider, body), 'not_found');
  const result = a.store.roster.update(a.team, scorer, body); assert.equal(result.team.players.length, 1);
  a.store.db.prepare("UPDATE members SET role='member' WHERE team_id=? AND role='scorer'").run(a.team);
  rejected(() => a.store.roster.update(a.team, scorer, body), 'forbidden');
  assert.equal(a.read().players.length, 1);
});

test('any invalid row, existing identity or repeated batch identity rejects every row and revision', t => {
  const a = setup(t), existing = player();
  a.store.roster.update(a.team, a.admin, op({ kind: 'player', baseRevision: a.read().revision, player: existing }));
  const duplicate = player(), before = a.read();
  const invalidRows = [
    [player(), { ...player(), numbers: ['01'] }], [player(), { ...player(), chineseName: '长'.repeat(81) }],
    [player(), { ...player(), englishName: 'x'.repeat(81) }], [player(), { ...player(), archived: true }],
    [player(), { ...player(), numbers: ['00', '00'] }], [player(), { ...player(), numbers: Array.from({length: 11}, (_,i) => String(i)) }],
    [player(), { ...player(), chineseName: '', englishName: '', numbers: [] }],
    [player(), existing], [duplicate, { ...duplicate }], [duplicate, { ...player(), sourceID: duplicate.sourceID }], []
  ];
  for (const players of invalidRows) {
    assert.throws(() => a.store.roster.update(a.team, a.admin, a.batch(players)));
    assert.deepEqual(a.read(), before);
  }
});

test('CAS conflict never mutates and a newly reviewed operation can import the same stable player identities', t => {
  const a = setup(t), body = a.batch([player(), player()]);
  a.store.roster.update(a.team, a.admin, a.batch([player('另一设备')]));
  const before = a.read(); rejected(() => a.store.roster.update(a.team, a.admin, body), 'roster_conflict');
  assert.deepEqual(a.read(), before);
  const reviewed = op({ ...body, operationID: randomUUID(), baseRevision: before.revision });
  const result = a.store.roster.update(a.team, a.admin, reviewed);
  assert.deepEqual(result.team.players.slice(-2), body.players);
  assert.equal(result.team.revision, before.revision + 1);
});

test('active and total roster capacities are checked for the whole batch before committing', t => {
  const a = setup(t), players = Array.from({length: 199}, () => player());
  a.store.roster.update(a.team, a.admin, a.batch(players));
  const before = a.read(); rejected(() => a.store.roster.update(a.team, a.admin, a.batch([player(), player()])), 'capacity');
  assert.deepEqual(a.read(), before);
  const profile = JSON.parse(a.store.db.prepare('SELECT profile FROM team_profiles WHERE team_id=?').get(a.team).profile);
  profile.players = Array.from({length: 1999}, () => ({...player(), archived: true}));
  a.store.db.prepare('UPDATE team_profiles SET profile=? WHERE team_id=?').run(JSON.stringify(profile), a.team);
  const archived = a.read(); rejected(() => a.store.roster.update(a.team, a.admin, a.batch([player(), player()])), 'capacity');
  assert.deepEqual(a.read(), archived);
  assert.equal(a.store.roster.update(a.team, a.admin, a.batch([player()])).team.players.length, 2000);
});

test('HTTP advertises bulk support and one PATCH returns an idempotent whole batch', async t => {
  const app = createCollaborationServer({requestsPerMinute: 1000});
  app.server.listen(0, '127.0.0.1'); await once(app.server, 'listening');
  t.after(async () => { app.server.closeAllConnections(); await new Promise(resolve => app.server.close(resolve)); app.store.close(); });
  const credential = secret(), base = `http://127.0.0.1:${app.server.address().port}${BASE}`;
  const request = async (path, method='GET', body) => {
    const r = await fetch(base+path, {method, headers: {'Content-Type':'application/json','X-BM-Protocol':'1',Authorization:`Bearer ${credential}`}, body: body ? JSON.stringify(body) : undefined});
    return {status:r.status,data:await r.json()};
  };
  assert.ok((await request('/capabilities')).data.capabilities.includes('bulkRosterImport'));
  await request('/devices','POST',{label:'批量测试'});
  const team = (await request('/teams','POST',op({name:'球队',nickname:'管理员'}))).data.teamID;
  const revision = (await request(`/teams/${team}`)).data.team.revision;
  const body = op({kind:'players',baseRevision:revision,players:[player(),player()]});
  const saved = await request(`/teams/${team}/roster`,'PATCH',body);
  assert.equal(saved.status,200); assert.equal(saved.data.team.players.length,2); assert.equal(saved.data.team.revision,revision+1);
  assert.deepEqual(await request(`/teams/${team}/roster`,'PATCH',body),saved);
});
