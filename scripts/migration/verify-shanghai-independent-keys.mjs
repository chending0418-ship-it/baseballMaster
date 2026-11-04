// Verify independent recovery with keys and cloud credentials supplied via SSH stdin.
// Deliberately never reads /etc/baseballmaster-staging or the serving databases.
import assert from 'node:assert/strict';
import {createHash,createDecipheriv,randomUUID} from 'node:crypto';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {createRequire} from 'node:module';
import {DatabaseSync} from 'node:sqlite';
import {unseal} from '/opt/baseballmaster-staging/releases/synthetic-20261023T104511Z/collaboration/safety-journal.mjs';
import {restoreEncryptedBackup} from '/opt/baseballmaster-staging/releases/synthetic-20261023T104511Z/collaboration/backup.mjs';

const CONTROL='/var/lib/baseballmaster-staging-control',BUCKET='bm-backup-sh-1387380066',REGION='ap-shanghai';
let step='host guard';
try {
  process.umask(0o077);
  assert.equal(process.getuid(),0);
  assert.equal((await fetch('http://metadata.tencentyun.com/latest/meta-data/public-ipv4',{signal:AbortSignal.timeout(3000)}).then(r=>r.text())).trim(),'101.43.112.8');
  assert.equal(JSON.parse(readFileSync(`${CONTROL}/synthetic-only.json`)).syntheticOnly,true);
  const payload=JSON.parse(readFileSync(0));
  assert.equal(payload.Keys.syntheticOnly,true);assert.equal(payload.Keys.bucket,BUCKET);assert.equal(payload.Keys.region,REGION);
  assert.equal(payload.Credentials.Username,'bm-shanghai-backup');
  const teamKey=Buffer.from(payload.Keys.teamBackupKey,'base64'),lifanKey=Buffer.from(payload.Keys.lifanBackupKey,'base64');
  const innerKey=Buffer.from(payload.Keys.lifanPayloadEncryptionKey,'hex');
  assert.equal(teamKey.length,32);assert.equal(lifanKey.length,32);assert.equal(innerKey.length,32);
  const prior=JSON.parse(readFileSync(`${CONTROL}/offsite-backup-receipt.json`));
  assert.equal(prior.passed,true);assert.equal(prior.syntheticOnly,true);
  assert(prior.jobDirectory.startsWith(`${CONTROL}/offsite/`));
  const directory=`${prior.jobDirectory}/independent-${randomUUID()}`;
  mkdirSync(`${directory}/journal`,{recursive:true,mode:0o700});
  const COS=createRequire(`${CONTROL}/cos-client/package.json`)('cos-nodejs-sdk-v5');
  const cos=new COS({SecretId:payload.Credentials.SecretId,SecretKey:payload.Credentials.SecretKey,
    Protocol:'https:',FollowRedirect:false,Timeout:15000,MaxRetryTimes:1});
  step='cloud download with independently supplied credentials';
  for(const file of prior.files) {
    assert(file.key.startsWith(prior.prefix));
    const name=file.key.slice(prior.prefix.length);assert(/^(team\.sealed|lifan\.sealed|journal\/(identity|[a-f0-9]{64})\.sealed)$/.test(name));
    const response=await new Promise((resolve,reject)=>cos.getObject({Bucket:BUCKET,Region:REGION,Key:file.key},(error,data)=>error?reject(error):resolve(data)));
    const bytes=Buffer.from(response.Body);assert.equal(createHash('sha256').update(bytes).digest('hex'),file.sha256);
    writeFileSync(`${directory}/${name}`,bytes,{mode:0o600,flag:'wx'});
  }
  step='team restore with independently supplied key';
  const result=await restoreEncryptedBackup({archive:`${directory}/team.sealed`,destination:`${directory}/team.sqlite`,key:teamKey,journalDirectory:`${directory}/journal`});
  assert.equal(result.quarantined,false);
  const team=new DatabaseSync(`${directory}/team.sqlite`,{readOnly:true});
  assert.equal(team.prepare('PRAGMA integrity_check').get().integrity_check,'ok');assert.equal(team.prepare('PRAGMA foreign_key_check').all().length,0);
  assert.equal(team.prepare('SELECT COUNT(*) AS n FROM teams').get().n,prior.restoredTeamCounts.teams);
  assert.equal(team.prepare("SELECT COUNT(*) AS n FROM shared_games WHERE access_mode<>'closed' OR live_enabled<>0").get().n,0);
  team.close();
  step='lifan restore with independently supplied keys';
  const bytes=unseal(readFileSync(`${directory}/lifan.sealed`),lifanKey);
  writeFileSync(`${directory}/lifan.sqlite`,bytes,{mode:0o600,flag:'wx'});
  const lifan=new DatabaseSync(`${directory}/lifan.sqlite`,{readOnly:true});
  assert.equal(lifan.prepare('PRAGMA integrity_check').get().integrity_check,'ok');assert.equal(lifan.prepare('PRAGMA foreign_key_check').all().length,0);
  const rows=lifan.prepare('SELECT payload FROM registrations').all();assert.equal(rows.length,prior.lifanPayloadsDecrypted);
  let health=false;
  for(const row of rows) {
    const [iv,tag,body]=row.payload.split('.').map(value=>Buffer.from(value,'base64'));
    const decrypt=createDecipheriv('aes-256-gcm',innerKey,iv);decrypt.setAuthTag(tag);
    const decoded=JSON.parse(Buffer.concat([decrypt.update(body),decrypt.final()]));
    if(decoded.data.injuryDetails==='测试伤病说明')health=true;
  }
  lifan.close();assert.equal(health,true);
  const receipt={checkedAt:new Date().toISOString(),passed:true,syntheticOnly:true,bucket:BUCKET,region:REGION,
    keysSuppliedFromIndependentMacFile:true,cloudCredentialsSuppliedFromMacCSV:true,
    originalServerSecretFilesRead:false,servingDatabasesRead:false,downloadedFiles:prior.files.length,
    teamRecoveryVerified:true,lifanRecoveryAndTestHealthVerified:true,isolatedRecoveryDirectory:directory,
    productionDataCopied:false,hongKongConnected:false,dnsChanged:false};
  writeFileSync(`${CONTROL}/independent-keys-receipt.json`,JSON.stringify(receipt,null,2)+'\n',{mode:0o600});
  console.log(JSON.stringify(receipt));
} catch(error) {
  const code=String(error.Code??error.code??error.name??'Failure');
  console.error(JSON.stringify({passed:false,step,statusCode:Number(error.statusCode)||null,
    code:/^[A-Za-z0-9_]{1,60}$/.test(code)?code:'Failure',detailsSuppressed:true}));
  process.exitCode=1;
}
