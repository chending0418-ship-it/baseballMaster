import {createHash} from 'node:crypto';
const hash=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
// Portable business records only. No memberships, devices, watch links, sessions, or recovery secrets.
export class TeamArchiveService {
 constructor(store,check){this.store=store;this.db=store.db;this.check=check;}
 manifest(team,device){
  this.store.membership(team,device,'exportArchive');
  const profile=this.store.roster.read(team,device).team;
  const games=this.db.prepare('SELECT id,revision AS serverRevision,deleted,purged FROM shared_games WHERE team_id=? ORDER BY id').all(team);
  const recoveryEpoch=this.store.listTeams(device).recoveryEpoch;
  const data={format:'BaseballMaster Team Archive',version:1,profile,games:games.map(g=>({...g,deleted:!!g.deleted,purged:!!g.purged})),recoveryEpoch};
  return {...data,snapshot:hash(data)};
 }
 item(team,game,device,snapshot){
  const current=this.manifest(team,device);this.check(snapshot===current.snapshot,409,'archive_changed','球队资料正在变化，请重新导出。');
  const row=this.db.prepare('SELECT * FROM shared_games WHERE team_id=? AND id=?').get(team,game);
  this.check(row,404,'not_found','比赛不存在。');
  const source=this.store.imports.info(game);
  return {id:row.id,serverRevision:row.revision,deleted:!!row.deleted,purged:!!row.purged,
   payload:row.purged?null:JSON.parse(row.payload),projection:row.purged?null:JSON.parse(row.projection),
   playerLinks:row.purged?[]:this.store.playerLinks.links(game),historySourceID:source.historySourceID??null};
 }
 finish(team,device,body){
  return this.store.operation(device,body,`archive:${team}`,()=>this.store.membership(team,device,'exportArchive'),()=>{
   this.check(body.snapshot===this.manifest(team,device).snapshot,409,'archive_changed','球队资料正在变化，请重新导出。');
   this.store.audit(team,device,'archiveExported',team);return {verified:true,snapshot:body.snapshot};
  });
 }
}
