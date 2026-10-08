// Release 2.3's compatible service without reading or backing up live user data.
import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {createHash,randomBytes,randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {snapshot,uiSnapshot} from '../test/fixture.mjs';
const release='2.3-2026-10-08', dir=`output/deployments/live/${release}`;
const output='output/validation/2.3/release-acceptance/production-live';mkdirSync(output,{recursive:true});
const base='https://baseballmaster.cc/livestreaming/novideo';
const ssh=cmd=>execFileSync('ssh',['-o','BatchMode=yes','-o','ConnectTimeout=10','jingsen-prod',cmd],{encoding:'utf8',timeout:45000});
const token=randomBytes(32).toString('hex');let code;let switched=false;
async function request(method,path,body){const r=await fetch(base+path,{method,headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});assert.ok(r.ok,`Request failed ${method} ${r.status}`);return r.json();}
const old=ssh('readlink -f /www/server/baseballmaster-live/current').trim();
assert.match(old,/^\/www\/server\/baseballmaster-live\/releases\/[a-zA-Z0-9._-]+$/);
assert.notEqual(old,`/www/server/baseballmaster-live/releases/${release}`,'Already active; inspect deployment evidence instead of redeploying');
const archive=readFileSync(`${dir}/release.tar.gz`),sha256=createHash('sha256').update(archive).digest('hex');
const evidence={date:new Date().toISOString(),oldRelease:old,newRelease:release,archiveSHA256:sha256,checks:[],databaseCopied:false};
try{
 const legacy=snapshot();code=(await request('POST','/api/sessions',{requestID:randomUUID(),createdAt:Date.now(),snapshot:legacy})).code;
 assert.deepEqual((await request('GET',`/api/sessions/${code}`)).snapshot,legacy);
 evidence.checks.push('Synthetic legacy snapshot accepted before upgrade');
 execFileSync('scp',[`${dir}/release.tar.gz`,'jingsen-prod:/www/server/baseballmaster-live/releases/release-2.3-20261008.tar.gz'],{timeout:45000});
 ssh(`set -eu
cd /www/server/baseballmaster-live/releases
printf '%s  release-2.3-20261008.tar.gz\\n' '${sha256}' | sha256sum -c -
test ! -e ${release}
mkdir ${release}
tar -xzf release-2.3-20261008.tar.gz -C ${release}
chown -R root:root ${release}
chmod -R go-w ${release}
ln -s /www/server/baseballmaster-live/releases/${release} /www/server/baseballmaster-live/current.2.3-next
mv -Tf /www/server/baseballmaster-live/current.2.3-next /www/server/baseballmaster-live/current
systemctl restart baseballmaster-live.service
systemctl is-active baseballmaster-live.service`);
 switched=true;
 for(let i=0;i<20;i++){try{if((await fetch(base+'/health')).ok)break;}catch{}await new Promise(r=>setTimeout(r,500));}
 assert.deepEqual((await request('GET',`/api/sessions/${code}`)).snapshot,legacy);
 evidence.checks.push('Same legacy link and snapshot survive program restart');
 const standard=uiSnapshot(2);standard.gameID=legacy.gameID;
 await request('PUT',`/api/sessions/${code}`,standard);assert.deepEqual((await request('GET',`/api/sessions/${code}`)).snapshot,standard);
 const slow=uiSnapshot(3);slow.gameID=legacy.gameID;slow.mode='slowPitch';slow.balls=1;slow.strikes=2;
 slow.rules={scheduledInnings:6,timeLimitMinutes:90,competitionFormat:'timed',initialBalls:1,initialStrikes:1,twoStrikeFoulPolicy:'oneExtraFoul',fieldersCount:10,rulesVersion:2,extraFoulUsed:true};
 await request('PUT',`/api/sessions/${code}`,slow);assert.deepEqual((await request('GET',`/api/sessions/${code}`)).snapshot,slow);
 evidence.checks.push('Same link accepts 2.2 standard then 2.3 slow-pitch fields');
 const page=await fetch(`${base}/${code}`);assert.equal(page.status,200);assert.match(page.headers.get('cache-control'),/no-store/);
 const manifest=JSON.parse(readFileSync(`${dir}/manifest.json`));
 for(const [path,hash] of Object.entries(manifest)){
  if(path.startsWith('public/')){const r=await fetch(`${base}/${path.slice(7)==='index.html'?'':path.slice(7)}`);assert.equal(r.status,200);if(path==='public/index.html')continue;assert.equal(createHash('sha256').update(Buffer.from(await r.arrayBuffer())).digest('hex'),hash,path);}
 }
 evidence.checks.push('Live viewer assets match deployed 2.3 source; no-store preserved');
 await request('DELETE',`/api/sessions/${code}`);assert.equal((await fetch(base+'/api/sessions/'+code)).status,404);code=undefined;
 evidence.checks.push('Synthetic test stream deleted');evidence.passed=true;
 writeFileSync(`${output}/deployment.json`,JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify(evidence,null,2));
}catch(error){
 if(switched)ssh(`ln -s '${old}' /www/server/baseballmaster-live/current.2.3-rollback && mv -Tf /www/server/baseballmaster-live/current.2.3-rollback /www/server/baseballmaster-live/current && systemctl restart baseballmaster-live.service`);
 writeFileSync(`${output}/failed-deployment.json`,JSON.stringify({...evidence,error:error.message,rolledBack:switched},null,2)+'\n');throw error;
}finally{if(code)await request('DELETE',`/api/sessions/${code}`).catch(()=>{});}
