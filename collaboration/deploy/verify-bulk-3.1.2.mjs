// Public checks only use a new synthetic team. Secrets stay in a mode-0600 local file.
import assert from 'node:assert/strict';
import {randomBytes,randomUUID,createHash} from 'node:crypto';
import {mkdirSync,readFileSync,writeFileSync,existsSync,unlinkSync} from 'node:fs';
import {homedir} from 'node:os';
import {join} from 'node:path';
import {fixture} from '../test/match-fixture.mjs';
const base='https://baseballmaster.cc/api/v3',mode=process.argv[2],out='output/validation/3.1.2/bulk-backend-deploy-2026-11-04';
const dir=join(homedir(),'.config/baseballmaster'),secretFile=join(dir,'bulk-deploy-3.1.2-qa.json');mkdirSync(dir,{recursive:true,mode:0o700});mkdirSync(out,{recursive:true});
const secret=()=>randomBytes(32).toString('base64url'),op=b=>({operationID:randomUUID(),...b}),hash=x=>createHash('sha256').update(JSON.stringify(x)).digest('hex');
async function req(key,method,path,body,status=200){const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json','X-BM-Protocol':'1',...(key?{Authorization:'Bearer '+key}:{})},body:body?JSON.stringify(body):undefined});const data=await r.json();assert.equal(r.status,status,`${method} ${path}: ${r.status} ${data.error?.code??''}`);return data}
const save=s=>writeFileSync(secretFile,JSON.stringify(s),{mode:0o600});
const player=(name,n)=>({id:randomUUID(),sourceID:randomUUID(),chineseName:name,englishName:'',numbers:[n],archived:false});
if(mode==='prepare'){
 assert(!existsSync(secretFile),'Existing synthetic QA must be completed first');let s={admin:secret(),scorer:secret(),member:secret(),late:secret(),pending:secret(),name:'发布校验合成球队-不是真实球队'};save(s);
 for(const key of [s.admin,s.scorer,s.member,s.late])await req(key,'POST','/devices',{label:'synthetic bulk deployment QA'});
 s.team=(await req(s.admin,'POST','/teams',op({name:s.name,nickname:'合成管理员'}))).teamID;save(s);
 for(const [key,role] of [[s.scorer,'scorer'],[s.member,'member']]){const token=secret();await req(s.admin,'POST',`/teams/${s.team}/invitations`,op({token,role}));const v=await req(key,'POST','/invitations/redeem',op({token,nickname:'合成'+role}));s[role+'MemberID']=v.memberID;save(s)}
 await req(s.admin,'POST',`/teams/${s.team}/invitations`,op({token:s.pending,role:'member'}));
 s.oldPlayer=player('演示旧队员','00');await req(s.admin,'PATCH',`/teams/${s.team}/roster`,op({kind:'player',baseRevision:1,player:s.oldPlayer}));
 const g=await req(s.admin,'POST',`/teams/${s.team}/games`,op(fixture()));s.game=g.id;s.payloadHash=hash((await req(s.member,'GET',`/teams/${s.team}/games/${s.game}`)).payload);save(s);
 writeFileSync(out+'/public-before.json',JSON.stringify({syntheticOnly:true,oldSinglePlayerSaved:true,existingMemberAndScorerPrepared:true,pendingOldInvitationPrepared:true,oldMatchPrepared:true},null,2));console.log('Synthetic old player, member, scorer, invitation and match prepared.');
}else if(mode==='verify'){
 const s=JSON.parse(readFileSync(secretFile)),path=`/teams/${s.team}/roster`,checks=[];const cap=await req(null,'GET','/capabilities');assert(cap.capabilities.includes('bulkRosterImport'));assert.equal(cap.protocol,1);assert.equal(cap.minimumAppVersion,'3.0');assert.equal(cap.matchFormat,2);checks.push('bulk capability and old protocol available');
 assert.equal(hash((await req(s.member,'GET',`/teams/${s.team}/games/${s.game}`)).payload),s.payloadHash);if(!s.oldInvitationVerified){await req(s.late,'POST','/invitations/redeem',op({token:s.pending,nickname:'合成旧邀请'}));s.oldInvitationVerified=true;save(s)}checks.push('pre-deployment match, members and old invitation preserved');
 let roster=await req(s.admin,'GET',`/teams/${s.team}`);assert.equal(roster.team.players.length,1);const rev=roster.team.revision,players=[player('演示批量甲','00'),player('演示批量乙','12')];
 await req(s.member,'PATCH',path,op({kind:'players',baseRevision:rev,players}),403);checks.push('ordinary member bulk write forbidden');
 await req(s.scorer,'PATCH',path,op({kind:'players',baseRevision:rev,players:[players[0],{...players[1],numbers:['100']}]}),400);assert.equal((await req(s.admin,'GET',`/teams/${s.team}`)).team.players.length,1);checks.push('invalid batch writes nothing');
 const body=op({kind:'players',baseRevision:rev,players});const added=await req(s.scorer,'PATCH',path,body);assert.equal(added.team.players.length,3);assert.equal(added.team.revision,rev+1);assert(added.team.players.find(x=>x.id===players[0].id).numbers.includes('00'));const replay=await req(s.scorer,'PATCH',path,body);assert.equal(replay.team.revision,added.team.revision);assert.equal(replay.team.players.length,3);checks.push('scorer atomic bulk add and idempotent retry; 00 preserved');
 await req(s.admin,'PATCH',path,op({kind:'players',baseRevision:rev,players:[player('合成冲突','9')]}),409);
 await req(s.admin,'PATCH',path,op({kind:'players',baseRevision:added.team.revision,players:[players[0]]}),409);checks.push('stale revision and duplicate identities rejected');
 const edited=await req(s.scorer,'PATCH',path,op({kind:'player',baseRevision:added.team.revision,player:{...s.oldPlayer,chineseName:'演示旧队员改名'}}));assert.equal(edited.team.players.length,3);checks.push('old single-player editing retained');
 await req(s.admin,'PATCH',`/teams/${s.team}/members/${s.scorerMemberID}`,op({baseRevision:1,role:'member'}));await req(s.scorer,'PATCH',path,body,403);checks.push('role revocation fences old bulk retry');
 roster=await req(s.admin,'GET',`/teams/${s.team}`);await req(s.admin,'POST',`/teams/${s.team}/dissolve`,op({name:s.name,confirm:true,baseRevision:roster.team.revision}));checks.push('synthetic team dissolved using normal lifecycle API');unlinkSync(secretFile);
 writeFileSync(out+'/public-verification.json',JSON.stringify({checkedAt:new Date().toISOString(),syntheticOnly:true,checks,passed:true,credentialsRemoved:true},null,2));console.log(JSON.stringify({passed:true,checks},null,2));
}else throw Error('Use prepare or verify');
