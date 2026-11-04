// Separate authenticated transport format: never calls disaster-recovery restore.
import assert from 'node:assert/strict';
import {createCipheriv,createDecipheriv,randomBytes,randomUUID} from 'node:crypto';
import {createReadStream,createWriteStream,readFileSync,writeFileSync,openSync,closeSync,
  readSync,writeSync,fsyncSync,renameSync,rmSync,existsSync,statSync} from 'node:fs';
import {createGzip,createGunzip} from 'node:zlib';
import {pipeline} from 'node:stream/promises';
import {resolve,dirname} from 'node:path';
import {pathToFileURL} from 'node:url';

const MAGIC=Buffer.from('BMMOVE01'),AAD=Buffer.from('BaseballMaster state-preserving migration v1');
function guard(input,output,keyFile) {
  assert.notEqual(resolve(input),resolve(output));assert(!existsSync(output),'Output already exists');
  assert(statSync(input).isFile());assert.equal(statSync(keyFile).mode&0o077,0);
  assert.equal(statSync(dirname(output)).mode&0o077,0,'Output parent must be private');
  const key=readFileSync(keyFile);assert.equal(key.length,32);return key;
}
export async function encryptContainer(input,output,keyFile) {
  const key=guard(input,output,keyFile),iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,iv);
  cipher.setAAD(AAD);
  const pending=output+'.'+randomUUID()+'.partial';
  writeFileSync(pending,Buffer.concat([MAGIC,iv,Buffer.alloc(16)]),{mode:0o600,flag:'wx'});
  try {
    await pipeline(createReadStream(input),createGzip({level:6}),cipher,createWriteStream(pending,{flags:'a'}));
    const fd=openSync(pending,'r+');try{writeSync(fd,cipher.getAuthTag(),0,16,20);fsyncSync(fd);}finally{closeSync(fd);}
    assert(!existsSync(output));renameSync(pending,output);
  }finally{rmSync(pending,{force:true});}
}
export async function decryptContainer(input,output,keyFile) {
  const key=guard(input,output,keyFile),header=Buffer.alloc(36),fd=openSync(input,'r');
  try{assert.equal(readSync(fd,header,0,36,0),36);}finally{closeSync(fd);}
  assert.deepEqual(header.subarray(0,8),MAGIC,'Not a state-preserving migration archive');
  const decipher=createDecipheriv('aes-256-gcm',key,header.subarray(8,20));
  decipher.setAAD(AAD);decipher.setAuthTag(header.subarray(20,36));
  const pending=output+'.'+randomUUID()+'.unverified';
  try {
    await pipeline(createReadStream(input,{start:36}),decipher,createGunzip(),createWriteStream(pending,{flags:'wx',mode:0o600}));
    const verified=openSync(pending,'r');try{fsyncSync(verified);}finally{closeSync(verified);}
    assert(!existsSync(output));renameSync(pending,output);
  }finally{rmSync(pending,{force:true});}
}
if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href) {
  try {
    process.umask(0o077);
    const [action,input,output,keyFile]=process.argv.slice(2);
    assert(['encrypt','decrypt'].includes(action) && input && output && keyFile);
    await (action==='encrypt'?encryptContainer:decryptContainer)(input,output,keyFile);
    console.log(JSON.stringify({passed:true,action,format:'BMMOVE01',credentialsPrinted:false}));
  } catch(error) {console.error(JSON.stringify({passed:false,errorType:error.name,detailsSuppressed:true}));process.exitCode=1;}
}
