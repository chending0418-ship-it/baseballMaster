import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes,randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { mkdtempSync,rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createCollaborationServer,BASE } from '../server.mjs';
const token=()=>randomBytes(32).toString('base64url');
const op=body=>({operationID:randomUUID(),...body});
import { fixture } from './match-fixture.mjs';

async function setup(t,options={}) {
 const app=createCollaborationServer({requestsPerMinute:10000,allowLocalViewer:true,...options});app.server.listen(0,'127.0.0.1');await once(app.server,'listening');
 t.after(async()=>{app.server.closeAllConnections();await new Promise(r=>app.server.close(r));app.store.close()});
 const origin=`http://127.0.0.1:${app.server.address().port}`;
 const req=async(key,method,path,body,cookie)=>{const r=await fetch(origin+BASE+path,{method,headers:{'Content-Type':'application/json','X-BM-Protocol':'1',Authorization:`Bearer ${key}`,Origin:origin,...(cookie?{Cookie:cookie}:{})},body:body===undefined?undefined:JSON.stringify(body)});return {status:r.status,data:await r.json(),cookie:r.headers.get('set-cookie'),headers:r.headers}};
 const device=async()=>{const key=token(),r=await req(key,'POST','/devices',{label:'iPhone 17 Pro'});return {key,id:r.data.deviceID}};
 const admin=await device(),team=(await req(admin.key,'POST','/teams',op({name:'协作队',nickname:'教练'}))).data.teamID;
 const member=async(role)=>{const d=await device(),v=token();await req(admin.key,'POST',`/teams/${team}/invitations`,op({token:v,role}));const r=await req(d.key,'POST','/invitations/redeem',op({token:v,nickname:role}));return {...d,memberID:r.data.memberID}};
 const create=async(final=false,mode='standard')=>{const data=fixture(final,mode),r=await req(admin.key,'POST',`/teams/${team}/games`,op(data));assert.equal(r.status,200,JSON.stringify(r.data));return {...r.data,fixture:data,path:`/teams/${team}/games/${r.data.id}`}};
 return {...app,req,device,admin,team,member,create,origin};
}
test('complete match revisions: permission isolation, replay, conflict, projection validation and incremental paging',async t=>{
 const a=await setup(t),member=await a.member('member'),scorer=await a.member('scorer'),g=await a.create();
 assert.equal((await a.req(member.key,'GET',g.path)).data.payload.id,g.fixture.payload.id);
 const update=op({...g.fixture,baseRevision:1,writerEpoch:1});
 assert.equal((await a.req(member.key,'PUT',g.path,update)).status,409);
 assert.equal((await a.req(scorer.key,'PUT',g.path,update)).status,409);
 const result=await a.req(a.admin.key,'PUT',g.path,update);assert.equal(result.status,200);assert.equal(result.data.serverRevision,2);
 assert.deepEqual((await a.req(a.admin.key,'PUT',g.path,update)).data,result.data);
 assert.equal((await a.req(a.admin.key,'PUT',g.path,{...update,baseRevision:2})).status,409);
 assert.equal((await a.req(a.admin.key,'PUT',g.path,op({...g.fixture,baseRevision:1,writerEpoch:1}))).status,409);
 const invalid=structuredClone(g.fixture);invalid.projection.home.runs=5;
 assert.equal((await a.req(a.admin.key,'PUT',g.path,op({...invalid,baseRevision:2,writerEpoch:1}))).status,400);
 const page=await a.req(member.key,'GET',`/teams/${a.team}/games`);assert.equal(page.data.games.length,1);assert.equal(page.data.games[0].serverRevision,2);
 assert.equal((await a.req(member.key,'GET',`/teams/${a.team}/games?cursor=${page.data.cursor}`)).data.games.length,0);
 const stranger=await a.device();assert.equal((await a.req(stranger.key,'GET',g.path)).status,404);
 for(const mode of ['coachPitch','slowPitch']) assert.equal((await a.create(false,mode)).serverRevision,1);
});
test('two-party handoff freezes source; only fetched revision/digest can accept; lost replies and old epochs cannot take ownership back',async t=>{
 const a=await setup(t),b=await a.member('scorer'),g=await a.create();
 const proposal=op({targetDeviceID:b.id,baseRevision:1,writerEpoch:1});
 const h=await a.req(a.admin.key,'POST',g.path+'/handoff/propose',proposal);assert.equal(h.status,200);assert.equal(h.data.canWrite,false);
 assert.equal((await a.req(a.admin.key,'PUT',g.path,op({...g.fixture,baseRevision:1,writerEpoch:1}))).status,409);
 const remote=(await a.req(b.key,'GET',g.path)).data;
 const accept=op({handoffID:remote.handoff.id,digest:remote.digest,baseRevision:remote.serverRevision,writerEpoch:remote.writerEpoch});
 assert.equal((await a.req(b.key,'POST',g.path+'/handoff/accept',{...accept,digest:'bad'})).status,409);
 const accepted=await a.req(b.key,'POST',g.path+'/handoff/accept',accept);assert.equal(accepted.status,200);assert.equal(accepted.data.writerEpoch,2);
 assert.deepEqual((await a.req(b.key,'POST',g.path+'/handoff/accept',accept)).data,accepted.data);
 assert.equal((await a.req(a.admin.key,'PUT',g.path,op({...g.fixture,baseRevision:1,writerEpoch:1}))).status,409);
 assert.equal((await a.req(b.key,'PUT',g.path,op({...g.fixture,baseRevision:1,writerEpoch:2}))).status,200);
});
test('administrator force takeover and role revocation fence old device; no timeout takeover',async t=>{
 const a=await setup(t),b=await a.member('scorer'),g=await a.create();
 assert.equal((await a.req(b.key,'POST',g.path+'/handoff/force',op({targetDeviceID:b.id,baseRevision:1,writerEpoch:1,confirmRisk:true}))).status,403);
 const forced=await a.req(a.admin.key,'POST',g.path+'/handoff/force',op({targetDeviceID:b.id,baseRevision:1,writerEpoch:1,confirmRisk:true}));assert.equal(forced.status,200);
 await a.req(a.admin.key,'PATCH',`/teams/${a.team}/members/${b.memberID}`,op({role:'member',baseRevision:1}));
 assert.equal((await a.req(b.key,'PUT',g.path,op({...g.fixture,baseRevision:1,writerEpoch:2}))).status,409);
 assert.equal((await a.req(a.admin.key,'GET',g.path)).data.writerActive,false);
});
test('anonymous live link keeps its original final hour independently of archive permissions; expired links never disclose archives',async t=>{
 let now=Date.now();const a=await setup(t,{now:()=>now}),member=await a.member('member'),g=await a.create();
 assert.equal((await a.req('', 'GET','/watch/'+g.viewCode)).status,404);
 const opened=(await a.req(a.admin.key,'POST',g.path+'/live',op({enabled:true}))).data;
 const live='/live/'+opened.liveCode;assert.notEqual(opened.liveCode,g.viewCode);
 assert.equal((await a.req('', 'GET',live)).status,200);
 now+=3500000;await a.req(a.admin.key,'POST',g.path+'/live-heartbeat',{});
 now+=3500000;assert.equal((await a.req('', 'GET',live)).status,200);
 const final=structuredClone(g.fixture);final.payload.status=1;final.payload.state.isFinal=true;final.projection.isFinal=true;final.projection.endedAt=now;
 assert.equal((await a.req(a.admin.key,'PUT',g.path,op({...final,baseRevision:1,writerEpoch:1}))).status,200);
 assert.equal((await a.req('', 'GET',live)).status,200);
 assert.equal((await a.req('', 'GET','/watch/'+g.viewCode)).status,404);
 assert.equal((await a.req(member.key,'GET',g.path)).data.payload.status,1);
 await a.req(a.admin.key,'POST',g.path+'/access',op({mode:'restricted',baseAccessVersion:1}));
 assert.equal((await a.req('','GET','/watch/'+g.viewCode)).status,403);
 const watched=await a.req('','GET',live);assert.equal(watched.status,200);assert.equal(watched.data.payload,undefined);assert.equal(watched.headers.get('cache-control'),'no-store');
 now+=3599999;assert.equal((await a.req('', 'GET',live)).status,200);
 await a.req(a.admin.key,'POST',g.path+'/live-heartbeat',{});
 await a.req(a.admin.key,'PUT',g.path,op({...final,baseRevision:2,writerEpoch:1}));
 now+=1;assert.equal((await a.req('', 'GET',live)).status,404);
 await a.req(a.admin.key,'POST',g.path+'/access',op({mode:'public',baseAccessVersion:2}));
 assert.equal((await a.req('', 'GET','/watch/'+g.viewCode)).status,200);
 assert.equal((await a.req('', 'GET',live)).status,404);
});
test('closing and inactivity expiration stop live access without deleting the game; heartbeat cannot revive an expired link',async t=>{
 let now=Date.now();const a=await setup(t,{now:()=>now}),g=await a.create();
 const opened=(await a.req(a.admin.key,'POST',g.path+'/live',op({enabled:true}))).data;
 now+=3600000;await a.req(a.admin.key,'POST',g.path+'/live-heartbeat',{});
 assert.equal((await a.req('','GET','/live/'+opened.liveCode)).status,404);
 const again=(await a.req(a.admin.key,'POST',g.path+'/live',op({enabled:true}))).data;assert.notEqual(again.liveCode,opened.liveCode);
 await a.req(a.admin.key,'POST',g.path+'/live',op({enabled:false}));
 assert.equal((await a.req('','GET','/live/'+again.liveCode)).status,404);
 assert.ok((await a.req(a.admin.key,'GET',g.path)).data.payload);
});
test('external viewers need one-time invitation AND explicit approval; forwarded link, revoked session and closed/reopened access cannot revive grants',async t=>{
 const a=await setup(t),g=await a.create(true),invitation=token(),session=token();
 await a.req(a.admin.key,'POST',g.path+'/access',op({mode:'restricted',baseAccessVersion:1}));
 await a.req(a.admin.key,'POST',g.path+'/viewer-invitations',op({token:invitation}));
 const apply=await a.req('','POST','/watch/'+g.viewCode+'/apply',{token:invitation,session,nickname:'家长'});assert.equal(apply.status,200);assert.equal(apply.data.status,'pending');
 assert.match(apply.cookie,/HttpOnly/);const cookie=apply.cookie.split(';')[0];
 assert.equal((await a.req('','GET','/watch/'+g.viewCode,undefined,cookie)).status,403);
 assert.equal((await a.req('','POST','/watch/'+g.viewCode+'/apply',{token:invitation,session:token(),nickname:'冒用'})).status,410);
 const viewer=(await a.req(a.admin.key,'GET',g.path+'/viewers')).data.viewers[0];
 await a.req(a.admin.key,'POST',g.path+'/viewers',op({viewerID:viewer.id,allow:true,baseAccessVersion:2}));
 assert.equal((await a.req('','GET','/watch/'+g.viewCode,undefined,cookie)).status,200);
 assert.equal((await a.req('','GET','/watch/'+g.viewCode)).status,403);
 await a.req(a.admin.key,'POST',g.path+'/access',op({mode:'closed',baseAccessVersion:3}));
 assert.equal((await a.req('','GET','/watch/'+g.viewCode,undefined,cookie)).status,404);
 await a.req(a.admin.key,'POST',g.path+'/access',op({mode:'restricted',baseAccessVersion:4}));
 assert.equal((await a.req('','GET','/watch/'+g.viewCode,undefined,cookie)).status,403);
 assert.equal((await a.req(a.admin.key,'GET',g.path)).data.payload.status,1);
});
test('selected member browser ticket requires current membership; leaving revokes browser access',async t=>{
 const a=await setup(t),m=await a.member('member'),g=await a.create(true),ticket=token();
 await a.req(a.admin.key,'POST',g.path+'/access',op({mode:'restricted',baseAccessVersion:1}));
 assert.equal((await a.req(m.key,'POST',g.path+'/member-ticket',op({token:ticket}))).status,403);
 await a.req(a.admin.key,'POST',g.path+'/viewers',op({memberID:m.memberID,allow:true,baseAccessVersion:2}));
 assert.equal((await a.req(m.key,'POST',g.path+'/member-ticket',op({token:ticket}))).status,200);
 const apply=await a.req('','POST','/watch/'+g.viewCode+'/apply',{token:ticket,session:token(),nickname:'队内成员'});assert.equal(apply.status,200);
 const cookie=apply.cookie.split(';')[0];assert.equal((await a.req('','GET','/watch/'+g.viewCode,undefined,cookie)).status,200);
 await a.req(m.key,'POST',`/teams/${a.team}/leave`,op({}));assert.equal((await a.req('','GET','/watch/'+g.viewCode,undefined,cookie)).status,403);
});
test('persistent archives and closed permissions survive restart without expiry',async t=>{
 const dir=mkdtempSync(join(tmpdir(),'bm-archive-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
 const a=await setup(t,{database:join(dir,'matches.sqlite')}),g=await a.create(true);
 const count=a.store.db.prepare('SELECT COUNT(*) n FROM shared_games').get().n;assert.equal(count,1);
 const {CollaborationStore}=await import('../server.mjs');const reopened=new CollaborationStore({database:join(dir,'matches.sqlite'),now:()=>Date.now()+365*86400000});t.after(()=>reopened.close());
 assert.equal(reopened.games.document(a.team,g.id,a.admin.id).payload.status,1);
 assert.throws(()=>reopened.games.publicRead(g.viewCode),e=>e.status===404);
});

test('deletion propagates tombstone, invalidates old epochs and closes grants; restore never reopens links',async t=>{
 const a=await setup(t),g=await a.create(true),m=await a.member('member');
 await a.req(a.admin.key,'POST',g.path+'/access',op({mode:'public',baseAccessVersion:1}));
 assert.equal((await a.req(m.key,'POST',g.path+'/deletion',op({deleted:true,baseRevision:1,writerEpoch:1}))).status,403);
 const deleted=await a.req(a.admin.key,'POST',g.path+'/deletion',op({deleted:true,baseRevision:1,writerEpoch:1}));assert.equal(deleted.status,200);assert.equal(deleted.data.deleted,true);
 assert.equal((await a.req(m.key,'GET',g.path)).data.payload,null);assert.equal((await a.req('','GET','/watch/'+g.viewCode)).status,404);
 assert.equal((await a.req(a.admin.key,'PUT',g.path,op({...g.fixture,baseRevision:1,writerEpoch:1}))).status,409);
 const restored=await a.req(a.admin.key,'POST',g.path+'/deletion',op({deleted:false,baseRevision:2,writerEpoch:2}));assert.equal(restored.status,200);assert.equal(restored.data.accessMode,'closed');assert.equal(restored.data.serverRevision,3);
 assert.equal((await a.req(m.key,'GET',g.path)).data.payload.status,1);assert.equal((await a.req('','GET','/watch/'+g.viewCode)).status,404);
});
test('restricted mode and approval operations cannot be changed by unrelated scorer; stale access versions are rejected',async t=>{
 const a=await setup(t),g=await a.create(true),scorer=await a.member('scorer');
 assert.equal((await a.req(scorer.key,'GET',g.path)).status,200);
 assert.equal((await a.req(scorer.key,'POST',g.path+'/access',op({mode:'public',baseAccessVersion:1}))).status,403);
 assert.equal((await a.req(scorer.key,'POST',g.path+'/viewer-invitations',op({token:token()}))).status,403);
 await a.req(a.admin.key,'POST',g.path+'/access',op({mode:'restricted',baseAccessVersion:1}));
 assert.equal((await a.req(a.admin.key,'POST',g.path+'/access',op({mode:'public',baseAccessVersion:1}))).status,409);
 assert.equal((await a.req('','GET','/watch/'+g.viewCode)).status,403);
});

test('3.1.0 contact fields survive sharing, while older writers cannot drop them after a handoff', async t => {
 const a = await setup(t), b = await a.member('scorer');
 const f = fixture(); f.formatVersion = 2; f.payload.rules.recordingMethod = 'situation';
 const observation = {type:'line',precision:'region',region:'foulLeft'};
 f.payload.state.scoringEvents = [{id:randomUUID(),category:'battedBall',battedBall:observation}];
 const created = await a.req(a.admin.key,'POST',`/teams/${a.team}/games`,op(f));
 assert.equal(created.status,200,JSON.stringify(created.data));
 const path = `/teams/${a.team}/games/${created.data.id}`;
 let remote = (await a.req(a.admin.key,'GET',path)).data;
 assert.deepEqual(remote.payload.state.scoringEvents[0].battedBall,observation);
 assert.equal(remote.payload.minimumWriterFormat,2);
 const lost = structuredClone(f); lost.formatVersion = 1; delete lost.payload.rules.recordingMethod; delete lost.payload.state.scoringEvents;
 const rejected = await a.req(a.admin.key,'PUT',path,op({...lost,baseRevision:1,writerEpoch:1}));
 assert.equal(rejected.status,426);
 assert.equal((await a.req(a.admin.key,'GET',path)).data.serverRevision,1);
 const proposal=await a.req(a.admin.key,'POST',path+'/handoff/propose',op({targetDeviceID:b.id,baseRevision:1,writerEpoch:1}));
 assert.equal(proposal.status,200);
 remote=(await a.req(b.key,'GET',path)).data;
 assert.equal((await a.req(b.key,'POST',path+'/handoff/accept',op({handoffID:remote.handoff.id,digest:remote.digest,baseRevision:1,writerEpoch:1}))).status,200);
 assert.equal((await a.req(b.key,'PUT',path,op({...lost,baseRevision:1,writerEpoch:2}))).status,426);
 assert.equal((await a.req(b.key,'PUT',path,op({...f,baseRevision:1,writerEpoch:2}))).status,200);
 const final=(await a.req(b.key,'GET',path)).data;
 assert.deepEqual(final.payload.state.scoringEvents[0].battedBall,observation);
});

test('3.1.0 observation validation rejects fake precision, invalid coordinates and slow-pitch bunts', async t => {
 const a=await setup(t);
 for (const [mode,o] of [
  ['standard',{type:'ground',precision:'point',x:2,y:.4}],
  ['standard',{type:'unknown',precision:'region',region:'center',x:.5,y:.2}],
  ['standard',{type:'fly',precision:'unknown',x:.5}],
  ['slowPitch',{type:'bunt',precision:'unknown'}],
 ]) {
  const f=fixture(false,mode);f.formatVersion=2;f.payload.state.scoringEvents=[{category:'battedBall',battedBall:o}];
  assert.equal((await a.req(a.admin.key,'POST',`/teams/${a.team}/games`,op(f))).status,400);
 }
 for(const observation of [{type:'unknown',precision:'point',x:.05,y:.1},{type:'fly',precision:'unknown'},{type:'bunt',precision:'region',region:'infieldLeft'}]) {
  const f=fixture();f.formatVersion=2;f.payload.state.scoringEvents=[{category:'battedBall',battedBall:observation}];
  assert.equal((await a.req(a.admin.key,'POST',`/teams/${a.team}/games`,op(f))).status,200);
 }
 // Legacy matches and clients retain format-1 writes until a 3.1.0 writer upgrades that match.
 const legacy=await a.create();
 assert.equal((await a.req(a.admin.key,'PUT',legacy.path,op({...legacy.fixture,baseRevision:1,writerEpoch:1}))).status,200);
});

test('V4 same-field fielding, return throws, independent error and archive writer format are preserved', async t => {
 const a=await setup(t),f=fixture();f.formatVersion=2;f.payload.rules.recordingMethod='situation';
 const fielding={status:'recorded',route:[8,4,8],participants:[]};
 const observation={type:'line',precision:'region',region:'center',fielding};
 f.payload.state.scoringEvents=[{category:'battedBall',resolvedOutcome:'一垒安打',battedBall:observation}];
 const created=await a.req(a.admin.key,'POST',`/teams/${a.team}/games`,op(f));assert.equal(created.status,200);
 const path=`/teams/${a.team}/games/${created.data.id}`;
 assert.deepEqual((await a.req(a.admin.key,'GET',path)).data.payload.state.scoringEvents[0].battedBall,observation);
 assert.equal(a.store.archives.manifest(a.team,a.admin.id).version,2);
 for(const bad of [
  {status:'recorded',route:[]}, {status:'noFielding',route:[8]}, {status:'recorded',route:[11]}, {status:'noFielding',route:[]}
 ]) {
  const body=structuredClone(f);body.payload.id=randomUUID();body.projection.gameID=body.payload.id;body.payload.state.scoringEvents[0].battedBall.fielding=bad;
  assert.equal((await a.req(a.admin.key,'POST',`/teams/${a.team}/games`,op(body))).status,400);
 }
 const error=structuredClone(f);error.payload.id=randomUUID();error.projection.gameID=error.payload.id;
 error.payload.state.scoringEvents[0].resolvedOutcome='对方失误';error.payload.state.scoringEvents[0].battedBall.fielding={status:'recorded',route:[6,3],errorPosition:3};
 assert.equal((await a.req(a.admin.key,'POST',`/teams/${a.team}/games`,op(error))).status,200);
 const pending=structuredClone(f);pending.payload.id=randomUUID();pending.projection.gameID=pending.payload.id;
 pending.payload.state.scoringEvents[0].battedBall.fielding={status:'pending',route:[7]};
 assert.equal((await a.req(a.admin.key,'POST',`/teams/${a.team}/games`,op(pending))).status,200);
});
