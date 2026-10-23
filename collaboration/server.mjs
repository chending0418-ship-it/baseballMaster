import { createServer } from 'node:http';
import { DatabaseSync } from 'node:sqlite';
import { createHash, randomUUID, randomBytes } from 'node:crypto';
import { mkdirSync, chmodSync, readFileSync, statfsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { can, ROLES } from './permissions.mjs';
import { GameService } from './games.mjs';
import { TeamSecurity } from './security.mjs';
import { RosterService } from './roster.mjs';
import { HistoryImportService } from './imports.mjs';
import { PlayerLinkService } from './player-links.mjs';
import { DraftService } from './drafts.mjs';
import { TeamLifecycle } from './lifecycle.mjs';
import { DeviceCredentials } from './credentials.mjs';
import { TeamArchiveService } from './archive.mjs';
import { inflateSync, gzipSync } from 'node:zlib';
import { isIP } from 'node:net';
import { createAdminHandler } from './admin.mjs';
import { LaunchScreenService, LaunchScreenError } from './launch-screen.mjs';

export const BASE = '/api/v3';
export const PROTOCOL = 1;
export const INVITE_TTL = 24 * 60 * 60 * 1000;
const hash = s => createHash('sha256').update(s).digest('hex');
const uuid = s => typeof s === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s);
// The client generates and retains 32 random bytes before sending the first request.
const secret = s => typeof s === 'string' && /^[A-Za-z0-9_-]{43}$/.test(s)
  && Buffer.from(s, 'base64url').length === 32 && Buffer.from(s, 'base64url').toString('base64url') === s;
const label = (s, max = 80) => typeof s === 'string' && s.trim().length > 0 && s.length <= max && !/[\u0000-\u001f\u007f]/.test(s);
class APIError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}
const requireValue = (condition, status = 400, code = 'invalid_request', message = '资料格式不正确。') => {
  if (!condition) throw new APIError(status, code, message);
};
const canonical = value => JSON.stringify(value, function (_, v) {
  return v && !Array.isArray(v) && typeof v === 'object'
    ? Object.fromEntries(Object.keys(v).sort().map(k => [k, v[k]])) : v;
});

export class CollaborationStore {
  constructor({ database = ':memory:', now = () => Date.now(), maxTeamsPerDevice = 30, safetyJournal,
    maxGamesPerTeam = 5000, maxTeamBytes = 2 * 1024 ** 3, minimumFreeBytes = 0 } = {}) {
    this.now = now; this.maxTeamsPerDevice = maxTeamsPerDevice;
    this.databasePath = database;
    this.maxGamesPerTeam = maxGamesPerTeam; this.maxTeamBytes = maxTeamBytes;
    this.safetyJournal = safetyJournal;
    this.databaseDirectory = database === ':memory:' ? null : dirname(database); this.minimumFreeBytes = minimumFreeBytes;
    if (database !== ':memory:') mkdirSync(dirname(database), { recursive: true, mode: 0o700 });
    this.db = new DatabaseSync(database);
    if (database !== ':memory:') chmodSync(database, 0o600);
    const hasMetadata = this.db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='metadata'").get();
    const existingVersion = hasMetadata && this.db.prepare("SELECT value FROM metadata WHERE key='schema'").get();
    if (existingVersion && existingVersion.value !== '1') {
      this.db.close(); throw new APIError(503, 'schema_unsupported', '服务数据版本不兼容。');
    }
    this.db.exec(`PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
      CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, credential_hash TEXT NOT NULL UNIQUE,
        label TEXT NOT NULL, created_at INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS teams(id TEXT PRIMARY KEY, name TEXT NOT NULL,
        revision INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS team_profiles(team_id TEXT PRIMARY KEY REFERENCES teams(id), profile TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS members(id TEXT PRIMARY KEY, team_id TEXT NOT NULL REFERENCES teams(id),
        nickname TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('admin','scorer','member')),
        revision INTEGER NOT NULL DEFAULT 1, active INTEGER NOT NULL DEFAULT 1);
      CREATE TABLE IF NOT EXISTS member_devices(team_id TEXT NOT NULL REFERENCES teams(id),
        member_id TEXT NOT NULL REFERENCES members(id), device_id TEXT NOT NULL REFERENCES devices(id),
        active INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(team_id,device_id));
      CREATE TABLE IF NOT EXISTS invitations(id TEXT PRIMARY KEY, team_id TEXT NOT NULL REFERENCES teams(id),
        secret_hash TEXT NOT NULL UNIQUE, role TEXT NOT NULL CHECK(role IN ('scorer','member')),
        expires_at INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0, consumed_by TEXT REFERENCES devices(id));
      CREATE TABLE IF NOT EXISTS operations(device_id TEXT NOT NULL REFERENCES devices(id), operation_id TEXT NOT NULL,
        request_hash TEXT NOT NULL, result TEXT NOT NULL, PRIMARY KEY(device_id,operation_id));
      CREATE TABLE IF NOT EXISTS device_recovery_reviews(device_id TEXT PRIMARY KEY,epoch TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT, team_id TEXT NOT NULL REFERENCES teams(id),
        actor_device_id TEXT NOT NULL REFERENCES devices(id), action TEXT NOT NULL, object_id TEXT NOT NULL,
        occurred_at INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS audit_team ON audit(team_id,id);
      CREATE INDEX IF NOT EXISTS membership_team ON members(team_id,active);`);
    const version = this.db.prepare("SELECT value FROM metadata WHERE key='schema'").get();
    requireValue(!version || version.value === '1', 503, 'schema_unsupported', '服务数据版本不兼容。');
    this.db.prepare("INSERT OR IGNORE INTO metadata VALUES('schema','1')").run();
    this.db.prepare("INSERT OR IGNORE INTO metadata VALUES('recoveryEpoch',?)").run(randomUUID());
    const auditColumns = this.db.prepare('PRAGMA table_info(audit)').all().map(c => c.name);
    for (const column of ['actor_nickname','actor_device_label']) {
      if (!auditColumns.includes(column)) this.db.exec(`ALTER TABLE audit ADD COLUMN ${column} TEXT`);
    }
    const operationColumns = this.db.prepare('PRAGMA table_info(operations)').all().map(c=>c.name);
    if (!operationColumns.includes('retired')) this.db.exec('ALTER TABLE operations ADD COLUMN retired INTEGER NOT NULL DEFAULT 0');
    if (!operationColumns.includes('created_at')) {
      this.db.exec('ALTER TABLE operations ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0');
      this.db.prepare('UPDATE operations SET created_at=?').run(this.now());
    }
    const inviteColumns = this.db.prepare('PRAGMA table_info(invitations)').all().map(c=>c.name);
    if (!inviteColumns.includes('reusable_token')) this.db.exec('ALTER TABLE invitations ADD COLUMN reusable_token TEXT');
    this.games = new GameService(this, requireValue);
    this.security = new TeamSecurity(this, requireValue);
    this.roster = new RosterService(this, requireValue);
    this.imports = new HistoryImportService(this, requireValue);
    this.playerLinks = new PlayerLinkService(this, requireValue);
    this.drafts = new DraftService(this, requireValue);
    this.lifecycle = new TeamLifecycle(this, requireValue);
    this.credentials = new DeviceCredentials(this, requireValue);
    this.archives = new TeamArchiveService(this, requireValue);
  }
  close() { this.db.close(); }
  transaction(fn) {
    if(this.databaseDirectory && this.minimumFreeBytes>0) {
      const disk=statfsSync(this.databaseDirectory);
      requireValue(disk.bavail*disk.bsize>=this.minimumFreeBytes,507,'storage_capacity','服务存储空间不足，本机记录会保留，请稍后重试。');
    }
    this.db.exec('BEGIN IMMEDIATE');
    try { const value = fn(); this.safetyJournal?.record(this); this.db.exec('COMMIT'); return value; }
    catch (e) { this.db.exec('ROLLBACK'); throw e; }
  }
  audit(teamID, deviceID, action, objectID) {
    const actor = this.db.prepare(`SELECT m.nickname,d.label FROM devices d LEFT JOIN member_devices md ON md.device_id=d.id AND md.team_id=?
      LEFT JOIN members m ON m.id=md.member_id WHERE d.id=?`).get(teamID,deviceID);
    this.db.prepare('INSERT INTO audit(team_id,actor_device_id,action,object_id,occurred_at,actor_nickname,actor_device_label) VALUES(?,?,?,?,?,?,?)')
      .run(teamID, deviceID, action, objectID, this.now(),actor?.nickname ?? '球队成员',actor?.label ?? '记录设备');
  }
  auditPage(teamID,deviceID,params = new URLSearchParams()) {
    this.membership(teamID,deviceID,'manageMembers');
    const before = Number(params.get('before') ?? Number.MAX_SAFE_INTEGER), from = Number(params.get('from') ?? 0), to = Number(params.get('to') ?? Number.MAX_SAFE_INTEGER);
    const action = params.get('action') ?? '';
    requireValue([before,from,to].every(v => Number.isSafeInteger(v) && v >= 0) && from <= to && /^[a-zA-Z:]{0,60}$/.test(action));
    const rows = this.db.prepare(`SELECT id,action,object_id AS objectID,occurred_at AS occurredAt,
      COALESCE(actor_nickname,'球队成员') AS nickname,COALESCE(actor_device_label,'记录设备') AS deviceName
      FROM audit WHERE team_id=? AND id<? AND occurred_at>=? AND occurred_at<=? AND (?='' OR action=?) ORDER BY id DESC LIMIT 51`)
      .all(teamID,before,from,to,action,action);
    const events = rows.slice(0,50);
    return { events, before: events.at(-1)?.id ?? before, hasMore: rows.length > 50,
      actions: this.db.prepare('SELECT DISTINCT action FROM audit WHERE team_id=? ORDER BY action').all(teamID).map(r=>r.action) };
  }
  checkGameCapacity(team,valid,replacing, draftOnly=false) {
    const totals=this.db.prepare('SELECT COUNT(*) AS games,COALESCE(SUM(length(CAST(payload AS BLOB))+length(CAST(projection AS BLOB))),0) AS bytes FROM shared_games WHERE team_id=? AND purged=0').get(team);
    const previous=replacing ? this.db.prepare('SELECT length(CAST(payload AS BLOB))+length(CAST(projection AS BLOB)) AS bytes FROM shared_games WHERE id=? AND team_id=?').get(replacing,team)?.bytes ?? 0 : 0;
    const drafts=this.db.prepare('SELECT COALESCE(SUM(length(CAST(d.payload AS BLOB))+length(CAST(d.projection AS BLOB))),0) AS bytes FROM conflict_drafts d JOIN shared_games g ON g.id=d.game_id WHERE g.team_id=?').get(team).bytes;
    requireValue(draftOnly || replacing || totals.games<this.maxGamesPerTeam,413,'team_capacity','球队比赛档案已达上限，请先导出并联系支持。');
    requireValue(totals.bytes+drafts-previous+Buffer.byteLength(valid.payload)+Buffer.byteLength(valid.projection)<=this.maxTeamBytes,
      413,'team_capacity','球队存储空间已达上限，本机记录会保留，请导出并联系支持。');
  }
  authenticate(token) {
    requireValue(secret(token), 401, 'unauthorized', '设备授权无效，请重新授权。');
    const device = this.db.prepare('SELECT id FROM devices WHERE credential_hash=? AND revoked=0').get(hash(token));
    requireValue(device, 401, 'unauthorized', '设备授权无效，请重新授权。');
    return device.id;
  }
  register(token, body) {
    requireValue(secret(token) && label(body.label));
    return this.transaction(() => {
      requireValue(!this.db.prepare('SELECT 1 FROM retired_credentials WHERE credential_hash=?').get(hash(token)),401,'unauthorized','旧设备授权已失效，请使用更新后的授权。');
      requireValue(!this.db.prepare('SELECT 1 FROM credential_rotations WHERE new_hash=? AND committed=0').get(hash(token)),409,'rotation_pending','请先完成设备授权更新。');
      const prior = this.db.prepare('SELECT id,revoked FROM devices WHERE credential_hash=?').get(hash(token));
      requireValue(!prior?.revoked, 401, 'unauthorized', '设备授权已失效。');
      if (prior) return { deviceID: prior.id, protocol: PROTOCOL };
      requireValue(this.db.prepare('SELECT COUNT(*) AS n FROM devices').get().n<100000,503,'service_capacity','服务当前无法接入新设备，请稍后重试。');
      const id = randomUUID();
      this.db.prepare('INSERT INTO devices(id,credential_hash,label,created_at) VALUES(?,?,?,?)').run(id, hash(token), body.label.trim(), this.now());
      this.db.prepare('INSERT INTO device_recovery_reviews VALUES(?,?)').run(id,this.db.prepare("SELECT value FROM metadata WHERE key='recoveryEpoch'").get().value);
      return { deviceID: id, protocol: PROTOCOL };
    });
  }
  membership(teamID, deviceID, action = 'readTeam') {
    requireValue(!this.lifecycle?.deleted(teamID),404,'not_found','球队不存在或当前设备无权访问。');
    const member = this.db.prepare(`SELECT m.* FROM members m JOIN member_devices d ON d.member_id=m.id
      JOIN devices actor ON actor.id=d.device_id AND actor.revoked=0
      WHERE m.team_id=? AND d.team_id=m.team_id AND d.device_id=? AND m.active=1 AND d.active=1`).get(teamID, deviceID);
    requireValue(member, 404, 'not_found', '球队不存在或当前设备无权访问。');
    requireValue(can(member.role, action), 403, 'forbidden', '当前角色不能执行此操作。');
    return member;
  }
  operation(deviceID, body, scope, authorize, perform) {
    requireValue(uuid(body.operationID));
    const digest = hash(canonical({ scope, body }));
    return this.transaction(() => {
      requireValue(this.db.prepare('SELECT id FROM devices WHERE id=? AND revoked=0').get(deviceID),
        401, 'unauthorized', '设备授权已失效。');
      authorize(); // Check current authorization even when returning an earlier response.
      const prior = this.db.prepare('SELECT * FROM operations WHERE device_id=? AND operation_id=?').get(deviceID, body.operationID);
      if (prior) {
        requireValue(prior.request_hash === digest, 409, 'operation_conflict', '操作编号已用于不同内容。');
        requireValue(!prior.retired,410,'operation_expired','这次旧操作已归档，请刷新核对结果，不会重新执行。');
        return JSON.parse(prior.result);
      }
      const result = perform();
      this.db.prepare('INSERT INTO operations(device_id,operation_id,request_hash,result,created_at) VALUES(?,?,?,?,?)').run(deviceID, body.operationID, digest, JSON.stringify(result),this.now());
      return result;
    });
  }
  listTeams(deviceID) {
    return { teams: this.db.prepare(`SELECT t.id,t.name,t.revision,t.access_epoch AS accessEpoch,m.role,m.id AS memberID,
      CASE WHEN m.role='admin' THEN COALESCE((SELECT version FROM team_recovery WHERE team_id=t.id),0) END AS recoveryVersion FROM teams t
      JOIN members m ON m.team_id=t.id JOIN member_devices d ON d.member_id=m.id AND d.team_id=t.id
      JOIN devices actor ON actor.id=d.device_id AND actor.revoked=0
      WHERE d.device_id=? AND d.active=1 AND m.active=1 ORDER BY t.created_at,t.id`).all(deviceID),
      recoveryEpoch: this.db.prepare("SELECT value FROM metadata WHERE key='recoveryEpoch'").get().value };
  }
  createTeam(deviceID, body) {
    requireValue(label(body.name) && label(body.nickname) && (body.recoveryToken === undefined || secret(body.recoveryToken)));
    const seed = body.profile;
    if (seed !== undefined) {
      const text = (v, n) => typeof v === 'string' && v.length <= n && !/[\u0000-\u001f\u007f]/.test(v);
      requireValue(seed && label(seed.shortName, 80) && text(seed.city, 80) && Array.isArray(seed.players) && seed.players.length <= 200);
      const ids = new Set();
      for (const p of seed.players) {
        requireValue(p && uuid(p.sourceID) && !ids.has(p.sourceID.toLowerCase()) && text(p.chineseName, 80)
          && text(p.englishName, 80) && Array.isArray(p.numbers) && p.numbers.length <= 10
          && new Set(p.numbers).size === p.numbers.length && p.numbers.every(n => /^(00|0|[1-9][0-9]?)$/.test(n) && typeof n === 'string'));
        ids.add(p.sourceID.toLowerCase());
      }
    }
    const result = this.operation(deviceID, body, 'createTeam', () => {}, () => {
      requireValue(this.listTeams(deviceID).teams.length < this.maxTeamsPerDevice, 409, 'capacity', '当前设备加入的球队已达上限。');
      const teamID = randomUUID(), memberID = randomUUID();
      this.db.prepare('INSERT INTO teams(id,name,created_at) VALUES(?,?,?)').run(teamID, body.name.trim(), this.now());
      const profile = { shortName: seed?.shortName.trim() ?? body.name.trim(), city: seed?.city ?? '',
        players: (seed?.players ?? []).map(p => ({ id: randomUUID(), sourceID: p.sourceID.toLowerCase(),
          chineseName: p.chineseName, englishName: p.englishName, numbers: p.numbers })) };
      this.db.prepare('INSERT INTO team_profiles VALUES(?,?)').run(teamID, JSON.stringify(profile));
      this.db.prepare('INSERT INTO members(id,team_id,nickname,role) VALUES(?,?,?,?)').run(memberID, teamID, body.nickname.trim(), 'admin');
      this.db.prepare('INSERT INTO member_devices(team_id,member_id,device_id) VALUES(?,?,?)').run(teamID, memberID, deviceID);
      const recoveryVersion = this.security.initializeRecovery(teamID,body.recoveryToken);
      this.audit(teamID, deviceID, 'teamCreated', teamID);
      return { teamID, memberID, role: 'admin', revision: 1, recoveryVersion };
    });
    this.membership(result.teamID, deviceID);
    return result;
  }
  invite(teamID, deviceID, body) {
    requireValue(['scorer', 'member'].includes(body.role) && secret(body.token));
    return this.operation(deviceID, body, `invite:${teamID}`, () => this.membership(teamID, deviceID, 'invite'), () => {
      requireValue(this.db.prepare('SELECT COUNT(*) AS n FROM invitations WHERE team_id=? AND revoked=0 AND consumed_by IS NULL AND expires_at>?').get(teamID,this.now()).n<200,409,'capacity','待使用邀请已达上限，请先撤销不再使用的邀请。');
      requireValue(!this.db.prepare('SELECT id FROM invitations WHERE secret_hash=?').get(hash(body.token)), 409, 'token_reused', '请生成新的邀请。');
      const invitationID = randomUUID(), expiresAt = this.now() + INVITE_TTL;
      this.db.prepare('INSERT INTO invitations(id,team_id,secret_hash,role,expires_at) VALUES(?,?,?,?,?)').run(invitationID, teamID, hash(body.token), body.role, expiresAt);
      this.audit(teamID, deviceID, 'invitationCreated', invitationID);
      return { invitationID, expiresAt, role: body.role };
    });
  }
  fixedInvite(teamID, deviceID, body) {
    requireValue(typeof body.rotate === 'boolean');
    return this.operation(deviceID, body, `fixedInvite:${teamID}`, () => this.membership(teamID, deviceID, 'invite'), () => {
      let row = this.db.prepare('SELECT * FROM invitations WHERE team_id=? AND reusable_token IS NOT NULL AND revoked=0').get(teamID);
      if (!row || body.rotate === true) {
        this.db.prepare('UPDATE invitations SET revoked=1 WHERE team_id=? AND reusable_token IS NOT NULL').run(teamID);
        const token = randomBytes(32).toString('base64url'), invitationID = randomUUID();
        this.db.prepare("INSERT INTO invitations(id,team_id,secret_hash,role,expires_at,reusable_token) VALUES(?,?,?,'member',?,?)")
          .run(invitationID,teamID,hash(token),8640000000000000,token);
        row = this.db.prepare('SELECT * FROM invitations WHERE id=?').get(invitationID);
        this.audit(teamID,deviceID,'teamInvitationRotated',invitationID);
      }
      return {teamID, token:row.reusable_token, invitation:{invitationID:row.id,role:row.role,expiresAt:row.expires_at}};
    });
  }
  redeem(deviceID, body) {
    requireValue(secret(body.token) && label(body.nickname));
    const result = this.operation(deviceID, body, 'redeem', () => {}, () => {
      const invite = this.db.prepare('SELECT * FROM invitations WHERE secret_hash=?').get(hash(body.token));
      requireValue(invite && !invite.revoked && (!invite.consumed_by || invite.reusable_token) && invite.expires_at > this.now(), 410, 'invitation_invalid', '邀请已失效，请联系邀请人重新发送。');
      const linked = this.db.prepare('SELECT * FROM member_devices WHERE team_id=? AND device_id=?').get(invite.team_id, deviceID);
      requireValue(!linked || !linked.active, 409, 'already_joined', '此设备已加入球队。');
      requireValue(this.listTeams(deviceID).teams.length < this.maxTeamsPerDevice, 409, 'capacity', '当前设备加入的球队已达上限。');
      requireValue(this.db.prepare('SELECT COUNT(*) AS n FROM members WHERE team_id=? AND active=1').get(invite.team_id).n<200,409,'capacity','球队成员已达上限，请联系管理员。');
      const memberID = randomUUID();
      this.db.prepare('INSERT INTO members(id,team_id,nickname,role) VALUES(?,?,?,?)').run(memberID, invite.team_id, body.nickname.trim(), invite.role);
      this.db.prepare(`INSERT INTO member_devices(team_id,member_id,device_id) VALUES(?,?,?)
        ON CONFLICT(team_id,device_id) DO UPDATE SET member_id=excluded.member_id,active=1,revision=member_devices.revision+1`).run(invite.team_id, memberID, deviceID);
      if (!invite.reusable_token) this.db.prepare('UPDATE invitations SET consumed_by=? WHERE id=?').run(deviceID, invite.id);
      this.audit(invite.team_id, deviceID, 'invitationRedeemed', memberID);
      return { teamID: invite.team_id, memberID, role: invite.role };
    });
    // A consumed invitation never restores removed membership on a retry.
    const current = this.membership(result.teamID, deviceID);
    requireValue(current.id === result.memberID, 410, 'invitation_invalid', '原邀请已失效。');
    return result;
  }
  revokeInvite(teamID, invitationID, deviceID, body) {
    return this.operation(deviceID, body, `revokeInvite:${teamID}:${invitationID}`, () => this.membership(teamID, deviceID, 'invite'), () => {
      const row = this.db.prepare('SELECT id FROM invitations WHERE id=? AND team_id=?').get(invitationID, teamID);
      requireValue(row, 404, 'not_found', '邀请不存在。');
      this.db.prepare('UPDATE invitations SET revoked=1 WHERE id=?').run(invitationID);
      this.audit(teamID, deviceID, 'invitationRevoked', invitationID);
      return { revoked: true };
    });
  }
  previewInvite(deviceID, body) {
    requireValue(secret(body.token));
    const row = this.db.prepare(`SELECT i.*,t.name FROM invitations i JOIN teams t ON t.id=i.team_id
      WHERE i.secret_hash=?`).get(hash(body.token));
    requireValue(row && !row.revoked && (!row.consumed_by || row.reusable_token) && row.expires_at > this.now(), 410, 'invitation_invalid', '邀请已失效。');
    return { teamID: row.team_id, teamName: row.name, role: row.role, expiresAt: row.expires_at };
  }
  leave(teamID, deviceID, body) {
    // A terminal self-removal may be retried after access ended, but may disclose only that operation's result.
    requireValue(uuid(body.operationID));
    const scope = `leave:${teamID}`, digest = hash(canonical({ scope, body }));
    const prior = this.db.prepare('SELECT request_hash,result,retired FROM operations WHERE device_id=? AND operation_id=?').get(deviceID, body.operationID);
    if (prior) {
      requireValue(prior.request_hash === digest, 409, 'operation_conflict', '操作编号已用于不同内容。');
      requireValue(!prior.retired,410,'operation_expired','这次旧操作已归档，请刷新核对结果。');
      return JSON.parse(prior.result);
    }
    return this.operation(deviceID, body, scope, () => this.membership(teamID, deviceID), () => {
      const member = this.membership(teamID, deviceID);
      if (member.role === 'admin') {
        requireValue(this.db.prepare("SELECT COUNT(*) AS n FROM members WHERE team_id=? AND role='admin' AND active=1").get(teamID).n > 1,
          409, 'last_admin', '请先指定另一名管理员。');
      }
      this.db.prepare('UPDATE members SET active=0,revision=revision+1 WHERE id=?').run(member.id);
      this.db.prepare('UPDATE member_devices SET active=0 WHERE member_id=?').run(member.id);
      this.games.memberChanged(teamID,member.id,deviceID,true,true);
      if(member.role==='admin')this.security.invalidateRecovery(teamID,deviceID);
      this.audit(teamID, deviceID, 'memberLeft', member.id);
      return { left: true };
    });
  }
  changeMember(teamID, memberID, deviceID, body) {
    requireValue((body.active === undefined || body.active === false) && ROLES.includes(body.role) && Number.isSafeInteger(body.baseRevision));
    return this.operation(deviceID, body, `member:${teamID}:${memberID}`, () => this.membership(teamID, deviceID, 'manageMembers'), () => {
      const member = this.db.prepare('SELECT * FROM members WHERE id=? AND team_id=? AND active=1').get(memberID, teamID);
      requireValue(member, 404, 'not_found', '成员不存在。');
      requireValue(member.revision === body.baseRevision, 409, 'revision_conflict', '成员资料已有更新，请刷新。');
      if (body.role === 'admin' && member.role !== 'admin' && body.active !== false) {
        const admins = this.db.prepare("SELECT COUNT(*) AS n FROM members WHERE team_id=? AND role='admin' AND active=1").get(teamID).n;
        requireValue(admins < 3, 409, 'admin_limit', '每支球队最多三名管理员。请先调整现有管理员的角色，再设置新的管理员。');
      }
      if (member.role === 'admin' && (body.role !== 'admin' || body.active === false)) {
        const admins = this.db.prepare("SELECT COUNT(*) AS n FROM members WHERE team_id=? AND role='admin' AND active=1").get(teamID).n;
        requireValue(admins > 1, 409, 'last_admin', '请先指定另一名管理员。');
      }
      const active = body.active === false ? 0 : 1;
      this.db.prepare('UPDATE members SET role=?,active=?,revision=revision+1 WHERE id=?').run(body.role, active, memberID);
      if (!active) this.db.prepare('UPDATE member_devices SET active=0 WHERE member_id=?').run(memberID);
      this.games.memberChanged(teamID,memberID,deviceID,!active || body.role === 'member',!active);
      if(member.role==='admin' && (!active || body.role!=='admin'))this.security.invalidateRecovery(teamID,deviceID);
      this.audit(teamID, deviceID, active ? 'memberRoleChanged' : 'memberRemoved', memberID);
      return { memberID, role: body.role, active: Boolean(active), revision: member.revision + 1 };
    });
  }
}

export function createCollaborationServer(options = {}) {
  const store = new CollaborationStore(options);
  const launchScreen = new LaunchScreenService(store);
  store.launchScreen = launchScreen;
  const admin = createAdminHandler(store,options.admin);
  const windows = new Map();
  const server = createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store'); res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    const reply = (status, data) => {
      const json=Buffer.from(JSON.stringify(data));
      if (json.length > 1024 && /\bgzip\b/.test(req.headers['accept-encoding'] ?? '')) {
        res.setHeader('Content-Encoding','gzip');res.setHeader('Vary','Accept-Encoding, Cookie');res.writeHead(status);res.end(gzipSync(json));
      } else { res.writeHead(status);res.end(json); }
    };
    try {
      const path = new URL(req.url, 'http://localhost').pathname;
      if(admin && (path==='/admin' || path.startsWith('/admin/'))) return await admin(req,res);
      const asset = /^\/watch\/(index.html|watch.js|style.css|app-icon.png)?$/.exec(path);
      if (asset && req.method === 'GET') {
        const name=asset[1] || 'index.html';
        res.setHeader('Referrer-Policy','no-referrer');res.setHeader('X-Robots-Tag','noindex, nofollow, noarchive');
        res.setHeader('Content-Type',name.endsWith('.js')?'text/javascript; charset=utf-8':name.endsWith('.css')?'text/css; charset=utf-8':name.endsWith('.png')?'image/png':'text/html; charset=utf-8');
        res.writeHead(200);res.end(readFileSync(new URL('../website/public/watch/'+name,import.meta.url)));return;
      }
      requireValue(path === BASE || path.startsWith(BASE + '/'), 404, 'not_found', '页面不存在。');
      if(path===BASE+'/health' && req.method==='GET') {
        requireValue(store.db.prepare("SELECT value FROM metadata WHERE key='restoreQuarantine'").get()?.value !== '1',503,'recovery_review','服务正在核对恢复资料。');
        return reply(200,{status:'ok',protocol:PROTOCOL});
      }
      if(path===BASE+'/capabilities' && req.method==='GET') return reply(200,{protocol:PROTOCOL,matchFormat:2,minimumAppVersion:'3.0',
        capabilities:['batted-ball-observation','writer-format-guard','team-roles','match-revisions','writer-handoff','historical-access','device-pairing','management-recovery','roster-years','history-import','player-links','conflict-review','team-lifecycle','portable-archive','credential-rotation','recovery-review']});
      requireValue(store.db.prepare("SELECT value FROM metadata WHERE key='restoreQuarantine'").get()?.value !== '1',503,'recovery_review','服务正在核对恢复资料，请稍后重试；本机记录会保留。');
      const now = store.now();
      // Only a configured local reverse proxy may supply the client address.
      const peer = req.socket.remoteAddress, forwarded = req.headers['x-real-ip'];
      const key = options.trustProxy && ['127.0.0.1','::1','::ffff:127.0.0.1'].includes(peer) && typeof forwarded === 'string' && isIP(forwarded) ? forwarded : peer;
      for (const [ip, window] of windows) if (window.until <= now) windows.delete(ip);
      requireValue(windows.has(key) || windows.size < 10000,429,'rate_limited','请求过于频繁，请稍后重试。');
      const window = windows.get(key) ?? { count: 0, until: now + 60_000 };
      windows.set(key, window); window.count++;
      requireValue(window.count <= (options.requestsPerMinute ?? 180), 429, 'rate_limited', '请求过于频繁，请稍后重试。');
      if(launchScreen.public(req,res,path))return;
      requireValue(req.headers['x-bm-protocol'] === String(PROTOCOL), 426, 'upgrade_required', '请更新 App 后重试。');
      const token = /^Bearer ([A-Za-z0-9_-]+)$/.exec(req.headers.authorization ?? '')?.[1];
      let body = {};
      if (req.method !== 'GET') {
        requireValue((req.headers['content-type'] ?? '').split(';')[0] === 'application/json', 415, 'content_type', '需要 JSON 资料。');
        let bytes = 0; const chunks = [];
        for await (const chunk of req) {
          bytes += chunk.length; requireValue(bytes <= 18 * 1024 * 1024, 413, 'too_large', '资料超过大小限制。'); chunks.push(chunk);
        }
        const encoding=req.headers['content-encoding'];requireValue(!encoding || encoding==='deflate',415,'content_encoding','传输编码不支持。');
        try {
          const wire=Buffer.concat(chunks),decoded=encoding==='deflate'?inflateSync(wire,{maxOutputLength:18*1024*1024}):wire;
          body = JSON.parse(decoded.toString('utf8'));
        } catch { throw new APIError(400, 'invalid_json', '资料格式不正确。'); }
        requireValue(body && typeof body === 'object' && !Array.isArray(body));
      }
      if (path === BASE + '/devices' && req.method === 'POST') return reply(200, store.register(token, body));
      if (path === BASE + '/devices/credential-commit' && req.method === 'POST') return reply(200,store.credentials.commit(token,body));
      const temporaryLive = new RegExp(`^${BASE}/live/([A-Za-z0-9_-]{32})$`).exec(path);
      if (temporaryLive && req.method === 'GET') return reply(200,store.games.publicRead(temporaryLive[1],undefined,true));
      const watch = new RegExp(`^${BASE}/watch/([A-Za-z0-9_-]{32})(?:/(apply))?$`).exec(path);
      if (watch) {
        const session = /(?:^|; )bm_view=([A-Za-z0-9_-]{43})(?:;|$)/.exec(req.headers.cookie ?? '')?.[1];
        if (req.method === 'GET' && !watch[2]) return reply(200, store.games.publicRead(watch[1], session));
        if (req.method === 'POST' && watch[2] === 'apply') {
          const origin = req.headers.origin;
          requireValue(origin === 'https://baseballmaster.cc' || (options.allowLocalViewer && origin === 'http://' + req.headers.host),403,'origin','请从观看页面申请。');
          const result = store.games.applyViewer(watch[1],body,session);
          res.setHeader('Set-Cookie', `bm_view=${body.session}; HttpOnly; ${options.allowLocalViewer ? '' : 'Secure; '}SameSite=Strict; Path=${BASE}/watch/${watch[1]}; Max-Age=31536000`);
          return reply(200,result);
        }
      }
      const deviceID = store.authenticate(token);
      const recoveryEpoch=store.db.prepare("SELECT value FROM metadata WHERE key='recoveryEpoch'").get().value;
      if(path===BASE+'/devices/recovery-review' && req.method==='POST') {
        requireValue(body.recoveryEpoch===recoveryEpoch,409,'recovery_changed','服务资料仍在变化，请重新核对。');
        store.db.prepare('INSERT INTO device_recovery_reviews VALUES(?,?) ON CONFLICT(device_id) DO UPDATE SET epoch=excluded.epoch').run(deviceID,recoveryEpoch);
        return reply(200,{reviewed:true,recoveryEpoch});
      }
      if(req.method!=='GET' && store.db.prepare("SELECT value FROM metadata WHERE key='needsDeviceRecoveryReview'").get()?.value==='1') {
        requireValue(store.db.prepare('SELECT epoch FROM device_recovery_reviews WHERE device_id=?').get(deviceID)?.epoch===recoveryEpoch,
          409,'recovery_review','请先核对恢复后的球队资料，原操作不会自动重做。');
      }
      if(path===BASE+'/devices/credential-prepare' && req.method==='POST') return reply(200,store.credentials.prepare(deviceID,body));
      if(path===BASE+'/dissolved-teams' && req.method==='GET') return reply(200,store.lifecycle.list(deviceID));
      const archive = new RegExp(`^${BASE}/teams/([a-f0-9-]+)/archive(?:/([a-f0-9-]+|finish))?$`,'i').exec(path);
      if(archive && uuid(archive[1])) {
        if(!archive[2] && req.method==='GET') return reply(200,store.archives.manifest(archive[1],deviceID));
        if(archive[2]==='finish' && req.method==='POST') return reply(200,store.archives.finish(archive[1],deviceID,body));
        if(uuid(archive[2]) && req.method==='GET') return reply(200,store.archives.item(archive[1],archive[2],deviceID,new URL(req.url,'http://localhost').searchParams.get('snapshot')));
      }
      const lifecyclePath=new RegExp(`^${BASE}/teams/([a-f0-9-]+)/(dissolve|restore)$`).exec(path);
      if(lifecyclePath && uuid(lifecyclePath[1]) && req.method==='POST') {
        return reply(200,store.lifecycle[lifecyclePath[2]==='dissolve'?'dissolve':'restore'](lifecyclePath[1],deviceID,body));
      }
      if (path.startsWith(BASE+'/recovery/')) {
        const limitKey='recovery:'+deviceID, bucket=windows.get(limitKey) ?? {count:0,until:now+600000};
        bucket.count++;windows.set(limitKey,bucket);
        requireValue(bucket.count<=(options.recoveryAttempts ?? 12),429,'rate_limited','恢复尝试过于频繁，请稍后再试。');
        if(path===BASE+'/recovery/preview' && req.method==='POST')return reply(200,store.security.recoveryPreview(body));
        if(path===BASE+'/recovery/redeem' && req.method==='POST')return reply(200,store.security.recover(deviceID,body));
      }
      if(path===BASE+'/pairings' && req.method==='POST')return reply(200,store.security.createPair(deviceID,body));
      const pairing=new RegExp(`^${BASE}/pairings/([a-f0-9-]+)(?:/(accept|cancel))?$`).exec(path);
      if(pairing && uuid(pairing[1])) {
        if(req.method==='GET' && !pairing[2])return reply(200,store.security.readPair(pairing[1],deviceID));
        if(req.method==='POST' && pairing[2]==='accept')return reply(200,store.security.acceptPair(pairing[1],deviceID,body));
        if(req.method==='POST' && pairing[2]==='cancel')return reply(200,store.security.cancelPair(pairing[1],deviceID,body));
      }
      const securityPath=new RegExp(`^${BASE}/teams/([a-f0-9-]+)/(devices|pairings|recovery)(?:/([a-f0-9-]+|preview|rotate)(?:/(approve))?)?$`).exec(path);
      if(securityPath && uuid(securityPath[1])) {
        const [,team,resource,object,action]=securityPath,svc=store.security;
        if(resource==='devices' && !object && req.method==='GET')return reply(200,svc.devices(team,deviceID));
        if(resource==='devices' && uuid(object) && req.method==='DELETE')return reply(200,svc.removeDevice(team,object,deviceID,body));
        if(resource==='pairings' && object==='preview' && req.method==='POST')return reply(200,svc.previewPair(team,deviceID,body));
        if(resource==='pairings' && uuid(object) && action==='approve' && req.method==='POST')return reply(200,svc.approvePair(team,object,deviceID,body));
        if(resource==='recovery' && !object && req.method==='GET')return reply(200,svc.recoveryStatus(team,deviceID));
        if(resource==='recovery' && object==='rotate' && req.method==='POST')return reply(200,svc.rotateRecovery(team,deviceID,body));
      }
      const importPath = new RegExp(`^${BASE}/teams/([a-f0-9-]+)/history-imports/(preview|commit)$`).exec(path);
      if (importPath && uuid(importPath[1]) && req.method === 'POST') {
        const [,team,action]=importPath;
        return reply(200,store.imports[action](team,deviceID,body));
      }
      const draftPath = new RegExp(`^${BASE}/teams/([a-f0-9-]+)/games/([a-f0-9-]+)/drafts(?:/([a-f0-9-]+))?$`).exec(path);
      if (draftPath && uuid(draftPath[1]) && uuid(draftPath[2]) && (!draftPath[3] || uuid(draftPath[3]))) {
        const [,team,game,draft] = draftPath;
        if (req.method === 'GET') return reply(200,draft ? store.drafts.read(team,game,draft,deviceID) : store.drafts.list(team,game,deviceID));
        if (req.method === 'POST') return reply(200,draft ? store.drafts.resolve(team,game,draft,deviceID,body) : store.drafts.submit(team,game,deviceID,body));
      }
      const gamePath = new RegExp(`^${BASE}/teams/([a-f0-9-]+)/games(?:/([a-f0-9-]+)(?:/(participants|handoff|access|viewers|viewer-invitations|member-ticket|live|live-heartbeat|deletion|player-links)(?:/(propose|accept|cancel|force))?)?)?$`).exec(path);
      if (gamePath && uuid(gamePath[1]) && (!gamePath[2] || uuid(gamePath[2]))) {
        const [,team,game,resource,action] = gamePath, service = store.games;
        if (!game && req.method === 'GET') return reply(200,service.list(team,deviceID,Number(new URL(req.url,'http://localhost').searchParams.get('cursor') ?? 0)));
        if (!game && req.method === 'POST') return reply(200,service.create(team,deviceID,body));
        if (game && !resource && req.method === 'GET') return reply(200,service.document(team,game,deviceID));
        if (game && !resource && req.method === 'PUT') return reply(200,service.update(team,game,deviceID,body));
        if (resource === 'player-links' && !action && req.method === 'POST') return reply(200,store.playerLinks.update(team,game,deviceID,body));
        if (resource === 'player-links' && !action && req.method === 'GET') return reply(200,store.playerLinks.history(team,game,deviceID));
        if (resource === 'participants' && req.method === 'GET') return reply(200,service.participants(team,game,deviceID));
        if (resource === 'handoff' && req.method === 'POST') return reply(200,service.handoff(team,game,deviceID,action,body));
        if (resource === 'access' && req.method === 'POST') return reply(200,service.access(team,game,deviceID,body));
        if (resource === 'viewers' && req.method === 'GET') return reply(200,service.viewers(team,game,deviceID));
        if (resource === 'viewers' && req.method === 'POST') return reply(200,service.viewerChange(team,game,deviceID,body));
        if (resource === 'viewer-invitations' && req.method === 'POST') return reply(200,service.inviteViewer(team,game,deviceID,body));
        if (resource === 'member-ticket' && req.method === 'POST') return reply(200,service.memberTicket(team,game,deviceID,body));
        if (resource === 'deletion' && req.method === 'POST') return reply(200,service.deleteOrRestore(team,game,deviceID,body));
        if (resource === 'live-heartbeat' && req.method === 'POST') return reply(200,service.heartbeat(team,game,deviceID));
        if (resource === 'live' && req.method === 'POST') return reply(200,service.live(team,game,deviceID,body));
      }
      if (path === BASE + '/teams') {
        if (req.method === 'GET') return reply(200, store.listTeams(deviceID));
        if (req.method === 'POST') return reply(200, store.createTeam(deviceID, body));
      }
      if (path === BASE + '/invitations/redeem' && req.method === 'POST') return reply(200, store.redeem(deviceID, body));
      if (path === BASE + '/invitations/preview' && req.method === 'POST') return reply(200, store.previewInvite(deviceID, body));
      const match = new RegExp(`^${BASE}/teams/([a-f0-9-]+)(?:/(members|invitations|team-invitation|audit|leave|roster)(?:/([a-f0-9-]+))?)?$`, 'i').exec(path);
      if (match && uuid(match[1])) {
        const [, teamID, resource, objectID] = match;
        if (!resource && req.method === 'GET') {
          return reply(200, store.roster.read(teamID, deviceID));
        }
        if (resource === 'team-invitation' && !objectID && req.method === 'POST') return reply(200, store.fixedInvite(teamID,deviceID,body));
        if (resource === 'roster' && !objectID && req.method === 'PATCH') return reply(200, store.roster.update(teamID, deviceID, body));
        if (resource === 'leave' && !objectID && req.method === 'POST') return reply(200, store.leave(teamID, deviceID, body));
        if (resource === 'invitations' && !objectID && req.method === 'GET') {
          store.membership(teamID, deviceID, 'invite');
          return reply(200, { invitations: store.db.prepare(`SELECT id AS invitationID,role,expires_at AS expiresAt
            FROM invitations WHERE team_id=? AND revoked=0 AND consumed_by IS NULL AND expires_at>? ORDER BY expires_at DESC,id`).all(teamID, store.now()) });
        }
        if (resource === 'invitations' && !objectID && req.method === 'POST') return reply(200, store.invite(teamID, deviceID, body));
        if (resource === 'invitations' && uuid(objectID) && req.method === 'DELETE') return reply(200, store.revokeInvite(teamID, objectID, deviceID, body));
        if (resource === 'members' && uuid(objectID) && req.method === 'PATCH') return reply(200, store.changeMember(teamID, objectID, deviceID, body));
        if (resource === 'members' && !objectID && req.method === 'GET') {
          store.membership(teamID, deviceID, 'manageMembers');
          return reply(200, { members: store.db.prepare('SELECT id,nickname,role,revision FROM members WHERE team_id=? AND active=1 ORDER BY id').all(teamID) });
        }
        if (resource === 'audit' && !objectID && req.method === 'GET') {
          return reply(200,store.auditPage(teamID,deviceID,new URL(req.url,'http://localhost').searchParams));
        }
      }
      throw new APIError(404, 'not_found', '页面不存在。');
    } catch (e) {
      // No body, bearer token, invitation or database exception is ever logged or returned.
      reply(e instanceof APIError || e instanceof LaunchScreenError ? e.status : 503,
        { error: { code: e instanceof APIError ? e.code : e instanceof LaunchScreenError ? 'launch_screen' : 'service_unavailable', message: e instanceof APIError || e instanceof LaunchScreenError ? e.message : '服务暂时不可用，请稍后重试。' } });
    }
  });
  server.requestTimeout = 15_000; server.headersTimeout = 10_000;
  return { server, store };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.umask(0o077);
  const { server, store } = createCollaborationServer({ database: process.env.BM_TEAM_DATABASE ?? fileURLToPath(new URL('./data/collaboration.sqlite', import.meta.url)),
    admin:process.env.BM_ADMIN_CONFIG ? {...JSON.parse(readFileSync(process.env.BM_ADMIN_CONFIG,'utf8')),trustProxy:process.env.BM_TEAM_TRUST_PROXY==='1'} : undefined,
    trustProxy:process.env.BM_TEAM_TRUST_PROXY==='1', requestsPerMinute:Number(process.env.BM_TEAM_REQUESTS_PER_MINUTE ?? 600),
    minimumFreeBytes:process.env.NODE_ENV==='production'?2*1024**3:0 });
  if(process.env.NODE_ENV==='production' || process.env.BM_TEAM_SAFETY_JOURNAL) {
    const {SafetyJournal}=await import('./safety-journal.mjs'),{readBackupKey}=await import('./backup.mjs');
    const key=readBackupKey(process.env.BM_TEAM_BACKUP_KEY),directory=process.env.BM_TEAM_SAFETY_JOURNAL;
    if(!directory)throw new Error('Independent safety journal is required in production');
    const previous=store.db.prepare("SELECT value FROM metadata WHERE key='safetyJournalID'").get()?.value;
    const journalID=previous ?? randomUUID();
    const journal=new SafetyJournal({directory,key,journalID,create:!previous});
    store.db.prepare("INSERT OR IGNORE INTO metadata VALUES('safetyJournalID',?)").run(journalID);
    store.safetyJournal=journal;journal.record(store);
  }
  const {maintain}=await import('./maintenance.mjs');
  maintain(store);
  const upkeep=setInterval(()=>{try{maintain(store);}catch{process.stderr.write('Collaboration maintenance failed; retry required.\n');}},3600000);upkeep.unref();
  const port = Number(process.env.BM_TEAM_PORT ?? 8788);
  server.listen(port, '127.0.0.1', () => process.stdout.write(`Collaboration API listening on 127.0.0.1:${port}\n`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => server.close(() => { store.close(); process.exit(0); }));
}
