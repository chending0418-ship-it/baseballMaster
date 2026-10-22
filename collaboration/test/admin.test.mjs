import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID,createHash} from 'node:crypto';
import {inflateRawSync} from 'node:zlib';
import {adminFixture,op} from './admin-fixture.mjs';
import {uiSnapshot} from '../../live/test/fixture.mjs';
async function setup(t,options){const a=await adminFixture(options);t.after(a.close);const auth=await a.login();assert.equal(auth.status,200);const get=(path,options={})=>a.request('api/'+path,{...options,headers:{Cookie:auth.cookie.split(';')[0],...options.headers}});return {...a,auth,get};}
function unzip(data){const files={};let at=0;while(data.readUInt32LE(at)===0x04034b50){const length=data.readUInt32LE(at+18),nameLength=data.readUInt16LE(at+26),extra=data.readUInt16LE(at+28),start=at+30+nameLength+extra;files[data.subarray(at+30,at+30+nameLength).toString()]=inflateRawSync(data.subarray(start,start+length));at=start+length;}assert.equal(data.readUInt32LE(at),0x02014b50);assert.equal(data.readUInt32LE(data.length-22),0x06054b50);return files;}
test('admin content and exports require independent owner session; no App bearer shortcut',async t=>{
 const a=await setup(t);
 for(const path of ['overview','export','teams',`teams/${a.team}`,`teams/${a.team}/download`,'games',`games/${a.game.id}`,`games/${a.game.id}/download`,'live',`live/temporary/${a.temporary.code}`,`live/temporary/${a.temporary.code}/download`]){
  const r=await a.request('api/'+path,{headers:{Authorization:'Bearer '+a.key}});assert.equal(r.status,401,path);assert.equal(r.headers.get('cache-control'),'no-store');
 }
 assert.equal((await a.login('wrong')).status,401);assert.match(a.auth.cookie,/HttpOnly/);assert.match(a.auth.cookie,/SameSite=Strict/);
 const page=await a.request('');assert.equal(page.status,200);assert.match(await page.text(),/管理后台/);assert.match(page.headers.get('content-security-policy'),/frame-ancestors 'none'/);
 assert.equal((await a.request('../collaboration.sqlite')).status,404);
});
test('production session is Secure, exact origin is required, logout and one-hour expiry revoke access',async t=>{
 const a=await setup(t,{allowLocal:false});assert.match(a.auth.cookie,/__Secure-bm_admin=/);assert.match(a.auth.cookie,/; Secure;/);
 assert.equal((await a.request('api/login',{method:'POST',body:JSON.stringify({password:a.password})})).status,403);
 const headers={Cookie:a.auth.cookie.split(';')[0],Origin:'https://baseballmaster.cc'};
 assert.equal((await a.request('api/session',{headers})).status,200);
 assert.equal((await a.request('api/session',{headers:{...headers,Origin:'https://evil.example'}})).status,403);
 assert.equal((await a.request('api/session',{headers:{...headers,'Sec-Fetch-Site':'cross-site'}})).status,403);
 assert.equal((await a.request('api/logout',{method:'POST',headers})).status,200);assert.equal((await a.request('api/session',{headers})).status,401);
 const second=await a.login();a.advance(3600001);assert.equal((await a.request('api/session',{headers:{...headers,Cookie:second.cookie.split(';')[0]}})).status,401);
});
test('password guessing is rate limited and forwarded IP cannot evade direct connection limits',async t=>{
 const a=await adminFixture();t.after(a.close);
 for(let i=0;i<8;i++)assert.equal((await a.request('api/login',{method:'POST',body:'{"password":"wrong"}',headers:{'X-Real-IP':`10.0.0.${i}`}})).status,401);
 assert.equal((await a.login()).status,429);a.advance(900001);assert.equal((await a.login()).status,200);
});
test('team search, paging, roles and number strings; read-only methods leave business data intact',async t=>{
 const a=await setup(t),before=a.store.db.prepare('SELECT total_changes() AS n').get().n;
 const teams=await (await a.get('teams?limit=1')).json();assert.equal(teams.total,2);assert.equal(teams.items.length,1);
 assert.equal((await (await a.get('teams?q='+encodeURIComponent('上海'))).json()).items[0].id,a.team);
 const detail=await (await a.get('teams/'+a.team)).json();assert.deepEqual(detail.team.players[0].numbers,['00','12']);assert.equal(detail.members[0].role,'admin');assert.equal(detail.members[0].devices,1);
 assert.deepEqual(Object.keys(detail.members[0]).sort(),['active','devices','id','nickname','role']);
 for(const method of ['POST','PATCH','DELETE'])assert.equal((await a.get('teams/'+a.team,{method,body:'{}'})).status,405);
 for(const path of ['teams?limit=10000','games?team=bad','games?state=bad','live?state=bad'])assert.equal((await a.get(path)).status,400);
 assert.equal(a.store.db.prepare('SELECT total_changes() AS n').get().n,before);
});
test('cloud match contents honor filters without changing closed web archive permissions',async t=>{
 const a=await setup(t),games=await (await a.get('games?team='+a.team)).json();assert.equal(games.total,2);assert.ok(games.items.every(g=>g.teamID===a.team));
 assert.equal((await (await a.get('games?state=completed')).json()).total,2);
 const detail=await (await a.get('games/'+a.completed.id)).json();assert.equal(detail.payload.status,1);assert.equal(detail.game.accessMode,'closed');assert.equal(detail.snapshot.home.name,'北京飞鹰');
 assert.throws(()=>a.store.games.publicRead(a.completed.viewCode),e=>e.status===404);
 assert.equal((await a.get('games/'+randomUUID())).status,404);
});
test('live overview merges both services, distinguishes final links, and removes closed/expired links',async t=>{
 const a=await setup(t);let data=await (await a.get('live')).json();assert.equal(data.total,2);assert.deepEqual(new Set(data.items.map(x=>x.source)),new Set(['shared','temporary']));
 const detail=await (await a.get('live/temporary/'+a.temporary.code)).json();assert.equal(detail.snapshot.away.lineup.length,9);
 const final=uiSnapshot(2);final.isFinal=true;final.endedAt=a.clock();final.batterOrder=null;assert.equal((await a.liveRequest('PUT','/'+a.temporary.code,final)).status,200);
 data=await (await a.get('live')).json();assert.equal(data.total,1);assert.equal(data.counts.completed,1);
 assert.equal((await (await a.get('live?state=completed')).json()).items[0].id,a.temporary.code);
 a.advance(3600001);const auth=await a.login(),headers={Cookie:auth.cookie.split(';')[0]};
 assert.equal((await (await a.request('api/live?state=all',{headers})).json()).total,0);
 assert.equal((await a.request('api/live/temporary/'+a.temporary.code,{headers})).status,404);
 assert.equal((await a.request('api/live/shared/'+a.game.id,{headers})).status,404);
});
test('private legacy bridge requires secret and returns business fields only',async t=>{
 const a=await setup(t),url=a.legacyBase+'/internal/admin-live';
 assert.equal((await fetch(url)).status,404);assert.equal((await fetch(url,{headers:{Authorization:'Bearer '+'a'.repeat(64)}})).status,404);
 const headers={Authorization:'Bearer '+a.config.legacyToken};assert.equal((await fetch(url,{method:'POST',headers})).status,405);
 for(const path of ['', '/'+a.temporary.code]){const r=await fetch(url+path,{headers});assert.equal(r.status,200);assert.doesNotMatch(await r.text(),/token_hash|payload_hash|request_id/);}
});
test('live bridge outage degrades explicitly without hiding shared matches',async t=>{
 const a=await setup(t,{legacyBase:'http://127.0.0.1:1'}),r=await a.get('live'),data=await r.json();assert.equal(r.status,200);assert.match(data.warning,/临时直播服务暂不可用/);assert.equal(data.items.length,1);
});
test('team ZIP includes complete own matches and stable number labels, hashes entries and omits credentials',async t=>{
 const a=await setup(t),r=await a.get('teams/'+a.team+'/download');assert.equal(r.status,200);assert.equal(r.headers.get('content-type'),'application/zip');assert.match(r.headers.get('content-disposition'),/attachment/);
 const files=unzip(Buffer.from(await r.arrayBuffer())),manifest=JSON.parse(files['manifest.json']);assert.equal(manifest.teamID,a.team);assert.equal(manifest.games.length,2);
 assert.deepEqual(JSON.parse(files['team.json']).players[0].numbers,['00','12']);assert.ok(files['matches/'+a.game.id+'.json']);assert.equal(files['matches/'+a.otherGame.id+'.json'],undefined);
 for(const f of manifest.files){assert.equal(createHash('sha256').update(files[f.path]).digest('hex'),f.sha256);assert.equal(files[f.path].length,f.bytes);}
 for(const content of Object.values(files)){assert.ok(!content.includes(a.key));assert.ok(!content.includes(a.config.passwordHash));assert.ok(!content.includes(a.config.legacyToken));assert.doesNotMatch(content.toString(),/credential_hash|session_hash|recoveryToken|token_hash/);}
 assert.equal((await a.get('teams/'+randomUUID()+'/download')).status,404);
});
test('deleted matches remain explicitly marked until purge; dissolved team is separate from active lists',async t=>{
 const a=await setup(t);a.store.games.deleteOrRestore(a.team,a.completed.id,a.device,op({deleted:true,baseRevision:1,writerEpoch:1}));
 assert.equal((await (await a.get('games?team='+a.team)).json()).total,1);assert.equal((await (await a.get('games?team='+a.team+'&state=deleted')).json()).total,1);
 const files=unzip(Buffer.from(await (await a.get('teams/'+a.team+'/download')).arrayBuffer()));assert.equal(JSON.parse(files['matches/'+a.completed.id+'.json']).deleted,true);
 a.store.lifecycle.dissolve(a.team,a.device,op({name:'上海海浪',confirm:true,baseRevision:1}));
 assert.equal((await (await a.get('teams')).json()).total,1);assert.equal((await (await a.get('teams?state=dissolved')).json()).items[0].id,a.team);assert.equal((await (await a.get('live')).json()).items.some(g=>g.teamID===a.team),false);
});
test('site owner downloads every team and single cloud/live contents without team membership',async t=>{
 const a=await setup(t);
 const r=await a.get('export');assert.equal(r.status,200);const files=unzip(Buffer.from(await r.arrayBuffer())),manifest=JSON.parse(files['manifest.json']);
 assert.equal(manifest.scope,'all-cloud');assert.equal(manifest.teams.length,2);
 for(const [team,game]of [[a.team,a.game],[a.otherTeam,a.otherGame]]){assert.ok(files[`teams/${team}/team.json`]);assert.ok(files[`teams/${team}/matches/${game.id}.json`]);}
 for(const path of [`games/${a.otherGame.id}`,`live/shared/${a.game.id}`,`live/temporary/${a.temporary.code}`]){
  const r=await a.get(path+'/download');assert.equal(r.status,200);assert.match(r.headers.get('content-disposition'),/attachment/);const data=await r.json();assert.ok(data.snapshot);assert.equal(data.format,'BaseballMaster Admin Document');
 }
});
