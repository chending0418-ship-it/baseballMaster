// Isolated operations job; no change to the existing lifan application.
import assert from 'node:assert/strict';
import {DatabaseSync,backup} from 'node:sqlite';
import {createHash,randomUUID} from 'node:crypto';
import {readFileSync,writeFileSync,mkdirSync,statSync,rmSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {createRequire} from 'node:module';

let work;
try {
  process.umask(0o077);
  const path=process.argv[2],config=JSON.parse(readFileSync(path));
  execFileSync(process.execPath,[config.prestartScript,path,'lifan-backup'],{stdio:'pipe'});
  assert.equal(process.getuid(),0);
  const credentials=JSON.parse(readFileSync(config.cosCredentialsFile));
  assert.equal(statSync(config.cosCredentialsFile).mode&0o077,0);
  assert.equal(credentials.Bucket,config.bucket);assert.equal(credentials.Region,'ap-shanghai');
  const prefix=config.syntheticValidation===true?config.validationBackupPrefix:'production/lifan/backups/';
  if(config.syntheticValidation===true)assert(prefix.startsWith('staging/candidate-validation/') && !prefix.includes('..'));
  assert.equal(credentials.Prefix,config.syntheticValidation===true?'staging/':'production/');
  assert.equal(statSync(config.lifanBackupKey).mode&0o077,0);
  const key=readFileSync(config.lifanBackupKey);assert.equal(key.length,32);
  const {seal,unseal}=await import(`${config.codeRoot}/collaboration/safety-journal.mjs`);
  const stamp=new Date().toISOString().replace(/[^0-9TZ]/g,'');
  work=`${config.backupWork}/lifan-${stamp}-${randomUUID()}`;mkdirSync(work,{recursive:true,mode:0o700});
  const db=new DatabaseSync(config.lifanDatabase,{readOnly:true});
  try{await backup(db,`${work}/snapshot.sqlite`);}finally{db.close();}
  const snapshot=new DatabaseSync(`${work}/snapshot.sqlite`,{readOnly:true});
  try{assert.equal(snapshot.prepare('PRAGMA integrity_check').get().integrity_check,'ok');assert.equal(snapshot.prepare('PRAGMA foreign_key_check').all().length,0);}finally{snapshot.close();}
  const plain=readFileSync(`${work}/snapshot.sqlite`),bytes=seal(plain,key);
  assert.deepEqual(unseal(bytes,key),plain);
  const archive=`${work}/lifan.sealed`;writeFileSync(archive,bytes,{mode:0o600,flag:'wx'});
  const COS=createRequire(`${config.cosClientRoot}/package.json`)('cos-nodejs-sdk-v5');
  const cos=new COS({SecretId:credentials.SecretId,SecretKey:credentials.SecretKey,Protocol:'https:',FollowRedirect:false,Timeout:30000,MaxRetryTimes:1});
  const call=(method,params)=>new Promise((resolve,reject)=>cos[method]({Bucket:config.bucket,Region:'ap-shanghai',...params},(error,data)=>error?reject(error):resolve(data)));
  const object=`${prefix}daily-${stamp}.sealed`;
  try{await call('headObject',{Key:object});throw new Error('Backup already exists');}catch(error){assert.equal(error.statusCode,404);}
  await call('putObject',{Key:object,Body:bytes,ContentLength:bytes.length,ContentType:'application/octet-stream'});
  const saved=Buffer.from((await call('getObject',{Key:object})).Body);
  const digest=value=>createHash('sha256').update(value).digest('hex');assert.equal(digest(saved),digest(bytes));
  assert.deepEqual(unseal(saved,key),plain);
  const receipt={checkedAt:new Date().toISOString(),verified:true,bucket:config.bucket,key:object,backupBytes:bytes.length,sha256:digest(bytes),automaticDeletionEnabled:false};
  writeFileSync(`${config.backupWork}/lifan-last-backup.json`,JSON.stringify(receipt)+'\n',{mode:0o600});
  console.log(JSON.stringify(receipt));
} catch(error) {console.error(JSON.stringify({passed:false,errorType:error.name,detailsSuppressed:true}));process.exitCode=1;}
finally {if(work)rmSync(work,{recursive:true,force:true});}
