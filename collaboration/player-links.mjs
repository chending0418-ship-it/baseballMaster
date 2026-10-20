const uuid = v => typeof v === 'string' && /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(v);
const normalized = links => links.map(l => ({ sourceID: l.sourceID.toLowerCase(), targetID: l.targetID.toLowerCase() }))
  .filter(l => l.sourceID !== l.targetID).sort((a,b) => a.sourceID.localeCompare(b.sourceID));

// Associations belong to one match. Historical names and event references are never rewritten.
export class PlayerLinkService {
  constructor(store, check) {
    this.store = store; this.db = store.db; this.check = check;
    this.db.exec(`CREATE TABLE IF NOT EXISTS game_player_links(game_id TEXT PRIMARY KEY REFERENCES shared_games(id),links TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS player_link_changes(id INTEGER PRIMARY KEY AUTOINCREMENT,game_id TEXT NOT NULL REFERENCES shared_games(id),
        revision INTEGER NOT NULL,actor_device TEXT NOT NULL,before_links TEXT NOT NULL,after_links TEXT NOT NULL,occurred_at INTEGER NOT NULL);`);
  }
  links(game) {
    const row = this.db.prepare('SELECT links FROM game_player_links WHERE game_id=?').get(game);
    return row ? JSON.parse(row.links) : this.store.imports.info(game).playerLinks ?? [];
  }
  validate(team, payload, links) {
    this.check(Array.isArray(links) && links.length <= 200,400,'invalid_player_link','球员对应资料不完整，请重新核对。');
    const side = payload.isHome ? payload.state.homeTeam : payload.state.awayTeam;
    const roster = JSON.parse(this.db.prepare('SELECT profile FROM team_profiles WHERE team_id=?').get(team).profile);
    const sources = new Set(side.players.map(p => p.id.toLowerCase()));
    const targets = new Set(roster.players.map(p => p.sourceID.toLowerCase()));
    const seen = new Set();
    for (const link of links) {
      this.check(link && uuid(link.sourceID) && uuid(link.targetID) && sources.has(link.sourceID.toLowerCase())
        && targets.has(link.targetID.toLowerCase()) && !seen.has(link.sourceID.toLowerCase()),400,'invalid_player_link','球员对应关系已失效，请刷新名单后核对。');
      seen.add(link.sourceID.toLowerCase());
    }
    const clean = normalized(links), effective = side.players.map(p => clean.find(l => l.sourceID === p.id.toLowerCase())?.targetID ?? p.id.toLowerCase());
    this.check(new Set(effective).size === effective.length,400,'ambiguous_player_link','同一场的两名球员不能对应到同一人，请分别核对。');
    return clean;
  }
  update(team, game, device, body) {
    return this.store.operation(device,body,`playerLinks:${team}:${game}`,() => this.store.membership(team,device,'linkPlayers'),() => {
      const row = this.store.games.row(team,game,device), payload = JSON.parse(row.payload);
      this.check(!row.deleted && payload.status === 1 && !payload.isSpectator && typeof payload.isHome === 'boolean',409,'invalid_link_match','请在已结束的本队比赛中核对球员对应。');
      this.check(body.baseRevision === row.revision && body.writerEpoch === row.epoch,409,'revision_conflict','比赛已有更新，请重新核对球员对应。');
      this.check(body.baseRosterRevision === this.db.prepare('SELECT revision FROM teams WHERE id=?').get(team).revision,409,'roster_conflict','名单已有更新，请刷新后核对球员对应。');
      this.check(!this.db.prepare('SELECT id FROM handoffs WHERE game_id=?').get(game),409,'handoff_pending','正在交接，请完成交接后再核对球员对应。');
      const before = this.links(game), after = this.validate(team,payload,body.playerLinks);
      if (JSON.stringify(before) === JSON.stringify(after)) return this.store.games.metadata(row,device);
      this.db.prepare('INSERT INTO game_player_links VALUES(?,?) ON CONFLICT(game_id) DO UPDATE SET links=excluded.links').run(game,JSON.stringify(after));
      this.db.prepare('UPDATE shared_games SET revision=revision+1,updated_at=? WHERE id=?').run(this.store.now(),game);
      this.db.prepare('INSERT INTO player_link_changes(game_id,revision,actor_device,before_links,after_links,occurred_at) VALUES(?,?,?,?,?,?)')
        .run(game,row.revision+1,device,JSON.stringify(before),JSON.stringify(after),this.store.now());
      const next = this.store.games.row(team,game,device); this.store.games.changed(next,device,'playerLinksUpdated');
      return this.store.games.metadata(next,device);
    });
  }
  history(team, game, device) {
    this.store.games.row(team,game,device);
    return { changes: this.db.prepare(`SELECT c.revision,c.before_links,c.after_links,c.occurred_at AS occurredAt,
        d.label AS deviceName,COALESCE(m.nickname,'原队内成员') AS nickname
      FROM player_link_changes c JOIN devices d ON d.id=c.actor_device
      LEFT JOIN member_devices md ON md.device_id=c.actor_device AND md.team_id=?
      LEFT JOIN members m ON m.id=md.member_id WHERE c.game_id=? ORDER BY c.id DESC LIMIT 50`).all(team,game)
      .map(({before_links,after_links,...r}) => ({...r,before:JSON.parse(before_links),after:JSON.parse(after_links)})) };
  }
}
