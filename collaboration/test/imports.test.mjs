import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID,randomBytes } from 'node:crypto';
import { CollaborationStore,createCollaborationServer,BASE } from '../server.mjs';
import { fixture } from './match-fixture.mjs';
import { importFingerprint } from '../imports.mjs';
const secret=()=>randomBytes(32).toString('base64url'),op=body=>({operationID:randomUUID(),...body});
function setup(t) {
 const store=new CollaborationStore();t.after(()=>store.close());
 const admin=store.register(secret(),{label:'管理员 iPhone'}).deviceID;
 const target=randomUUID();
 const team=store.createTeam(admin,op({name:'海浪',nickname:'教练',profile:{shortName:'海浪',city:'',players:[{sourceID:target,chineseName:'陈昊',englishName:'',numbers:['00']}]}})).teamID;
 function member(role) {const d=store.register(secret(),{label:role}).deviceID,token=secret();store.invite(team,admin,op({role,token}));store.redeem(d,op({token,nickname:role}));return d;}
 return {store,admin,team,target,member};
}
function document(mode='standard') {
 const body=fixture(true,mode),sourceID=body.payload.id;
 body.payload.id=randomUUID();body.projection.gameID=body.payload.id;
 body.payload.ourTeamID=body.payload.state.homeTeam.id;body.payload.isHome=true;body.payload.isSpectator=false;
 body.payload.playedAt=760000000;body.payload.playedTimeZoneID='Asia/Shanghai';
 const player={id:randomUUID(),chineseName:'陈昊',englishName:'',numbers:[0],jerseyNumbers:['00'],primaryPosition:1};
 body.payload.state.homeTeam.players=[player];
 return {...body,sourceID,sourceTeamID:body.payload.state.homeTeam.id,playerLinks:[]};
}
function commit(a,b,extra={}) {const preview=a.store.imports.preview(a.team,a.admin,b);return a.store.imports.commit(a.team,a.admin,op({...b,previewToken:preview.token,...extra}));}
const code=(f,expected)=>assert.throws(f,e=>e.code===expected);

test('history import preserves all modes, timestamps, source identities and closed archives; repeat sources never duplicate',t=>{
 const a=setup(t);
 for(const mode of ['standard','coachPitch','slowPitch']) {
  const b=document(mode);b.payload.playedAt+=['standard','coachPitch','slowPitch'].indexOf(mode)*86400;
  const untouched=structuredClone(b),p=a.store.imports.preview(a.team,a.admin,b);assert.equal(p.status,'new');
  const saved=commit(a,b),read=a.store.games.document(a.team,saved.id,a.admin);
  assert.deepEqual(read.payload,b.payload);assert.deepEqual(b,untouched);assert.equal(read.accessMode,'closed');assert.equal(read.liveEnabled,false);
  assert.equal(read.historySourceID,b.sourceID);assert.deepEqual(read.playerLinks,[]);
  code(()=>a.store.games.publicRead(read.viewCode,null),'not_found');
  const retry=structuredClone(b);retry.payload.id=randomUUID();retry.projection.gameID=retry.payload.id;
  assert.equal(a.store.imports.preview(a.team,a.admin,retry).status,'duplicate');
  code(()=>commit(a,retry),'import_duplicate');
 }
 assert.equal(a.store.db.prepare('SELECT count(*) n FROM shared_games').get().n,3);
 assert.equal(a.store.db.prepare("SELECT count(*) n FROM audit WHERE action='historyImported'").get().n,3);
});

test('admins and scorers can import, members cannot preview, commit or replay; other teams are isolated',t=>{
 const a=setup(t),b=document(),scorer=a.member('scorer'),member=a.member('member');
 for(const actor of [member]) {code(()=>a.store.imports.preview(a.team,actor,b),'forbidden');code(()=>a.store.imports.commit(a.team,actor,op(b)),'forbidden');}
 assert.equal(a.store.imports.preview(a.team,scorer,b).status,'new');
 const outsider=a.store.register(secret(),{label:'外队'}).deviceID;code(()=>a.store.imports.preview(a.team,outsider,b),'not_found');
 const request=op({...b,previewToken:a.store.imports.preview(a.team,a.admin,b).token});
 const first=a.store.imports.commit(a.team,a.admin,request);assert.equal(a.store.imports.commit(a.team,a.admin,request).id,first.id);
 a.store.db.prepare("UPDATE members SET role='member' WHERE team_id=? AND role='admin'").run(a.team);
 code(()=>a.store.imports.commit(a.team,a.admin,request),'forbidden');
});

test('same source conflicts require review, tombstones cannot be resurrected, replay never repeats a write',t=>{
 const a=setup(t),b=document(),preview=a.store.imports.preview(a.team,a.admin,b),request=op({...b,previewToken:preview.token});
 const saved=a.store.imports.commit(a.team,a.admin,request);
 const edited=structuredClone(b);edited.payload.playedAt+=30;
 assert.equal(a.store.imports.preview(a.team,a.admin,edited).status,'conflict');code(()=>commit(a,edited),'import_duplicate');
 a.store.games.deleteOrRestore(a.team,saved.id,a.admin,op({deleted:true,baseRevision:1,writerEpoch:1}));
 assert.equal(a.store.imports.preview(a.team,a.admin,b).status,'deleted');code(()=>commit(a,b),'import_duplicate');
 assert.equal(a.store.imports.commit(a.team,a.admin,request).id,saved.id);
 assert.equal(a.store.games.document(a.team,saved.id,a.admin).deleted,true);
 assert.equal(a.store.db.prepare('SELECT count(*) n FROM shared_games').get().n,1);
});

test('same day/opponent is a hint requiring explicit independent confirmation; stale previews reject races',t=>{
 const a=setup(t),first=document(),second=document();
 const stale=a.store.imports.preview(a.team,a.admin,second);commit(a,first);
 code(()=>a.store.imports.commit(a.team,a.admin,op({...second,previewToken:stale.token})),'import_changed');
 const p=a.store.imports.preview(a.team,a.admin,second);assert.equal(p.status,'similar');assert.equal(p.candidates.length,1);
 code(()=>a.store.imports.commit(a.team,a.admin,op({...second,previewToken:p.token})),'duplicate_review');
 const saved=commit(a,second,{independent:true});assert.ok(saved.id);
 assert.equal(a.store.db.prepare('SELECT count(*) n FROM shared_games').get().n,2);
});

test('explicit player links are team-scoped and injective per game; changed rosters invalidate confirmation',t=>{
 const a=setup(t),b=document();b.playerLinks=[{sourceID:b.payload.state.homeTeam.players[0].id,targetID:a.target}];
 const p=a.store.imports.preview(a.team,a.admin,b),wrong=structuredClone(b);wrong.playerLinks[0].targetID=randomUUID();
 code(()=>a.store.imports.preview(a.team,a.admin,wrong),'invalid_player_link');
 const malformed=structuredClone(b);malformed.playerLinks=[null];
 code(()=>a.store.imports.preview(a.team,a.admin,malformed),'invalid_player_link');
 const wrongIdentity=structuredClone(b);wrongIdentity.payload.ourTeamID=123;
 code(()=>a.store.imports.preview(a.team,a.admin,wrongIdentity),'invalid_import');
 const wrongDate=structuredClone(b);wrongDate.payload.playedAt=1e100;
 code(()=>a.store.imports.preview(a.team,a.admin,wrongDate),'invalid_import');
 const double=structuredClone(b);double.payload.state.homeTeam.players.push({...double.payload.state.homeTeam.players[0],id:randomUUID()});
 double.playerLinks.push({sourceID:double.payload.state.homeTeam.players[1].id,targetID:a.target});
 code(()=>a.store.imports.preview(a.team,a.admin,double),'ambiguous_player_link');
 a.store.db.prepare('UPDATE teams SET revision=revision+1 WHERE id=?').run(a.team);
 code(()=>a.store.imports.commit(a.team,a.admin,op({...b,previewToken:p.token})),'import_changed');
 const saved=commit(a,b);assert.deepEqual(saved.playerLinks,b.playerLinks);
 const read=a.store.games.document(a.team,saved.id,a.admin);assert.deepEqual(read.payload.state.homeTeam.players,b.payload.state.homeTeam.players);
 const update=op({...b,baseRevision:1,writerEpoch:1});update.payload.updatedAt+=1;
 a.store.games.update(a.team,saved.id,a.admin,update);
 assert.deepEqual(a.store.games.document(a.team,saved.id,a.admin).playerLinks,b.playerLinks);
});

test('semantic duplicate fingerprints ignore UUID dictionary order and transport revisions but preserve game content',()=>{
 const b=document().payload,a=randomUUID(),c=randomUUID();b.state.batting=[a,{hits:1,atBats:2},c,{hits:2,atBats:3}];
 const reordered=structuredClone(b);reordered.id=randomUUID();reordered.revision=88;reordered.updatedAt++;
 reordered.state.batting=[c,{atBats:3,hits:2},a,{atBats:2,hits:1}];
 assert.equal(importFingerprint(b),importFingerprint(reordered));
 reordered.playedAt++;assert.notEqual(importFingerprint(b),importFingerprint(reordered));
});

test('legacy team identity uses the recorded home/away side; unchanged identity links do not create a conflict',t=>{
 const a=setup(t),b=document();delete b.payload.ourTeamID;
 b.payload.state.homeTeam.players[0].id=a.target;
 const wrong=structuredClone(b);wrong.sourceTeamID=b.payload.state.awayTeam.id;
 code(()=>a.store.imports.preview(a.team,a.admin,wrong),'wrong_team');
 commit(a,b);
 b.playerLinks=[{sourceID:a.target,targetID:a.target}];
 assert.equal(a.store.imports.preview(a.team,a.admin,b).status,'duplicate');
});

test('history preview and commit routes enforce protocol and deliver saved documents',async t=>{
 const app=createCollaborationServer();app.server.listen(0,'127.0.0.1');await new Promise(r=>app.server.once('listening',r));
 t.after(async()=>{app.server.closeAllConnections();await new Promise(r=>app.server.close(r));app.store.close();});
 const token=secret(),origin=`http://127.0.0.1:${app.server.address().port}${BASE}`;
 async function req(path,body) {const r=await fetch(origin+path,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json','X-BM-Protocol':'1'},body:JSON.stringify(body)});assert.equal(r.status,200);return r.json();}
 await req('/devices',{label:'iPhone 17 Pro'});const team=await req('/teams',op({name:'海浪',nickname:'教练'})),b=document();
 const p=await req(`/teams/${team.teamID}/history-imports/preview`,b);
 const result=await req(`/teams/${team.teamID}/history-imports/commit`,op({...b,previewToken:p.token}));
 assert.equal(result.historySourceID,b.sourceID);assert.equal(result.accessMode,'closed');
});
