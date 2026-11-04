// All three simulated hosts run only on Shanghai loopback with new synthetic data.
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import {once} from 'node:events';
import {randomBytes,randomUUID,createHash} from 'node:crypto';
import {readFileSync,writeFileSync,mkdirSync,existsSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {createCollaborationServer} from '/opt/baseballmaster-staging/releases/synthetic-20261023T104511Z/collaboration/server.mjs';
import {SafetyJournal} from '/opt/baseballmaster-staging/releases/synthetic-20261023T104511Z/collaboration/safety-journal.mjs';
import {createApp as createLifan,passwordHash} from '/opt/baseballmaster-staging/releases/synthetic-20261023T104511Z/lifan/server.mjs';
import {fixture as matchFixture} from '/opt/baseballmaster-staging/releases/synthetic-20261023T104511Z/collaboration/test/match-fixture.mjs';
import {fixture as registrationFixture} from '/opt/baseballmaster-staging/releases/synthetic-20261023T104511Z/lifan/test/fixture.mjs';

const CONTROL='/var/lib/baseballmaster-staging-control';
const TOOLS=`${CONTROL}/migration-tools`,NODE='/opt/baseballmaster-node/node-v24.21.0-linux-x64/bin/node';
const checks=[],active=new Set(),secret=()=>randomBytes(32).toString('base64url');
const op=value=>({operationID:randomUUID(),...value});
let authority=null,gateway,root,stage='guard',gatewayPort;
const tool=(...args)=>JSON.parse(execFileSync('/usr/bin/python3',['-B',`${TOOLS}/state_transfer.py`,...args],{encoding:'utf8',maxBuffer:8*1024**2}));
const inspect=path=>tool('inspect',path);
const equalDigest=(a,b)=>assert.equal(a.logicalSHA256,b.logicalSHA256);
const hash=body=>createHash('sha256').update(JSON.stringify(body)).digest('hex');

async function start(directory,lifanConfig,journalKey,{fresh=false}={}) {
  assert.equal(active.size,0,'A second writable simulated host is forbidden');
  const app=createCollaborationServer({database:`${directory}/team.sqlite`,requestsPerMinute:10000});
  let id=app.store.db.prepare("SELECT value FROM metadata WHERE key='safetyJournalID'").get()?.value;
  if(fresh) {
    assert.equal(id,undefined);id=randomUUID();
    app.store.db.prepare("INSERT INTO metadata VALUES('safetyJournalID',?)").run(id);
  }
  app.store.safetyJournal=new SafetyJournal({directory:`${directory}/journal`,key:journalKey,journalID:id,create:fresh});
  app.store.safetyJournal.record(app.store);
  const lifan=createLifan({...lifanConfig,dbPath:`${directory}/lifan.sqlite`});
  const host={app,lifan,directory,closed:false};active.add(host);
  for(const server of [app.server,lifan.server]){server.listen(0,'127.0.0.1');await once(server,'listening');}
  host.teamPort=app.server.address().port;host.lifanPort=lifan.server.address().port;
  return host;
}
async function stop(host) {
  if(!host || host.closed)return;
  assert.notEqual(authority,host,'Maintenance gate must close before stopping a writer');
  host.app.server.closeAllConnections();host.lifan.server.closeAllConnections();
  await new Promise(resolve=>host.app.server.close(resolve));host.app.store.close();
  await host.lifan.close();host.closed=true;active.delete(host);
  for(const port of [host.teamPort,host.lifanPort]) {
    await assert.rejects(fetch(`http://127.0.0.1:${port}/`,{signal:AbortSignal.timeout(2000)}));
  }
}
async function request(method,path,body,headers={},expected=200) {
  const response=await new Promise((resolve,reject)=>{
    const bytes=body===undefined?undefined:Buffer.from(JSON.stringify(body));
    const req=https.request({hostname:'baseballmaster.cc',port:gatewayPort,path,method,servername:'baseballmaster.cc',
      lookup:(_name,options,callback)=>options.all?callback(null,[{address:'127.0.0.1',family:4}]):callback(null,'127.0.0.1',4),
      headers:{Host:'baseballmaster.cc',Origin:'https://baseballmaster.cc','Content-Type':'application/json','Connection':'close',
        ...(bytes?{'Content-Length':bytes.length}:{}),...headers}},res=>{
      const chunks=[];res.on('data',chunk=>chunks.push(chunk));res.on('end',()=>{
        let data;try{data=JSON.parse(Buffer.concat(chunks));}catch{}
        resolve({status:res.statusCode,headers:res.headers,data});
      });
    });req.on('error',reject);req.setTimeout(10000,()=>req.destroy());req.end(bytes);
  });
  assert.equal(response.status,expected,'Unexpected rehearsal API status');return response;
}
const team=(key,method,path,body,expected=200)=>request(method,'/api/v3'+path,body,{'X-BM-Protocol':'1',...(key?{Authorization:'Bearer '+key}:{})},expected);
const lifan=(method,path,body,headers={},expected=200)=>request(method,'/lifan/tryout/api'+path,body,headers,expected);
async function enroll(key){await team(key,'POST','/devices',{label:'Migration rehearsal synthetic device'});}
async function transfer(source,name) {
  assert(source.closed && active.size===0 && authority===null,'Source must be completely stopped');
  const snapshot=`${root}/${name}-snapshot`,archive=`${root}/${name}.tar`,sealed=`${root}/${name}.sealed`,opened=`${root}/${name}-opened.tar`,candidate=`${root}/${name}-candidate`;
  const manifest=tool('capture','--team',`${source.directory}/team.sqlite`,'--lifan',`${source.directory}/lifan.sqlite`,
    '--journal',`${source.directory}/journal`,'--destination',snapshot,'--writers-stopped');
  tool('pack',snapshot,archive);
  for(const [action,input,output] of [['encrypt',archive,sealed],['decrypt',sealed,opened]])
    execFileSync(NODE,[`${TOOLS}/state-archive.mjs`,action,input,output,`${root}/transport.key`],{stdio:'pipe',timeout:30000});
  tool('unpack',opened,candidate);const verified=tool('verify',candidate);
  assert.deepEqual(verified,manifest);
  equalDigest(inspect(`${source.directory}/team.sqlite`),inspect(`${candidate}/team.sqlite`));
  equalDigest(inspect(`${source.directory}/lifan.sqlite`),inspect(`${candidate}/lifan.sqlite`));
  return {directory:candidate,manifest,sealed};
}

try {
  process.umask(0o077);assert.equal(process.getuid(),0);
  assert.equal((await fetch('http://metadata.tencentyun.com/latest/meta-data/public-ipv4',{signal:AbortSignal.timeout(3000)}).then(r=>r.text())).trim(),'101.43.112.8');
  assert.equal(JSON.parse(readFileSync(`${CONTROL}/synthetic-only.json`)).syntheticOnly,true);
  root=`${CONTROL}/normal-transfer/${new Date().toISOString().replace(/[^0-9TZ]/g,'')}-${randomUUID().slice(0,8)}`;
  mkdirSync(root,{recursive:true,mode:0o700});mkdirSync(`${root}/source-a/journal`,{recursive:true,mode:0o700});
  writeFileSync(`${root}/transport.key`,randomBytes(32),{mode:0o600,flag:'wx'});
  const journalKey=randomBytes(32),lifanPassword=secret();
  const lifanConfig={origin:'https://baseballmaster.cc',encryptionKey:randomBytes(32).toString('hex'),passwordHash:await passwordHash(lifanPassword),trustProxy:false};
  const tls=JSON.parse(readFileSync(`${CONTROL}/public-certificate-receipt.json`));
  gateway=https.createServer({cert:readFileSync(tls.certificateFile),key:readFileSync(tls.privateKeyFile)},(req,res)=>{
    if(!authority){res.writeHead(503,{'Content-Type':'application/json','Retry-After':'30'});res.end('{"maintenance":true}');return;}
    const port=req.url.startsWith('/api/v3/')?authority.teamPort:req.url.startsWith('/lifan/tryout/')?authority.lifanPort:null;
    if(!port){res.writeHead(404);res.end();return;}
    const proxy=http.request({hostname:'127.0.0.1',port,path:req.url,method:req.method,headers:{...req.headers,connection:'close'}},upstream=>{
      res.writeHead(upstream.statusCode,upstream.headers);upstream.pipe(res);
    });proxy.on('error',()=>{res.writeHead(503);res.end();});req.pipe(proxy);
  });gateway.listen(0,'127.0.0.1');await once(gateway,'listening');gatewayPort=gateway.address().port;
  stage='source seed';
  const a=await start(`${root}/source-a`,lifanConfig,journalKey,{fresh:true});authority=a;
  const s={admin:secret(),revoked:secret(),later:secret(),newcomer:secret()};
  for(const key of [s.admin,s.revoked])await enroll(key);
  s.createTeam=op({name:'迁移回滚合成队',nickname:'合成管理员'});
  s.team=(await team(s.admin,'POST','/teams',s.createTeam)).data;
  const invitation=secret();await team(s.admin,'POST',`/teams/${s.team.teamID}/invitations`,op({token:invitation,role:'member'}));
  s.revokedID=(await team(s.revoked,'POST','/invitations/redeem',op({token:invitation,nickname:'将被撤权的合成成员'}))).data.memberID;
  s.fixed=(await team(s.admin,'POST',`/teams/${s.team.teamID}/team-invitation`,op({rotate:false}))).data.token;
  s.single=secret();await team(s.admin,'POST',`/teams/${s.team.teamID}/invitations`,op({token:s.single,role:'member'}));
  const match=matchFixture();s.match=match;
  s.game=(await team(s.admin,'POST',`/teams/${s.team.teamID}/games`,op(match))).data;
  s.gamePath=`/teams/${s.team.teamID}/games/${s.game.id}`;
  s.live=(await team(s.admin,'POST',s.gamePath+'/live',op({enabled:true}))).data.liveCode;
  match.payload.state.homeRunsByInning=[1];match.projection.home.runs=1;match.projection.home.innings=[1];
  await team(s.admin,'PUT',s.gamePath,op({...match,baseRevision:1,writerEpoch:1}));
  const history=matchFixture();history.payload.status=1;history.payload.state.isFinal=true;
  history.projection.isFinal=true;history.projection.endedAt=Date.now();
  s.history=(await team(s.admin,'POST',`/teams/${s.team.teamID}/games`,op(history))).data;
  await team(s.admin,'POST',`/teams/${s.team.teamID}/games/${s.history.id}/access`,op({mode:'public',baseAccessVersion:1}));
  s.historyResponse=(await team(null,'GET','/watch/'+s.history.viewCode)).data;
  const member=(await team(s.admin,'GET',`/teams/${s.team.teamID}/members`)).data.members.find(row=>row.id===s.revokedID);
  await team(s.admin,'PATCH',`/teams/${s.team.teamID}/members/${s.revokedID}`,op({role:member.role,baseRevision:member.revision,active:false}));
  s.registrationBody={requestId:randomUUID(),data:registrationFixture()};
  s.registration=(await lifan('POST','/registrations',s.registrationBody,{},201)).data;
  const login=await lifan('POST','/admin/login',{password:lifanPassword});s.cookie=login.headers['set-cookie'][0].split(';')[0];s.csrf=login.data.csrf;
  await lifan('GET','/admin/registrations/'+s.registration.id,undefined,{Cookie:s.cookie});
  const epochA=a.app.store.db.prepare("SELECT value FROM metadata WHERE key='recoveryEpoch'").get().value;
  checks.push('Synthetic source contains original device authorization, unused fixed and single-use invitations, public history, current live code, revoked member and lifan admin session');

  stage='forward transfer';authority=null;
  await team(s.admin,'GET','/teams',undefined,503);await lifan('GET','/admin/session',undefined,{Cookie:s.cookie},503);
  await stop(a);const forward=await transfer(a,'forward');
  const b=await start(forward.directory,lifanConfig,journalKey);authority=b;
  assert.equal(b.app.store.db.prepare("SELECT value FROM metadata WHERE key='recoveryEpoch'").get().value,epochA);
  assert.deepEqual((await team(s.admin,'POST','/teams',s.createTeam)).data,s.team);
  assert.deepEqual((await team(null,'GET','/watch/'+s.history.viewCode)).data,s.historyResponse);
  assert.equal((await team(null,'GET','/live/'+s.live)).data.snapshot.home.runs,1);
  await team(s.revoked,'GET',s.gamePath,undefined,404);
  assert.equal((await lifan('POST','/registrations',s.registrationBody)).data.id,s.registration.id);
  await lifan('GET','/admin/registrations/'+s.registration.id,undefined,{Cookie:s.cookie});
  await enroll(s.later);s.joined=(await team(s.later,'POST','/invitations/redeem',op({token:s.fixed,nickname:'切换后合成成员'}))).data;
  await enroll(s.newcomer);await team(s.newcomer,'POST','/invitations/redeem',op({token:s.single,nickname:'单次链接合成成员'}));
  checks.push('Forward transfer preserves logical database fingerprints, recovery epoch, active credentials, old public/live/invitation paths, operation receipts and lifan session/registration retry');

  stage='writes after cutover';
  match.payload.state.homeRunsByInning=[2];match.projection.home.runs=2;match.projection.home.innings=[2];
  s.postCutoverUpdate=op({...match,baseRevision:2,writerEpoch:1});
  s.updated=(await team(s.admin,'PUT',s.gamePath,s.postCutoverUpdate)).data;
  s.newGame=(await team(s.admin,'POST',`/teams/${s.team.teamID}/games`,op(matchFixture()))).data;
  const later=(await team(s.admin,'GET',`/teams/${s.team.teamID}/members`)).data.members.find(row=>row.id===s.joined.memberID);
  await team(s.admin,'PATCH',`/teams/${s.team.teamID}/members/${later.id}`,op({role:later.role,baseRevision:later.revision,active:false}));
  const secondData=registrationFixture();secondData.playerName='切换后新增合成选手';
  s.newRegistration=(await lifan('POST','/registrations',{requestId:randomUUID(),data:secondData},{},201)).data;
  await lifan('POST','/admin/logout',{}, {Cookie:s.cookie,'X-CSRF-Token':s.csrf});
  const secondLogin=await lifan('POST','/admin/login',{password:lifanPassword});s.newCookie=secondLogin.headers['set-cookie'][0].split(';')[0];
  checks.push('After cutover the sole writer accepts a new score revision, a new game and lifan registration; a member is revoked and an old admin session logged out');

  stage='reverse transfer with new data';authority=null;
  await team(s.admin,'PUT',s.gamePath,s.postCutoverUpdate,503);await lifan('GET','/admin/session',undefined,{Cookie:s.newCookie},503);
  await stop(b);const reverse=await transfer(b,'reverse');
  const c=await start(reverse.directory,lifanConfig,journalKey);authority=c;
  assert.equal(c.app.store.db.prepare("SELECT value FROM metadata WHERE key='recoveryEpoch'").get().value,epochA);
  assert.deepEqual((await team(s.admin,'PUT',s.gamePath,s.postCutoverUpdate)).data,s.updated);
  assert.equal((await team(null,'GET','/live/'+s.live)).data.snapshot.home.runs,2);
  await team(s.admin,'GET',`/teams/${s.team.teamID}/games/${s.newGame.id}`);
  assert.deepEqual((await team(null,'GET','/watch/'+s.history.viewCode)).data,s.historyResponse);
  await team(s.revoked,'GET',s.gamePath,undefined,404);await team(s.later,'GET',s.gamePath,undefined,404);
  await lifan('GET','/admin/session',undefined,{Cookie:s.cookie},401);
  await lifan('GET','/admin/registrations/'+s.newRegistration.id,undefined,{Cookie:s.newCookie});
  assert.equal((await lifan('POST','/registrations',s.registrationBody)).data.id,s.registration.id);
  equalDigest(inspect(`${a.directory}/team.sqlite`),forward.manifest.databases.team);
  equalDigest(inspect(`${a.directory}/lifan.sqlite`),forward.manifest.databases.lifan);
  checks.push('Reverse transfer starts from the latest post-cutover state: new score/game/registration survive; old and new revocations, logged-out session and idempotent update response remain enforced');
  authority=null;await stop(c);
  checks.push('GET and POST maintenance responses verified; previous backend sockets closed before every successor starts; original source candidate retained unchanged');
  const receipt={checkedAt:new Date().toISOString(),passed:true,syntheticOnly:true,host:'101.43.112.8',checks,
    simulatedHosts:3,forwardAndReverseLogicalFingerprintsVerified:true,recoveryEpochUnchanged:true,
    originalHistoryLiveAndInvitationPathsPreserved:true,lifanURLAndAdminSessionsPreserved:true,
    postCutoverWritesAndRevocationsSurviveRollback:true,noConcurrentWriterObserved:true,
    originalSourcePreserved:true,originalOrigin:'https://baseballmaster.cc',httpsHostnameVerified:true,
    temporaryGatewayLoopbackOnly:true,productionDataCopied:false,hongKongConnected:false,dnsChanged:false,
    realPublishedAppBinaryTested:false,jobDirectory:root,
    forwardDataSHA256:hash(forward.manifest.databases),reverseDataSHA256:hash(reverse.manifest.databases)};
  writeFileSync(`${root}/receipt.json`,JSON.stringify(receipt,null,2)+'\n',{mode:0o600,flag:'wx'});
  writeFileSync(`${CONTROL}/normal-transfer-receipt.json`,JSON.stringify(receipt,null,2)+'\n',{mode:0o600});
  console.log(JSON.stringify(receipt));
} catch(error) {
  console.error(JSON.stringify({passed:false,stage,jobDirectory:root??null,errorType:error.name,code:typeof error.code==='string'&&/^[A-Za-z0-9_]+$/.test(error.code)?error.code:null,
    stackFrames:String(error.stack??'').split('\n').filter(line=>/^\s+at /.test(line)).slice(0,4),detailsSuppressed:true}));
  process.exitCode=1;
} finally {
  authority=null;
  for(const host of [...active]){try{await stop(host);}catch{process.exitCode=1;}}
  if(gateway){gateway.closeAllConnections();await new Promise(resolve=>gateway.close(resolve));}
}
