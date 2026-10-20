import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync,mkdirSync,existsSync,chmodSync} from 'node:fs';
import {randomBytes,randomUUID,scryptSync,createHash} from 'node:crypto';
import {homedir} from 'node:os';
import {resolve,join} from 'node:path';
import assert from 'node:assert/strict';
import {uiSnapshot} from '../../live/test/fixture.mjs';
const release=process.env.BM_ADMIN_RELEASE||'3.0-20261013-admin';assert.match(release,/^[a-zA-Z0-9._-]+$/);
const password=process.env.BM_ADMIN_PASSWORD;assert.ok(password,'Provide BM_ADMIN_PASSWORD privately');
const folder=resolve('output/deployments/admin/'+release),privateFolder=join(homedir(),'.config/baseballmaster');
mkdirSync(folder,{recursive:true});mkdirSync(privateFolder,{recursive:true,mode:0o700});
const configPath=join(privateFolder,'admin-auth.json'),envPath=join(privateFolder,'admin-live.env');
let config;
if(existsSync(configPath)){config=JSON.parse(readFileSync(configPath));assert.equal(scryptSync(password,config.salt,64).toString('hex'),config.passwordHash,'Existing admin password differs');}
else{const salt=randomBytes(16).toString('hex');config={salt,passwordHash:scryptSync(password,salt,64).toString('hex'),legacyToken:randomBytes(32).toString('hex')};writeFileSync(configPath,JSON.stringify(config)+'\n',{mode:0o600,flag:'wx'});}
writeFileSync(envPath,'BM_ADMIN_BRIDGE_TOKEN='+config.legacyToken+'\n',{mode:0o600});chmodSync(configPath,0o600);chmodSync(envPath,0o600);
const files=['collaboration/server.mjs','collaboration/admin.mjs','collaboration/admin-export.mjs','collaboration/admin/public','collaboration/deploy/nginx-v3.conf','live/server.mjs'];
const archive=join(folder,'overlay.tar.gz');execFileSync('tar',['-czf',archive,...files],{env:{...process.env,COPYFILE_DISABLE:'1'}});
const sha=createHash('sha256').update(readFileSync(archive)).digest('hex');writeFileSync(join(folder,'overlay.sha256'),sha+'  overlay.tar.gz\n');
const ssh=command=>execFileSync('ssh',['-o','BatchMode=yes','-o','ConnectTimeout=10','-o','LogLevel=ERROR','jingsen-prod',command],{encoding:'utf8',timeout:120000});
const q=value=>"'"+value.replaceAll("'","'\\''")+"'",stage='/root/baseballmaster-deploy/'+release;
ssh('install -d -m 700 '+q(stage));
execFileSync('scp',['-q','-o','BatchMode=yes','-o','LogLevel=ERROR',archive,join(folder,'overlay.sha256'),configPath,envPath,'collaboration/deploy/install-admin.sh','jingsen-prod:'+stage+'/'],{timeout:60000});
const liveBase='https://baseballmaster.cc/livestreaming/novideo/api/sessions',liveKey=randomBytes(32).toString('hex');
const snapshot=uiSnapshot();snapshot.away.name='后台上线合成测试 A';snapshot.home.name='后台上线合成测试 B';
const liveHeaders={Authorization:'Bearer '+liveKey,'Content-Type':'application/json'};
const created=await fetch(liveBase,{method:'POST',headers:liveHeaders,body:JSON.stringify({requestID:randomUUID(),createdAt:Date.now(),snapshot})});assert.equal(created.status,201);
const code=(await created.json()).code;
try{
 console.log(ssh('bash '+q(stage+'/install-admin.sh')+' '+q(release)));
 const r=await fetch(liveBase+'/'+code);assert.equal(r.status,200);assert.deepEqual((await r.json()).snapshot,snapshot);
}finally{const r=await fetch(liveBase+'/'+code,{method:'DELETE',headers:liveHeaders});assert.equal(r.status,200);}
const evidence={release,deployedAt:new Date().toISOString(),overlaySHA256:sha,scope:'website owner console only',appModified:false,liveDatabaseCopied:false,existingLiveLinkSurvived:true,syntheticLiveClosed:true,url:'https://baseballmaster.cc/admin/'};
writeFileSync(join(folder,'deployment.json'),JSON.stringify(evidence,null,2)+'\n');
