import {randomBytes,randomUUID,createHash} from 'node:crypto';
import {inflateRawSync} from 'node:zlib';
import {mkdirSync,writeFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
import {fixture} from '../test/match-fixture.mjs';
import {uiSnapshot} from '../../live/test/fixture.mjs';
const {webkit}=createRequire(import.meta.url)(process.env.BM_PLAYWRIGHT||'/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const password=process.env.BM_ADMIN_PASSWORD;assert.ok(password);
const origin='https://baseballmaster.cc',output='output/validation/3.0/admin/production';mkdirSync(output,{recursive:true});
const deviceKey=randomBytes(32).toString('base64url'),liveKey=randomBytes(32).toString('hex');let team,liveCode,browser,cookie;
const evidence={startedAt:new Date().toISOString(),checks:[],passed:false};
const app=async(method,path,body)=>{const r=await fetch(origin+'/api/v3'+path,{method,headers:{Authorization:'Bearer '+deviceKey,'X-BM-Protocol':'1','Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify({operationID:randomUUID(),...body})});assert.equal(r.status,200,method+' '+path);return r.json();};
const admin=path=>fetch(origin+'/admin/api/'+path,{headers:{Cookie:cookie}});
function verifyZip(bytes){const files={};let offset=0;while(bytes.readUInt32LE(offset)===0x04034b50){const length=bytes.readUInt32LE(offset+18),nl=bytes.readUInt16LE(offset+26),extra=bytes.readUInt16LE(offset+28),start=offset+30+nl+extra;const name=bytes.subarray(offset+30,offset+30+nl).toString();files[name]=inflateRawSync(bytes.subarray(start,start+length));offset=start+length;}
 assert.equal(bytes.readUInt32LE(bytes.length-22),0x06054b50);const m=JSON.parse(files['manifest.json']);for(const f of m.files)assert.equal(createHash('sha256').update(files[f.path]).digest('hex'),f.sha256);return {files,m};}
try{
 for(const path of ['/admin/api/overview','/admin/api/teams','/admin/api/export','/admin/api/teams/'+randomUUID()+'/download'])assert.equal((await fetch(origin+path)).status,401);
 assert.equal((await fetch(origin+'/internal/admin-live')).status,404);
 const login=await fetch(origin+'/admin/api/login',{method:'POST',headers:{Origin:origin,'Content-Type':'application/json'},body:JSON.stringify({password})});assert.equal(login.status,200);cookie=login.headers.get('set-cookie').split(';')[0];assert.match(login.headers.get('set-cookie'),/HttpOnly; Secure; SameSite=Strict/);
 assert.equal((await fetch(origin+'/admin/api/teams',{headers:{Cookie:cookie,Origin:'https://unrelated.example'}})).status,403);
 evidence.checks.push('Anonymous reads/downloads denied; private bridge hidden; Secure HttpOnly SameSite cookie and cross-origin denial verified');
 const overview=await (await admin('overview')).json();assert.equal(overview.warning,null);evidence.countsBefore={teams:overview.teams,games:overview.games,live:overview.live};
 await app('POST','/devices',{label:'后台上线验证临时设备'});
 const made=await app('POST','/teams',{name:'后台测试球队（临时）',nickname:'测试教练',profile:{shortName:'后台测试',city:'上海',players:[{sourceID:randomUUID(),chineseName:'测试球员',englishName:'Demo',numbers:['00','12']}]}});team=made.teamID;
 const value=fixture();value.payload.state.awayTeam.name=value.projection.away.name='后台测试客队';value.payload.state.homeTeam.name=value.projection.home.name='后台测试主队';
 const game=await app('POST',`/teams/${team}/games`,value);await app('POST',`/teams/${team}/games/${game.id}/live`,{enabled:true});
 const liveSnapshot=uiSnapshot();liveSnapshot.away.name='后台临时直播测试 A';liveSnapshot.home.name='后台临时直播测试 B';
 const live=await fetch(origin+'/livestreaming/novideo/api/sessions',{method:'POST',headers:{Authorization:'Bearer '+liveKey,'Content-Type':'application/json'},body:JSON.stringify({requestID:randomUUID(),createdAt:Date.now(),snapshot:liveSnapshot})});assert.equal(live.status,201);liveCode=(await live.json()).code;
 const listing=await (await admin('live?state=all')).json();assert.ok(listing.items.some(x=>x.source==='shared'&&x.id===game.id));assert.ok(listing.items.some(x=>x.source==='temporary'&&x.id===liveCode));
 assert.equal((await (await admin('games?team='+team)).json()).items[0].id,game.id);
 const detail=await (await admin('teams/'+team)).json();assert.deepEqual(detail.team.players[0].numbers,['00','12']);
 for(const path of [`games/${game.id}/download`,`live/shared/${game.id}/download`,`live/temporary/${liveCode}/download`]){const r=await admin(path);assert.equal(r.status,200);assert.match(r.headers.get('content-disposition'),/attachment/);assert.ok((await r.json()).snapshot);}
 for(const path of [`teams/${team}/download`,'export']){const r=await admin(path);assert.equal(r.status,200);const bytes=Buffer.from(await r.arrayBuffer()),{files,m}=verifyZip(bytes);assert.ok(files[(path==='export'?`teams/${team}/`:'')+'matches/'+game.id+'.json']);evidence.checks.push(path==='export'?'All-cloud ZIP streamed successfully and all entry hashes verified':'Single-team ZIP streamed successfully and all entry hashes verified');if(path!=='export')writeFileSync(output+'/synthetic-team.zip',bytes);}
 evidence.checks.push('Team names and number strings, cloud filters, both live sources and individual JSON downloads passed');
 browser=await webkit.launch();const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(origin+'/admin/');await page.locator('#password').waitFor();await page.screenshot({path:output+'/login.png'});await page.locator('#password').fill(password);await page.getByRole('button',{name:'登录后台'}).click();await page.getByRole('heading',{name:'云端资料总览'}).waitFor();
 await page.goto(origin+'/admin/#teams/'+team);await page.getByRole('heading',{name:'球员资料'}).waitFor();await page.screenshot({path:output+'/synthetic-team.png',fullPage:true});
 await page.goto(origin+'/admin/#live/temporary/'+liveCode);await page.getByRole('heading',{name:'当前阵容'}).waitFor();await page.screenshot({path:output+'/synthetic-live.png',fullPage:true});
 await page.getByRole('button',{name:'退出登录',exact:true}).filter({visible:true}).click();await page.locator('#login').waitFor({state:'visible'});assert.equal((await context.request.get(origin+'/admin/api/teams')).status(),401);assert.deepEqual(errors,[]);
 evidence.checks.push('HTTPS WebKit login, global access, synthetic team/live contents and logout passed without browser errors');
 for(const path of ['/api/v3/health','/api/v3/capabilities','/livestreaming/novideo/health','/','/privacy.html','/support.html'])assert.equal((await fetch(origin+path)).status,200);
 evidence.checks.push('Existing App API, live service and public site remain available');evidence.passed=true;
}finally{
 if(browser)await browser.close();
 if(liveCode){const r=await fetch(origin+'/livestreaming/novideo/api/sessions/'+liveCode,{method:'DELETE',headers:{Authorization:'Bearer '+liveKey}});assert.equal(r.status,200);evidence.syntheticLiveClosed=true;}
 if(team){const t=await app('GET',`/teams/${team}`);await app('POST',`/teams/${team}/dissolve`,{name:t.team.name,confirm:true,baseRevision:t.team.revision});evidence.syntheticTeamDissolved=true;}
 if(cookie){const r=await fetch(origin+'/admin/api/logout',{method:'POST',headers:{Cookie:cookie,Origin:origin}});assert.equal(r.status,200);evidence.sessionRevoked=true;}
 writeFileSync(output+'/verification.json',JSON.stringify(evidence,null,2)+'\n');
}
console.log(JSON.stringify({passed:evidence.passed,checks:evidence.checks,syntheticTeamDissolved:evidence.syntheticTeamDissolved,syntheticLiveClosed:evidence.syntheticLiveClosed}));
