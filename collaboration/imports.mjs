import { createHash, randomUUID, randomBytes } from 'node:crypto';
const uuid = v => typeof v === 'string' && /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(v);
const hash = v => createHash('sha256').update(v).digest('hex');
const dictionaries = new Set(['batting','pitching','fielding','runnerPitcherIDs','pitchingOutsBeforeRepair','positions','earnedRuns','addedPlayersHome']);
// Swift encodes dictionaries with UUID keys as alternating key/value arrays.
function canonical(value, key = '') {
  if (Array.isArray(value)) {
    if (dictionaries.has(key) && value.length % 2 === 0 && value.every((v,i) => i % 2 || uuid(v))) {
      const pairs=[]; for(let i=0;i<value.length;i+=2) pairs.push([value[i].toLowerCase(),canonical(value[i+1])]);
      return Object.fromEntries(pairs.sort(([a],[b])=>a.localeCompare(b)));
    }
    return value.map(v=>canonical(v));
  }
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k=>[k,canonical(value[k],k)]));
  return typeof value === 'string' && uuid(value) ? value.toLowerCase() : value;
}
export function importFingerprint(payload) {
  const copy={...payload};
  for(const k of ['id','revision','updatedAt','publicationState','pendingPublicationRevisions','sharedTeamID','sharedGameID','sharedPlayerLinks','historySourceID']) delete copy[k];
  return hash(JSON.stringify(canonical(copy)));
}
const normalizeLinks = links => [...links].map(l=>({sourceID:l.sourceID.toLowerCase(),targetID:l.targetID.toLowerCase()}))
  .filter(l=>l.sourceID!==l.targetID).sort((a,b)=>a.sourceID.localeCompare(b.sourceID));
function matchDay(p) {
  const date=new Date((p.playedAt ?? p.startedAt ?? p.scheduledAt ?? p.createdAt)*1000+978307200000);
  try { return new Intl.DateTimeFormat('en-CA',{timeZone:p.playedTimeZoneID || 'UTC',year:'numeric',month:'2-digit',day:'2-digit'}).format(date); }
  catch { return date.toISOString().slice(0,10); }
}
function opponent(p, sourceTeamID) { return p.state.homeTeam.id.toLowerCase() === sourceTeamID ? p.state.awayTeam.name : p.state.homeTeam.name; }
const name = v => v.trim().replace(/\s+/g,'').toLowerCase();

export class HistoryImportService {
  constructor(store,check) {
    this.store=store;this.db=store.db;this.check=check;
    this.db.exec(`CREATE TABLE IF NOT EXISTS history_imports(game_id TEXT PRIMARY KEY REFERENCES shared_games(id),
      source_team_id TEXT NOT NULL,source_id TEXT NOT NULL,original_digest TEXT NOT NULL,player_links TEXT NOT NULL,
      imported_at INTEGER NOT NULL,actor_device TEXT NOT NULL);`);
  }
  info(game) { const row=this.db.prepare('SELECT * FROM history_imports WHERE game_id=?').get(game);return row ? {historySourceID:row.source_id,playerLinks:JSON.parse(row.player_links)} : {}; }
  validate(team,device,body) {
    this.store.membership(team,device,'importHistory');
    this.check(uuid(body.sourceID) && uuid(body.sourceTeamID) && Array.isArray(body.playerLinks) && body.playerLinks.length<=200);
    const valid=this.store.games.validate(body),p=body.payload,s=p.state;
    this.check(typeof p.isHome==='boolean' && (p.ourTeamID==null || uuid(p.ourTeamID))
      && (p.isSpectator==null || typeof p.isSpectator==='boolean'),400,'invalid_import','比赛球队资料不完整，请核对原记录。');
    this.check(Number.isFinite(new Date((p.playedAt ?? p.startedAt ?? p.scheduledAt ?? p.createdAt)*1000+978307200000).getTime()),400,'invalid_import','比赛日期无法读取，请核对原记录。');
    this.check(p.status===1 && s.isFinal && body.sourceID.toLowerCase()!==p.id.toLowerCase(),400,'invalid_import','请选择已结束的本机比赛。');
    this.check(!p.sharedTeamID && !p.sharedGameID && !p.sharedPlayerLinks && !p.historySourceID,400,'invalid_import','共享比赛不能再次作为本机历史导入。');
    const side=[s.homeTeam,s.awayTeam].find(t=>t.id.toLowerCase()===body.sourceTeamID.toLowerCase());
    const originalTeam=p.ourTeamID ?? (p.isHome ? s.homeTeam.id : s.awayTeam.id);
    this.check(side && (p.isSpectator===true || originalTeam.toLowerCase()===body.sourceTeamID.toLowerCase()),400,'wrong_team','比赛与所选本机球队不一致。');
    const profile=JSON.parse(this.db.prepare('SELECT profile FROM team_profiles WHERE team_id=?').get(team).profile);
    const sources=new Set(side.players.map(p=>p.id.toLowerCase())),targets=new Set(profile.players.map(p=>p.sourceID.toLowerCase())),used=new Set();
    for(const l of body.playerLinks) {
      this.check(l && uuid(l.sourceID) && uuid(l.targetID) && sources.has(l.sourceID.toLowerCase()) && targets.has(l.targetID.toLowerCase()) && !used.has(l.sourceID.toLowerCase()),400,'invalid_player_link','球员对应关系已失效，请刷新名单后核对。');
      used.add(l.sourceID.toLowerCase());
    }
    // Two different people appearing in one match cannot be silently combined.
    const effective=side.players.map(p=>body.playerLinks.find(l=>l.sourceID.toLowerCase()===p.id.toLowerCase())?.targetID.toLowerCase() ?? p.id.toLowerCase());
    this.check(new Set(effective).size===effective.length,400,'ambiguous_player_link','同一场的两名球员不能对应到同一人，请分别核对。');
    return {...valid,links:normalizeLinks(body.playerLinks),fingerprint:importFingerprint(p)};
  }
  preview(team,device,body) {
    const valid=this.validate(team,device,body),source=body.sourceID.toLowerCase(),sourceTeam=body.sourceTeamID.toLowerCase();
    const rows=this.db.prepare('SELECT * FROM shared_games WHERE team_id=? ORDER BY id').all(team);
    const exact=rows.find(r=>r.source_id===source);
    let status='new',candidates=[];
    if(exact) {
      const links=this.store.playerLinks.links(exact.id);
      status=exact.deleted ? 'deleted' : (importFingerprint(JSON.parse(exact.payload))===valid.fingerprint && JSON.stringify(links)===JSON.stringify(valid.links) ? 'duplicate' : 'conflict');
      candidates=[exact];
    } else {
      candidates=rows.filter(r=>{
        if(r.deleted)return false;
        const p=JSON.parse(r.payload),meta=this.db.prepare('SELECT source_team_id FROM history_imports WHERE game_id=?').get(r.id);
        const teamID=meta?.source_team_id ?? p.ourTeamID?.toLowerCase() ?? (p.isHome ? p.state.homeTeam.id : p.state.awayTeam.id).toLowerCase();
        return !r.deleted && p.status===1 && matchDay(p)===matchDay(body.payload) && name(opponent(p,teamID))===name(opponent(body.payload,sourceTeam));
      });
      if(candidates.length) status='similar';
    }
    const teamRow=this.db.prepare('SELECT revision,access_epoch FROM teams WHERE id=?').get(team);
    const token=hash(JSON.stringify({source,sourceTeam,copyID:body.payload.id,links:valid.links,digest:valid.fingerprint,teamRow,status,
      candidates:candidates.map(r=>[r.id,r.revision,r.epoch,r.deleted]),recovery:this.store.listTeams(device).recoveryEpoch}));
    return {status,token,candidates:candidates.map(r=>{if(r.purged)return {id:r.id,revision:r.revision,deleted:true,title:'已删除并清除的比赛',date:'',awayScore:0,homeScore:0};const p=JSON.parse(r.payload);return {id:r.id,revision:r.revision,deleted:Boolean(r.deleted),title:p.state.awayTeam.name+' vs '+p.state.homeTeam.name,
      date:matchDay(p),awayScore:p.state.awayRunsByInning.reduce((a,b)=>a+b,0),homeScore:p.state.homeRunsByInning.reduce((a,b)=>a+b,0)};})};
  }
  commit(team,device,body) {
    return this.store.operation(device,body,`historyImport:${team}`,()=>this.store.membership(team,device,'importHistory'),()=>{
      const preview=this.preview(team,device,body);
      this.check(body.previewToken===preview.token,409,'import_changed','云端比赛或名单已有变化，请重新预览核对。');
      this.check(['new','similar'].includes(preview.status),409,'import_duplicate','同来源比赛已存在，请保留云端记录或进入该场核对；导入不会覆盖或恢复它。');
      this.check(preview.status!=='similar' || body.independent===true,409,'duplicate_review','存在同日同对手比赛，请明确确认这是另一场比赛。');
      const valid=this.validate(team,device,body),member=this.store.membership(team,device),game=randomUUID();
      this.store.checkGameCapacity(team,valid);
      this.check(!this.db.prepare("SELECT 1 FROM shared_games WHERE team_id=? AND lower(json_extract(payload,'$.id'))=?").get(team,body.payload.id.toLowerCase()),409,'copy_id_conflict','导入副本编号已占用，请重新选择比赛。');
      this.db.prepare(`INSERT INTO shared_games(id,team_id,source_id,origin_device,revision,epoch,writer_device,responsible_member,payload,projection,digest,updated_at,view_code)
        VALUES(?,?,?,?,1,1,?,?,?,?,?,?,?)`).run(game,team,body.sourceID.toLowerCase(),device,device,member.id,valid.payload,valid.projection,valid.digest,this.store.now(),randomBytes(24).toString('base64url'));
      this.db.prepare('INSERT INTO history_imports VALUES(?,?,?,?,?,?,?)').run(game,body.sourceTeamID.toLowerCase(),body.sourceID.toLowerCase(),valid.fingerprint,JSON.stringify(valid.links),this.store.now(),device);
      const row=this.store.games.row(team,game,device);this.store.games.changed(row,device,'historyImported');
      return this.store.games.metadata(row,device);
    });
  }
}
