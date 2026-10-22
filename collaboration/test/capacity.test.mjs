import test from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import {mkdtempSync,rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {CollaborationStore,createCollaborationServer,BASE} from '../server.mjs';
import {fixture} from './match-fixture.mjs';
const secret=()=>randomBytes(32).toString('base64url'),op=b=>({operationID:randomUUID(),...b});
test('match and draft byte quotas reject whole writes without truncation; reserved disk capacity blocks writes',t=>{
 const s=new CollaborationStore({maxGamesPerTeam:1});t.after(()=>s.close());const device=s.register(secret(),{label:'iPhone'}).deviceID,team=s.createTeam(device,op({name:'海浪',nickname:'教练'})).teamID;
 const body=fixture(),game=s.games.create(team,device,op(body));assert.throws(()=>s.games.create(team,device,op(fixture())),e=>e.code==='team_capacity');
 const bytes=s.db.prepare('SELECT length(CAST(payload AS BLOB))+length(CAST(projection AS BLOB)) AS n FROM shared_games').get().n;s.maxTeamBytes=bytes;
 assert.throws(()=>s.drafts.submit(team,game.id,device,op({...body,conflictID:randomUUID(),reason:'待核对'})),e=>e.code==='team_capacity');
 s.games.update(team,game.id,device,op({...body,baseRevision:1,writerEpoch:1}));assert.equal(s.games.document(team,game.id,device).serverRevision,2);
 const folder=mkdtempSync(join(tmpdir(),'bm-disk-'));t.after(()=>rmSync(folder,{recursive:true,force:true}));const disk=new CollaborationStore({database:join(folder,'team.sqlite'),minimumFreeBytes:Number.MAX_SAFE_INTEGER});t.after(()=>disk.close());
 assert.throws(()=>disk.register(secret(),{label:'iPhone'}),e=>e.code==='storage_capacity');assert.equal(disk.db.prepare('SELECT COUNT(*) AS n FROM devices').get().n,0);
});
test('configured loopback proxy separates real viewers; untrusted forwarded headers cannot bypass rate limits',async t=>{
 for(const trustProxy of [false,true]){
  const app=createCollaborationServer({trustProxy,requestsPerMinute:1});app.server.listen(0,'127.0.0.1');await new Promise(r=>app.server.once('listening',r));
  const origin=`http://127.0.0.1:${app.server.address().port}${BASE}`;
  try {const request=ip=>fetch(origin+'/devices',{method:'POST',headers:{'X-Real-IP':ip,'X-BM-Protocol':'1','Content-Type':'application/json',Authorization:'Bearer '+secret()},body:JSON.stringify({label:'iPhone'})});
   assert.equal((await request('192.0.2.1')).status,200);assert.equal((await request('192.0.2.2')).status,trustProxy?200:429);
   assert.equal((await request('192.0.2.1')).status,429);
  }finally{app.server.closeAllConnections();await new Promise(r=>app.server.close(r));app.store.close();}
 }
});
