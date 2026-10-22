import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

export function maintain(store) {
  const db=store.db,cutoff=store.now()-30*86400000;
  const result=store.transaction(()=>{
    const games=db.prepare('SELECT id,team_id FROM shared_games WHERE deleted=1 AND purged=0 AND updated_at<=?').all(cutoff);
    for(const g of games) {
      db.prepare("UPDATE operations SET result='{}',retired=1 WHERE json_extract(result,'$.id')=?").run(g.id);
      db.prepare("UPDATE shared_games SET payload='{}',projection='{}',digest=?,purged=1,revision=revision+1 WHERE id=?").run(createHash('sha256').update('{}').digest('hex'),g.id);
      for(const table of ['conflict_drafts','player_link_changes','game_player_links','history_imports']) db.prepare(`DELETE FROM ${table} WHERE game_id=?`).run(g.id);
      store.games.revokeAll(g.id);
      db.prepare('INSERT INTO game_changes(team_id,game_id) VALUES(?,?)').run(g.team_id,g.id);
    }
    const teams=db.prepare('SELECT team_id FROM team_deletions WHERE restored_at IS NULL AND deleted_at<=?').all(cutoff);
    for(const {team_id:team} of teams) {
      db.prepare("UPDATE operations SET result='{}',retired=1 WHERE json_extract(result,'$.teamID')=? OR json_extract(result,'$.team.id')=?").run(team,team);
      db.prepare("UPDATE teams SET name='已清除球队' WHERE id=?").run(team);
      db.prepare("UPDATE team_profiles SET profile=? WHERE team_id=?").run(JSON.stringify({shortName:'已清除球队',city:'',players:[]}),team);
      db.prepare("UPDATE members SET nickname='已清除成员' WHERE team_id=?").run(team);
      db.prepare('UPDATE audit SET actor_nickname=NULL,actor_device_label=NULL WHERE team_id=?').run(team);
      db.prepare('DELETE FROM team_restore_devices WHERE team_id=?').run(team);
    }
    // Keep small operation tombstones, so a very old client cannot repeat an expired side effect.
    db.prepare("UPDATE operations SET result='{}',retired=1 WHERE retired=0 AND created_at<=?").run(cutoff);
    return {purgedGames:games.length,expiredTeams:teams.length};
  });
  let compactedChanges=0;
  for(const {team_id:team} of db.prepare('SELECT DISTINCT team_id FROM game_changes').all()) compactedChanges+=store.games.compactChanges(team);
  return {...result,compactedChanges};
}

if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href) {
  const {CollaborationStore}=await import('./server.mjs');
  if(!process.env.BM_TEAM_DATABASE)throw new Error('BM_TEAM_DATABASE is required');
  const store=new CollaborationStore({database:process.env.BM_TEAM_DATABASE});
  try{process.stdout.write(JSON.stringify(maintain(store))+'\n');}finally{store.close();}
}
