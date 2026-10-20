import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID,randomBytes } from 'node:crypto';
import { CollaborationStore,createCollaborationServer,BASE } from '../server.mjs';
import { fixture } from './match-fixture.mjs';
const secret=()=>randomBytes(32).toString('base64url'),op=b=>({operationID:randomUUID(),...b});
const reject=(f,c)=>assert.throws(f,e=>e.code===c);
function setup(t) {
 const s=new CollaborationStore();t.after(()=>s.close());
 const credential=secret(),admin=s.register(credential,{label:'iPhone 17 Pro'}).deviceID;
 const team=s.createTeam(admin,op({name:'海浪',nickname:'陈教练'})).teamID;
 const b=fixture(),g=s.games.create(team,admin,op(b));
 const add=role=>{const d=s.register(secret(),{label:'另一台 iPhone'}).deviceID,v=secret();s.invite(team,admin,op({role,token:v}));s.redeem(d,op({token:v,nickname:role}));return d;};
 const submit=(actor=admin)=>{const body=op({...b,conflictID:randomUUID(),reason:'断网期间未同步'});return {...s.drafts.submit(team,g.id,actor,body),body};};
 const resolve=(draftID,decision='discarded',extra={})=>{const row=s.games.document(team,g.id,admin);return s.drafts.resolve(team,g.id,draftID,admin,op({baseRevision:row.serverRevision,writerEpoch:row.writerEpoch,decision,note:'已经逐条核对',...extra}));};
 return {s,admin,credential,team,b,g,add,submit,resolve};
}
test('submitted draft is immutable, does not overwrite the match or appear in statistics; only admins can read it',t=>{
 const a=setup(t),scorer=a.add('scorer'),member=a.add('member'),d=a.submit(scorer);
 assert.equal(a.s.games.document(a.team,a.g.id,a.admin).serverRevision,1);
 assert.deepEqual(a.s.games.document(a.team,a.g.id,a.admin).payload,a.b.payload);
 assert.equal(a.s.drafts.read(a.team,a.g.id,d.draftID,a.admin).payload.id,a.b.payload.id);
 for(const actor of [scorer,member])reject(()=>a.s.drafts.list(a.team,a.g.id,actor),'forbidden');
 reject(()=>a.submit(member),'forbidden');
 assert.equal(a.s.drafts.submit(a.team,a.g.id,scorer,d.body).draftID,d.draftID);
 assert.equal(a.s.drafts.submit(a.team,a.g.id,scorer,op({...d.body,operationID:randomUUID()})).draftID,d.draftID);
 reject(()=>a.s.drafts.submit(a.team,a.g.id,scorer,op({...d.body,operationID:randomUUID(),reason:'不同内容'})),'draft_conflict');
 const stranger=a.s.register(secret(),{label:'其他球队'}).deviceID;
 reject(()=>a.s.drafts.read(a.team,a.g.id,d.draftID,stranger),'not_found');
 const foreign=a.s.createTeam(stranger,op({name:'飞鹰',nickname:'其他教练'})).teamID;
 reject(()=>a.s.drafts.read(foreign,a.g.id,d.draftID,stranger),'not_found');
});
test('manual supplement needs a later formal correction; every decision creates a revision and preserves source',t=>{
 const a=setup(t),d=a.submit();reject(()=>a.resolve(d.draftID,'supplemented'),'correction_required');
 a.s.games.update(a.team,a.g.id,a.admin,op({...a.b,baseRevision:1,writerEpoch:1}));
 const r=a.resolve(d.draftID,'supplemented');assert.equal(r.serverRevision,3);
 const saved=a.s.drafts.read(a.team,a.g.id,d.draftID,a.admin);assert.equal(saved.status,'supplemented');assert.equal(saved.resolvedRevision,3);assert.deepEqual(saved.payload,a.b.payload);
 reject(()=>a.resolve(d.draftID),'draft_resolved');
 const other=a.submit(),discarded=a.resolve(other.draftID);assert.equal(discarded.serverRevision,4);
 assert.equal(a.s.drafts.read(a.team,a.g.id,other.draftID,a.admin).status,'discarded');
 assert.deepEqual(a.s.games.document(a.team,a.g.id,a.admin).payload,a.b.payload);
});
test('stale review, deleted match, current role, source ID, quota and failed transaction never silently change a decision',t=>{
 const a=setup(t),d=a.submit();reject(()=>a.resolve(d.draftID,'discarded',{baseRevision:0}),'revision_conflict');
 const bad=structuredClone(d.body);bad.payload.id=randomUUID();bad.projection.gameID=bad.payload.id;
 reject(()=>a.s.drafts.submit(a.team,a.g.id,a.admin,op({...bad,operationID:randomUUID()})),'invalid_request');
 a.s.db.exec("CREATE TRIGGER fail_resolution BEFORE UPDATE ON conflict_drafts BEGIN SELECT RAISE(ABORT,'disk full'); END;");
 assert.throws(()=>a.resolve(d.draftID));assert.equal(a.s.games.document(a.team,a.g.id,a.admin).serverRevision,1);assert.equal(a.s.drafts.read(a.team,a.g.id,d.draftID,a.admin).status,'pending');
 a.s.db.exec('DROP TRIGGER fail_resolution');
 for(let i=1;i<20;i++)a.submit();reject(()=>a.submit(),'draft_capacity');
 a.s.games.deleteOrRestore(a.team,a.g.id,a.admin,op({deleted:true,baseRevision:1,writerEpoch:1}));
 reject(()=>a.submit(),'deleted');reject(()=>a.resolve(d.draftID),'deleted');
 reject(()=>a.s.drafts.read(a.team,a.g.id,d.draftID,a.admin),'not_found');
});
test('change log compaction expires old cursors while retaining latest objects and deletion tombstones for rebuild',t=>{
 const a=setup(t),initial=a.s.games.list(a.team,a.admin).cursor;
 for(let revision=1;revision<6;revision++)a.s.games.update(a.team,a.g.id,a.admin,op({...a.b,baseRevision:revision,writerEpoch:1}));
 const another=a.s.games.create(a.team,a.admin,op(fixture()));
 a.s.games.deleteOrRestore(a.team,another.id,a.admin,op({deleted:true,baseRevision:1,writerEpoch:1}));
 const latest=a.s.games.list(a.team,a.admin).cursor;
 assert.ok(a.s.games.compactChanges(a.team)>0);
 reject(()=>a.s.games.list(a.team,a.admin,initial),'cursor_expired');
 const rebuilt=a.s.games.list(a.team,a.admin,0);assert.equal(rebuilt.games.length,2);assert.equal(rebuilt.games.find(g=>g.id===another.id).deleted,true);
 assert.equal(a.s.games.list(a.team,a.admin,latest).games.length,0);assert.equal(a.s.games.compactChanges(a.team),0);
});
test('audit uses captured source labels, scoped pagination and filters without credentials or request payloads',t=>{
 const a=setup(t),member=a.add('member');
 for(let i=0;i<64;i++)a.s.audit(a.team,a.admin,'handoff:propose',a.g.id);
 a.s.db.prepare("UPDATE devices SET label='新设备名' WHERE id=?").run(a.admin);
 const page=a.s.auditPage(a.team,a.admin,new URLSearchParams('action=handoff:propose'));assert.equal(page.events.length,50);assert.equal(page.hasMore,true);assert.equal(page.events[0].deviceName,'iPhone 17 Pro');assert.equal(page.events[0].nickname,'陈教练');
 const next=a.s.auditPage(a.team,a.admin,new URLSearchParams('action=handoff:propose&before='+page.before));assert.equal(next.events.length,14);assert.equal(next.hasMore,false);
 assert.equal(new Set([...page.events,...next.events].map(x=>x.id)).size,64);
 assert.equal(a.s.auditPage(a.team,a.admin,new URLSearchParams('to=0')).events.length,0);
 reject(()=>a.s.auditPage(a.team,member),'forbidden');reject(()=>a.s.auditPage(a.team,a.admin,new URLSearchParams('before=-1')),'invalid_request');
 assert.equal(JSON.stringify(page).includes(a.credential),false);assert.equal(JSON.stringify(page).includes('payload'),false);
});
test('HTTP draft submission, admin review and audit reject unauthenticated and downgraded replay',async t=>{
 const app=createCollaborationServer();app.server.listen(0,'127.0.0.1');await new Promise(r=>app.server.once('listening',r));
 t.after(async()=>{app.server.closeAllConnections();await new Promise(r=>app.server.close(r));app.store.close();});
 const s=app.store,key=secret(),admin=s.register(key,{label:'iPhone'}).deviceID,team=s.createTeam(admin,op({name:'海浪',nickname:'教练'})).teamID,b=fixture(),g=s.games.create(team,admin,op(b));
 const base=`http://127.0.0.1:${app.server.address().port}${BASE}/teams/${team}/games/${g.id}/drafts`;
 const headers={'X-BM-Protocol':'1','Content-Type':'application/json',Authorization:'Bearer '+key},body=op({...b,conflictID:randomUUID(),reason:'待核对'});
 const posted=await fetch(base,{method:'POST',headers,body:JSON.stringify(body)});assert.equal(posted.status,200);const {draftID}=await posted.json();
 assert.equal((await fetch(base+'/'+draftID,{headers})).status,200);
 assert.equal((await fetch(base,{headers:{'X-BM-Protocol':'1'}})).status,401);
 s.db.prepare("UPDATE members SET role='member' WHERE team_id=?").run(team);
 assert.equal((await fetch(base,{method:'POST',headers,body:JSON.stringify(body)})).status,403);
});
