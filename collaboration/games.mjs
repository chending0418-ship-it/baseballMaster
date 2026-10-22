import { randomUUID, createHash, randomBytes } from 'node:crypto';
import { validateSnapshot } from '../live/server.mjs';

const hash = value => createHash('sha256').update(value).digest('hex');
const uuid = v => typeof v === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(v);
const id = v => v.toLowerCase();
const int = (v, min = 0, max = 1e7) => Number.isSafeInteger(v) && v >= min && v <= max;
const secret = v => typeof v === 'string' && /^[A-Za-z0-9_-]{43}$/.test(v) && Buffer.from(v, 'base64url').toString('base64url') === v;
const text = (v, max = 200) => typeof v === 'string' && v.length <= max;
const dictionary = value => {
  if (Array.isArray(value)) {
    if (value.length % 2) return null;
    const entries = []; for (let i = 0; i < value.length; i += 2) entries.push([value[i], value[i + 1]]);
    if (new Set(entries.map(([key]) => key)).size !== entries.length) return null;
    return Object.fromEntries(entries);
  }
  return value && typeof value === 'object' ? value : null;
};
const statKeys = {
  batting: ['plateAppearances','atBats','hits','doubles','triples','homeRuns','runs','runsBattedIn','walks','hitByPitch','strikeouts','sacrifices','stolenBases','caughtStealing','reachedOnError','groundedIntoDoublePlay'],
  pitching: ['outsRecorded','battersFaced','hits','runs','earnedRuns','walks','hitByPitch','strikeouts','wildPitches','pitches','strikes'],
  fielding: ['putouts','assists','errors','passedBalls','doublePlays']
};

export class GameService {
  constructor(store, requireValue) {
    this.store = store; this.db = store.db; this.check = requireValue;
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS shared_games(id TEXT PRIMARY KEY,team_id TEXT NOT NULL REFERENCES teams(id),
        source_id TEXT NOT NULL,origin_device TEXT NOT NULL,revision INTEGER NOT NULL,epoch INTEGER NOT NULL,
        writer_device TEXT NOT NULL,responsible_member TEXT NOT NULL,payload TEXT NOT NULL,projection TEXT NOT NULL,
        digest TEXT NOT NULL,updated_at INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,
        access_mode TEXT NOT NULL DEFAULT 'closed',access_version INTEGER NOT NULL DEFAULT 1,
        live_enabled INTEGER NOT NULL DEFAULT 0,view_code TEXT NOT NULL UNIQUE,
        UNIQUE(team_id,origin_device,source_id));
      CREATE UNIQUE INDEX IF NOT EXISTS shared_source ON shared_games(team_id,source_id);
      CREATE TABLE IF NOT EXISTS game_changes(sequence INTEGER PRIMARY KEY AUTOINCREMENT,team_id TEXT NOT NULL,game_id TEXT NOT NULL);
      CREATE INDEX IF NOT EXISTS changes_team ON game_changes(team_id,sequence);
      CREATE TABLE IF NOT EXISTS cursor_floors(team_id TEXT PRIMARY KEY,sequence INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS handoffs(game_id TEXT PRIMARY KEY REFERENCES shared_games(id),id TEXT NOT NULL,
        source_device TEXT NOT NULL,target_device TEXT NOT NULL,target_member TEXT NOT NULL,revision INTEGER NOT NULL,epoch INTEGER NOT NULL,digest TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS viewer_invites(id TEXT PRIMARY KEY,game_id TEXT NOT NULL REFERENCES shared_games(id),
        token_hash TEXT UNIQUE NOT NULL,expires_at INTEGER NOT NULL,used INTEGER NOT NULL DEFAULT 0,revoked INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS viewers(id TEXT PRIMARY KEY,game_id TEXT NOT NULL REFERENCES shared_games(id),nickname TEXT NOT NULL,
        session_hash TEXT NOT NULL UNIQUE,status TEXT NOT NULL,member_id TEXT,created_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS viewer_members(game_id TEXT NOT NULL REFERENCES shared_games(id),member_id TEXT NOT NULL REFERENCES members(id),
        PRIMARY KEY(game_id,member_id));
      CREATE TABLE IF NOT EXISTS member_view_tickets(token_hash TEXT PRIMARY KEY,game_id TEXT NOT NULL,member_id TEXT NOT NULL,
        device_id TEXT NOT NULL,expires_at INTEGER NOT NULL,used INTEGER NOT NULL DEFAULT 0);
    `);
    const columns = new Set(this.db.prepare('PRAGMA table_info(shared_games)').all().map(c => c.name));
    for (const [name,type] of [['live_code','TEXT'],['live_expires_at','INTEGER'],['live_final_at','INTEGER'],['purged','INTEGER NOT NULL DEFAULT 0']]) {
      if (!columns.has(name)) this.db.exec(`ALTER TABLE shared_games ADD COLUMN ${name} ${type}`);
    }
    this.db.exec('CREATE UNIQUE INDEX IF NOT EXISTS game_live_code ON shared_games(live_code)');
  }
  memberChanged(team, member, device, revokedWriter, removed) {
    if (revokedWriter) {
      for (const row of this.db.prepare('SELECT * FROM shared_games WHERE team_id=? AND responsible_member=?').all(team,member)) {
        this.db.prepare('UPDATE shared_games SET epoch=epoch+1 WHERE id=?').run(row.id);
        this.db.prepare('DELETE FROM handoffs WHERE game_id=?').run(row.id);
        this.changed(row,device,'writerAuthorizationRevoked');
      }
      const abandoned=this.db.prepare('SELECT g.* FROM shared_games g JOIN handoffs h ON h.game_id=g.id WHERE h.target_member=?').all(member);
      this.db.prepare('DELETE FROM handoffs WHERE target_member=?').run(member);
      for(const row of abandoned) this.changed(row,device,'handoffTargetRemoved');
    }
    if (removed) {
      this.db.prepare('DELETE FROM viewer_members WHERE member_id=?').run(member);
      this.db.prepare("UPDATE viewers SET status='revoked' WHERE member_id=?").run(member);
      this.db.prepare('DELETE FROM member_view_tickets WHERE member_id=?').run(member);
    }
  }
  deleteOrRestore(team, game, device, body) {
    this.check(typeof body.deleted === 'boolean');
    return this.store.operation(device,body,`deleteGame:${team}:${game}`,()=>this.store.membership(team,device,'deleteGame'),()=>{
      const row=this.row(team,game,device);
      this.check(body.baseRevision===row.revision && body.writerEpoch===row.epoch,409,'revision_conflict','比赛已有变化，请刷新核对。');
      this.check(!row.purged,410,'restore_expired','比赛已超过保留期限，无法恢复。');
      this.check(body.deleted || this.store.now()-row.updated_at <= 30*86400000,410,'restore_expired','已超过恢复期限。');
      this.db.prepare("UPDATE shared_games SET deleted=?,revision=revision+1,epoch=epoch+1,access_mode='closed',access_version=access_version+1,live_enabled=0,updated_at=? WHERE id=?")
        .run(body.deleted?1:0,this.store.now(),game);
      this.db.prepare('DELETE FROM handoffs WHERE game_id=?').run(game);this.revokeAll(game);
      const next=this.row(team,game,device);this.changed(next,device,body.deleted?'gameDeleted':'gameRestored');return this.metadata(next,device);
    });
  }
  changed(row, device, action) {
    this.db.prepare('INSERT INTO game_changes(team_id,game_id) VALUES(?,?)').run(row.team_id, row.id);
    this.store.audit(row.team_id, device, action, row.id);
  }
  row(team, game, device) {
    this.store.membership(team, device, 'readGame');
    const row = this.db.prepare('SELECT * FROM shared_games WHERE team_id=? AND id=?').get(team, game);
    this.check(row, 404, 'not_found', '比赛不存在或无权查看。'); return row;
  }
  writer(row, device) {
    const m = this.store.membership(row.team_id, device);
    this.check(['admin','scorer'].includes(m.role) && row.writer_device === device && row.responsible_member === m.id,
      409, 'writer_changed', '记分权已经变化，本机记录已保留，请核对交接。');
    this.check(!row.deleted, 409, 'deleted', '比赛已删除，本机草稿保留。');
  }
  manager(row, device) {
    const m = this.store.membership(row.team_id, device);
    this.check(m.role === 'admin' || (m.role === 'scorer' && row.responsible_member === m.id), 403, 'forbidden', '只有管理员或负责记录员可以管理此场回看。');
  }
  target(team, device) {
    this.check(uuid(device));
    const m = this.store.membership(team, id(device));
    this.check(['admin','scorer'].includes(m.role), 409, 'invalid_target', '接手设备必须属于有效记录员或管理员。'); return m;
  }
  metadata(row, device) {
    const member = this.store.membership(row.team_id, device);
    const h = this.db.prepare('SELECT * FROM handoffs WHERE game_id=?').get(row.id);
    const writer = this.db.prepare(`SELECT m.role,m.active,d.active AS linked FROM members m JOIN member_devices d ON d.member_id=m.id
      WHERE m.id=? AND d.device_id=?`).get(row.responsible_member, row.writer_device);
    return { ...this.store.imports?.info(row.id), playerLinks: this.store.playerLinks?.links(row.id) ?? [], id: row.id, teamID: row.team_id, serverRevision: row.revision, writerEpoch: row.epoch,
      writerDeviceID: row.writer_device, responsibleMemberID: row.responsible_member, updatedAt: row.updated_at,
      deleted: Boolean(row.deleted), digest: row.digest, accessMode: row.access_mode, accessVersion: row.access_version,
      viewCode: row.view_code, liveEnabled: Boolean(row.live_enabled && row.live_expires_at > this.store.now()),
      liveCode: row.live_code, liveExpiresAt: row.live_expires_at,
      canWrite: !row.deleted && !h && row.writer_device === device && member.id === row.responsible_member && ['admin','scorer'].includes(member.role),
      writerActive: Boolean(writer?.active && writer?.linked && ['admin','scorer'].includes(writer?.role)),
      canManageAccess: member.role === 'admin' || (member.role === 'scorer' && member.id === row.responsible_member),
      handoff: h ? { id: h.id, sourceDeviceID: h.source_device, targetDeviceID: h.target_device, revision: h.revision, epoch: h.epoch, digest: h.digest } : null };
  }
  document(team, game, device) {
    const row = this.row(team, game, device);
    return { ...this.metadata(row, device), payload: row.deleted ? null : JSON.parse(row.payload), projection: row.deleted ? null : JSON.parse(row.projection) };
  }
  validate(body) {
    const p = body.payload, s = p?.state, projection = body.projection;
    this.check(body.formatVersion === 1, 426, 'upgrade_required', '比赛格式不兼容，请更新 App。');
    this.check(p && uuid(p.id) && [0,1,2].includes(p.status) && text(p.seasonID, 100)
      && Number.isFinite(p.createdAt) && Number.isFinite(p.updatedAt) && p.rules && s && typeof s.isFinal === 'boolean');
    this.check((p.status === 1) === s.isFinal && int(s.inning, 1, 999) && int(s.outs, 0, 3)
      && int(s.balls, 0, 20) && int(s.strikes, 0, 3) && typeof s.isTop === 'boolean');
    this.check(['standard','coachPitch','slowPitch'].includes((p.rules.mode ?? 'standard')));
    for (const k of ['playedAt','scheduledAt','startedAt']) this.check(p[k] == null || Number.isFinite(p[k]));
    const players = new Map();
    for (const side of ['home','away']) {
      const team = s[side + 'Team'], innings = s[side + 'RunsByInning'];
      this.check(team && uuid(team.id) && text(team.name) && Array.isArray(team.players) && team.players.length <= 200);
      const ids = new Set();
      for (const player of team.players) {
        this.check(uuid(player.id) && !ids.has(id(player.id)) && text(player.chineseName) && text(player.englishName));
        ids.add(id(player.id)); players.set(id(player.id), player);
      }
      this.check(Array.isArray(innings) && innings.length > 0 && innings.length <= 999 && innings.every(n => int(n)));
      this.check(int(s[side + 'Hits']) && int(s[side + 'Errors']));
      const order = s[side + 'BattingOrderIDs'];
      this.check(Array.isArray(order) && order.length <= 200 && new Set(order.map(id)).size === order.length && order.every(v => uuid(v) && ids.has(id(v))));
      for (const field of [side + 'FieldingPlayerIDs']) this.check(s[field] == null || (Array.isArray(s[field]) && s[field].every(v => uuid(v) && ids.has(id(v)))));
      this.check(s['active' + (side === 'home' ? 'Home' : 'Away') + 'PitcherID'] == null || ids.has(id(s['active' + (side === 'home' ? 'Home' : 'Away') + 'PitcherID'])));
    }
    let clean;
    try { clean = validateSnapshot(projection); } catch { this.check(false); }
    this.check(id(clean.gameID) === id(p.id) && clean.mode === (p.rules.mode ?? 'standard') && clean.isFinal === s.isFinal && clean.inning === s.inning && clean.isTop === s.isTop
      && clean.balls === s.balls && clean.strikes === s.strikes && clean.outs === s.outs);
    for (const side of ['home','away']) this.check(clean[side].name === s[side + 'Team'].name && clean[side].runs === s[side + 'RunsByInning'].reduce((a,b) => a+b,0)
      && clean[side].hits === s[side + 'Hits'] && clean[side].errors === s[side + 'Errors']);
    const box = {};
    for (const [category, keys] of Object.entries(statKeys)) {
      const lines = dictionary(s[category]); this.check(lines && Object.keys(lines).length <= 400);
      box[category] = [];
      for (const [key,line] of Object.entries(lines)) {
        this.check(uuid(key) && players.has(id(key)) && line && typeof line === 'object' && Object.values(line).every(v => int(v)));
        if (category === 'pitching') this.check(line.earnedRuns <= line.runs && line.strikes <= line.pitches);
        if (category === 'batting') this.check(line.hits <= line.atBats);
        const player = players.get(id(key));
        box[category].push({ player: { id: key, name: player.chineseName || player.englishName || '未填写姓名',
          number: (player.jerseyNumbers ?? player.numbers ?? []).map(String).join('/') },
          values: Object.fromEntries(keys.filter(k => k in line).map(k => [k,line[k]])) });
      }
    }
    const serialized = JSON.stringify(p);
    this.check(Buffer.byteLength(serialized) <= 16 * 1024 * 1024, 413, 'too_large', '单场比赛资料超过限制，请保留本机记录并联系支持。');
    return { payload: serialized, projection: JSON.stringify({ snapshot: clean, box, playedAt: (p.playedAt ?? p.startedAt ?? p.scheduledAt ?? p.createdAt) * 1000 + 978307200000,
      timeZone: p.playedTimeZoneID ?? null, statisticsIncomplete: s.statisticsIncomplete === true }), digest: hash(serialized) };
  }
  create(team, device, body) {
    const valid = this.validate(body);
    return this.store.operation(device, body, `createGame:${team}`, () => this.store.membership(team, device, 'createGame'), () => {
      const member = this.store.membership(team, device);
      this.check(!this.db.prepare('SELECT id FROM shared_games WHERE team_id=? AND source_id=?').get(team, id(body.payload.id)), 409, 'duplicate_source', '这场比赛已经共享，请从已有比赛继续。');
      this.store.checkGameCapacity(team,valid);
      const game = randomUUID();
      this.db.prepare(`INSERT INTO shared_games(id,team_id,source_id,origin_device,revision,epoch,writer_device,responsible_member,payload,projection,digest,updated_at,view_code)
        VALUES(?,?,?,?,1,1,?,?,?,?,?,?,?)`).run(game,team,id(body.payload.id),device,device,member.id,valid.payload,valid.projection,valid.digest,this.store.now(),randomBytes(24).toString('base64url'));
      const row = this.row(team,game,device); this.changed(row, device, 'gameCreated'); return this.metadata(row,device);
    });
  }
  update(team, game, device, body) {
    const valid = this.validate(body);
    return this.store.operation(device,body,`updateGame:${team}:${game}`, () => this.store.membership(team,device), () => {
      const row = this.row(team,game,device); this.writer(row,device);
      this.check(!this.db.prepare('SELECT id FROM handoffs WHERE game_id=?').get(game),409,'handoff_pending','正在交接，请暂停记分。');
      this.check(body.writerEpoch === row.epoch && body.baseRevision === row.revision,409,'revision_conflict','比赛已有新版本，本机修改已保留，请核对。');
      this.check(id(body.payload.id) === id(JSON.parse(row.payload).id));
      this.store.checkGameCapacity(team,valid,game);
      const links = this.store.playerLinks.links(game);
      if (links.length) {
        this.check(body.payload.isHome === JSON.parse(row.payload).isHome && !body.payload.isSpectator,409,'invalid_link_match','已有球员对应，请先核对关联再调整球队身份。');
        this.store.playerLinks.validate(team,body.payload,links);
      }
      const final = body.payload.state.isFinal, oldFinal = JSON.parse(row.payload).state.isFinal;
      const activeLive = row.live_enabled && row.live_expires_at > this.store.now();
      // The existing live-link lifecycle is independent of archive permissions:
      // one hour after the first final sync, or one hour without an ongoing sync.
      // Corrections to a final match never extend its temporary live expiry.
      const firstFinalAt = final ? (oldFinal ? row.live_final_at : this.store.now()) : null;
      const expires = activeLive ? (final ? (oldFinal ? row.live_expires_at : this.store.now()+3600000) : this.store.now()+3600000) : row.live_expires_at;
      this.db.prepare(`UPDATE shared_games SET payload=?,projection=?,digest=?,revision=revision+1,updated_at=?,live_expires_at=?,live_final_at=? WHERE id=?`)
        .run(valid.payload,valid.projection,valid.digest,this.store.now(),expires,firstFinalAt,game);
      const next = this.row(team,game,device); this.changed(next,device,'gameUpdated'); return this.metadata(next,device);
    });
  }
  list(team,device,cursor = 0) {
    this.store.membership(team,device); this.check(int(cursor));
    const floor = this.db.prepare('SELECT sequence FROM cursor_floors WHERE team_id=?').get(team)?.sequence ?? 0;
    this.check(cursor === 0 || cursor >= floor,410,'cursor_expired','同步记录已整理，请重新核对完整球队资料。本机草稿会保留。');
    const rows = this.db.prepare(`SELECT g.*,MAX(c.sequence) AS sequence FROM shared_games g JOIN game_changes c ON c.game_id=g.id
      WHERE g.team_id=? GROUP BY g.id HAVING MAX(c.sequence)>? ORDER BY sequence LIMIT 40`).all(team,cursor);
    return { games: rows.map(r => ({ ...this.metadata(r,device), title: r.deleted ? '' : (() => { const s=JSON.parse(r.projection).snapshot; return s.away.name+' vs '+s.home.name; })(),
      status: r.deleted ? 'deleted' : ['ongoing','completed','scheduled'][JSON.parse(r.payload).status] })), cursor: rows.at(-1)?.sequence ?? cursor, hasMore: rows.length === 40,
      recoveryEpoch: this.db.prepare("SELECT value FROM metadata WHERE key='recoveryEpoch'").get().value };
  }
  compactChanges(team) {
    // Keep the latest entry for every object, including tombstones. Cursor zero can always rebuild.
    return this.store.transaction(() => {
      const floor = this.db.prepare(`SELECT MAX(sequence) AS sequence FROM game_changes WHERE team_id=?
        AND sequence NOT IN (SELECT MAX(sequence) FROM game_changes WHERE team_id=? GROUP BY game_id)`).get(team,team).sequence;
      if (floor == null) return 0;
      const deleted = this.db.prepare(`DELETE FROM game_changes WHERE team_id=? AND sequence NOT IN
        (SELECT MAX(sequence) FROM game_changes WHERE team_id=? GROUP BY game_id)`).run(team,team).changes;
      this.db.prepare(`INSERT INTO cursor_floors VALUES(?,?) ON CONFLICT(team_id) DO UPDATE SET sequence=MAX(sequence,excluded.sequence)`).run(team,floor);
      return deleted;
    });
  }
  participants(team,game,device) {
    const row = this.row(team,game,device), m = this.store.membership(team,device);
    this.check(m.role === 'admin' || row.responsible_member === m.id,403,'forbidden','当前角色不能交接此场比赛。');
    return { devices: this.db.prepare(`SELECT d.device_id AS deviceID,m.id AS memberID,m.nickname,dev.label FROM member_devices d JOIN members m ON m.id=d.member_id
      JOIN devices dev ON dev.id=d.device_id WHERE d.team_id=? AND d.active=1 AND m.active=1 AND m.role IN ('admin','scorer') AND dev.revoked=0 ORDER BY m.nickname,dev.id`).all(team) };
  }
  handoff(team,game,device,action,body) {
    return this.store.operation(device,body,`handoff:${team}:${game}:${action}`, () => this.store.membership(team,device), () => {
      const row = this.row(team,game,device); this.check(!row.deleted,409,'deleted','比赛已删除。');
      if (action === 'propose') {
        this.writer(row,device); const target = this.target(team,body.targetDeviceID);
        this.check(id(body.targetDeviceID) !== device && body.baseRevision === row.revision && body.writerEpoch === row.epoch,409,'revision_conflict','请先同步最新记录再交接。');
        this.check(!this.db.prepare('SELECT id FROM handoffs WHERE game_id=?').get(game),409,'handoff_pending','已有交接正在等待确认。');
        this.db.prepare('INSERT INTO handoffs VALUES(?,?,?,?,?,?,?,?)').run(game,randomUUID(),device,id(body.targetDeviceID),target.id,row.revision,row.epoch,row.digest);
      } else if (action === 'accept') {
        const h = this.db.prepare('SELECT * FROM handoffs WHERE game_id=?').get(game);
        this.check(h && h.target_device === device && body.handoffID === h.id && body.digest === row.digest && body.baseRevision === row.revision && body.writerEpoch === row.epoch,
          409,'handoff_changed','交接资料已变化，请重新读取并核对。');
        this.target(team,device); this.writer(row,h.source_device);
        this.db.prepare('UPDATE shared_games SET writer_device=?,responsible_member=?,epoch=epoch+1 WHERE id=?').run(device,h.target_member,game);
        this.db.prepare('DELETE FROM handoffs WHERE game_id=?').run(game);
      } else if (action === 'cancel') {
        const h=this.db.prepare('SELECT * FROM handoffs WHERE game_id=?').get(game);
        this.check(h && h.source_device === device && h.id === body.handoffID,409,'handoff_changed','交接状态已变化，请刷新。');
        this.db.prepare('DELETE FROM handoffs WHERE game_id=?').run(game);
      } else if (action === 'force') {
        this.store.membership(team,device,'forceHandoff'); const target=this.target(team,body.targetDeviceID);
        this.check(body.confirmRisk === true && body.baseRevision === row.revision && body.writerEpoch === row.epoch,409,'revision_conflict','请核对最后同步版本并确认旧设备可能有未同步记录。');
        this.db.prepare('UPDATE shared_games SET writer_device=?,responsible_member=?,epoch=epoch+1 WHERE id=?').run(id(body.targetDeviceID),target.id,game);
        this.db.prepare('DELETE FROM handoffs WHERE game_id=?').run(game);
      } else this.check(false);
      const next=this.row(team,game,device); this.changed(next,device,'handoff:'+action); return this.metadata(next,device);
    });
  }
  access(team,game,device,body) {
    this.check(['closed','public','restricted'].includes(body.mode));
    return this.store.operation(device,body,`access:${team}:${game}`, () => this.manager(this.row(team,game,device),device), () => {
      const row=this.row(team,game,device);
      this.check(!row.deleted && JSON.parse(row.payload).state.isFinal,409,'not_final','终场后才能设置历史观看权限。');
      this.check(body.baseAccessVersion === row.access_version,409,'access_conflict','观看设置已有更新，请刷新核对。');
      this.db.prepare('UPDATE shared_games SET access_mode=?,access_version=access_version+1 WHERE id=?').run(body.mode,game);
      if (body.mode === 'closed') this.revokeAll(game);
      const next=this.row(team,game,device); this.changed(next,device,'historicalAccessChanged'); return this.metadata(next,device);
    });
  }
  revokeAll(game) {
    this.db.prepare("UPDATE viewers SET status='revoked' WHERE game_id=?").run(game);
    this.db.prepare('DELETE FROM viewer_members WHERE game_id=?').run(game);
    this.db.prepare('UPDATE viewer_invites SET revoked=1 WHERE game_id=?').run(game);
    this.db.prepare('DELETE FROM member_view_tickets WHERE game_id=?').run(game);
  }
  viewers(team,game,device) {
    this.manager(this.row(team,game,device),device);
    return { viewers: this.db.prepare('SELECT id,nickname,status,member_id AS memberID FROM viewers WHERE game_id=? ORDER BY created_at').all(game),
      members: this.db.prepare(`SELECT m.id,m.nickname,CASE WHEN v.member_id IS NULL THEN 0 ELSE 1 END AS granted FROM members m LEFT JOIN viewer_members v
        ON v.member_id=m.id AND v.game_id=? WHERE m.team_id=? AND m.active=1`).all(game,team) };
  }
  viewerChange(team,game,device,body) {
    return this.store.operation(device,body,`viewerChange:${team}:${game}`, () => this.manager(this.row(team,game,device),device), () => {
      const row=this.row(team,game,device); this.check(!row.deleted && row.access_mode === 'restricted',409,'access_changed','请先设为仅指定人员。');
      this.check(body.baseAccessVersion === row.access_version && typeof body.allow === 'boolean',409,'access_conflict','授权名单已变化，请刷新。');
      if (body.memberID) {
        this.check(this.db.prepare('SELECT id FROM members WHERE id=? AND team_id=? AND active=1').get(body.memberID,team),404,'not_found','成员不可用。');
        if (body.allow) this.db.prepare('INSERT OR IGNORE INTO viewer_members VALUES(?,?)').run(game,body.memberID);
        else { this.db.prepare('DELETE FROM viewer_members WHERE game_id=? AND member_id=?').run(game,body.memberID); this.db.prepare("UPDATE viewers SET status='revoked' WHERE game_id=? AND member_id=?").run(game,body.memberID); }
      } else {
        const viewer=this.db.prepare('SELECT * FROM viewers WHERE id=? AND game_id=?').get(body.viewerID,game); this.check(viewer && !viewer.member_id,404,'not_found','观看者不可用。');
        this.check(!body.allow || viewer.status === 'pending',409,'viewer_revoked','已撤销的设备须重新申请。');
        this.db.prepare('UPDATE viewers SET status=? WHERE id=?').run(body.allow ? 'approved':'revoked',viewer.id);
      }
      this.db.prepare('UPDATE shared_games SET access_version=access_version+1 WHERE id=?').run(game);
      this.store.audit(team,device,'viewerAuthorizationChanged',game); return { updated: true };
    });
  }
  inviteViewer(team,game,device,body) {
    this.check(secret(body.token));
    return this.store.operation(device,body,`inviteViewer:${team}:${game}`,()=>this.manager(this.row(team,game,device),device),()=>{
      const row=this.row(team,game,device); this.check(!row.deleted && row.access_mode==='restricted',409,'access_changed','请先设为仅指定人员。');
      const invitationID=randomUUID(),expiresAt=this.store.now()+86400000;
      this.check(!this.db.prepare('SELECT id FROM viewer_invites WHERE token_hash=?').get(hash(body.token)),409,'token_reused','请生成新邀请。');
      this.db.prepare('INSERT INTO viewer_invites(id,game_id,token_hash,expires_at) VALUES(?,?,?,?)').run(invitationID,game,hash(body.token),expiresAt);
      this.store.audit(team,device,'viewerInvited',game); return { invitationID,expiresAt };
    });
  }
  memberTicket(team,game,device,body) {
    this.check(secret(body.token));
    return this.store.operation(device,body,`memberTicket:${team}:${game}`,()=>{
      const row=this.row(team,game,device),m=this.store.membership(team,device);
      this.check(row.access_mode==='restricted' && this.db.prepare('SELECT 1 FROM viewer_members WHERE game_id=? AND member_id=?').get(game,m.id),403,'forbidden','你尚未获准在网页观看此场比赛。');
    },()=>{
      const m=this.store.membership(team,device), expiresAt=this.store.now()+300000;
      this.db.prepare('INSERT INTO member_view_tickets VALUES(?,?,?,?,?,0)').run(hash(body.token),game,m.id,device,expiresAt); return { expiresAt };
    });
  }
  applyViewer(code, body, cookie) {
    this.check(secret(body.token) && text(body.nickname,80) && body.nickname.trim().length > 0 && secret(body.session));
    return this.store.transaction(()=>{
      const row=this.db.prepare('SELECT * FROM shared_games WHERE view_code=? AND deleted=0').get(code);
      this.check(row && row.access_mode==='restricted' && JSON.parse(row.payload).state.isFinal,404,'not_found','暂时无法观看。');
      const prior=this.db.prepare('SELECT * FROM viewers WHERE game_id=? AND session_hash=?').get(row.id,hash(body.session));
      if(prior) return { status:prior.status };
      const ticket=this.db.prepare('SELECT * FROM member_view_tickets WHERE token_hash=? AND game_id=?').get(hash(body.token),row.id);
      let memberID=null, nickname=body.nickname.trim(),status='pending';
      if(ticket) {
        const member=this.store.membership(row.team_id,ticket.device_id);
        this.check(!ticket.used && ticket.expires_at>this.store.now() && member.id===ticket.member_id
          && this.db.prepare('SELECT 1 FROM viewer_members WHERE game_id=? AND member_id=?').get(row.id,member.id),410,'invitation_invalid','观看授权已失效。');
        memberID=member.id; nickname=member.nickname;status='approved';
        this.db.prepare('UPDATE member_view_tickets SET used=1 WHERE token_hash=?').run(ticket.token_hash);
      } else {
        const invitation=this.db.prepare('SELECT * FROM viewer_invites WHERE token_hash=? AND game_id=?').get(hash(body.token),row.id);
        this.check(invitation && !invitation.used && !invitation.revoked && invitation.expires_at>this.store.now(),410,'invitation_invalid','观看邀请已失效，请重新申请。');
        this.db.prepare('UPDATE viewer_invites SET used=1 WHERE id=?').run(invitation.id);
      }
      this.db.prepare('INSERT INTO viewers(id,game_id,nickname,session_hash,status,member_id,created_at,device_id) VALUES(?,?,?,?,?,?,?,?)').run(randomUUID(),row.id,nickname,hash(body.session),status,memberID,this.store.now(),ticket?.device_id ?? null);
      return { status };
    });
  }
  publicRead(code, session, live = false) {
    if (live) {
      const row=this.db.prepare("SELECT * FROM shared_games WHERE live_code=? AND deleted=0 AND live_enabled=1 AND live_expires_at>?").get(code,this.store.now());
      this.check(row,404,"live_expired","直播已关闭或到期；历史档案由球队另行授权查看。");
      return { ...JSON.parse(row.projection), serverRevision:row.revision, updatedAt:row.updated_at, expiresAt:row.live_expires_at, isLiveLink:true };
    }
    const row=this.db.prepare('SELECT * FROM shared_games WHERE view_code=? AND deleted=0').get(code);
    this.check(row,404,'not_found','暂时无法观看。'); const projection=JSON.parse(row.projection);
    this.check(projection.snapshot.isFinal,404,'not_found','比赛尚未进入历史回看。');
    if (row.access_mode !== 'public') {
      this.check(row.access_mode==='restricted',404,'not_found','历史回看暂未开放。');
      const viewer=secret(session) && this.db.prepare('SELECT * FROM viewers WHERE game_id=? AND session_hash=?').get(row.id,hash(session));
      this.check(viewer?.status==='approved',403,'viewer_unapproved','请先申请观看，等待邀请人核对。');
      if(viewer.member_id) this.check(this.db.prepare(`SELECT 1 FROM viewer_members v JOIN members m ON m.id=v.member_id
        WHERE v.game_id=? AND m.id=? AND m.team_id=? AND m.active=1 AND EXISTS(SELECT 1 FROM member_devices md JOIN devices d ON d.id=md.device_id WHERE md.member_id=m.id AND md.active=1 AND d.revoked=0 AND md.device_id=?)`).get(row.id,viewer.member_id,row.team_id,viewer.device_id),403,'viewer_revoked','观看授权已撤销。');
    }
    return { ...projection, serverRevision:row.revision, accessVersion:row.access_version, updatedAt:row.updated_at };
  }
  heartbeat(team,game,device) {
    const row=this.row(team,game,device);this.writer(row,device);
    // Presence extends an ongoing broadcast only; it never reopens an expired link or extends the final hour.
    if(row.live_enabled && row.live_expires_at>this.store.now() && !JSON.parse(row.payload).state.isFinal) {
      this.db.prepare('UPDATE shared_games SET live_expires_at=? WHERE id=?').run(this.store.now()+3600000,game);
    }
    return this.metadata(this.row(team,game,device),device);
  }
  live(team,game,device,body) {
    this.check(typeof body.enabled==='boolean');
    return this.store.operation(device,body,`live:${team}:${game}`,()=>this.writer(this.row(team,game,device),device),()=>{
      const row=this.row(team,game,device); this.check(!body.enabled || !JSON.parse(row.payload).state.isFinal,409,'final','已结束的比赛不能重新开播，请使用历史观看设置。');
      this.db.prepare('UPDATE shared_games SET live_enabled=?,live_code=?,live_expires_at=?,live_final_at=NULL WHERE id=?')
        .run(body.enabled?1:0,body.enabled?randomBytes(24).toString('base64url'):row.live_code,body.enabled?this.store.now()+3600000:this.store.now(),game);
      this.store.audit(team,device,body.enabled?'liveOpened':'liveClosed',game); return this.metadata(this.row(team,game,device),device);
    });
  }
}
