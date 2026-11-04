// Production units fail closed until the explicitly approved activation record exists.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync,statSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {DatabaseSync} from 'node:sqlite';

let stage='configuration';
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'
  ?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value;

try {
  const [configuration,role]=process.argv.slice(2);
  const config=JSON.parse(readFileSync(configuration));
  assert(['team','live','lifan','web','backup','check','lifan-backup'].includes(role));
  if(config.syntheticValidation===true) {
    assert.equal(config.codeRoot,'/opt/baseballmaster-staging/releases/synthetic-20261023T104511Z');
    assert(config.activationFile.startsWith('/etc/baseballmaster-candidate-validation/'));
    assert(config.finalDataManifest.startsWith('/etc/baseballmaster-candidate-validation/'));
    assert(config.teamDatabase.startsWith('/var/lib/baseballmaster-candidate-validation/'));
    assert(config.lifanDatabase.startsWith('/var/lib/baseballmaster-candidate-validation/'));
    assert(config.backupWork.startsWith('/var/lib/baseballmaster-candidate-validation/'));
    assert(config.teamMount.startsWith('/mnt/baseballmaster-candidate-validation-'));
    assert.equal(config.liveMount,'/run/baseballmaster-staging-live');
  } else {
    assert.equal(config.codeRoot,'/opt/baseballmaster-production/current');
    assert.equal(config.activationFile,'/etc/baseballmaster-production/activation.json');
    assert(config.teamDatabase.startsWith('/var/lib/baseballmaster-production/'));
    assert(config.lifanDatabase.startsWith('/var/lib/baseballmaster-production/'));
  }
  assert.equal((await fetch('http://metadata.tencentyun.com/latest/meta-data/public-ipv4',{signal:AbortSignal.timeout(3000)}).then(r=>r.text())).trim(),config.host);
  stage='activation';
  const gate=JSON.parse(readFileSync(config.activationFile));
  assert.equal(gate.dataCopyApproved,true);assert.equal(gate.trafficActivationApproved,true);
  assert.equal(gate.oldWritersStopped,true);assert.equal(gate.activeHost,config.host);
  assert.equal(gate.finalDataManifestVerified,true);
  assert(typeof gate.approvalReference==='string' && gate.approvalReference.length>0);
  if(['backup','check','lifan-backup'].includes(role))assert.equal(gate.productionTimersApproved,true);
  stage='final-data-manifest';
  const finalData=JSON.parse(readFileSync(config.finalDataManifest));
  assert.equal(finalData.purpose,'state-preserving-migration');
  assert.equal(finalData.writersStoppedConfirmed,true);assert.equal(finalData.activationEligible,true);
  assert.equal(finalData.onlinePrecopyOnly,false);assert.equal(finalData.recoveryFencesApplied,false);
  assert.equal(createHash('sha256').update(JSON.stringify(canonical(finalData))).digest('hex'),gate.finalDataManifestCanonicalSHA256);
  stage='source-manifest';
  const manifest=JSON.parse(readFileSync(config.sourceManifest));
  assert.equal(createHash('sha256').update(JSON.stringify(canonical(manifest))).digest('hex'),config.sourceManifestCanonicalSHA256);
  stage='source-files';
  for(const [name,digest] of Object.entries(manifest.files)) {
    assert(!name.startsWith('/') && !name.split('/').includes('..'));
    assert.equal(createHash('sha256').update(readFileSync(`${config.codeRoot}/${name}`)).digest('hex'),digest);
  }
  const privateFile=path=>{assert.equal(statSync(path).mode&0o077,0);return readFileSync(path);};
  if(['team','backup','check'].includes(role)) {
    stage='team-environment';
    assert.equal(process.env.BM_TEAM_DATABASE,config.teamDatabase);
    assert.equal(Number(process.env.BM_TEAM_PORT),config.teamPort);
    assert.equal(process.env.BM_TEAM_SAFETY_JOURNAL,`${config.teamMount}/journal`);
    assert.equal(process.env.BM_TEAM_BACKUPS,`${config.teamMount}/backups`);
    assert.equal(process.env.BM_TEAM_BACKUP_KEY,config.teamBackupKey);
    if(role==='team') {
      const admin=JSON.parse(privateFile(config.teamAdminConfig));
      assert.equal(admin.legacyBase,config.liveInternalBase);
      assert.match(admin.salt,/^[a-f0-9]{32}$/);assert.match(admin.passwordHash,/^[a-f0-9]{128}$/);
      assert.match(admin.legacyToken,/^[a-f0-9]{64}$/);
      assert.equal(createHash('sha256').update(admin.legacyToken).digest('hex'),gate.adminBridgeSHA256);
    }
    stage='independent-storage-mount';
    execFileSync('/usr/bin/mountpoint',['-q',config.teamMount],{stdio:'ignore'});
    const mount=JSON.parse(execFileSync('/usr/bin/findmnt',['-J','--mountpoint',config.teamMount],{encoding:'utf8'})).filesystems[0];
    assert.equal(mount.target,config.teamMount);assert(mount.fstype.includes('cosfs'));
    const key=privateFile(config.teamBackupKey);assert.equal(key.length,32);
    stage='journal-identity';
    const db=new DatabaseSync(config.teamDatabase,{readOnly:true});
    let identity,epoch;
    try {
      identity=db.prepare("SELECT value FROM metadata WHERE key='safetyJournalID'").get()?.value;
      epoch=db.prepare("SELECT value FROM metadata WHERE key='recoveryEpoch'").get()?.value;
    }finally{db.close();}
    assert(identity && identity===gate.safetyJournalID);assert.equal(epoch,gate.recoveryEpoch);
    const {unseal}=await import(`${config.codeRoot}/collaboration/safety-journal.mjs`);
    assert.equal(JSON.parse(unseal(readFileSync(`${config.teamMount}/journal/identity.sealed`),key)).journalID,identity);
  }
  if(role==='live') {
    stage='temporary-live-memory';
    assert(typeof process.env.BM_ADMIN_BRIDGE_TOKEN==='string' && process.env.BM_ADMIN_BRIDGE_TOKEN.length>=32 && !process.env.BM_ADMIN_BRIDGE_TOKEN.includes('COPY_'));
    assert.equal(createHash('sha256').update(process.env.BM_ADMIN_BRIDGE_TOKEN).digest('hex'),gate.adminBridgeSHA256);
    assert.equal(Number(process.env.PORT),config.livePort);assert.equal(process.env.HOST,'127.0.0.1');
    assert.equal(process.env.LIVE_DB,`${config.liveMount}/live.sqlite`);
    const mount=JSON.parse(execFileSync('/usr/bin/findmnt',['-J','--mountpoint',config.liveMount],{encoding:'utf8'})).filesystems[0];
    assert.equal(mount.target,config.liveMount);assert.equal(mount.fstype,'tmpfs');
    assert(mount.options.split(',').includes('noswap'));
  }
  if(['lifan','lifan-backup'].includes(role)) {
    stage='lifan-private-config';
    const lifan=JSON.parse(privateFile(config.lifanConfig));
    assert.equal(lifan.dbPath,config.lifanDatabase);assert.equal(lifan.origin,'https://baseballmaster.cc');
    assert.equal(lifan.port,config.lifanPort);assert.equal(lifan.trustProxy,true);
    assert.match(lifan.encryptionKey,/^[a-f0-9]{64}$/);assert(lifan.passwordHash && !lifan.passwordHash.includes('COPY_'));
    if(role==='lifan-backup')assert.equal(privateFile(config.lifanBackupKey).length,32);
  }
  console.log(JSON.stringify({passed:true,role,activeHost:config.host,credentialsPrinted:false}));
} catch(error) {
  console.error(JSON.stringify({passed:false,blockedBeforeStart:true,stage,errorType:error.name,detailsSuppressed:true}));
  process.exitCode=1;
}
