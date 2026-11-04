// Full synthetic-only backup round trip through Shanghai COSFS and the COS API.
import assert from 'node:assert/strict';
import {createHash,createDecipheriv,randomBytes,randomUUID} from 'node:crypto';
import {DatabaseSync,backup} from 'node:sqlite';
import {mkdirSync,readFileSync,writeFileSync,readdirSync,statSync,lstatSync,existsSync,
  openSync,closeSync,fsyncSync,renameSync} from 'node:fs';
import {spawn,execFileSync} from 'node:child_process';
import {createRequire} from 'node:module';
import {setTimeout as delay} from 'node:timers/promises';
import {seal,unseal} from '/opt/baseballmaster-staging/releases/synthetic-20261023T104511Z/collaboration/safety-journal.mjs';
import {createEncryptedBackup,restoreEncryptedBackup,readBackupKey} from '/opt/baseballmaster-staging/releases/synthetic-20261023T104511Z/collaboration/backup.mjs';

const CONTROL='/var/lib/baseballmaster-staging-control',CONFIG='/etc/baseballmaster-staging';
const MOUNT='/mnt/baseballmaster-staging-cos',BUCKET='bm-backup-sh-1387380066',REGION='ap-shanghai';
const TEAM='/var/lib/baseballmaster-staging/team/synthetic.sqlite';
const LIFAN='/var/lib/baseballmaster-staging/lifan/synthetic.sqlite';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const checks=[];
let step='host guard',child,logfd,mounted=false,job;
const isMounted=()=>{try {execFileSync('mountpoint',['-q',MOUNT],{stdio:'ignore',timeout:3000});return true;}catch{return false;}};
const privateWrite=(path,bytes)=>writeFileSync(path,bytes,{mode:0o600,flag:'wx'});
const integrity=db=>{
  assert.equal(db.prepare('PRAGMA integrity_check').get().integrity_check,'ok');
  assert.equal(db.prepare('PRAGMA foreign_key_check').all().length,0);
};
const summarize=db=>{
  const result={};
  for(const {name} of db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").all()) {
    assert.match(name,/^[a-z0-9_]+$/i);
    result[name]=db.prepare(`SELECT COUNT(*) AS n FROM "${name}"`).get().n;
  }
  return result;
};

async function cleanupMount() {
  if(isMounted())execFileSync('umount',[MOUNT],{stdio:'ignore',timeout:20000});
  mounted=false;
  if(child) {
    for(let n=0;n<20 && child.exitCode===null;n++)await delay(100);
    if(child.exitCode===null)child.kill('SIGTERM');
    assert.equal(isMounted(),false,'Temporary COSFS mount must be released');
  }
  if(logfd!==undefined){closeSync(logfd);logfd=undefined;}
}

try {
  process.umask(0o077);
  assert.equal(process.getuid(),0);
  assert.equal((await fetch('http://metadata.tencentyun.com/latest/meta-data/public-ipv4',{signal:AbortSignal.timeout(3000)}).then(r=>r.text())).trim(),'101.43.112.8');
  assert.equal(JSON.parse(readFileSync(`${CONTROL}/synthetic-only.json`)).syntheticOnly,true);
  const access=JSON.parse(readFileSync(`${CONTROL}/cos-access-receipt.json`));
  assert.equal(access.bucket,BUCKET);assert.equal(access.region,REGION);
  assert.equal(access.anonymousBucketAccessDenied,true);assert.equal(access.anonymousObjectReadDenied,true);
  assert.equal(access.objectAccessOutsideStagingDenied,true);
  const credentials=JSON.parse(readFileSync(`${CONFIG}/cos-credentials.json`));
  assert.equal(credentials.Prefix,'staging/');
  const COS=createRequire(`${CONTROL}/cos-client/package.json`)('cos-nodejs-sdk-v5');
  const cos=new COS({SecretId:credentials.SecretId,SecretKey:credentials.SecretKey,
    Protocol:'https:',FollowRedirect:false,Timeout:15000,MaxRetryTimes:1});
  const call=(method,params)=>new Promise((resolve,reject)=>cos[method]({Bucket:BUCKET,Region:REGION,...params},(err,data)=>err?reject(err):resolve(data)));
  const stamp=new Date().toISOString().replace(/[^0-9TZ]/g,'')+'-'+randomUUID().slice(0,8);
  job=`${CONTROL}/offsite/${stamp}`;mkdirSync(job,{recursive:true,mode:0o700});
  const key=readBackupKey(`${CONFIG}/backup.key`);
  const lifanKeyFile=`${CONFIG}/lifan-backup.key`;
  if(!existsSync(lifanKeyFile))privateWrite(lifanKeyFile,randomBytes(32));
  const lifanKey=readBackupKey(lifanKeyFile);
  const lifanConfig=JSON.parse(readFileSync(`${CONFIG}/lifan.json`));
  assert.equal(lifanConfig.dbPath,LIFAN);
  const before=new DatabaseSync(TEAM,{readOnly:true});integrity(before);
  const epoch=before.prepare("SELECT value FROM metadata WHERE key='recoveryEpoch'").get()?.value;
  const inactive=before.prepare('SELECT id FROM members WHERE active=0').all();
  const deleted=before.prepare('SELECT id FROM shared_games WHERE deleted=1').all();
  const sourceTeamCounts=summarize(before);before.close();

  step='local consistent encrypted snapshots';
  const teamFile=`${job}/team.sealed`;
  const teamResult=await createEncryptedBackup({database:TEAM,destination:teamFile,key});
  assert.equal(teamResult.verified,true);
  const lifan=new DatabaseSync(LIFAN,{readOnly:true});integrity(lifan);
  const sourceLifanCounts=summarize(lifan);
  const lifanSnapshot=`${job}/lifan-snapshot.sqlite`;
  await backup(lifan,lifanSnapshot);lifan.close();
  const snapshot=readFileSync(lifanSnapshot);
  privateWrite(`${job}/lifan.sealed`,seal(snapshot,lifanKey));
  assert.deepEqual(unseal(readFileSync(`${job}/lifan.sealed`),lifanKey),snapshot);
  const journal=readdirSync('/var/lib/baseballmaster-staging/journal').filter(name=>/^(identity|[a-f0-9]{64})\.sealed$/.test(name));
  assert(journal.includes('identity.sealed'));
  const files=[{name:'team.sealed',bytes:readFileSync(teamFile)},
    {name:'lifan.sealed',bytes:readFileSync(`${job}/lifan.sealed`)},
    ...journal.map(name=>({name:'journal/'+name,bytes:readFileSync('/var/lib/baseballmaster-staging/journal/'+name)}))];
  assert(files.every(f=>f.bytes.subarray(0,9).toString()==='BMSEALED1'));
  checks.push('Only consistent synthetic SQLite snapshots and sealed test journal files selected; temporary live database excluded');

  step='private temporary COSFS mount';
  assert.equal(isMounted(),false);
  if(!existsSync(MOUNT))mkdirSync(MOUNT,{mode:0o700});
  assert.equal(lstatSync(MOUNT).isSymbolicLink(),false);
  assert.equal(readdirSync(MOUNT).length,0,'Mount point must be empty');
  logfd=openSync(`${job}/cosfs.log`,'wx',0o600);
  const options=['url=https://cos.ap-shanghai.myqcloud.com',`passwd_file=${CONFIG}/passwd-cosfs`,
    'default_acl=private','dbglevel=crit','retries=1','connect_timeout=5','readwrite_timeout=15',
    'parallel_count=2','stat_cache_expire=1','default_permissions','umask=077','mp_umask=077','nosuid,nodev,noexec'];
  child=spawn('/usr/local/bin/cosfs',[`${BUCKET}:/staging`,MOUNT,'-f',...options.flatMap(value=>['-o',value])],{stdio:['ignore',logfd,logfd]});
  let childError=false;child.once('error',()=>{childError=true;});
  for(let i=0;i<75;i++) {
    if(isMounted()){mounted=true;break;}
    assert(!childError && child.exitCode===null,'COSFS stopped before mounting; private log retained');
    await delay(200);
  }
  assert.equal(mounted,true,'COSFS mount timed out');
  const remoteDirectory=`${MOUNT}/backups/${stamp}`;
  mkdirSync(remoteDirectory,{recursive:true,mode:0o700});
  for(const file of files) {
    const destination=`${remoteDirectory}/${file.name}`;
    mkdirSync(destination.slice(0,destination.lastIndexOf('/')),{recursive:true,mode:0o700});
    const pending=destination+'.partial';privateWrite(pending,file.bytes);
    const fd=openSync(pending,'r');try{fsyncSync(fd);}finally{closeSync(fd);}
    renameSync(pending,destination);
  }
  await cleanupMount();
  checks.push('COSFS staging prefix mount performed private write, fsync, rename and unmount; no business service configuration changed');

  step='independent COS API download and anonymous denial';
  mkdirSync(`${job}/downloaded/journal`,{recursive:true,mode:0o700});
  const manifest=[];
  for(const file of files) {
    const objectKey=`staging/backups/${stamp}/${file.name}`;
    const response=await call('getObject',{Key:objectKey});
    const bytes=Buffer.from(response.Body);
    assert.equal(hash(bytes),hash(file.bytes));
    privateWrite(`${job}/downloaded/${file.name}`,bytes);
    const anonymous=await fetch(`https://${BUCKET}.cos.${REGION}.myqcloud.com/${objectKey}`,{redirect:'error',signal:AbortSignal.timeout(15000)});
    assert.equal(anonymous.status,403);await anonymous.body?.cancel();
    manifest.push({key:objectKey,sha256:hash(bytes),bytes:bytes.length});
  }
  checks.push('After unmounting, fresh authenticated HTTPS downloads match every encrypted file hash; anonymous reads denied for every object');

  step='isolated team disaster recovery';
  const restoredTeam=`${job}/restored-team.sqlite`;
  const restored=await restoreEncryptedBackup({archive:`${job}/downloaded/team.sealed`,destination:restoredTeam,key,journalDirectory:`${job}/downloaded/journal`});
  assert.equal(restored.quarantined,false);
  const team=new DatabaseSync(restoredTeam,{readOnly:true});integrity(team);
  for(const {id} of inactive)assert.equal(team.prepare('SELECT active FROM members WHERE id=?').get(id).active,0);
  for(const {id} of deleted)assert.equal(team.prepare('SELECT deleted FROM shared_games WHERE id=?').get(id).deleted,1);
  assert.equal(team.prepare("SELECT COUNT(*) AS n FROM shared_games WHERE access_mode<>'closed' OR live_enabled<>0").get().n,0);
  assert.equal(team.prepare('SELECT COUNT(*) AS n FROM invitations WHERE revoked<>1').get().n,0);
  const restoredTeamCounts=summarize(team);team.close();
  checks.push('Downloaded team archive and journal restore into a separate database with integrity/foreign keys valid; revocations persist and external links close');

  step='isolated lifan recovery and payload decryption';
  const lifanBytes=unseal(readFileSync(`${job}/downloaded/lifan.sealed`),lifanKey);
  assert.equal(hash(lifanBytes),hash(snapshot));
  const restoredLifan=`${job}/restored-lifan.sqlite`;privateWrite(restoredLifan,lifanBytes);
  const registration=new DatabaseSync(restoredLifan,{readOnly:true});integrity(registration);
  assert.deepEqual(summarize(registration),sourceLifanCounts);
  let decrypted=0,healthVerified=false;
  for(const row of registration.prepare('SELECT payload FROM registrations').all()) {
    const [iv,tag,body]=row.payload.split('.').map(value=>Buffer.from(value,'base64'));
    const decrypt=createDecipheriv('aes-256-gcm',Buffer.from(lifanConfig.encryptionKey,'hex'),iv);decrypt.setAuthTag(tag);
    const payload=JSON.parse(Buffer.concat([decrypt.update(body),decrypt.final()]));
    assert(payload.data && typeof payload.data.playerName==='string');decrypted++;
    if(payload.data.injuryDetails==='测试伤病说明')healthVerified=true;
  }
  registration.close();assert(decrypted>0);assert.equal(healthVerified,true);
  assert.throws(()=>unseal(readFileSync(`${job}/downloaded/lifan.sealed`),randomBytes(32)));
  checks.push('Downloaded lifan encrypted archive restores byte-for-byte; all synthetic registration payloads and test health detail decrypt with separate keys; wrong key rejected');

  step='serving database unchanged';
  const after=new DatabaseSync(TEAM,{readOnly:true});integrity(after);
  assert.equal(after.prepare("SELECT value FROM metadata WHERE key='recoveryEpoch'").get()?.value,epoch);
  assert.deepEqual(summarize(after),sourceTeamCounts);after.close();
  const afterLifan=new DatabaseSync(LIFAN,{readOnly:true});assert.deepEqual(summarize(afterLifan),sourceLifanCounts);afterLifan.close();
  checks.push('Serving synthetic database table counts and team recovery epoch preserved; no production or live data copied');
  const receipt={checkedAt:new Date().toISOString(),passed:true,host:'101.43.112.8',syntheticOnly:true,
    bucket:BUCKET,region:REGION,prefix:`staging/backups/${stamp}/`,checks,files:manifest,
    teamSnapshot:teamResult,sourceTeamCounts,restoredTeamCounts,sourceLifanCounts,lifanPayloadsDecrypted:decrypted,
    lifanTestHealthDetailVerified:healthVerified,jobDirectory:job,temporaryMountReleased:!isMounted(),
    recoveryKeysIndependentlyStored:false,safetyJournalStillLocalForServingTestService:true,
    productionBackupTimersEnabled:false,productionDataCopied:false,dnsChanged:false,hongKongConnected:false};
  privateWrite(`${job}/receipt.json`,JSON.stringify(receipt,null,2)+'\n');
  writeFileSync(`${CONTROL}/offsite-backup-receipt.json`,JSON.stringify(receipt,null,2)+'\n',{mode:0o600});
  console.log(JSON.stringify(receipt));
} catch(error) {
  const code=String(error.Code??error.code??error.name??'Failure');
  console.error(JSON.stringify({passed:false,step,jobDirectory:job??null,statusCode:Number(error.statusCode)||null,
    code:/^[A-Za-z0-9_]{1,60}$/.test(code)?code:'Failure',detailsSuppressed:true}));
  process.exitCode=1;
} finally {
  try{await cleanupMount();}
  catch{console.error('Temporary COSFS cleanup needs inspection; no production service touched');process.exitCode=1;}
}
