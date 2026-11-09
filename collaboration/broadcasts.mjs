import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { validateSnapshot } from '../live/server.mjs';

const idle = 3_600_000;
const finalHold = 60_000;

function snapshotDigest(snapshot, pitching) {
  const json = JSON.stringify([snapshot, pitching ?? null], (_, value) => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return value;
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, value[key]]));
  });
  return createHash('sha256').update(json).digest('hex');
}

export class TeamBroadcasts {
  constructor(store, check) {
    this.store = store;
    this.check = check;
    this.db = store.db;
    this.active = new Map();
    // Seed confirmation for existing links once. The old fixed-code table remains compatible on rollback.
    this.db.exec(`
      BEGIN IMMEDIATE;
      CREATE TABLE IF NOT EXISTS team_broadcast_codes (
        team_id TEXT PRIMARY KEY REFERENCES teams(id), code TEXT UNIQUE NOT NULL, access_epoch INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS team_broadcast_selection (
        team_id TEXT PRIMARY KEY REFERENCES teams(id), access_epoch INTEGER NOT NULL, game_id TEXT NOT NULL,
        writer_device TEXT NOT NULL, writer_epoch INTEGER, revision INTEGER NOT NULL, digest TEXT NOT NULL,
        is_final INTEGER NOT NULL, expires_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS team_broadcast_displaced (
        team_id TEXT NOT NULL REFERENCES teams(id), access_epoch INTEGER NOT NULL, game_id TEXT NOT NULL,
        PRIMARY KEY(team_id, game_id)
      );
      CREATE TABLE IF NOT EXISTS team_broadcast_confirmation (
        team_id TEXT PRIMARY KEY REFERENCES teams(id), access_epoch INTEGER NOT NULL
      );
      INSERT OR IGNORE INTO team_broadcast_confirmation (team_id, access_epoch)
        SELECT team_id, access_epoch FROM team_broadcast_codes
        WHERE NOT EXISTS (SELECT 1 FROM metadata WHERE key='teamBroadcastSelectionV1');
      INSERT OR IGNORE INTO metadata VALUES ('teamBroadcastSelectionV1', '1');
      COMMIT;
    `);
    this.timer = setInterval(() => this.sweep(), 30_000).unref();
  }

  close() { clearInterval(this.timer); this.active.clear(); }

  sweep() {
    const now = this.store.now();
    for (const [team, session] of this.active) {
      if (session.expiresAt <= now) this.active.delete(team);
    }
  }

  selection(team, epoch, now) {
    return this.db.prepare(`SELECT * FROM team_broadcast_selection
      WHERE team_id=? AND access_epoch=? AND expires_at>?`).get(team, epoch, now);
  }

  actor(team, device, adminOnly = false) {
    const member = this.store.membership(team, device);
    this.check(adminOnly ? member.role === 'admin' : ['admin', 'scorer'].includes(member.role), 403, 'forbidden');
    return this.db.prepare('SELECT access_epoch FROM teams WHERE id=?').get(team).access_epoch;
  }

  session(team, epoch) {
    const current = this.active.get(team);
    if (!current) return null;
    if (current.epoch === epoch && current.expiresAt > this.store.now()) {
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
    const selection = this.selection(team, epoch, this.store.now());
    return {
      code: this.db.prepare('SELECT code FROM team_broadcast_codes WHERE team_id=?').get(team).code,
      activeGameID: selection?.game_id ?? null,
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
    const gameID = snapshot.gameID.toLowerCase();
    const shared = this.store.games.broadcastMatch(team, gameID);
    if (shared) {
      this.store.games.writer(shared, device);
      this.check(!shared.handoff_pending && body.writerEpoch === shared.epoch,
        409, 'broadcast_writer_changed', '记分权已经变化，请同步后继续转播。');
    } else {
      this.check(!Object.hasOwn(body, 'writerEpoch'), 409, 'broadcast_writer_changed', '共享比赛暂时无法核对。');
    }
    snapshot = {
      ...snapshot, gameID: shared?.source_id ?? gameID, notice: '', nextBatters: [],
      entries: snapshot.entries.filter(entry => entry.kind === 'appearance' && entry.status === 'completed'
        && entry.player?.id === snapshot.batter?.id).slice(-3).map(entry => ({ ...entry, details: [] }))
    };
    const line = body.pitching;
    this.check(line == null || (snapshot.pitcher && line && Object.keys(line).every(k => ['strikes', 'strikeouts'].includes(k))
      && [line.strikes, line.strikeouts].every(n => Number.isSafeInteger(n) && n >= 0 && n <= 100000)));
    const now = this.store.now();
    const selection = this.selection(team, epoch, now);
    const current = this.session(team, epoch);
    const sameGame = selection?.game_id === snapshot.gameID;
    const match = current ? current.snapshot.away.name + ' vs ' + current.snapshot.home.name : null;
    const blocked = { selected: false, activeGameID: selection?.game_id ?? null, activeMatch: match };
    const switching = Object.hasOwn(body, 'expectedGameID');
    const expected = typeof body.expectedGameID === 'string' ? body.expectedGameID.toLowerCase() : body.expectedGameID;
    if (switching && expected !== blocked.activeGameID) return blocked;
    const displaced = this.db.prepare(`SELECT 1 FROM team_broadcast_displaced
      WHERE team_id=? AND access_epoch=? AND game_id=?`).get(team, epoch, snapshot.gameID);
    const needsSelection = this.db.prepare(`SELECT 1 FROM team_broadcast_confirmation
      WHERE team_id=? AND access_epoch=?`).get(team, epoch);
    if (!switching && (displaced || needsSelection)) return blocked;

    const writerEpoch = shared?.epoch ?? null;
    const digest = snapshotDigest(snapshot, line);
    // Shared writer generations can resume from an earlier confirmed local revision.
    const sameWriter = sameGame && selection.writer_epoch === writerEpoch;
    if (sameWriter) {
      this.check(selection.writer_device === device, 409, 'broadcast_writer_changed', '此场转播已在另一设备记分。');
      this.check(snapshot.revision > selection.revision || (snapshot.revision === selection.revision && digest === selection.digest),
        409, 'broadcast_revision', '转播已有更新版本。');
    }
    if (!sameGame && (snapshot.isFinal || (selection && !selection.is_final && !switching))) return blocked;
    if (!current && this.active.size >= 500) this.sweep();
    this.check(current || this.active.size < 500, 503, 'broadcast_capacity', '转播服务繁忙，请稍后再试。');

    const expiresAt = snapshot.isFinal
      ? (selection.is_final ? selection.expires_at : now + finalHold)
      : now + idle;
    const saveSelection = () => this.db.prepare(`INSERT INTO team_broadcast_selection
      (team_id, access_epoch, game_id, writer_device, writer_epoch, revision, digest, is_final, expires_at)
      VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(team_id) DO UPDATE SET access_epoch=excluded.access_epoch,
      game_id=excluded.game_id, writer_device=excluded.writer_device, writer_epoch=excluded.writer_epoch,
      revision=excluded.revision, digest=excluded.digest, is_final=excluded.is_final, expires_at=excluded.expires_at`)
      .run(team, epoch, snapshot.gameID, device, writerEpoch, snapshot.revision, digest, Number(snapshot.isFinal), expiresAt);
    if (switching && !sameGame && (selection || displaced || needsSelection)) {
      this.store.transaction(() => {
        if (needsSelection) this.db.prepare('DELETE FROM team_broadcast_confirmation WHERE team_id=?').run(team);
        if (displaced) this.db.prepare('DELETE FROM team_broadcast_displaced WHERE team_id=? AND game_id=?')
          .run(team, snapshot.gameID);
        if (selection) this.db.prepare(`INSERT INTO team_broadcast_displaced VALUES (?,?,?)
          ON CONFLICT(team_id,game_id) DO UPDATE SET access_epoch=excluded.access_epoch`)
          .run(team, epoch, selection.game_id);
        saveSelection();
      });
    } else {
      saveSelection();
    }
    this.active.set(team, { epoch, device, snapshot, pitching: line ?? null, expiresAt,
      stream: current && sameWriter ? current.stream : randomUUID() });
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
