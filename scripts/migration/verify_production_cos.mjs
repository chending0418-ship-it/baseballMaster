// Authorized production-prefix check: only this new synthetic probe is deleted.
import assert from 'node:assert/strict';
import {readFileSync,statSync} from 'node:fs';
import {createRequire} from 'node:module';
import {randomBytes,randomUUID,createHash} from 'node:crypto';
let step='configuration';
try {
  const path='/etc/baseballmaster-production/cos-credentials.json';
  assert.equal(statSync(path).mode&0o077,0);
  const c=JSON.parse(readFileSync(path));
  assert.equal(c.Bucket,'bm-backup-sh-1387380066');assert.equal(c.Region,'ap-shanghai');assert.equal(c.Prefix,'production/');
  const COS=createRequire('/var/lib/baseballmaster-staging-control/cos-client/package.json')('cos-nodejs-sdk-v5');
  const cos=new COS({SecretId:c.SecretId,SecretKey:c.SecretKey,Protocol:'https:',FollowRedirect:false,Timeout:20000,MaxRetryTimes:1});
  const call=(method,p={})=>new Promise((resolve,reject)=>cos[method]({Bucket:c.Bucket,Region:c.Region,...p},(e,d)=>e?reject(e):resolve(d)));
  let bucketACLReadAllowed=false;
  step='private-bucket';try {const acl=await call('getBucketAcl');assert(!/AllUsers|AuthenticatedUsers/.test(JSON.stringify(acl.Grant??acl.AccessControlList??[])));bucketACLReadAllowed=true;} catch(e) {if(e.statusCode!==403)throw e;}
  step='production-list';await call('getBucket',{Prefix:'production/',MaxKeys:3});
  const Key='production/migration-acceptance/probe-'+randomUUID()+'.synthetic';
  const Body=randomBytes(256);step='production-write';await call('putObject',{Key,Body,ContentLength:Body.length,ContentType:'application/octet-stream'});
  step='production-head';await call('headObject',{Key});
  step='production-read';const saved=Buffer.from((await call('getObject',{Key})).Body);
  assert.deepEqual(saved,Body);step='anonymous-read';const anonymous=await fetch('https://'+c.Bucket+'.cos.ap-shanghai.myqcloud.com/'+Key,{redirect:'manual'});assert.equal(anonymous.status,403);step='delete-own-probe';await call('deleteObject',{Key});
  console.log(JSON.stringify({passed:true,productionPrefixReadWriteVerified:true,bucketACLReadAllowed,anonymousProductionObjectReadDenied:true,syntheticProbeDeleted:true,realBusinessDataUsed:false,credentialsPrinted:false,probeSHA256:createHash('sha256').update(Body).digest('hex')}));
} catch(e) {console.error(JSON.stringify({passed:false,step,statusCode:e.statusCode??null,errorType:e.name,detailsSuppressed:true}));process.exitCode=1;}
