// Public HTTPS checks use only a synthetic team and stream; never real member credentials.
import assert from 'node:assert/strict';
import {randomBytes, randomUUID, createHash} from 'node:crypto';
import {mkdirSync, readFileSync, writeFileSync, existsSync, unlinkSync} from 'node:fs';
import {homedir} from 'node:os';
import {join} from 'node:path';
import {createRequire} from 'node:module';
import {fixture} from '../test/match-fixture.mjs';
import {snapshot, uiSnapshot} from '../../live/test/fixture.mjs';

const mode=process.argv[2], origin='https://baseballmaster.cc', base=origin+'/api/v3';
const live=origin+'/livestreaming/novideo', out='output/validation/3.1.0/production';
const privateFolder=join(homedir(),'.config/baseballmaster');
mkdirSync(out,{recursive:true}); mkdirSync(privateFolder,{recursive:true,mode:0o700});
const receipt=join(privateFolder,'release-3.1.0-qa.json');
const secret=()=>randomBytes(32).toString('base64url');
const hash=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
async function request(key,method,path,body,expected=200){
 const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json','X-BM-Protocol':'1',...(key?{Authorization:'Bearer '+key}:{})},body:body?JSON.stringify({operationID:randomUUID(),...body}):undefined});
 const data=await r.json(); assert.equal(r.status,expected,`${method} ${path}: ${r.status} ${data.error?.code??''}`);return data;
}
async function liveRequest(s,method,path,body,expected=200){
 const r=await fetch(live+path,{method,headers:{'Content-Type':'application/json',Authorization:'Bearer '+s.liveKey},body:body?JSON.stringify(body):undefined});
 const data=await r.json();assert.equal(r.status,expected,`independent live ${method}: ${r.status}`);return data;
}
const save=s=>writeFileSync(receipt,JSON.stringify(s),{mode:0o600});

if(mode==='prepare'){
 assert.ok(!existsSync(receipt),'Finish existing synthetic QA first');
 const s={admin:secret(),existing:secret(),pending:secret(),liveKey:randomBytes(32).toString('hex')};
 writeFileSync(receipt,JSON.stringify(s),{mode:0o600,flag:'wx'});
 for(const key of [s.admin,s.existing])await request(key,'POST','/devices',{label:'3.1.0 synthetic deployment QA'});
 s.team=(await request(s.admin,'POST','/teams',{name:'3.1.0 直播部署验证（合成）',nickname:'合成管理员'})).teamID;save(s);
 const joined=secret();await request(s.admin,'POST',`/teams/${s.team}/invitations`,{token:joined,role:'member'});
 await request(s.existing,'POST','/invitations/redeem',{token:joined,nickname:'部署前成员'});
 await request(s.admin,'POST',`/teams/${s.team}/invitations`,{token:s.pending,role:'member'});
 s.fixed=(await request(s.admin,'POST',`/teams/${s.team}/team-invitation`,{rotate:false})).token;
 s.legacyFixture=fixture();const created=await request(s.admin,'POST',`/teams/${s.team}/games`,s.legacyFixture);
 s.legacyID=created.id;save(s);
 const g=await request(s.existing,'GET',`/teams/${s.team}/games/${s.legacyID}`);
 s.payloadHash=hash(g.payload);s.projectionHash=hash(g.projection);
 s.liveFixture=snapshot();const stream=await liveRequest(s,'POST','/api/sessions',{requestID:randomUUID(),createdAt:Date.now(),snapshot:s.liveFixture},201);
 s.liveCode=stream.code;save(s);
 writeFileSync(out+'/before-deployment.json',JSON.stringify({preparedAt:new Date().toISOString(),syntheticOnly:true,legacyMatchSaved:true,existingMemberJoined:true,oldInvitationPending:true,fixedInvitationPrepared:true,independentLiveCreated:true},null,2));
 console.log('Prepared synthetic legacy match, memberships, both invitation forms and independent live stream.');
}else if(mode==='verify'){
 const s=JSON.parse(readFileSync(receipt)),checks=[];
 const cap=await request(null,'GET','/capabilities');assert.equal(cap.protocol,1);assert.equal(cap.matchFormat,2);assert.equal(cap.minimumAppVersion,'3.0');
 assert.ok(cap.capabilities.includes('batted-ball-observation'));checks.push('protocol 1/minimum App 3.0 retained; format 2 available');
 const legacyPath=`/teams/${s.team}/games/${s.legacyID}`;
 let g=await request(s.existing,'GET',legacyPath);assert.equal(hash(g.payload),s.payloadHash);assert.equal(hash(g.projection),s.projectionHash);
 await request(s.admin,'PUT',legacyPath,{...s.legacyFixture,baseRevision:1,writerEpoch:1});
 assert.equal((await request(s.admin,'GET',`/teams/${s.team}/archive`)).version,1);
 checks.push('pre-existing member/match preserved; old format still writes and exports archive 1');
 const late=secret();await request(late,'POST','/devices',{label:'3.1.0 old invitation QA'});
 await request(late,'POST','/invitations/redeem',{token:s.pending,nickname:'旧邀请后加入'});
 assert.equal((await request(s.admin,'POST',`/teams/${s.team}/team-invitation`,{rotate:false})).token,s.fixed);
 const fixedMember=secret();await request(fixedMember,'POST','/devices',{label:'3.1.0 fixed invitation QA'});
 await request(fixedMember,'POST','/invitations/redeem',{token:s.fixed,nickname:'固定邀请后加入'});
 checks.push('both pre-deployment single-use and fixed invitation paths remain usable');
 assert.deepEqual((await liveRequest(s,'GET',`/api/sessions/${s.liveCode}`)).snapshot,s.liveFixture);
 checks.push('same independent legacy live link and snapshot survived');

 const route='防守路线 6-4-3，游击手张三-二垒手李四-一垒手王五';
 const projected=uiSnapshot(2);projected.entries[0].label='6-4-3';
 projected.entries[0].summary='陈昊：双杀，'+route+'；击球观察：地滚 · 内野中间';
 projected.entries[0].details=[{id:'route-names',text:projected.entries[0].summary}];
 const f=fixture();f.formatVersion=2;f.payload.rules.recordingMethod='situation';
 const observation={type:'ground',precision:'point',x:0.4,y:0.5,fielding:{status:'recorded',route:[6,4,3],participants:[{position:6,playerID:randomUUID(),name:'张三',number:'6'},{position:4,playerID:randomUUID(),name:'李四',number:'4'},{position:3,playerID:randomUUID(),name:'王五',number:'3'}]}};
 f.payload.state.scoringEvents=[{id:randomUUID(),category:'battedBall',resolvedOutcome:'双杀',battedBall:observation}];
 f.projection={...structuredClone(projected),gameID:f.payload.id};
 for(const key of ['inning','isTop','balls','strikes','outs'])f.payload.state[key]=projected[key];
 for(const side of ['home','away']){
  f.payload.state[side+'Team'].name=projected[side].name;f.payload.state[side+'RunsByInning']=[...projected[side].innings];
  f.payload.state[side+'Hits']=projected[side].hits;f.payload.state[side+'Errors']=projected[side].errors;
 }
 const created=await request(s.admin,'POST',`/teams/${s.team}/games`,f),path=`/teams/${s.team}/games/${created.id}`;
 g=await request(s.admin,'GET',path);assert.deepEqual(g.payload.state.scoringEvents[0].battedBall,observation);assert.equal(g.payload.minimumWriterFormat,2);
 const old=structuredClone(f);old.formatVersion=1;delete old.payload.rules.recordingMethod;delete old.payload.state.scoringEvents;
 const rejected=await request(s.admin,'PUT',path,{...old,baseRevision:1,writerEpoch:1},426);assert.equal(rejected.error.code,'upgrade_required');
 assert.equal((await request(s.admin,'GET',path)).serverRevision,1);
 const archive=await request(s.admin,'GET',`/teams/${s.team}/archive`);assert.equal(archive.version,2);
 const item=await request(s.admin,'GET',`/teams/${s.team}/archive/${created.id}?snapshot=${archive.snapshot}`);assert.deepEqual(item.payload.state.scoringEvents[0].battedBall,observation);
 checks.push('format 2 fielding/name snapshots preserved; old-format overwrite rejected; archive 2 preserves fields');
 const opened=await request(s.admin,'POST',path+'/live',{enabled:true});s.sharedLive={path,code:opened.liveCode};save(s);
 assert.ok(JSON.stringify(await request(null,'GET','/live/'+opened.liveCode)).includes(route));
 await request(s.admin,'PUT',legacyPath,{...s.legacyFixture,baseRevision:2,writerEpoch:1});checks.push('old-format matches still writable after another match upgrades to format 2');
 f.payload.status=1;f.payload.state.isFinal=true;Object.assign(f.projection,{isFinal:true,endedAt:Date.now(),batterOrder:null,currentAppearanceID:null});
 await request(s.admin,'PUT',path,{...f,baseRevision:1,writerEpoch:1});await request(s.admin,'POST',path+'/access',{mode:'public',baseAccessVersion:1});
 const independent=structuredClone(projected);independent.gameID=s.liveFixture.gameID;
 await liveRequest(s,'PUT',`/api/sessions/${s.liveCode}`,independent);
 const {webkit}=createRequire(import.meta.url)('/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
 const browser=await webkit.launch(),screens=[],errors=[];
 try{
  for(const [name,url] of [['independent-live',`${live}/${s.liveCode}`],['shared-live',`${origin}/watch/#live/${opened.liveCode}`],['shared-history',`${origin}/watch/#${created.viewCode}`]]){
   for(const [width,height] of [[393,852],[402,874]])for(const colorScheme of ['light','dark']){
    const context=await browser.newContext({viewport:{width,height},colorScheme}),page=await context.newPage();
    page.on('pageerror',e=>errors.push(e.message));await page.goto(url);await page.locator('#match').waitFor({state:'visible'});
    await page.locator('#entry-pa-1 summary').click();assert.ok((await page.locator('#entry-pa-1').innerText()).includes(route));
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    const file=`${name}-${width}-${colorScheme}.png`;await page.locator('#entry-pa-1').screenshot({path:out+'/'+file});screens.push(file);await context.close();
   }
  }
 }finally{await browser.close();}
 assert.deepEqual(errors,[]);checks.push('12 public HTTPS WebKit checks: independent/shared live/history, two widths, light/dark');
 await request(s.admin,'POST',path+'/live',{enabled:false});await request(null,'GET','/live/'+opened.liveCode,undefined,404);
 await request(s.admin,'POST',path+'/access',{mode:'closed',baseAccessVersion:2});await request(null,'GET','/watch/'+created.viewCode,undefined,404);
 assert.deepEqual((await request(s.admin,'GET',path)).payload.state.scoringEvents[0].battedBall,observation);
 checks.push('closing live/history access hides viewing without deleting stored match');
 writeFileSync(out+'/public-acceptance.json',JSON.stringify({passed:true,checkedAt:new Date().toISOString(),syntheticOnly:true,checks,screens,errors,routeWasSuppliedBySyntheticNewClient:true,oldAppAutomaticallyEnriched:false},null,2)+'\n');
 console.log(JSON.stringify({passed:true,checks:checks.length,browserChecks:screens.length}));
}else if(mode==='cleanup'){
 const s=JSON.parse(readFileSync(receipt));
 if(s.sharedLive)await request(s.admin,'POST',s.sharedLive.path+'/live',{enabled:false});
 if(s.liveCode)await liveRequest(s,'DELETE',`/api/sessions/${s.liveCode}`);
 if(s.team){const d=await request(s.admin,'GET',`/teams/${s.team}`);await request(s.admin,'POST',`/teams/${s.team}/dissolve`,{confirm:true,name:d.team.name,baseRevision:d.team.revision});}
 unlinkSync(receipt);writeFileSync(out+'/cleanup.json',JSON.stringify({cleanedAt:new Date().toISOString(),syntheticTeamDissolved:true,independentStreamDeleted:true,privateReceiptRemoved:true},null,2)+'\n');
 console.log('Synthetic team dissolved, stream deleted, private receipt removed.');
}else throw new Error('Use prepare, verify or cleanup');
