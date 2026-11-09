import { randomBytes, randomUUID } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { validateSnapshot } from '../live/server.mjs';

const idle = 3_600_000;
const finalHold = 60_000;

export class TeamBroadcasts {
  constructor(store, check) {
    this.store = store;
    this.check = check;
    this.db = store.db;
    this.active = new Map();
    this.displaced = new Map();
    this.db.exec(`CREATE TABLE IF NOT EXISTS team_broadcast_codes (
      team_id TEXT PRIMARY KEY REFERENCES teams(id), code TEXT UNIQUE NOT NULL, access_epoch INTEGER NOT NULL
    )`);
    this.timer = setInterval(() => this.sweep(), 30_000).unref();
  }

  close() { clearInterval(this.timer); this.active.clear(); this.displaced.clear(); }

  sweep() {
    const now = this.store.now();
    for (const [team, session] of this.active) {
      if (session.expiresAt <= now) this.active.delete(team);
    }
    for (const [team, selections] of this.displaced) {
      for (const [game, expiresAt] of selections.games) {
        if (expiresAt <= now) selections.games.delete(game);
      }
      if (!selections.games.size) this.displaced.delete(team);
    }
  }

  actor(team, device, adminOnly = false) {
    const member = this.store.membership(team, device);
    this.check(adminOnly ? member.role === 'admin' : ['admin', 'scorer'].includes(member.role), 403, 'forbidden');
    return this.db.prepare('SELECT access_epoch FROM teams WHERE id=?').get(team).access_epoch;
  }

  session(team, epoch) {
    this.sweep();
    const current = this.active.get(team);
    if (!current) return null;
    if (current.epoch === epoch) {
      try { this.actor(team, current.device); return current; }
      catch { /* A revoked writer no longer holds this team's output. */ }
    }
    this.active.delete(team);
    return null;
  }

  sources(team, device) {
    const epoch = this.actor(team, device);
    const code = randomBytes(24).toString('base64url');
    this.db.prepare(`INSERT INTO team_broadcast_codes VALUES(?,?,?)
      ON CONFLICT(team_id) DO UPDATE SET code=excluded.code,access_epoch=excluded.access_epoch
      WHERE team_broadcast_codes.access_epoch<>excluded.access_epoch`).run(team, code, epoch);
    const current = this.session(team, epoch);
    return {
      code: this.db.prepare('SELECT code FROM team_broadcast_codes WHERE team_id=?').get(team).code,
      activeGameID: current?.snapshot.gameID ?? null,
      activeMatch: current ? current.snapshot.away.name + ' vs ' + current.snapshot.home.name : null
    };
  }

  rotate(team, device, body) {
    const epoch = this.actor(team, device, true);
    const code = randomBytes(24).toString('base64url');
    const result = this.db.prepare('UPDATE team_broadcast_codes SET code=? WHERE team_id=? AND access_epoch=? AND code=?')
      .run(code, team, epoch, body.expectedCode ?? '');
    this.check(result.changes === 1, 409, 'broadcast_changed', '转播源已有变化，请刷新后重试。');
    return this.sources(team, device);
  }

  publish(team, device, body) {
    const epoch = this.actor(team, device);
    this.check(body.snapshot && Buffer.byteLength(JSON.stringify(body.snapshot)) <= 2 * 1024 * 1024, 413, 'too_large');
    let snapshot;
    try { snapshot = validateSnapshot(body.snapshot); }
    catch { this.check(false, 400, 'invalid_broadcast_snapshot', '转播资料格式不正确。'); }
    this.check(snapshot.hasStarted === true && /^[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$/.test(snapshot.gameID), 409, 'broadcast_not_started', '请先开始比赛。');
    snapshot = {
      ...snapshot, gameID: snapshot.gameID.toLowerCase(), notice: '', nextBatters: [],
      entries: snapshot.entries.filter(entry => entry.kind === 'appearance' && entry.status === 'completed'
        && entry.player?.id === snapshot.batter?.id).slice(-3).map(entry => ({ ...entry, details: [] }))
    };
    const line = body.pitching;
    this.check(line == null || (snapshot.pitcher && line && Object.keys(line).every(k => ['strikes', 'strikeouts'].includes(k))
      && [line.strikes, line.strikeouts].every(n => Number.isSafeInteger(n) && n >= 0 && n <= 100000)));
    const current = this.session(team, epoch);
    const sameGame = current?.snapshot.gameID === snapshot.gameID;
    const match = current ? current.snapshot.away.name + ' vs ' + current.snapshot.home.name : null;
    const blocked = { selected: false, activeGameID: current?.snapshot.gameID ?? null, activeMatch: match };
    const switching = Object.hasOwn(body, 'expectedGameID');
    const expected = typeof body.expectedGameID === 'string' ? body.expectedGameID.toLowerCase() : body.expectedGameID;
    if (switching && expected !== blocked.activeGameID) return blocked;
    const previous = this.displaced.get(team);
    const displaced = previous?.epoch === epoch ? previous.games : new Map();
    if (!switching && displaced.has(snapshot.gameID)) return blocked;
    if (sameGame) {
      this.check(current.device === device, 409, 'broadcast_writer_changed', '此场转播已在另一设备记分。');
      this.check(snapshot.revision > current.snapshot.revision
        || (snapshot.revision === current.snapshot.revision && isDeepStrictEqual(snapshot, current.snapshot)
          && isDeepStrictEqual(line ?? null, current.pitching)), 409, 'broadcast_revision', '转播已有更新版本。');
    } else {
      if (snapshot.isFinal || (current && !current.snapshot.isFinal && !switching)) return blocked;
      this.check(current || this.active.size < 500, 503, 'broadcast_capacity', '转播服务繁忙，请稍后再试。');
    }
    const now = this.store.now();
    if (switching && !sameGame) {
      displaced.delete(snapshot.gameID);
      if (current && !current.snapshot.isFinal) displaced.set(current.snapshot.gameID, now + idle);
      if (displaced.size) this.displaced.set(team, { epoch, games: displaced });
    }
    const expiresAt = snapshot.isFinal
      ? (current.snapshot.isFinal ? current.expiresAt : now + finalHold)
      : now + idle;
    this.active.set(team, { epoch, device, snapshot, pitching: line ?? null, expiresAt, stream: sameGame ? current.stream : randomUUID() });
    return { selected: true, activeGameID: snapshot.gameID, activeMatch: snapshot.away.name + ' vs ' + snapshot.home.name };
  }

  read(code, cursor) {
    const binding = this.db.prepare(`SELECT b.team_id,t.access_epoch FROM team_broadcast_codes b
      JOIN teams t ON t.id=b.team_id AND t.access_epoch=b.access_epoch WHERE b.code=?`).get(code);
    this.check(binding && !this.store.lifecycle.deleted(binding.team_id), 404, 'broadcast_link_invalid', '转播源无效。');
    const current = this.session(binding.team_id, binding.access_epoch);
    const serverTime = this.store.now();
    if (!current) return { active: false, serverTime };
    const { snapshot, pitching, expiresAt } = current;
    const revision = current.stream + ':' + snapshot.revision;
    const metadata = { active: true, revision, expiresAt, serverTime };
    if (cursor === revision) return metadata;
    const box = pitching ? { pitching: [{ player: snapshot.pitcher, values: pitching }] } : null;
    return { ...metadata, snapshot, box };
  }
}
