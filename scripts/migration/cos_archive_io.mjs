// Transfer one newly created encrypted backup. Never prints SDK errors or credentials.
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,statSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
try {
  const [input,key,output]=process.argv.slice(2);
  assert(/^production\/(full-state|configuration)\/daily-[0-9TZ]+-[a-f0-9]+\.sealed$/.test(key));
  const path='/etc/baseballmaster-production/cos-credentials.json';
  assert.equal(statSync(path).mode&0o077,0);const c=JSON.parse(readFileSync(path));
  assert.equal(c.Prefix,'production/');assert.equal(c.Region,'ap-shanghai');
  const COS=createRequire('/var/lib/baseballmaster-staging-control/cos-client/package.json')('cos-nodejs-sdk-v5');
  const cos=new COS({SecretId:c.SecretId,SecretKey:c.SecretKey,Protocol:'https:',FollowRedirect:false,Timeout:30000,MaxRetryTimes:1});
  const call=(m,p)=>new Promise((resolve,reject)=>cos[m]({Bucket:c.Bucket,Region:c.Region,...p},(e,d)=>e?reject(e):resolve(d)));
  try {await call('headObject',{Key:key});throw new Error('Refusing overwrite');}catch(e){assert.equal(e.statusCode,404);}
  const Body=readFileSync(input);await call('putObject',{Key:key,Body,ContentLength:Body.length,ContentType:'application/octet-stream'});
  const saved=Buffer.from((await call('getObject',{Key:key})).Body);assert.deepEqual(saved,Body);
  const anonymous=await fetch('https://'+c.Bucket+'.cos.ap-shanghai.myqcloud.com/'+key,{redirect:'manual'});assert.equal(anonymous.status,403);
  writeFileSync(output,saved,{mode:0o600,flag:'wx'});
  console.log(JSON.stringify({passed:true,key,bytes:saved.length,sha256:createHash('sha256').update(saved).digest('hex'),anonymousReadDenied:true}));
}catch(e){console.error(JSON.stringify({passed:false,errorType:e.name,statusCode:e.statusCode??null,detailsSuppressed:true}));process.exitCode=1;}
