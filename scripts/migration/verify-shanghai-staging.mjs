// Shanghai-only probes: all HTTPS sockets resolve to loopback, never public DNS.
import assert from 'node:assert/strict';
import https from 'node:https';
import {readFileSync,writeFileSync,existsSync} from 'node:fs';
import {randomBytes,randomUUID} from 'node:crypto';
import {DatabaseSync,backup} from 'node:sqlite';
import {fixture as matchFixture} from '/opt/baseballmaster-staging/current/collaboration/test/match-fixture.mjs';
import {uiSnapshot} from '/opt/baseballmaster-staging/current/live/test/fixture.mjs';
import {fixture as registrationFixture} from '/opt/baseballmaster-staging/current/lifan/test/fixture.mjs';
import {createApp as createLifan} from '/opt/baseballmaster-staging/current/lifan/server.mjs';
import {createEncryptedBackup,restoreEncryptedBackup,readBackupKey} from '/opt/baseballmaster-staging/current/collaboration/backup.mjs';

const DIR='/var/lib/baseballmaster-staging/verification';
const sessionFile=DIR+'/qa-session.json',reportFile=DIR+'/api-verification.json';
const ca=readFileSync('/etc/baseballmaster-staging/tls/ca.pem');
const qa=JSON.parse(readFileSync('/etc/baseballmaster-staging/qa.json'));
assert.equal(qa.syntheticOnly,true);
const checks=[];
const note=name=>checks.push(name);
const secret=()=>randomBytes(32).toString('base64url');
const op=value=>({operationID:randomUUID(),...value});

async function call(method,path,body,headers={},expected=200){
 const response=await new Promise((resolve,reject)=>{
  const bytes=body===undefined?undefined:Buffer.from(JSON.stringify(body));
  const req=https.request({hostname:'baseballmaster.cc',port:8443,path,method,servername:'baseballmaster.cc',ca,
   lookup:(_hostname,options,callback)=>options.all?callback(null,[{address:'127.0.0.1',family:4}]):callback(null,'127.0.0.1',4),
   headers:{Host:'baseballmaster.cc',Origin:'https://baseballmaster.cc','Content-Type':'application/json',
    ...(bytes?{'Content-Length':bytes.length}:{}),...headers}},res=>{
    const chunks=[];res.on('data',b=>chunks.push(b));res.on('end',()=>{
     const text=Buffer.concat(chunks).toString();let data;try{data=JSON.parse(text);}catch{}
     resolve({status:res.statusCode,headers:res.headers,text,data});
    });
   });
  req.on('error',reject);req.setTimeout(15000,()=>req.destroy(new Error('Loopback HTTPS timeout')));
  req.end(bytes);
 });
 assert.equal(response.status,expected,'Unexpected status in isolated HTTPS probe');
 return response;
}
const team=(key,method,path,body,expected=200)=>call(method,'/api/v3'+path,body,
 {'X-BM-Protocol':'1',...(key?{Authorization:'Bearer '+key}:{})},expected);
const live=(key,method,path,body,expected=200)=>call(method,'/livestreaming/novideo/api/sessions'+path,body,
 key?{Authorization:'Bearer '+key}:{},expected);
const persist=s=>writeFileSync(sessionFile,JSON.stringify(s)+'\n',{mode:0o600});

async function seed(){
 assert.equal(existsSync(sessionFile),false,'Existing synthetic session requires check mode');
 for(const path of ['/','/privacy.html','/support.html','/scorekeeper.html','/coach-insights.html','/coach-insights-support.html',
  '/join/','/pair/','/watch/','/recording/demoReview/','/lifan/tryout/','/lifan/tryout/admin/','/admin/'])await call('GET',path);
 for(const path of ['/.env','/.git/config','/server.mjs','/collaboration.sqlite','/internal/admin-live/',
  '/lifan/tryout/config.json','/lifan/tryout/registrations.sqlite'])await call('GET',path,undefined,{},404);
 await call('GET','/admin/api/overview',undefined,{},401);
 await call('GET','/lifan/tryout/api/admin/registrations',undefined,{},401);
 note('13 original page paths work through verified test TLS; private files and unauthenticated administration denied');
 const capabilities=(await team(null,'GET','/capabilities')).data;
 assert.equal(capabilities.protocol,1);assert.equal(capabilities.minimumAppVersion,'3.0');assert.equal(capabilities.matchFormat,2);
 const s={syntheticOnly:true,admin:secret(),member:secret(),stranger:secret(),liveKey:randomBytes(32).toString('hex')};
 for(const key of [s.admin,s.member,s.stranger])await team(key,'POST','/devices',{label:'Shanghai migration synthetic device'});
 const createdBody=op({name:'迁移合成测试队',nickname:'合成管理员'});
 const made=await team(s.admin,'POST','/teams',createdBody);s.team=made.data.teamID;
 assert.deepEqual((await team(s.admin,'POST','/teams',createdBody)).data,made.data);
 const invitation=secret();await team(s.admin,'POST',`/teams/${s.team}/invitations`,op({token:invitation,role:'member'}));
 const joined=await team(s.member,'POST','/invitations/redeem',op({token:invitation,nickname:'合成只读成员'}));s.memberID=joined.data.memberID;
 const fixed=(await team(s.admin,'POST',`/teams/${s.team}/team-invitation`,op({rotate:false}))).data.token;
 const fixedDevice=secret();await team(fixedDevice,'POST','/devices',{label:'Shanghai fixed invitation synthetic device'});
 await team(fixedDevice,'POST','/invitations/redeem',op({token:fixed,nickname:'合成固定邀请成员'}));
 note('protocol 1 and minimum App 3.0 retained; single-use and reusable invitations and operation retries work');
 let f=matchFixture(),g=(await team(s.admin,'POST',`/teams/${s.team}/games`,op(f))).data;
 s.game=g.id;s.historyCode=g.viewCode;s.path=`/teams/${s.team}/games/${g.id}`;
 await team(s.member,'GET',s.path);await team(s.stranger,'GET',s.path,undefined,404);
 await team(s.member,'PUT',s.path,op({...f,baseRevision:1,writerEpoch:1}),409);
 const opened=(await team(s.admin,'POST',s.path+'/live',op({enabled:true}))).data;s.sharedLiveCode=opened.liveCode;
 f.payload.state.homeRunsByInning=[1];f.projection.home.runs=1;f.projection.home.innings=[1];
 const update=op({...f,baseRevision:1,writerEpoch:1});
 const first=(await team(s.admin,'PUT',s.path,update)).data;
 assert.deepEqual((await team(s.admin,'PUT',s.path,update)).data,first);
 await team(s.admin,'PUT',s.path,op({...f,baseRevision:1,writerEpoch:1}),409);
 assert.equal((await team(null,'GET','/live/'+s.sharedLiveCode)).data.snapshot.home.runs,1);
 // Undo is a new formal revision, retaining the same match and view paths.
 f.payload.state.homeRunsByInning=[0];f.projection.home.runs=0;f.projection.home.innings=[0];
 await team(s.admin,'PUT',s.path,op({...f,baseRevision:2,writerEpoch:1}));
 assert.equal((await team(null,'GET','/live/'+s.sharedLiveCode)).data.snapshot.home.runs,0);
 note('shared scores update and undo; current-writer authorization, cross-team isolation, idempotency and stale revision rejection work');
 const newer=matchFixture();newer.formatVersion=2;newer.payload.rules.recordingMethod='situation';
 newer.payload.state.scoringEvents=[{id:randomUUID(),category:'battedBall',resolvedOutcome:'双杀',battedBall:{type:'ground',precision:'point',x:0.4,y:0.5,
  fielding:{status:'recorded',route:[6,4,3],participants:[{position:6,playerID:randomUUID(),name:'合成游击手',number:'6'},
   {position:4,playerID:randomUUID(),name:'合成二垒手',number:'4'},{position:3,playerID:randomUUID(),name:'合成一垒手',number:'3'}]}}}];
 const ng=(await team(s.admin,'POST',`/teams/${s.team}/games`,op(newer))).data;
 const old=structuredClone(newer);old.formatVersion=1;delete old.payload.rules.recordingMethod;delete old.payload.state.scoringEvents;
 await team(s.admin,'PUT',`/teams/${s.team}/games/${ng.id}`,op({...old,baseRevision:1,writerEpoch:1}),426);
 assert.equal((await team(s.admin,'GET',`/teams/${s.team}/games/${ng.id}`)).data.serverRevision,1);
 f.payload.status=1;f.payload.state.isFinal=true;f.projection.isFinal=true;f.projection.endedAt=Date.now();
 await team(s.admin,'PUT',s.path,op({...f,baseRevision:3,writerEpoch:1}));
 await team(s.admin,'POST',s.path+'/access',op({mode:'public',baseAccessVersion:1}));
 await team(null,'GET','/watch/'+s.historyCode);
 await team(s.admin,'POST',s.path+'/access',op({mode:'closed',baseAccessVersion:2}));
 await team(null,'GET','/watch/'+s.historyCode,undefined,404);
 await team(s.admin,'GET',s.path);
 await team(s.admin,'POST',s.path+'/access',op({mode:'public',baseAccessVersion:3}));
 const m=(await team(s.admin,'GET',`/teams/${s.team}/members`)).data;
 const row=m.members.find(x=>x.id===s.memberID);
 await team(s.admin,'PATCH',`/teams/${s.team}/members/${s.memberID}`,op({role:row.role,baseRevision:row.revision,active:false}));
 await team(s.member,'GET',s.path,undefined,404);
 note('format 1 remains writable, format 2 rejects destructive legacy writes; closed history hides without deletion; removed member loses access');
 const snapshot=uiSnapshot();snapshot.gameID=randomUUID();
 const stream=(await live(s.liveKey,'POST','',{requestID:randomUUID(),createdAt:Date.now(),snapshot},201)).data;s.liveCode=stream.code;
 await live(null,'PUT','/'+s.liveCode,{...snapshot,revision:2},401);
 await live(randomBytes(32).toString('hex'),'DELETE','/'+s.liveCode,undefined,403);
 snapshot.revision=2;snapshot.entries[0].details[0].text='上海合成逐球更新';
 await live(s.liveKey,'PUT','/'+s.liveCode,snapshot);
 assert.equal((await live(null,'GET','/'+s.liveCode)).data.snapshot.entries[0].details[0].text,'上海合成逐球更新');
 assert.equal((await live(null,'GET','/'+s.liveCode+'?since=2')).data.unchanged,true);
 s.liveRevision=2;
 note('legacy independent live publishing, pitch detail updates, unchanged polling and write credential isolation work');
 const regBody={requestId:randomUUID(),data:registrationFixture()};
 const submitted=(await call('POST','/lifan/tryout/api/registrations',regBody,{},201)).data;s.registration=submitted.id;
 assert.equal((await call('POST','/lifan/tryout/api/registrations',regBody)).data.id,s.registration);
 await call('POST','/lifan/tryout/api/registrations',{...regBody,data:{...regBody.data,playerName:'另一位合成选手'}},{},409);
 await call('POST','/lifan/tryout/api/registrations',{requestId:randomUUID(),data:regBody.data},{Origin:'https://invalid.example'},403);
 await call('POST','/lifan/tryout/api/registrations',{requestId:randomUUID(),data:{...regBody.data,dataConsent:false}},{},422);
 await call('POST','/lifan/tryout/api/admin/login',{password:'intentionally-wrong-synthetic-password'},{},401);
 const login=await call('POST','/lifan/tryout/api/admin/login',{password:qa.lifanPassword});
 assert.match(login.headers['set-cookie'][0],/Secure/);assert.match(login.headers['set-cookie'][0],/HttpOnly/);
 s.lifanCookie=login.headers['set-cookie'][0].split(';')[0];s.lifanCSRF=login.data.csrf;
 const details=(await call('GET','/lifan/tryout/api/admin/registrations/'+s.registration,undefined,{Cookie:s.lifanCookie})).data;
 assert.equal(details.data.publicityConsent,'不同意');assert.equal(details.data.injuryDetails,'测试伤病说明');
 await call('POST','/lifan/tryout/api/admin/logout',{}, {Cookie:s.lifanCookie},403);
 note('lifan original URL accepts a complete synthetic registration; duplicate retry, conflicting retry, consent, origin and administrator checks work');
 persist(s);
 writeFileSync(reportFile,JSON.stringify({syntheticOnly:true,phase:'seed',passed:true,checkedAt:new Date().toISOString(),checks},null,2)+'\n',{mode:0o600});
 console.log(JSON.stringify({passed:true,syntheticOnly:true,phase:'seed',checks:checks.length}));
}

async function check(){
 const s=JSON.parse(readFileSync(sessionFile));assert.equal(s.syntheticOnly,true);
 assert.equal((await live(null,'GET','/'+s.liveCode)).data.snapshot.revision,s.liveRevision);
 assert.equal((await team(s.admin,'GET',s.path)).data.payload.state.isFinal,true);
 await team(s.member,'GET',s.path,undefined,404);
 const details=(await call('GET','/lifan/tryout/api/admin/registrations/'+s.registration,undefined,{Cookie:s.lifanCookie})).data;
 assert.equal(details.data.playerName,'测试选手');
 note('all three systemd process restarts preserve synthetic records, live link and existing authorization/revocation state');
 const stamp=Date.now(),key=readBackupKey('/etc/baseballmaster-staging/backup.key');
 const before=new DatabaseSync('/var/lib/baseballmaster-staging/team/synthetic.sqlite',{readOnly:true});
 const oldEpoch=before.prepare("SELECT value FROM metadata WHERE key='recoveryEpoch'").get()?.value;before.close();
 const archive=`/var/lib/baseballmaster-staging/backups/synthetic-${stamp}.sealed`;
 const saved=await createEncryptedBackup({database:'/var/lib/baseballmaster-staging/team/synthetic.sqlite',destination:archive,key});
 assert.equal(saved.verified,true);
 const recovered=await restoreEncryptedBackup({archive,destination:`${DIR}/restored-team-${stamp}.sqlite`,key,journalDirectory:'/var/lib/baseballmaster-staging/journal'});
 assert.equal(recovered.quarantined,false);
 const after=new DatabaseSync('/var/lib/baseballmaster-staging/team/synthetic.sqlite',{readOnly:true});
 assert.equal(after.prepare("SELECT value FROM metadata WHERE key='recoveryEpoch'").get()?.value,oldEpoch);
 assert.equal(after.prepare('PRAGMA integrity_check').get().integrity_check,'ok');assert.equal(after.prepare('PRAGMA foreign_key_check').all().length,0);after.close();
 const restored=new DatabaseSync(`${DIR}/restored-team-${stamp}.sqlite`,{readOnly:true});
 assert.equal(restored.prepare('SELECT active FROM members WHERE id=?').get(s.memberID).active,0);
 assert.equal(restored.prepare('SELECT access_mode FROM shared_games WHERE id=?').get(s.game).access_mode,'closed');restored.close();
 note('team encrypted backup decrypts and restores into an isolated file; revoked member stays revoked, recovery closes external links, serving database remains unchanged');
 const config=JSON.parse(readFileSync('/etc/baseballmaster-staging/lifan.json'));
 const db=new DatabaseSync(config.dbPath,{readOnly:true}),target=`${DIR}/restored-lifan-${stamp}.sqlite`;
 assert.equal(db.prepare('SELECT payload FROM registrations WHERE id=?').get(s.registration).payload.includes('测试选手'),false);
 await backup(db,target);db.close();
 const app=createLifan({...config,dbPath:target});await new Promise(resolve=>app.server.listen(0,'127.0.0.1',resolve));
 try{
  const response=await fetch(`http://127.0.0.1:${app.server.address().port}/lifan/tryout/api/admin/registrations/${s.registration}`,{headers:{Cookie:s.lifanCookie}});
  assert.equal(response.status,200);assert.equal((await response.json()).data.injuryDetails,'测试伤病说明');
 }finally{await app.close();}
 await call('POST','/lifan/tryout/api/admin/logout',{}, {Cookie:s.lifanCookie,'X-CSRF-Token':s.lifanCSRF});
 await call('GET','/lifan/tryout/api/admin/registrations',undefined,{Cookie:s.lifanCookie},401);
 note('lifan payload is encrypted at rest; consistent backup can decrypt with test key; CSRF-protected logout revokes the session');
 const initial=JSON.parse(readFileSync(reportFile));
 writeFileSync(reportFile,JSON.stringify({syntheticOnly:true,phase:'complete',passed:true,checkedAt:new Date().toISOString(),checks:[...initial.checks,...checks]},null,2)+'\n',{mode:0o600});
 console.log(JSON.stringify({passed:true,syntheticOnly:true,phase:'complete',checks:initial.checks.length+checks.length}));
}

try{if(process.argv[2]==='seed')await seed();else if(process.argv[2]==='check')await check();else throw new Error('Use seed or check');}
catch(error){console.error('Synthetic verification failed:',error.code??error.name,error.message);process.exitCode=1;}
