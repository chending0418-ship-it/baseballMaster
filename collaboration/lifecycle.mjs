import { randomUUID,randomBytes,createHash } from 'node:crypto';
const hash = v => createHash('sha256').update(v).digest('hex');
const canonical = value => JSON.stringify(value,function(_,v){return v && !Array.isArray(v) && typeof v==='object' ? Object.fromEntries(Object.keys(v).sort().map(k=>[k,v[k]])) : v;});
export class TeamLifecycle {
  constructor(store,check) {
    this.store=store;this.db=store.db;this.check=check;
    this.db.exec(`CREATE TABLE IF NOT EXISTS team_deletions(team_id TEXT PRIMARY KEY REFERENCES teams(id),deleted_at INTEGER NOT NULL,restored_at INTEGER);
      CREATE TABLE IF NOT EXISTS team_restore_devices(team_id TEXT NOT NULL,device_id TEXT NOT NULL,nickname TEXT NOT NULL,PRIMARY KEY(team_id,device_id));
      CREATE TABLE IF NOT EXISTS dissolved_games(team_id TEXT NOT NULL,game_id TEXT NOT NULL,PRIMARY KEY(team_id,game_id));
      CREATE TABLE IF NOT EXISTS lifecycle_operations(device_id TEXT NOT NULL,operation_id TEXT NOT NULL,digest TEXT NOT NULL,result TEXT NOT NULL,PRIMARY KEY(device_id,operation_id));`);
  }
  deleted(team) {return this.db.prepare('SELECT * FROM team_deletions WHERE team_id=? AND restored_at IS NULL').get(team);}
  mutation(device,body,scope,perform) {
    this.check(typeof body.operationID==='string' && /^[a-f0-9-]{36}$/i.test(body.operationID));
    const digest=hash(canonical({scope,body}));
    return this.store.transaction(()=>{
      this.check(this.db.prepare('SELECT id FROM devices WHERE id=? AND revoked=0').get(device),401,'unauthorized','设备授权已失效。');
      // These terminal receipts only acknowledge a known operation; they never return team data or restore access.
      const previous=this.db.prepare('SELECT * FROM lifecycle_operations WHERE device_id=? AND operation_id=?').get(device,body.operationID);
      if(previous){this.check(previous.digest===digest,409,'operation_conflict','操作编号已用于不同内容。');return JSON.parse(previous.result);}
      const result=perform();
      this.db.prepare('INSERT INTO lifecycle_operations VALUES(?,?,?,?)').run(device,body.operationID,digest,JSON.stringify(result));return result;
    });
  }
  dissolve(team,device,body) {
    return this.mutation(device,body,`dissolve:${team}`,()=>{
      this.store.membership(team,device,'dissolveTeam');
      const current=this.db.prepare('SELECT * FROM teams WHERE id=?').get(team);
      this.check(body.confirm===true && body.name===current.name,400,'confirmation_required','请输入完整球队名称，确认解散。');
      this.check(body.baseRevision===current.revision,409,'revision_conflict','球队资料已有变化，请刷新后重新核对。');
      this.db.prepare('DELETE FROM team_restore_devices WHERE team_id=?').run(team);
      this.db.prepare(`INSERT INTO team_restore_devices SELECT md.team_id,md.device_id,m.nickname FROM member_devices md
        JOIN members m ON m.id=md.member_id JOIN devices d ON d.id=md.device_id
        WHERE md.team_id=? AND md.active=1 AND m.active=1 AND m.role='admin' AND d.revoked=0`).run(team);
      this.db.prepare('DELETE FROM dissolved_games WHERE team_id=?').run(team);
      this.db.prepare('INSERT INTO dissolved_games SELECT team_id,id FROM shared_games WHERE team_id=? AND deleted=0').run(team);
      for(const row of this.db.prepare('SELECT * FROM shared_games WHERE team_id=?').all(team)) {
        this.db.prepare(`UPDATE shared_games SET deleted=1,revision=revision+1,epoch=epoch+1,access_mode='closed',access_version=access_version+1,
          live_enabled=0,live_code=NULL,live_expires_at=NULL,view_code=?,updated_at=CASE WHEN deleted=0 THEN ? ELSE updated_at END WHERE id=?`)
          .run(randomBytes(24).toString('base64url'),this.store.now(),row.id);
        this.db.prepare('DELETE FROM handoffs WHERE game_id=?').run(row.id);this.store.games.revokeAll(row.id);
        this.store.games.changed(row,device,'gameClosedByDissolution');
      }
      this.db.prepare('UPDATE invitations SET revoked=1 WHERE team_id=?').run(team);
      this.db.prepare("UPDATE device_pairings SET status='revoked' WHERE team_id=?").run(team);
      this.store.security.invalidateRecovery(team,device);
      this.store.audit(team,device,'teamDissolved',team);
      this.db.prepare('UPDATE members SET active=0,revision=revision+1 WHERE team_id=?').run(team);
      this.db.prepare('UPDATE member_devices SET active=0,revision=revision+1 WHERE team_id=?').run(team);
      this.db.prepare('UPDATE teams SET revision=revision+1,access_epoch=access_epoch+1 WHERE id=?').run(team);
      this.db.prepare('INSERT INTO team_deletions VALUES(?,?,NULL) ON CONFLICT(team_id) DO UPDATE SET deleted_at=excluded.deleted_at,restored_at=NULL').run(team,this.store.now());
      return {teamID:team,dissolved:true};
    });
  }
  list(device) {
    return {teams:this.db.prepare(`SELECT t.id,t.name,d.deleted_at AS deletedAt,d.deleted_at+2592000000 AS expiresAt
      FROM team_deletions d JOIN teams t ON t.id=d.team_id JOIN team_restore_devices r ON r.team_id=t.id
      JOIN devices actor ON actor.id=r.device_id AND actor.revoked=0
      WHERE r.device_id=? AND d.restored_at IS NULL AND d.deleted_at>? ORDER BY d.deleted_at DESC,t.id`).all(device,this.store.now()-30*86400000)};
  }
  restore(team,device,body) {
    return this.mutation(device,body,`restoreTeam:${team}`,()=>{
      const former=this.db.prepare('SELECT nickname FROM team_restore_devices WHERE team_id=? AND device_id=?').get(team,device),deletion=this.deleted(team);
      this.check(former && deletion && this.store.now()-deletion.deleted_at<30*86400000,404,'not_found','球队不存在、无法恢复或已超过 30 天。');
      const current=this.db.prepare('SELECT * FROM teams WHERE id=?').get(team);
      this.check(body.confirm===true && body.name===current.name,400,'confirmation_required','请确认恢复球队并重新授权成员。');
      this.check(this.store.listTeams(device).teams.length<this.store.maxTeamsPerDevice,409,'capacity','当前设备加入的球队已达上限。');
      const member=randomUUID();
      this.db.prepare("INSERT INTO members(id,team_id,nickname,role) VALUES(?,?,?,'admin')").run(member,team,former.nickname);
      this.db.prepare('UPDATE member_devices SET member_id=?,active=1,revision=revision+1 WHERE team_id=? AND device_id=?').run(member,team,device);
      this.db.prepare('UPDATE team_deletions SET restored_at=? WHERE team_id=?').run(this.store.now(),team);
      this.db.prepare('UPDATE teams SET revision=revision+1,access_epoch=access_epoch+1 WHERE id=?').run(team);
      for(const {game_id:game} of this.db.prepare('SELECT game_id FROM dissolved_games WHERE team_id=?').all(team)) {
        this.db.prepare('UPDATE shared_games SET deleted=0,revision=revision+1,epoch=epoch+1,updated_at=?,writer_device=?,responsible_member=? WHERE id=?').run(this.store.now(),device,member,game);
        this.store.games.changed(this.db.prepare('SELECT * FROM shared_games WHERE id=?').get(game),device,'gameRestoredWithTeam');
      }
      this.db.prepare('DELETE FROM team_restore_devices WHERE team_id=?').run(team);
      this.store.audit(team,device,'teamRestored',team);
      return {teamID:team,restored:true};
    });
  }
}
