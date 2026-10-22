// Synthetic release checks only. Secrets are kept outside the workspace and deleted after cleanup.
import {randomBytes,randomUUID,createHash} from 'node:crypto';
import {readFileSync,writeFileSync,mkdirSync,existsSync,unlinkSync} from 'node:fs';
import {homedir} from 'node:os';
import {join} from 'node:path';
import assert from 'node:assert/strict';
const base='https://baseballmaster.cc/api/v3', mode=process.argv[2];
const out='output/validation/3.0.1/release-2026-10-19';mkdirSync(out,{recursive:true});
const privateFolder=join(homedir(),'.config/baseballmaster');mkdirSync(privateFolder,{recursive:true,mode:0o700});
const receipt=join(privateFolder,'release-3.0.1-qa.json');
const secret=()=>randomBytes(32).toString('base64url');
async function req(key,method,path,body,expected=200){
 const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json','X-BM-Protocol':'1',...(key?{Authorization:'Bearer '+key}:{})},body:body?JSON.stringify({operationID:randomUUID(),...body}):undefined});
 const data=await r.json();assert.equal(r.status,expected,`${method} ${path} status ${r.status}; code ${data.error?.code??''}`);return data;
}
const digest=v=>createHash('sha256').update(JSON.stringify(v)).digest('hex');
if(mode==='prepare'){
 assert.ok(!existsSync(receipt),'Existing QA receipt must be completed first');
 const s={admin:secret(),legacy:secret(),existing:secret(),existingInvite:secret(),team:null,createdAt:new Date().toISOString()};
 writeFileSync(receipt,JSON.stringify(s),{mode:0o600,flag:'wx'});
 for(const key of [s.admin,s.existing])await req(key,'POST','/devices',{label:'3.0.1 synthetic compatibility QA'});
 s.team=(await req(s.admin,'POST','/teams',{name:'3.0.1 发布兼容验证（合成）',nickname:'兼容验证管理员'})).teamID;
 writeFileSync(receipt,JSON.stringify(s),{mode:0o600});
 await req(s.admin,'POST',`/teams/${s.team}/invitations`,{token:s.legacy,role:'member'});
 await req(s.admin,'POST',`/teams/${s.team}/invitations`,{token:s.existingInvite,role:'member'});
 await req(s.existing,'POST','/invitations/redeem',{token:s.existingInvite,nickname:'部署前已加入成员'});
 const folder='output/validation/3.0/release-completion/full-match-sources';
 const manifest=JSON.parse(readFileSync(folder+'/manifest.json'));
 const source=manifest.flatMap(t=>t.attachments).find(a=>a.exportedFileName.endsWith('.json'));
 const fixture=JSON.parse(readFileSync(folder+'/'+source.exportedFileName));
 fixture.payload.id=randomUUID();fixture.projection.gameID=fixture.payload.id;
 const created=await req(s.admin,'POST',`/teams/${s.team}/games`,fixture);
 const game=await req(s.admin,'GET',`/teams/${s.team}/games/${created.id}`);
 s.game=game.id;s.payloadHash=digest(game.payload);s.projectionHash=digest(game.projection);
 writeFileSync(receipt,JSON.stringify(s),{mode:0o600});
 writeFileSync(out+'/before-deployment.json',JSON.stringify({createdAt:s.createdAt,teamID:s.team,gameID:s.game,oldInvitationPending:true,existingMemberJoined:true,payloadHash:s.payloadHash,projectionHash:s.projectionHash},null,2));
 console.log('Prepared legacy invitation, existing member and complete match before deployment.');
}else if(mode==='verify'){
 const s=JSON.parse(readFileSync(receipt)),checks=[];
 const g=await req(s.existing,'GET',`/teams/${s.team}/games/${s.game}`);assert.equal(digest(g.payload),s.payloadHash);assert.equal(digest(g.projection),s.projectionHash);checks.push('pre-deployment member, complete match and projection unchanged');
 const keys=Array.from({length:5},secret);
 for(const key of keys)await req(key,'POST','/devices',{label:'3.0.1 synthetic join device'});
 await req(keys[0],'POST','/invitations/preview',{token:s.legacy});
 await req(keys[0],'POST','/invitations/redeem',{token:s.legacy,nickname:'旧版邀请成员'});
 await req(keys[1],'POST','/invitations/redeem',{token:s.legacy,nickname:'旧版重复邀请'},410);checks.push('pre-deployment legacy invitation joins once through unchanged protocol-1 endpoints');
 const fixed=await req(s.admin,'POST',`/teams/${s.team}/team-invitation`,{rotate:false});
 const same=await req(s.admin,'POST',`/teams/${s.team}/team-invitation`,{rotate:false});assert.equal(fixed.token,same.token);
 await req(keys[1],'POST','/invitations/redeem',{token:fixed.token,nickname:'固定邀请成员甲'});
 await req(keys[2],'POST','/invitations/redeem',{token:fixed.token,nickname:'固定邀请成员乙'});
 await req(keys[1],'POST','/invitations/redeem',{token:fixed.token,nickname:'重复加入'},409);
 checks.push('fixed invitation stays identical and two installations join through legacy-compatible redemption');
 let members=(await req(s.admin,'GET',`/teams/${s.team}/members`)).members;
 for(const nickname of ['固定邀请成员甲','固定邀请成员乙']){
  const m=members.find(m=>m.nickname===nickname);await req(s.admin,'PATCH',`/teams/${s.team}/members/${m.id}`,{baseRevision:m.revision,role:'admin'});
 }
 members=(await req(s.admin,'GET',`/teams/${s.team}/members`)).members;
 const fourth=members.find(m=>m.nickname==='旧版邀请成员');
 const denied=await req(s.admin,'PATCH',`/teams/${s.team}/members/${fourth.id}`,{baseRevision:fourth.revision,role:'admin'},409);assert.equal(denied.error.code,'admin_limit');
 assert.equal(members.filter(m=>m.role==='admin').length,3);checks.push('third administrator allowed; fourth rejected');
 assert.equal((await req(keys[1],'POST',`/teams/${s.team}/team-invitation`,{rotate:false})).token,fixed.token);
 const changed=await req(s.admin,'POST',`/teams/${s.team}/team-invitation`,{rotate:true});assert.notEqual(changed.token,fixed.token);
 await req(keys[3],'POST','/invitations/preview',{token:fixed.token},410);
 await req(keys[3],'POST','/invitations/redeem',{token:changed.token,nickname:'更换后加入成员'});
 await req(keys[2],'GET',`/teams/${s.team}`);checks.push('administrators see the same fixed link; rotation invalidates only old link and retains members');
 const freshLegacy=secret();await req(s.admin,'POST',`/teams/${s.team}/invitations`,{token:freshLegacy,role:'scorer'});
 await req(keys[4],'POST','/invitations/redeem',{token:freshLegacy,nickname:'新版服务上的旧版邀请'});checks.push('legacy App can still create and redeem single-use scorer invitations after upgrade');
 const health=await req(null,'GET','/health');assert.equal(health.protocol,1);
 writeFileSync(out+'/production-compatibility.json',JSON.stringify({passed:true,checkedAt:new Date().toISOString(),teamID:s.team,checks,protocol:1,syntheticOnly:true},null,2));console.log(JSON.stringify({passed:true,checks:checks.length,protocol:1}));
}else if(mode==='cleanup'){
 const s=JSON.parse(readFileSync(receipt));
 if(s.team){const detail=await req(s.admin,'GET',`/teams/${s.team}`);await req(s.admin,'POST',`/teams/${s.team}/dissolve`,{confirm:true,name:detail.team.name,baseRevision:detail.team.revision});}
 writeFileSync(out+'/production-cleanup.json',JSON.stringify({cleanedAt:new Date().toISOString(),syntheticTeamDissolved:true,privateReceiptRemoved:true},null,2));unlinkSync(receipt);console.log('Synthetic QA team dissolved; private receipt removed.');
}else throw Error('Use prepare, verify or cleanup');
