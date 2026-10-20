import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CollaborationStore, createCollaborationServer, BASE } from '../server.mjs';
import { fixture } from './match-fixture.mjs';
const secret=()=>randomBytes(32).toString('base64url'), op=b=>({operationID:randomUUID(),...b});
const rejected=(f,c)=>assert.throws(f,e=>e.code===c);
function setup(t, options={}) {
 const store=new CollaborationStore(options);t.after(()=>{try {store.close();} catch {}});
 const credential=secret(), admin=store.register(credential,{label:'iPhone 17 Pro'}).deviceID;
 const target=randomUUID(), other=randomUUID();
 const team=store.createTeam(admin,op({name:'海浪',nickname:'陈教练',profile:{shortName:'海浪',city:'',players:[target,other].map(sourceID=>({sourceID,chineseName:'陈昊',englishName:'',numbers:['00']}))}})).teamID;
 function member(role) {const device=store.register(secret(),{label:role}).deviceID,token=secret();store.invite(team,admin,op({role,token}));store.redeem(device,op({token,nickname:role}));return device;}
 function game(mode='standard',final=true) {
  const b=fixture(final,mode);b.payload.isHome=true;b.payload.ourTeamID=b.payload.state.homeTeam.id;b.payload.isSpectator=false;
  const player={id:randomUUID(),chineseName:'临时陈昊',englishName:'',numbers:[7],jerseyNumbers:['07']};
  b.payload.state.homeTeam.players=[player];b.payload.state.homeBattingOrderIDs=[player.id];
  b.payload.state.batting=[player.id,{atBats:2,hits:1}];
  b.payload.state.pitching=[player.id,{outsRecorded:2,runs:0,earnedRuns:0,pitches:9,strikes:6}];
  b.payload.state.fielding=[player.id,{putouts:2,assists:1,errors:0}];
  const saved=store.games.create(team,admin,op(b));return {b,player,saved};
 }
 return {store,credential,admin,team,target,other,member,game};
}
function request(a,g,links,extra={}) {const row=a.store.games.document(a.team,g.saved.id,a.admin);return op({baseRevision:row.serverRevision,writerEpoch:row.writerEpoch,baseRosterRevision:a.store.roster.read(a.team,a.admin).team.revision,playerLinks:links,...extra});}
const link=(sourceID,targetID)=>({sourceID,targetID});

test('player association changes only per-match metadata across three modes; unlink, audit, and all readers see the same revision',t=>{
 const a=setup(t),reader=a.member('member'),scorer=a.member('scorer');
 for(const mode of ['standard','coachPitch','slowPitch']) {
  const g=a.game(mode),before=a.store.games.document(a.team,g.saved.id,a.admin);
  const r=request(a,g,[link(g.player.id,a.target)]),saved=a.store.playerLinks.update(a.team,g.saved.id,a.admin,r);
  assert.equal(saved.serverRevision,2);assert.equal(saved.writerDeviceID,before.writerDeviceID);
  assert.equal(saved.accessMode,'closed');assert.equal(saved.writerEpoch,1);
  for(const actor of [reader,scorer,a.admin]) {
   const after=a.store.games.document(a.team,g.saved.id,actor);
   assert.deepEqual(after.payload,before.payload);assert.deepEqual(after.projection,before.projection);assert.equal(after.digest,before.digest);
   assert.deepEqual(after.playerLinks,[link(g.player.id,a.target)]);
  }
  assert.equal(a.store.playerLinks.update(a.team,g.saved.id,a.admin,r).serverRevision,2);
  const events=a.store.playerLinks.history(a.team,g.saved.id,reader).changes;
  assert.equal(events.length,1);assert.equal(events[0].nickname,'陈教练');assert.equal(events[0].deviceName,'iPhone 17 Pro');assert.deepEqual(events[0].before,[]);
  a.store.playerLinks.update(a.team,g.saved.id,a.admin,request(a,g,[]));
  assert.deepEqual(a.store.games.document(a.team,g.saved.id,reader).playerLinks,[]);
  assert.equal(a.store.playerLinks.history(a.team,g.saved.id,reader).changes.length,2);
 }
});

test('associations are admin-only, reject cross-team targets and duplicate identities including unchanged roster IDs',t=>{
 const a=setup(t),g=a.game(),body=request(a,g,[link(g.player.id,a.target)]);
 for(const actor of [a.member('scorer'),a.member('member')]) rejected(()=>a.store.playerLinks.update(a.team,g.saved.id,actor,body),'forbidden');
 const outsider=a.store.register(secret(),{label:'外队'}).deviceID;
 rejected(()=>a.store.playerLinks.update(a.team,g.saved.id,outsider,body),'not_found');
 rejected(()=>a.store.playerLinks.history(a.team,g.saved.id,outsider),'not_found');
 for(const links of [[null],[link(randomUUID(),a.target)],[link(g.player.id,randomUUID())],[link(g.player.id,a.target),link(g.player.id,a.other)]]) {
  rejected(()=>a.store.playerLinks.update(a.team,g.saved.id,a.admin,request(a,g,links)),'invalid_player_link');
 }
 g.b.payload.state.homeTeam.players.push({id:a.target,chineseName:'同名正式球员',englishName:''});
 a.store.games.update(a.team,g.saved.id,a.admin,op({...g.b,baseRevision:1,writerEpoch:1}));
 rejected(()=>a.store.playerLinks.update(a.team,g.saved.id,a.admin,request(a,g,[link(g.player.id,a.target)])),'ambiguous_player_link');
 assert.equal(a.store.playerLinks.history(a.team,g.saved.id,a.admin).changes.length,0);
});

test('stale match and roster confirmation, handoff and deletion block edits; changed request ID cannot replay',t=>{
 const a=setup(t),g=a.game(),stale=request(a,g,[link(g.player.id,a.target)]);
 a.store.db.prepare('UPDATE teams SET revision=revision+1 WHERE id=?').run(a.team);
 rejected(()=>a.store.playerLinks.update(a.team,g.saved.id,a.admin,stale),'roster_conflict');
 const good=request(a,g,[link(g.player.id,a.target)]);a.store.playerLinks.update(a.team,g.saved.id,a.admin,good);
 rejected(()=>a.store.playerLinks.update(a.team,g.saved.id,a.admin,{...good,playerLinks:[]}), 'operation_conflict');
 rejected(()=>a.store.playerLinks.update(a.team,g.saved.id,a.admin,op({...good,operationID:randomUUID()})),'revision_conflict');
 const target=a.member('scorer'),current=a.store.games.document(a.team,g.saved.id,a.admin);
 a.store.games.handoff(a.team,g.saved.id,a.admin,'propose',op({baseRevision:current.serverRevision,writerEpoch:current.writerEpoch,targetDeviceID:target}));
 rejected(()=>a.store.playerLinks.update(a.team,g.saved.id,a.admin,request(a,g,[])),'handoff_pending');
 a.store.games.deleteOrRestore(a.team,g.saved.id,a.admin,op({deleted:true,baseRevision:current.serverRevision,writerEpoch:current.writerEpoch}));
 rejected(()=>a.store.playerLinks.update(a.team,g.saved.id,a.admin,request(a,g,[])),'invalid_link_match');
 a.store.db.prepare("UPDATE members SET role='member' WHERE team_id=? AND role='admin'").run(a.team);
 rejected(()=>a.store.playerLinks.update(a.team,g.saved.id,a.admin,good),'forbidden');
});

test('existing import associations can be corrected, stay separate from original payload, and survive ordinary corrections',t=>{
 const a=setup(t),g=a.game();
 const copy=structuredClone(g.b);copy.payload.id=randomUUID();copy.projection.gameID=copy.payload.id;copy.payload.playedAt=700000000;
 const b={...copy,sourceID:randomUUID(),sourceTeamID:copy.payload.state.homeTeam.id,playerLinks:[link(g.player.id,a.target)]};
 const token=a.store.imports.preview(a.team,a.admin,b).token;
 const saved=a.store.imports.commit(a.team,a.admin,op({...b,previewToken:token}));
 const imported={saved};assert.deepEqual(a.store.playerLinks.links(saved.id),b.playerLinks);
 a.store.playerLinks.update(a.team,saved.id,a.admin,request(a,imported,[link(g.player.id,a.other)]));
 assert.equal(a.store.imports.preview(a.team,a.admin,b).status,'conflict');
 b.playerLinks=[link(g.player.id,a.other)];assert.equal(a.store.imports.preview(a.team,a.admin,b).status,'duplicate');
 copy.payload.updatedAt++;
 a.store.games.update(a.team,saved.id,a.admin,op({...copy,baseRevision:2,writerEpoch:1}));
 assert.deepEqual(a.store.playerLinks.links(saved.id),[link(g.player.id,a.other)]);
 assert.equal(a.store.games.document(a.team,saved.id,a.admin).historySourceID,b.sourceID);
 const invalid=structuredClone(copy);invalid.payload.state.homeTeam.players=[];invalid.payload.state.homeBattingOrderIDs=[];invalid.payload.state.batting=[];invalid.payload.state.pitching=[];invalid.payload.state.fielding=[];
 rejected(()=>a.store.games.update(a.team,saved.id,a.admin,op({...invalid,baseRevision:3,writerEpoch:1})),'invalid_player_link');
});

test('ongoing and observation games cannot be associated; archived targets remain valid and invalid writes roll back',t=>{
 const a=setup(t),ongoing=a.game('standard',false);
 rejected(()=>a.store.playerLinks.update(a.team,ongoing.saved.id,a.admin,request(a,ongoing,[])),'invalid_link_match');
 const g=a.game();g.b.payload.isSpectator=true;
 a.store.games.update(a.team,g.saved.id,a.admin,op({...g.b,baseRevision:1,writerEpoch:1}));
 rejected(()=>a.store.playerLinks.update(a.team,g.saved.id,a.admin,request(a,g,[])),'invalid_link_match');
 const proper=a.game(),profile=a.store.roster.read(a.team,a.admin).team;
 const archived={...profile.players[0],archived:true};a.store.roster.update(a.team,a.admin,op({kind:'player',baseRevision:profile.revision,player:archived}));
 a.store.playerLinks.update(a.team,proper.saved.id,a.admin,request(a,proper,[link(proper.player.id,a.target)]));
 const rev=a.store.games.document(a.team,proper.saved.id,a.admin).serverRevision;
 a.store.db.exec("CREATE TRIGGER reject_link_audit BEFORE INSERT ON player_link_changes BEGIN SELECT RAISE(ABORT,'test disk failure'); END;");
 assert.throws(()=>a.store.playerLinks.update(a.team,proper.saved.id,a.admin,request(a,proper,[])));
 assert.equal(a.store.games.document(a.team,proper.saved.id,a.admin).serverRevision,rev);
 assert.deepEqual(a.store.playerLinks.links(proper.saved.id),[link(proper.player.id,a.target)]);
});

test('player link metadata, audit and idempotency survive database reopening',t=>{
 const dir=mkdtempSync(join(tmpdir(),'bm-links-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
 const database=join(dir,'db.sqlite'),a=setup(t,{database}),g=a.game(),body=request(a,g,[link(g.player.id,a.target)]);
 a.store.playerLinks.update(a.team,g.saved.id,a.admin,body);a.store.close();
 const store=new CollaborationStore({database});t.after(()=>store.close());
 assert.deepEqual(store.playerLinks.links(g.saved.id),[link(g.player.id,a.target)]);
 assert.equal(store.playerLinks.update(a.team,g.saved.id,a.admin,body).serverRevision,2);
 assert.equal(store.playerLinks.history(a.team,g.saved.id,a.admin).changes.length,1);
});

test('authenticated HTTP player links route returns revision and history without changing anonymous archive access',async t=>{
 const a=setup(t),g=a.game();
 const serverApp=createCollaborationServer();serverApp.server.listen(0,'127.0.0.1');await new Promise(r=>serverApp.server.once('listening',r));
 t.after(async()=>{serverApp.server.closeAllConnections();await new Promise(r=>serverApp.server.close(r));serverApp.store.close();});
 const credential=secret(),s=serverApp.store,admin=s.register(credential,{label:'手机'}).deviceID;
 const team=s.createTeam(admin,op({name:'飞鹰',nickname:'管理员'})).teamID;
 const target=randomUUID();s.roster.update(team,admin,op({kind:'player',baseRevision:1,player:{id:randomUUID(),sourceID:target,chineseName:'甲',englishName:'',numbers:['0'],archived:false}}));
 const b=structuredClone(g.b),saved=s.games.create(team,admin,op(b));
 const url=`http://127.0.0.1:${serverApp.server.address().port}${BASE}/teams/${team}/games/${saved.id}/player-links`;
 const response=await fetch(url,{method:'POST',headers:{Authorization:`Bearer ${credential}`,'Content-Type':'application/json','X-BM-Protocol':'1'},body:JSON.stringify(op({baseRevision:1,writerEpoch:1,baseRosterRevision:2,playerLinks:[link(g.player.id,target)]}))});
 assert.equal(response.status,200);assert.equal((await response.json()).serverRevision,2);
 const history=await fetch(url,{headers:{Authorization:`Bearer ${credential}`,'X-BM-Protocol':'1'}});assert.equal(history.status,200);assert.equal((await history.json()).changes.length,1);
 assert.equal((await fetch(url,{headers:{'X-BM-Protocol':'1'}})).status,401);rejected(()=>s.games.publicRead(saved.viewCode,null),'not_found');
});
