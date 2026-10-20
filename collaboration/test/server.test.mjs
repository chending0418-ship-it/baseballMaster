import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { createCollaborationServer, CollaborationStore, BASE, INVITE_TTL } from '../server.mjs';
import { can, ROLES } from '../permissions.mjs';
const token = () => randomBytes(32).toString('base64url');
const operation = fields => ({ operationID: randomUUID(), ...fields });
async function setup(t, options = {}) {
  let now = 1_800_000_000_000;
  const app = createCollaborationServer({ now: () => now, requestsPerMinute: 1000, ...options });
  app.server.listen(0, '127.0.0.1'); await once(app.server, 'listening');
  t.after(async () => { app.server.closeAllConnections(); await new Promise(r => app.server.close(r)); app.store.close(); });
  const request = async (key, method, path, body, headers = {}) => {
    const response = await fetch(`http://127.0.0.1:${app.server.address().port}${BASE}${path}`, {
      method, headers: { 'Content-Type': 'application/json', 'X-BM-Protocol': '1', Authorization: `Bearer ${key}`, ...headers },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    return { status: response.status, data: await response.json(), headers: response.headers };
  };
  const device = async () => { const key = token(); assert.equal((await request(key, 'POST', '/devices', { label: '测试设备' })).status, 200); return key; };
  const create = (key, name = '同名球队') => request(key, 'POST', '/teams', operation({ name, nickname: '管理员' }));
  const joinTeam = async (admin, teamID, role = 'member') => {
    const key = await device(), invitation = token();
    assert.equal((await request(admin, 'POST', `/teams/${teamID}/invitations`, operation({ token: invitation, role }))).status, 200);
    const body = operation({ token: invitation, nickname: '同名成员' });
    const joined = await request(key, 'POST', '/invitations/redeem', body);
    assert.equal(joined.status, 200);
    return { key, memberID: joined.data.memberID, body };
  };
  return { ...app, request, device, create, joinTeam, advance: ms => { now += ms; } };
}

test('fixed role matrix keeps all team reads available to scorers and denies unknown actions', () => {
  for (const role of ROLES) for (const action of ['readGame', 'readTeam', 'readStatistics', 'exportReport']) assert.equal(can(role, action), true);
  for (const action of ['invite', 'manageMembers', 'recover', 'deleteGame', 'forceHandoff']) {
    assert.equal(can('admin', action), true); assert.equal(can('scorer', action), false); assert.equal(can('member', action), false);
  }
  assert.equal(can('scorer', 'editGame', { currentWriter: true }), false);
  assert.equal(can('scorer', 'editGame', { currentWriter: true, responsible: true }), true);
  assert.equal(can('admin', 'editGame'), false);
  assert.equal(can('admin', 'editGame', { currentWriter: true }), true);
  assert.equal(can('scorer', 'manageHistoricalAccess', { responsible: true }), true);
  assert.equal(can('member', 'editGame', { currentWriter: true, responsible: true }), false);
  assert.equal(can('superuser', 'readTeam'), false); assert.equal(can('admin', 'unknown'), false);
});

test('device enrollment and creation retries are stable; same-named teams remain distinct', async t => {
  const a = await setup(t), key = await a.device();
  const first = await a.request(key, 'POST', '/devices', { label: '设备' });
  assert.deepEqual((await a.request(key, 'POST', '/devices', { label: '设备' })).data, first.data);
  const body = operation({ name: '同名球队', nickname: '管理员' });
  const team = await a.request(key, 'POST', '/teams', body);
  assert.deepEqual((await a.request(key, 'POST', '/teams', body)).data, team.data);
  assert.equal((await a.request(key, 'POST', '/teams', { ...body, name: '另一内容' })).status, 409);
  const second = await a.create(key);
  assert.notEqual(second.data.teamID, team.data.teamID);
  assert.equal((await a.request(key, 'GET', '/teams')).data.teams.length, 2);
});

test('invitation grants only its stored role; concurrent redemption and lost response are safe', async t => {
  const a = await setup(t), admin = await a.device(), b = await a.device(), c = await a.device();
  const team = (await a.create(admin)).data.teamID, invitation = token();
  const created = await a.request(admin, 'POST', `/teams/${team}/invitations`, operation({ token: invitation, role: 'scorer' }));
  assert.equal(created.status, 200);
  const bodyB = operation({ token: invitation, nickname: '相同昵称', role: 'admin' });
  const bodyC = operation({ token: invitation, nickname: '相同昵称' });
  const results = await Promise.all([a.request(b, 'POST', '/invitations/redeem', bodyB), a.request(c, 'POST', '/invitations/redeem', bodyC)]);
  assert.deepEqual(results.map(r => r.status).sort(), [200, 410]);
  const winner = results[0].status === 200 ? b : c, body = winner === b ? bodyB : bodyC;
  const replay = await a.request(winner, 'POST', '/invitations/redeem', body);
  assert.equal(replay.data.role, 'scorer');
  a.advance(INVITE_TTL + 1);
  assert.deepEqual((await a.request(winner, 'POST', '/invitations/redeem', body)).data, replay.data);
  assert.equal((await a.request(winner, 'GET', `/teams/${team}`)).status, 200);
  assert.equal((await a.request(winner, 'POST', `/teams/${team}/invitations`, operation({ token: token(), role: 'member' }))).status, 403);
});

test('uninvited devices and member/scorer management cannot cross the team boundary', async t => {
  const a = await setup(t), admin = await a.device(), stranger = await a.device();
  const team = (await a.create(admin)).data.teamID;
  for (const suffix of ['', '/members', '/audit']) assert.equal((await a.request(stranger, 'GET', `/teams/${team}${suffix}`)).status, 404);
  for (const role of ['member', 'scorer']) {
    const member = await a.joinTeam(admin, team, role);
    assert.equal((await a.request(member.key, 'GET', `/teams/${team}`)).status, 200);
    assert.equal((await a.request(member.key, 'GET', `/teams/${team}/members`)).status, 403);
    assert.equal((await a.request(member.key, 'PATCH', `/teams/${team}/members/${member.memberID}`, operation({ role: 'admin', baseRevision: 1 }))).status, 403);
  }
  const otherTeam = (await a.create(stranger)).data.teamID;
  const member = await a.joinTeam(admin, team);
  assert.equal((await a.request(stranger, 'PATCH', `/teams/${otherTeam}/members/${member.memberID}`, operation({ role: 'admin', baseRevision: 1 }))).status, 404);
});

test('expiry, revocation and invalid invitations never grant membership', async t => {
  const a = await setup(t), admin = await a.device(), key = await a.device(), team = (await a.create(admin)).data.teamID;
  for (const revoke of [true, false]) {
    const invitation = token();
    const result = await a.request(admin, 'POST', `/teams/${team}/invitations`, operation({ token: invitation, role: 'member' }));
    if (revoke) assert.equal((await a.request(admin, 'DELETE', `/teams/${team}/invitations/${result.data.invitationID}`, operation({}))).status, 200);
    else a.advance(INVITE_TTL);
    assert.equal((await a.request(key, 'POST', '/invitations/redeem', operation({ token: invitation, nickname: '成员' }))).status, 410);
  }
  assert.equal((await a.request(admin, 'POST', `/teams/${team}/invitations`, operation({ token: token(), role: 'admin' }))).status, 400);
  assert.equal((await a.request(key, 'GET', '/teams')).data.teams.length, 0);
});

test('last administrator is protected; stale changes and removed member invitation retries fail', async t => {
  const a = await setup(t), admin = await a.device(), created = (await a.create(admin)).data, team = created.teamID;
  assert.equal((await a.request(admin, 'PATCH', `/teams/${team}/members/${created.memberID}`, operation({ role: 'member', baseRevision: 1 }))).data.error.code, 'last_admin');
  const member = await a.joinTeam(admin, team, 'scorer');
  assert.equal((await a.request(admin, 'PATCH', `/teams/${team}/members/${member.memberID}`, operation({ role: 'member', baseRevision: 8 }))).status, 409);
  assert.equal((await a.request(admin, 'PATCH', `/teams/${team}/members/${member.memberID}`, operation({ role: 'member', active: false, baseRevision: 1 }))).status, 200);
  assert.equal((await a.request(member.key, 'GET', `/teams/${team}`)).status, 404);
  assert.equal((await a.request(member.key, 'POST', '/invitations/redeem', member.body)).status, 404);
  assert.equal((await a.request(member.key, 'GET', '/teams')).data.teams.length, 0);
});

test('current permissions are checked before replaying an old administrative operation', async t => {
  const a = await setup(t), admin = await a.device(), created = (await a.create(admin)).data, team = created.teamID;
  const other = await a.joinTeam(admin, team);
  await a.request(admin, 'PATCH', `/teams/${team}/members/${other.memberID}`, operation({ role: 'admin', baseRevision: 1 }));
  const invitation = operation({ role: 'member', token: token() });
  assert.equal((await a.request(admin, 'POST', `/teams/${team}/invitations`, invitation)).status, 200);
  assert.equal((await a.request(other.key, 'PATCH', `/teams/${team}/members/${created.memberID}`, operation({ role: 'member', baseRevision: 1 }))).status, 200);
  assert.equal((await a.request(admin, 'POST', `/teams/${team}/invitations`, invitation)).status, 403);
});

test('durable database survives restart and never stores bearer/invitation secrets', () => {
  const directory = mkdtempSync(join(tmpdir(), 'bm-team-test-')), database = join(directory, 'teams.sqlite');
  const key = token(), invitation = token();
  let store = new CollaborationStore({ database });
  try {
    const deviceID = store.register(key, { label: '设备' }).deviceID;
    const body = operation({ name: '球队', nickname: '管理员' });
    const team = store.createTeam(deviceID, body);
    store.invite(team.teamID, deviceID, operation({ token: invitation, role: 'scorer' }));
    store.close(); store = new CollaborationStore({ database });
    assert.equal(store.authenticate(key), deviceID);
    assert.deepEqual(store.createTeam(deviceID, body), team);
    const dump = ['devices', 'invitations', 'operations', 'audit'].map(table => JSON.stringify(store.db.prepare(`SELECT * FROM ${table}`).all())).join('');
    assert.equal(dump.includes(key), false); assert.equal(dump.includes(invitation), false);
    assert.equal(readFileSync(database).includes(Buffer.from(key)), false);
    store.db.prepare('UPDATE devices SET revoked=1 WHERE id=?').run(deviceID);
    assert.throws(() => store.authenticate(key)); assert.throws(() => store.register(key, { label: '重新注册' }));
  } finally { store.close(); rmSync(directory, { recursive: true, force: true }); }
});

test('protocol, malformed credentials and server-side request limiting fail closed', async t => {
  const a = await setup(t, { requestsPerMinute: 5 });
  assert.equal((await a.request('nickname', 'POST', '/devices', { label: '设备' })).status, 400);
  const key = await a.device();
  assert.equal((await a.request(key, 'GET', '/teams', undefined, { 'X-BM-Protocol': '999' })).status, 426);
  assert.equal((await a.request(token(), 'GET', '/teams')).status, 401);
  const response = await a.request(key, 'GET', '/teams');
  assert.equal(response.status, 200); assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal((await a.request(key, 'GET', '/teams', undefined, { 'X-Forwarded-For': '1.2.3.4' })).status, 429);
});

test('revoked credentials cannot mutate through a previously authenticated device ID', () => {
  const store = new CollaborationStore();
  try {
    const deviceID = store.register(token(), { label: '测试设备' }).deviceID;
    const body = operation({ name: '球队', nickname: '管理员' });
    const team = store.createTeam(deviceID, body);
    store.db.prepare('UPDATE devices SET revoked=1 WHERE id=?').run(deviceID);
    assert.throws(() => store.createTeam(deviceID, body), /设备授权已失效/);
    assert.throws(() => store.createTeam(deviceID, operation({ name: '另一球队', nickname: '管理员' })), /设备授权已失效/);
    assert.throws(() => store.membership(team.teamID, deviceID));
    assert.equal(store.db.prepare('SELECT COUNT(*) AS n FROM teams').get().n, 1);
  } finally { store.close(); }
});

test('local roster seed is atomic, preserves jersey labels, and assigns independent shared identities', async t => {
  const a = await setup(t), admin = await a.device(), sourceID = randomUUID();
  const body = operation({ name: '本机名单', nickname: '管理员', profile: { shortName: '名单', city: '上海',
    players: [{ sourceID, chineseName: '同名球员', englishName: '', numbers: ['00', '0', '12'] }] } });
  const created = await a.request(admin, 'POST', '/teams', body);
  assert.equal(created.status, 200);
  assert.deepEqual((await a.request(admin, 'POST', '/teams', body)).data, created.data);
  const teamID = created.data.teamID, viewer = await a.joinTeam(admin, teamID);
  const detail = (await a.request(viewer.key, 'GET', `/teams/${teamID}`)).data;
  assert.deepEqual(detail.team.players[0].numbers, ['00', '0', '12']);
  assert.equal(detail.team.players[0].sourceID, sourceID);
  assert.notEqual(detail.team.players[0].id, sourceID);
  assert.equal(detail.role, 'member'); assert.equal(detail.memberID, viewer.memberID);
  const invalid = structuredClone(body); invalid.operationID = randomUUID(); invalid.profile.players.push(invalid.profile.players[0]);
  assert.equal((await a.request(admin, 'POST', '/teams', invalid)).status, 400);
  assert.equal((await a.request(admin, 'GET', '/teams')).data.teams.length, 1);
});

test('preview reveals the invited team and role without consuming; invitation list omits secrets', async t => {
  const a = await setup(t), admin = await a.device(), guest = await a.device(), teamID = (await a.create(admin, '核对球队')).data.teamID;
  const secret = token();
  const invitation = (await a.request(admin, 'POST', `/teams/${teamID}/invitations`, operation({ token: secret, role: 'scorer' }))).data;
  const preview = await a.request(guest, 'POST', '/invitations/preview', { token: secret });
  assert.equal(preview.data.teamName, '核对球队'); assert.equal(preview.data.role, 'scorer');
  assert.equal((await a.request(guest, 'GET', '/teams')).data.teams.length, 0);
  const list = await a.request(admin, 'GET', `/teams/${teamID}/invitations`);
  assert.equal(list.data.invitations[0].invitationID, invitation.invitationID);
  assert.equal(JSON.stringify(list.data).includes(secret), false);
  assert.equal((await a.request(guest, 'GET', `/teams/${teamID}/invitations`)).status, 404);
  await a.request(admin, 'DELETE', `/teams/${teamID}/invitations/${invitation.invitationID}`, operation({}));
  assert.equal((await a.request(guest, 'POST', '/invitations/preview', { token: secret })).status, 410);
  assert.equal((await a.request(admin, 'GET', `/teams/${teamID}/invitations`)).data.invitations.length, 0);
});

test('self leave protects last administrator, is retryable, and does not erase team or regain access', async t => {
  const a = await setup(t), admin = await a.device(), team = (await a.create(admin)).data;
  assert.equal((await a.request(admin, 'POST', `/teams/${team.teamID}/leave`, operation({}))).data.error.code, 'last_admin');
  const guest = await a.joinTeam(admin, team.teamID);
  const leaving = operation({});
  assert.equal((await a.request(guest.key, 'POST', `/teams/${team.teamID}/leave`, leaving)).data.left, true);
  assert.equal((await a.request(guest.key, 'POST', `/teams/${team.teamID}/leave`, leaving)).data.left, true);
  assert.equal((await a.request(guest.key, 'GET', `/teams/${team.teamID}`)).status, 404);
  assert.equal((await a.request(admin, 'GET', `/teams/${team.teamID}`)).status, 200);
  assert.equal((await a.request(guest.key, 'POST', `/teams/${team.teamID}/leave`, { ...leaving, extra: 1 })).status, 409);
});

test('administrator transfer and downgrade immediately change management access', async t => {
  const a = await setup(t), admin = await a.device(), team = (await a.create(admin)).data;
  const other = await a.joinTeam(admin, team.teamID);
  assert.equal((await a.request(admin, 'PATCH', `/teams/${team.teamID}/members/${other.memberID}`, operation({ role: 'admin', baseRevision: 1 }))).status, 200);
  assert.equal((await a.request(other.key, 'PATCH', `/teams/${team.teamID}/members/${team.memberID}`, operation({ role: 'scorer', baseRevision: 1 }))).status, 200);
  assert.equal((await a.request(admin, 'GET', `/teams/${team.teamID}/invitations`)).status, 403);
  assert.equal((await a.request(admin, 'GET', `/teams/${team.teamID}`)).data.role, 'scorer');
  assert.equal((await a.request(other.key, 'POST', `/teams/${team.teamID}/leave`, operation({}))).data.error.code, 'last_admin');
});

test('roster revisions synchronize stable identities, archive and restore, and reject stale or unauthorized writes', async t => {
  const a = await setup(t), admin = await a.device();
  const team = (await a.create(admin)).data.teamID;
  const member = await a.joinTeam(admin, team), scorer = await a.joinTeam(admin, team, 'scorer');
  const path = `/teams/${team}/roster`, player = { id: randomUUID(), sourceID: randomUUID(), chineseName: '同名', englishName: '', numbers: ['00', '12'], archived: false };
  const add = operation({ baseRevision: 1, kind: 'player', player });
  assert.equal((await a.request(member.key, 'PATCH', path, add)).status, 403);
  assert.equal((await a.request(scorer.key, 'PATCH', path, operation({ baseRevision: 1, kind: 'profile', name: '改队名', shortName: '队', city: '' }))).status, 403);
  const result = await a.request(admin, 'PATCH', path, add);
  assert.equal(result.status, 200); assert.equal(result.data.team.revision, 2);
  assert.deepEqual((await a.request(admin, 'PATCH', path, add)).data, result.data);
  assert.deepEqual((await a.request(member.key, 'GET', `/teams/${team}`)).data.team.players, [player]);
  assert.equal((await a.request(admin, 'PATCH', path, operation({ ...add, operationID: randomUUID(), player: { ...player, chineseName: '过期修改' } }))).status, 409);
  const change = fields => a.request(admin, 'PATCH', path, operation(fields));
  assert.equal((await change({ baseRevision: 2, kind: 'player', player: { ...player, sourceID: randomUUID() } })).status, 409);
  const second = { ...player, id: randomUUID(), sourceID: randomUUID() };
  assert.equal((await change({ baseRevision: 2, kind: 'player', player: second })).status, 200);
  const archive = await change({ baseRevision: 3, kind: 'player', player: { ...player, archived: true, chineseName: '已更名', numbers: ['0'] } });
  assert.equal(archive.data.team.players.length, 2); assert.equal(archive.data.team.players[0].sourceID, player.sourceID);
  const restored = await change({ baseRevision: 4, kind: 'player', player });
  assert.deepEqual(restored.data.team.players[0], player);
  const renamed = await change({ baseRevision: 5, kind: 'profile', name: '新队名', shortName: '新队', city: '上海' });
  assert.equal(renamed.data.team.revision, 6); assert.equal(renamed.data.team.players.length, 2);
  assert.equal((await a.request(scorer.key, 'GET', '/teams')).data.teams[0].name, '新队名');
  const stranger = await a.device(); assert.equal((await a.request(stranger, 'PATCH', path, operation({ baseRevision: 6, kind: 'player', player }))).status, 404);
  const otherTeam = (await a.create(admin)).data.teamID;
  assert.equal((await a.request(member.key, 'GET', `/teams/${otherTeam}`)).status, 404);
  assert.equal((await change({ baseRevision: 6, kind: 'player', player: { ...player, numbers: ['00', '00'] } })).status, 400);
  assert.equal((await change({ baseRevision: 6, kind: 'player', player: { ...player, numbers: ['01'] } })).status, 400);
});

test('roster replay after role revocation never grants write access, and database reopen retains the roster', async t => {
  const folder = mkdtempSync(join(tmpdir(), 'bm-roster-')); t.after(() => rmSync(folder, { recursive: true, force: true }));
  const database = join(folder, 'team.sqlite'), store = new CollaborationStore({ database });
  const admin = store.register(token(), { label: '管理设备' }).deviceID;
  const team = store.createTeam(admin, operation({ name: '持久球队', nickname: '教练' })).teamID;
  const player = { id: randomUUID(), sourceID: randomUUID(), chineseName: '球员', englishName: '', numbers: [], archived: false };
  const add = operation({ baseRevision: 1, kind: 'player', player });
  store.roster.update(team, admin, add); store.close();
  const reopened = new CollaborationStore({ database }); t.after(() => reopened.close());
  assert.equal(reopened.roster.read(team, admin).team.players[0].id, player.id);
  assert.equal(reopened.roster.update(team, admin, add).team.revision, 2);
  reopened.db.prepare("UPDATE members SET role='member' WHERE team_id=?").run(team);
  assert.throws(() => reopened.roster.update(team, admin, add), e => e.status === 403);
});

test('roster active capacity is enforced atomically and unnamed legacy players can be archived and restored', t => {
  const store = new CollaborationStore(); t.after(() => store.close());
  const admin = store.register(token(), { label: '名单容量设备' }).deviceID;
  const team = store.createTeam(admin, operation({ name: '满员队', nickname: '教练', profile: { shortName: '满员', city: '',
    players: Array.from({ length: 200 }, () => ({ sourceID: randomUUID(), chineseName: '', englishName: '', numbers: [] })) } })).teamID;
  const first = store.roster.read(team, admin).team.players[0];
  const extra = { id: randomUUID(), sourceID: randomUUID(), chineseName: '新球员', englishName: '', numbers: ['12'], archived: false };
  const change = (baseRevision, player) => store.roster.update(team, admin, operation({ baseRevision, kind: 'player', player }));
  assert.throws(() => change(1, extra), e => e.code === 'capacity');
  assert.equal(store.roster.read(team, admin).team.revision, 1);
  change(1, { ...first, archived: true }); change(2, extra);
  assert.throws(() => change(3, { ...first, archived: false }), e => e.code === 'capacity');
  change(3, { ...extra, archived: true }); change(4, { ...first, archived: false });
  const roster = store.roster.read(team, admin).team;
  assert.equal(roster.players.length, 201); assert.equal(roster.players.filter(p => !p.archived).length, 200);
  assert.equal(roster.revision, 5);
});

test('scorers add and edit players with revision protection but cannot archive, edit the team or manage roles', async t => {
  const a=await setup(t),admin=await a.device(),team=(await a.create(admin)).data.teamID;
  const scorer=await a.joinTeam(admin,team,'scorer'),member=await a.joinTeam(admin,team),path=`/teams/${team}/roster`;
  const player={id:randomUUID(),sourceID:randomUUID(),chineseName:'新球员',englishName:'',numbers:['0','00'],archived:false};
  const add=operation({baseRevision:1,kind:'player',player});
  assert.equal((await a.request(scorer.key,'PATCH',path,add)).status,200);
  assert.equal((await a.request(scorer.key,'PATCH',path,add)).data.team.revision,2);
  const edit=operation({baseRevision:2,kind:'player',player:{...player,chineseName:'改后姓名',numbers:['12']}});
  assert.equal((await a.request(scorer.key,'PATCH',path,edit)).status,200);
  assert.equal((await a.request(admin,'PATCH',path,operation({...edit,operationID:randomUUID()}))).data.error.code,'roster_conflict');
  assert.equal((await a.request(scorer.key,'PATCH',path,operation({baseRevision:3,kind:'player',player:{...player,archived:true}}))).status,403);
  assert.equal((await a.request(scorer.key,'PATCH',path,operation({baseRevision:3,kind:'profile',name:'非法队名',shortName:'非法',city:''}))).status,403);
  assert.equal((await a.request(member.key,'PATCH',path,operation({baseRevision:3,kind:'player',player}))).status,403);
  assert.equal((await a.request(scorer.key,'PATCH',`/teams/${team}/members/${member.memberID}`,operation({baseRevision:1,role:'admin'}))).status,403);
  const saved=(await a.request(member.key,'GET',`/teams/${team}`)).data.team;
  assert.equal(saved.revision,3);assert.equal(saved.players[0].chineseName,'改后姓名');assert.equal(saved.players[0].sourceID,player.sourceID);
  assert.equal((await a.request(admin,'PATCH',`/teams/${team}/members/${scorer.memberID}`,operation({baseRevision:1,role:'member'}))).status,200);
  assert.equal((await a.request(scorer.key,'PATCH',path,edit)).status,403);
  assert.equal((await a.request(admin,'GET',`/teams/${team}`)).data.team.revision,3);
});

test('three-administrator limit is team-scoped, transactional and retry-safe; demotion frees one slot', async t => {
  const a=await setup(t),admin=await a.device(),created=(await a.create(admin)).data,team=created.teamID;
  const existing=await a.joinTeam(admin,team,'scorer');
  assert.equal((await a.request(admin,'PATCH',`/teams/${team}/members/${existing.memberID}`,operation({baseRevision:1,role:'admin'}))).status,200);
  const first=await a.joinTeam(admin,team,'scorer'),second=await a.joinTeam(admin,team,'scorer');
  const promote=operation({baseRevision:1,role:'admin'}),other=operation({baseRevision:1,role:'admin'});
  const calls=await Promise.all([a.request(admin,'PATCH',`/teams/${team}/members/${first.memberID}`,promote),a.request(admin,'PATCH',`/teams/${team}/members/${second.memberID}`,other)]);
  assert.deepEqual(calls.map(r=>r.status).sort(),[200,409]);
  const win=calls[0].status===200?first:second,lose=win===first?second:first,body=win===first?promote:other;
  assert.equal(calls.find(r=>r.status===409).data.error.code,'admin_limit');
  assert.equal((await a.request(admin,'PATCH',`/teams/${team}/members/${win.memberID}`,body)).status,200);
  let members=(await a.request(admin,'GET',`/teams/${team}/members`)).data.members;assert.equal(members.filter(m=>m.role==='admin').length,3);
  assert.equal((await a.request(admin,'PATCH',`/teams/${team}/members/${created.memberID}`,operation({baseRevision:1,role:'admin'}))).status,200);
  assert.equal((await a.request(admin,'PATCH',`/teams/${team}/members/${win.memberID}`,operation({baseRevision:2,role:'scorer'}))).status,200);
  assert.equal((await a.request(admin,'PATCH',`/teams/${team}/members/${lose.memberID}`,operation({baseRevision:1,role:'admin'}))).status,200);
  members=(await a.request(admin,'GET',`/teams/${team}/members`)).data.members;assert.equal(members.filter(m=>m.role==='admin').length,3);
  const otherTeam=(await a.create(lose.key)).data.teamID;assert.notEqual(team,otherTeam);
  assert.equal((await a.request(lose.key,'GET',`/teams/${otherTeam}/members`)).data.members.filter(m=>m.role==='admin').length,1);
  assert.equal(can('scorer','editRoster'),true);assert.equal(can('member','editRoster'),false);assert.equal(can('scorer','archivePlayer'),false);
});


test('fixed team invitation survives multiple joins, reopening, rotation and role checks', async t => {
  const a=await setup(t),admin=await a.device(),team=(await a.create(admin)).data.teamID;
  const path=`/teams/${team}/team-invitation`;
  const get=()=>a.request(admin,'POST',path,operation({rotate:false}));
  const saved=(await get()).data;
  assert.ok(saved.token); assert.equal((await get()).data.token,saved.token);
  const one=await a.device(),two=await a.device();
  const joins=await Promise.all([one,two].map(key=>a.request(key,'POST','/invitations/redeem',operation({token:saved.token,nickname:'成员'}))));
  assert.deepEqual(joins.map(r=>r.status),[200,200]);
  assert.equal((await a.request(one,'POST',path,operation({rotate:true}))).status,403);
  assert.equal((await a.request(one,'POST','/invitations/redeem',operation({token:saved.token,nickname:'重复'}))).status,409);
  a.advance(INVITE_TTL*100);
  assert.equal((await a.request(two,'POST','/invitations/preview',{token:saved.token})).status,200);
  const rotation=operation({rotate:true}),updated=(await a.request(admin,'POST',path,rotation)).data;
  assert.notEqual(updated.token,saved.token);
  assert.equal((await a.request(admin,'POST',path,rotation)).data.token,updated.token);
  assert.equal((await a.request(two,'POST','/invitations/preview',{token:saved.token})).status,410);
  assert.equal((await a.request(two,'GET',`/teams/${team}`)).status,200);
  assert.equal((await a.request(admin,'GET',`/teams/${team}/members`)).data.members.length,3);
});

test('fixed invitation persists after server restart and is shared by multiple administrators', t => {
  const folder=mkdtempSync(join(tmpdir(),'bm-fixed-invite-'));t.after(()=>rmSync(folder,{recursive:true,force:true}));
  const database=join(folder,'team.sqlite'),store=new CollaborationStore({database});
  const admin=store.register(token(),{label:'管理设备'}).deviceID;
  const team=store.createTeam(admin,operation({name:'固定邀请持久化',nickname:'管理者'})).teamID;
  const saved=store.fixedInvite(team,admin,operation({rotate:false}));
  const second=store.register(token(),{label:'第二设备'}).deviceID;
  const joined=store.redeem(second,operation({token:saved.token,nickname:'第二管理员'}));
  store.changeMember(team,joined.memberID,admin,operation({role:'admin',baseRevision:1}));
  assert.equal(store.fixedInvite(team,second,operation({rotate:false})).token,saved.token);
  store.close();
  const reopened=new CollaborationStore({database});t.after(()=>reopened.close());
  assert.equal(reopened.fixedInvite(team,admin,operation({rotate:false})).token,saved.token);
  const updated=reopened.fixedInvite(team,second,operation({rotate:true}));
  assert.notEqual(updated.token,saved.token);
  assert.equal(reopened.fixedInvite(team,admin,operation({rotate:false})).token,updated.token);
  assert.throws(()=>reopened.previewInvite(admin,{token:saved.token}),e=>e.status===410);
});
