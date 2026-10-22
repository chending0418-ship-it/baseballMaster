const uuid = s => typeof s === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s);
const text = s => typeof s === 'string' && s.length <= 80 && !/[\u0000-\u001f\u007f]/.test(s);

// Profile revision fences the whole roster. No last-writer-wins and no physical player deletion.
export class RosterService {
  constructor(store, check) { this.store = store; this.db = store.db; this.check = check; }
  read(team, device) {
    const member = this.store.membership(team, device);
    const row = this.db.prepare('SELECT id,name,revision FROM teams WHERE id=?').get(team);
    const saved = this.db.prepare('SELECT profile FROM team_profiles WHERE team_id=?').get(team);
    return { team: { ...row, ...(saved ? JSON.parse(saved.profile) : { shortName: row.name, city: '', players: [] }) }, role: member.role, memberID: member.id };
  }
  update(team, device, body) {
    this.check(Number.isSafeInteger(body.baseRevision) && body.baseRevision > 0 && ['profile','player'].includes(body.kind));
    return this.store.operation(device, body, `roster:${team}`, () => this.store.membership(team, device, body.kind === 'profile' ? 'editTeam' : 'editRoster'), () => {
      const detail = this.read(team, device), profile = detail.team;
      this.check(profile.revision === body.baseRevision, 409, 'roster_conflict', '名单已在其他设备更新，请核对后重新保存。');
      let objectID = team;
      if (body.kind === 'profile') {
        this.check(text(body.name) && body.name.trim() && text(body.shortName) && body.shortName.trim() && text(body.city));
        profile.name = body.name.trim(); profile.shortName = body.shortName.trim(); profile.city = body.city.trim();
      } else {
        const p = body.player;
        this.check(p && uuid(p.id) && uuid(p.sourceID) && text(p.chineseName) && text(p.englishName)
          && Array.isArray(p.numbers) && p.numbers.length <= 10 && new Set(p.numbers).size === p.numbers.length
          && p.numbers.every(n => typeof n === 'string' && /^(00|0|[1-9][0-9]?)$/.test(n)) && typeof p.archived === 'boolean');
        const id = p.id.toLowerCase(), sourceID = p.sourceID.toLowerCase();
        const index = profile.players.findIndex(v => v.id === id);
        if (p.archived !== (index >= 0 ? profile.players[index].archived : false)) this.store.membership(team, device, 'archivePlayer');
        this.check(index < 0 || profile.players[index].sourceID === sourceID, 409, 'identity_conflict', '球员身份不能更换。');
        this.check(!profile.players.some(v => v.id !== id && v.sourceID === sourceID), 409, 'identity_conflict', '这位球员已有档案，请修改原档案。');
        this.check(index >= 0 || (!p.archived && profile.players.length < 2000), 409, 'capacity', '球员档案已达上限。');
        const player = { id, sourceID, chineseName: p.chineseName.trim(), englishName: p.englishName.trim(), numbers: p.numbers, archived: p.archived };
        if (index < 0) profile.players.push(player); else profile.players[index] = player;
        this.check(profile.players.filter(v => !v.archived).length <= 200, 409, 'capacity', '现役名单最多 200 人，请先停用已离队球员。');
        objectID = id;
      }
      const { name, shortName, city, players } = profile;
      this.db.prepare('UPDATE teams SET name=?,revision=revision+1 WHERE id=?').run(name, team);
      this.db.prepare('INSERT INTO team_profiles VALUES(?,?) ON CONFLICT(team_id) DO UPDATE SET profile=excluded.profile').run(team, JSON.stringify({ shortName, city, players }));
      this.store.audit(team, device, body.kind === 'profile' ? 'teamProfileUpdated' : body.player.archived ? 'playerArchived' : 'playerUpdated', objectID);
      return this.read(team, device);
    });
  }
}
