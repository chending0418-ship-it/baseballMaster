import test from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import {once} from 'node:events';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createCollaborationServer,BASE,CollaborationStore} from '../server.mjs';
import {fixture} from './match-fixture.mjs';
const token=()=>randomBytes(32).toString('base64url'),op=body=>({operationID:randomUUID(),...body});
async function setup(t,options={}) {
 let now=Date.now();const app=createCollaborationServer({requestsPerMinute:10000,allowLocalViewer:true,now:()=>now,...options});app.server.listen(0,'127.0.0.1');await once(app.server,'listening');
 t.after(async()=>{app.server.closeAllConnections();await new Promise(r=>app.server.close(r));app.store.close()});
 const origin=`http://127.0.0.1:${app.server.address().port}`;
 const req=async(d,method,path,body,cookie)=>{const r=await fetch(origin+BASE+path,{method,headers:{'Content-Type':'application/json','X-BM-Protocol':'1',Authorization:`Bearer ${d?.key??''}`,Origin:origin,...(cookie?{Cookie:cookie}:{})},body:body===undefined?undefined:JSON.stringify(body)});return {status:r.status,data:await r.json(),cookie:r.headers.get('set-cookie')?.split(';')[0]}};
 const device=async label=>{const key=token(),r=await req({key},'POST','/devices',{label:label??'iPhone 17 Pro'});return {key,id:r.data.deviceID}};
 const admin=await device('原管理手机'),recovery=token(),team=(await req(admin,'POST','/teams',op({name:'配对恢复队',nickname:'陈教练',recoveryToken:recovery}))).data;
 const member=async role=>{const d=await device(),invitation=token();await req(admin,'POST',`/teams/${team.teamID}/invitations`,op({token:invitation,role}));const joined=await req(d,'POST','/invitations/redeem',op({token:invitation,nickname:role}));return {...d,memberID:joined.data.memberID}};
 const pair=async(source=admin,target)=>{
  target??=await device('新管理手机');const secret=token(),created=await req(target,'POST','/pairings',op({token:secret}));assert.equal(created.status,200);
  const preview=await req(source,'POST',`/teams/${team.teamID}/pairings/preview`,{token:secret});assert.equal(preview.status,200);
  const body=op({token:secret,phrase:preview.data.phrase,memberRevision:preview.data.memberRevision});
  const path=`/teams/${team.teamID}/pairings/${created.data.id}/approve`,approved=await req(source,'POST',path,body);assert.equal(approved.status,200);
  return {target,created:created.data,preview:preview.data,body,path};
 };
 const accept=async p=>req(p.target,'POST',`/pairings/${p.created.id}/accept`,op({phrase:p.preview.phrase,memberRevision:p.preview.memberRevision}));
 const recover=async(d=admin,old=recovery,baseVersion=1)=>{const newToken=token(),body=op({teamID:team.teamID,token:old,newToken,nickname:'新管理员',confirmReset:true,baseVersion});return {body,result:await req(d,'POST','/recovery/redeem',body),newToken}};
 return {...app,req,device,admin,recovery,team,member,pair,accept,recover,advance:ms=>now+=ms};
}
test('pairing grants only the chosen membership after both devices confirm, with independent credentials and no scoring takeover',async t=>{
 const a=await setup(t),g=(await a.req(a.admin,'POST',`/teams/${a.team.teamID}/games`,op(fixture()))).data;
 const other=(await a.req(a.admin,'POST','/teams',op({name:'另一队',nickname:'教练'}))).data;
 const p=await a.pair();assert.equal((await a.req(p.target,'GET','/teams')).data.teams.length,0);
 assert.equal((await a.req(p.target,'GET',`/pairings/${p.created.id}`)).data.status,'offered');
 const accepted=await a.accept(p);assert.equal(accepted.status,200);assert.equal(accepted.data.memberID,a.team.memberID);
 const teams=(await a.req(p.target,'GET','/teams')).data.teams;assert.equal(teams.length,1);assert.equal(teams[0].id,a.team.teamID);assert.equal(teams[0].role,'admin');
 assert.equal((await a.req(p.target,'GET',`/teams/${other.teamID}`)).status,404);
 assert.equal((await a.req(p.target,'GET',`/teams/${a.team.teamID}/games/${g.id}`)).data.canWrite,false);
 const devices=(await a.req(p.target,'GET',`/teams/${a.team.teamID}/devices`)).data.devices;assert.equal(devices.length,2);
 assert.equal(new Set(devices.map(d=>d.memberID)).size,1);assert.notEqual(a.admin.key,p.target.key);
});
test('pair request expiry, phrase mismatch, source demotion and forwarded used requests never grant access',async t=>{
 const a=await setup(t),scorer=await a.member('scorer'),p=await a.pair(scorer);
 assert.equal((await a.req(p.target,'POST',`/pairings/${p.created.id}/accept`,op({phrase:'错误短语',memberRevision:p.preview.memberRevision}))).status,410);
 await a.req(a.admin,'PATCH',`/teams/${a.team.teamID}/members/${scorer.memberID}`,op({role:'member',baseRevision:1}));
 assert.equal((await a.accept(p)).status,410);
 const q=await a.pair();a.advance(600001);assert.equal((await a.accept(q)).status,410);
 assert.equal((await a.req(q.target,'GET',`/pairings/${q.created.id}`)).data.status,'expired');
 const stranger=await a.device();assert.equal((await a.req(stranger,'GET',`/pairings/${p.created.id}`)).status,404);
});
test('lost pairing replies retry the same operation; removing one device fences writes without removing other teams or paired devices',async t=>{
 const a=await setup(t),p=await a.pair();assert.equal((await a.req(a.admin,'POST',p.path,p.body)).status,200);
 const body=op({phrase:p.preview.phrase,memberRevision:p.preview.memberRevision}),path=`/pairings/${p.created.id}/accept`;
 assert.equal((await a.req(p.target,'POST',path,body)).status,200);assert.equal((await a.req(p.target,'POST',path,body)).status,200);
 const other=(await a.req(a.admin,'POST','/teams',op({name:'其他球队',nickname:'教练'}))).data;
 const g=(await a.req(a.admin,'POST',`/teams/${a.team.teamID}/games`,op(fixture()))).data;
 const devices=(await a.req(p.target,'GET',`/teams/${a.team.teamID}/devices`)).data.devices,old=devices.find(d=>d.id===a.admin.id);
 assert.equal((await a.req(p.target,'DELETE',`/teams/${a.team.teamID}/devices/${old.id}`,op({memberID:old.memberID,baseRevision:old.revision}))).status,200);
 assert.equal((await a.req(a.admin,'GET',`/teams/${a.team.teamID}`)).status,404);
 assert.equal((await a.req(a.admin,'GET',`/teams/${other.teamID}`)).status,200);
 assert.equal((await a.req(p.target,'GET',`/teams/${a.team.teamID}/games/${g.id}`)).data.writerActive,false);
 assert.equal((await a.req(a.admin,'POST',p.path,p.body)).status,404);
 assert.equal((await a.req(p.target,'POST',path,body)).status,200);
});
test('device removal protects the last reachable administrator; members can remove their own paired device only',async t=>{
 const a=await setup(t),base=`/teams/${a.team.teamID}`,devices=(await a.req(a.admin,'GET',base+'/devices')).data.devices;
 assert.equal((await a.req(a.admin,'DELETE',base+'/devices/'+a.admin.id,op({memberID:a.team.memberID,baseRevision:devices[0].revision}))).status,409);
 const member=await a.member('member'),p=await a.pair(member);await a.accept(p);
 const list=(await a.req(member,'GET',base+'/devices')).data.devices;assert.equal(list.length,2);assert.ok(list.every(d=>d.memberID===member.memberID));
 assert.equal((await a.req(member,'DELETE',base+'/devices/'+a.admin.id,op({memberID:a.team.memberID,baseRevision:1}))).status,403);
 const own=list.find(d=>d.id===member.id),body=op({memberID:own.memberID,baseRevision:own.revision});
 assert.equal((await a.req(member,'DELETE',base+'/devices/'+member.id,body)).status,200);
 assert.equal((await a.req(member,'DELETE',base+'/devices/'+member.id,body)).status,200);
 assert.equal((await a.req(p.target,'GET',base)).status,200);
});
test('recovery atomically closes old memberships, invitations, pairings, live and history links while retaining complete games',async t=>{
 const a=await setup(t),member=await a.member('member'),p=await a.pair(),base=`/teams/${a.team.teamID}`;
 const invite=token();await a.req(a.admin,'POST',base+'/invitations',op({token:invite,role:'scorer'}));
 const f=fixture(),g=(await a.req(a.admin,'POST',base+'/games',op(f))).data,path=base+'/games/'+g.id;
 const live=(await a.req(a.admin,'POST',path+'/live',op({enabled:true}))).data;
 f.payload.status=1;f.payload.state.isFinal=true;f.projection.isFinal=true;f.projection.endedAt=Date.now();await a.req(a.admin,'PUT',path,op({...f,baseRevision:1,writerEpoch:1}));
 await a.req(a.admin,'POST',path+'/access',op({mode:'public',baseAccessVersion:1}));
 const newDevice=await a.device(),recovered=await a.recover(newDevice);assert.equal(recovered.result.status,200);
 for(const d of [a.admin,member])assert.equal((await a.req(d,'GET',path)).status,404);
 assert.equal((await a.req(newDevice,'POST','/invitations/redeem',op({token:invite,nickname:'旧邀请'}))).status,410);
 assert.equal((await a.accept(p)).status,410);
 assert.equal((await a.req(null,'GET','/live/'+live.liveCode)).status,404);
 assert.equal((await a.req(null,'GET','/watch/'+g.viewCode)).status,404);
 const archive=(await a.req(newDevice,'GET',path)).data;assert.deepEqual(archive.payload,f.payload);assert.equal(archive.accessMode,'closed');assert.equal(archive.writerActive,false);assert.equal(archive.canWrite,false);
 assert.notEqual(archive.viewCode,g.viewCode);assert.equal(archive.writerEpoch,2);
 await a.req(newDevice,'POST',path+'/access',op({mode:'public',baseAccessVersion:archive.accessVersion}));
 assert.equal((await a.req(null,'GET','/watch/'+g.viewCode)).status,404);
 assert.equal((await a.req(null,'GET','/watch/'+archive.viewCode)).status,200);
});
test('recovery lost-response retry is bound to the exact operation and new device; consumed codes cannot create another admin',async t=>{
 const a=await setup(t),d=await a.device(),r=await a.recover(d);assert.equal(r.result.status,200);
 assert.deepEqual((await a.req(d,'POST','/recovery/redeem',r.body)).data,r.result.data);
 assert.equal((await a.req(d,'POST','/recovery/redeem',{...r.body,nickname:'改名重试'})).status,409);
 const stranger=await a.device();assert.equal((await a.req(stranger,'POST','/recovery/redeem',r.body)).status,404);
 assert.equal((await a.req(d,'POST','/recovery/redeem',{...r.body,operationID:randomUUID()})).status,404);
 assert.equal((await a.req(d,'GET',`/teams/${a.team.teamID}/members`)).data.members.length,1);
 assert.equal((await a.req(d,'POST','/recovery/preview',{teamID:a.team.teamID,token:r.newToken})).status,200);
 assert.equal((await a.req(d,'POST','/recovery/preview',{teamID:a.team.teamID,token:a.recovery})).status,404);
});
test('rotation requires current admin and version; secrets are hashed and persistence keeps recovery usable',async t=>{
 const dir=mkdtempSync(join(tmpdir(),'bm-recovery-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
 const a=await setup(t,{database:join(dir,'team.sqlite')}),scorer=await a.member('scorer'),fresh=token(),body=op({newToken:fresh,baseVersion:1,confirmReset:true}),path=`/teams/${a.team.teamID}/recovery`;
 assert.equal((await a.req(scorer,'GET',path)).status,403);assert.equal((await a.req(scorer,'POST',path+'/rotate',body)).status,403);
 assert.equal((await a.req(a.admin,'POST',path+'/rotate',body)).status,200);
 assert.equal((await a.req(a.admin,'POST',path+'/rotate',op({...body,newToken:token(),operationID:randomUUID()}))).status,409);
 assert.equal((await a.req(a.admin,'POST','/recovery/preview',{teamID:a.team.teamID,token:a.recovery})).status,404);
 const stored=JSON.stringify(a.store.db.prepare('SELECT * FROM team_recovery').all());assert.equal(stored.includes(fresh),false);assert.equal(stored.includes(a.recovery),false);
 const operations=JSON.stringify(a.store.db.prepare('SELECT * FROM operations').all());assert.equal(operations.includes(fresh),false);
 const second=new CollaborationStore({database:join(dir,'team.sqlite')});t.after(()=>second.close());
 assert.equal(second.security.recoveryPreview({teamID:a.team.teamID,token:fresh}).version,2);
});
test('recovery throttles guessing without revealing unknown teams',async t=>{
 const a=await setup(t,{recoveryAttempts:2}),d=await a.device();
 for(let i=0;i<2;i++){const r=await a.req(d,'POST','/recovery/preview',{teamID:randomUUID(),token:token()});assert.equal(r.status,404);assert.equal(JSON.stringify(r.data).includes(a.team.teamName??'配对恢复队'),false);}
 assert.equal((await a.req(d,'POST','/recovery/preview',{teamID:a.team.teamID,token:a.recovery})).status,429);
});
test('revoking an administrator device also invalidates its known recovery card; source can cancel a persisted pending pairing',async t=>{
 const a=await setup(t),p=await a.pair();
 assert.equal((await a.req(a.admin,'GET',`/pairings/${p.created.id}`)).data.status,'offered');
 assert.equal((await a.req(a.admin,'POST',`/pairings/${p.created.id}/cancel`,op({}))).status,200);
 assert.equal((await a.accept(p)).status,410);
 const q=await a.pair();await a.accept(q);
 const devices=(await a.req(q.target,'GET',`/teams/${a.team.teamID}/devices`)).data.devices,old=devices.find(d=>d.id===a.admin.id);
 await a.req(q.target,'DELETE',`/teams/${a.team.teamID}/devices/${old.id}`,op({memberID:old.memberID,baseRevision:old.revision}));
 assert.equal((await a.req(a.admin,'POST','/recovery/preview',{teamID:a.team.teamID,token:a.recovery})).status,404);
 const status=(await a.req(q.target,'GET',`/teams/${a.team.teamID}/recovery`)).data;assert.equal(status.hasCode,false);assert.equal(status.version,2);
 const next=token();await a.req(q.target,'POST',`/teams/${a.team.teamID}/recovery/rotate`,op({newToken:next,baseVersion:status.version,confirmReset:true}));
 assert.equal((await a.req(q.target,'POST','/recovery/preview',{teamID:a.team.teamID,token:next})).status,200);
});
test('removing one paired member device revokes only its browser sessions and unused tickets',async t=>{
 const a=await setup(t),m=await a.member('member'),p=await a.pair(m);await a.accept(p);
 const base=`/teams/${a.team.teamID}`,f=fixture();f.payload.status=1;f.payload.state.isFinal=true;f.projection.isFinal=true;f.projection.endedAt=Date.now();
 const g=(await a.req(a.admin,'POST',base+'/games',op(f))).data,path=base+'/games/'+g.id;
 assert.equal((await a.req(a.admin,'POST',path+'/access',op({mode:'restricted',baseAccessVersion:1}))).status,200);
 assert.equal((await a.req(a.admin,'POST',path+'/viewers',op({memberID:m.memberID,allow:true,baseAccessVersion:2}))).status,200);
 const cookies=[];
 for(const d of [m,p.target]) {
  const ticket=token();assert.equal((await a.req(d,'POST',path+'/member-ticket',op({token:ticket}))).status,200);
  const applied=await a.req(null,'POST','/watch/'+g.viewCode+'/apply',{token:ticket,session:token(),nickname:'成员'});assert.equal(applied.status,200);cookies.push(applied.cookie);
 }
 const unused=token();await a.req(m,'POST',path+'/member-ticket',op({token:unused}));
 assert.equal((await a.req(p.target,'DELETE',base+'/devices/'+m.id,op({memberID:m.memberID,baseRevision:1}))).status,200);
 assert.equal((await a.req(null,'GET','/watch/'+g.viewCode,undefined,cookies[0])).status,403);
 assert.equal((await a.req(null,'GET','/watch/'+g.viewCode,undefined,cookies[1])).status,200);
 assert.equal((await a.req(null,'POST','/watch/'+g.viewCode+'/apply',{token:unused,session:token(),nickname:'旧设备'})).status,410);
});
test('admin demotion invalidates its known recovery card without changing remaining admin membership',async t=>{
 const a=await setup(t),m=await a.member('scorer'),base=`/teams/${a.team.teamID}`;
 assert.equal((await a.req(a.admin,'PATCH',base+'/members/'+m.memberID,op({role:'admin',baseRevision:1}))).status,200);
 assert.equal((await a.req(m,'PATCH',base+'/members/'+a.team.memberID,op({role:'scorer',baseRevision:1}))).status,200);
 assert.equal((await a.req(a.admin,'POST','/recovery/preview',{teamID:a.team.teamID,token:a.recovery})).status,404);
 assert.equal((await a.req(m,'GET',base+'/recovery')).data.hasCode,false);
 assert.equal((await a.req(a.admin,'GET',base)).data.role,'scorer');
});
test('failed recovery transaction restores all old access; concurrent redemption creates exactly one replacement admin',async t=>{
 const a=await setup(t),base=`/teams/${a.team.teamID}`,g=(await a.req(a.admin,'POST',base+'/games',op(fixture()))).data;
 const first=await a.device(),second=await a.device(),original=a.store.games.revokeAll;
 a.store.games.revokeAll=()=>{throw Error('injected archive authorization write failure')};
 const failed=await a.recover(first);assert.equal(failed.result.status,503);
 assert.equal((await a.req(a.admin,'GET',base+'/games/'+g.id)).data.writerEpoch,1);
 assert.equal((await a.req(first,'GET','/teams')).data.teams.length,0);
 assert.equal((await a.req(a.admin,'POST','/recovery/preview',{teamID:a.team.teamID,token:a.recovery})).data.version,1);
 a.store.games.revokeAll=original;
 const rival={...failed.body,operationID:randomUUID(),newToken:token()};
 const attempts=await Promise.all([a.req(first,'POST','/recovery/redeem',failed.body),a.req(second,'POST','/recovery/redeem',rival)]);
 assert.deepEqual(attempts.map(r=>r.status).sort(),[200,404]);
 const winner=attempts[0].status===200?first:second;
 assert.equal((await a.req(winner,'GET',base+'/members')).data.members.length,1);
 assert.equal((await a.req(winner,'GET',base+'/games/'+g.id)).data.writerEpoch,2);
});
