// Team notices contain only the public-to-team schedule. Scoring data stays on the device.
export class TeamSchedules {
  constructor(store, requireValue) {
    this.store = store;
    this.check = requireValue;
    store.db.exec(`CREATE TABLE IF NOT EXISTS team_schedules(
      team_id TEXT NOT NULL REFERENCES teams(id), source_id TEXT NOT NULL, owner_id TEXT NOT NULL REFERENCES members(id),
      revision INTEGER NOT NULL, starts_at INTEGER NOT NULL, away TEXT NOT NULL, home TEXT NOT NULL,
      venue TEXT NOT NULL, cancelled INTEGER NOT NULL, PRIMARY KEY(team_id,source_id));
      CREATE INDEX IF NOT EXISTS team_schedules_time ON team_schedules(team_id,starts_at);`);
  }
  list(deviceID) {
    const result = [];
    const rows = this.store.db.prepare(`SELECT team_id AS teamID,source_id AS id,revision,starts_at AS startsAt,
        away,home,venue,cancelled FROM team_schedules WHERE team_id=? AND starts_at>=? ORDER BY starts_at,source_id`);
    for (const team of this.store.listTeams(deviceID).teams) {
      if (this.store.lifecycle.deleted(team.id)) continue;
      for (const row of rows.all(team.id, this.store.now())) result.push({ ...row, cancelled: Boolean(row.cancelled) });
    }
    return { schedules: result };
  }
  save(deviceID, team, source, body) {
    const name = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 120 && !/[\u0000-\u001f\u007f]/u.test(value);
    const fields = ['operationID','expectedRevision','startsAt','away','home','venue','cancelled'];
    this.check(Object.keys(body).every(key => fields.includes(key)));
    this.check(name(body.away) && name(body.home) && (body.venue === '' || name(body.venue)) &&
      typeof body.cancelled === 'boolean' && Number.isSafeInteger(body.startsAt) && body.startsAt > 0 &&
      body.startsAt < 253402300800000 && Number.isSafeInteger(body.expectedRevision) && body.expectedRevision >= 0);
    let member;
    return this.store.operation(deviceID, body, 'schedule:' + team + ':' + source, () => {
      member = this.store.membership(team, deviceID, 'createGame');
      const prior = this.store.db.prepare('SELECT owner_id FROM team_schedules WHERE team_id=? AND source_id=?').get(team,source);
      this.check(!prior || prior.owner_id === member.id || member.role === 'admin',403,'forbidden','只有发布人和管理员可以修改赛程。');
    }, () => {
      const prior = this.store.db.prepare('SELECT revision FROM team_schedules WHERE team_id=? AND source_id=?').get(team,source);
      this.check((prior?.revision ?? 0) === body.expectedRevision,409,'schedule_changed','赛程已更新，请重新核对。');
      this.check(body.cancelled ? Boolean(prior) : body.startsAt > this.store.now(),400,'schedule_time','请选择未来的开赛时间。');
      if (!prior) {
        const count = this.store.db.prepare('SELECT COUNT(*) AS n FROM team_schedules WHERE team_id=? AND starts_at>=?').get(team,this.store.now()).n;
        this.check(count < 500,409,'schedule_capacity','未来赛程已达上限。');
      }
      const revision = body.expectedRevision + 1;
      this.store.db.prepare(`INSERT INTO team_schedules(team_id,source_id,owner_id,revision,starts_at,away,home,venue,cancelled) VALUES (?,?,?,?,?,?,?,?,?)
        ON CONFLICT(team_id,source_id) DO UPDATE SET revision=excluded.revision,starts_at=excluded.starts_at,
        away=excluded.away,home=excluded.home,venue=excluded.venue,cancelled=excluded.cancelled`)
        .run(team,source,member.id,revision,body.startsAt,body.away,body.home,body.venue,Number(body.cancelled));
      return { id: source, teamID: team, revision };
    });
  }
}
