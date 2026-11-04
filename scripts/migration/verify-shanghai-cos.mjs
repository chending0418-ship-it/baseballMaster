// Uses only the dedicated Shanghai staging credentials and synthetic test objects.
import assert from 'node:assert/strict';
import {createHash,randomUUID} from 'node:crypto';
import {readFileSync,writeFileSync,statSync} from 'node:fs';
import {createRequire} from 'node:module';

const CONTROL='/var/lib/baseballmaster-staging-control';
const CONFIG='/etc/baseballmaster-staging';
const require=createRequire(`${CONTROL}/cos-client/package.json`);
const COS=require('cos-nodejs-sdk-v5');
const bucket='bm-backup-sh-1387380066',region='ap-shanghai';
let step='host guard';

try {
  process.umask(0o077);
  const ip=(await fetch('http://metadata.tencentyun.com/latest/meta-data/public-ipv4',{signal:AbortSignal.timeout(3000)}).then(r=>r.text())).trim();
  assert.equal(ip,'101.43.112.8');
  assert.equal(JSON.parse(readFileSync(`${CONTROL}/synthetic-only.json`)).syntheticOnly,true);
  assert.equal(statSync(`${CONFIG}/cos-credentials.json`).mode&0o077,0);
  const credentials=JSON.parse(readFileSync(`${CONFIG}/cos-credentials.json`));
  assert.equal(credentials.Bucket,bucket);assert.equal(credentials.Region,region);assert.equal(credentials.Prefix,'staging/');
  const cos=new COS({SecretId:credentials.SecretId,SecretKey:credentials.SecretKey,
    Protocol:'https:',FollowRedirect:false,Timeout:15000,MaxRetryTimes:1});
  const call=(method,params={})=>new Promise((resolve,reject)=>cos[method]({Bucket:bucket,Region:region,...params},(err,data)=>err?reject(err):resolve(data)));
  step='bucket access';
  const head=await call('headBucket');
  step='private bucket ACL';
  let acl=null,aclRoleModeQueryDenied=false;
  try {acl=await call('getBucketAcl');}
  catch(error) {
    // Some buckets reject ACL inspection with this service-side mode message.
    // Record the limitation, check anonymous behavior, and request console review.
    assert.equal(error.statusCode,403);
    assert.equal(error.error?.Message??error.message,'bucket is role mode, check operation auth failed');
    aclRoleModeQueryDenied=true;
  }
  if(acl) {
    assert.equal(acl.ACL,'private');
    const grants=acl.Grants??[];
    assert(grants.length>=1);
    assert(grants.every(g=>g.Grantee?.ID===acl.Owner?.ID && g.Permission==='FULL_CONTROL'));
  }
  const verifiedRegion=head.headers?.['x-cos-bucket-region'];
  assert(!verifiedRegion || verifiedRegion===region);
  step='anonymous bucket access denied';
  const endpoint=`https://${bucket}.cos.${region}.myqcloud.com`;
  for(const method of ['HEAD','GET']) {
    const response=await fetch(endpoint+'/?max-keys=1&prefix=staging%2F',{method,redirect:'error',signal:AbortSignal.timeout(15000)});
    assert.equal(response.status,403);await response.body?.cancel();
  }
  step='prefix restriction';
  let outsideDenied=false;
  try {await call('headObject',{Key:`production/__read_only_permission_probe_${randomUUID()}`});}
  catch(error){outsideDenied=error.statusCode===403;}
  assert.equal(outsideDenied,true,'Object access outside staging must be denied');
  step='synthetic read/write';
  const stamp=new Date().toISOString().replace(/[^0-9TZ]/g,'');
  const key=`staging/verification/${stamp}/connectivity.bin`;
  const bytes=Buffer.from('BaseballMaster Shanghai synthetic-only backup storage check\n');
  await call('putObject',{Key:'staging/',Body:Buffer.alloc(0),ContentLength:0,ContentType:'application/x-directory'});
  await call('putObject',{Key:key,Body:bytes,ContentLength:bytes.length,ContentType:'application/octet-stream'});
  const read=await call('getObject',{Key:key});
  assert.deepEqual(Buffer.from(read.Body),bytes);
  step='anonymous access denied';
  const anonymous=await fetch(`https://${bucket}.cos.${region}.myqcloud.com/${key}`,{redirect:'error',signal:AbortSignal.timeout(15000)});
  assert.equal(anonymous.status,403);await anonymous.body?.cancel();
  step='list staging';
  const list=await call('getBucket',{Prefix:`staging/verification/${stamp}/`,MaxKeys:10});
  assert(list.Contents?.some(item=>item.Key===key));
  const receipt={checkedAt:new Date().toISOString(),host:ip,bucket,region,endpoint,
    serverReportedRegion:verifiedRegion??null,bucketACL:acl?.ACL??null,bucketGrantsOwnerOnly:acl?true:null,
    aclRoleModeQueryDenied,anonymousBucketAccessDenied:true,anonymousObjectReadDenied:true,
    objectAccessOutsideStagingDenied:true,verifiedPrefix:'staging/',testObject:key,
    testObjectSHA256:createHash('sha256').update(bytes).digest('hex'),storageClass:'STANDARD',
    uploadDownloadHashMatches:true,credentialsPrinted:false,productionDataCopied:false,dnsChanged:false};
  writeFileSync(`${CONTROL}/cos-access-receipt.json`,JSON.stringify(receipt,null,2)+'\n',{mode:0o600});
  console.log(JSON.stringify(receipt));
} catch(error) {
  // SDK error objects can include signed request headers; never serialize them.
  const code=String(error.Code??error.code??error.name??'Failure');
  console.error(JSON.stringify({passed:false,step,statusCode:Number(error.statusCode)||null,
    code:/^[A-Za-z0-9_]{1,60}$/.test(code)?code:'Failure',detailsSuppressed:true}));
  process.exitCode=1;
}
