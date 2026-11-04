import test from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes} from 'node:crypto';
import {mkdtempSync,writeFileSync,readFileSync,existsSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {encryptContainer,decryptContainer} from './state-archive.mjs';

async function fixture(t) {
  const root=mkdtempSync(join(tmpdir(),'bm-move-'));
  t.after(()=>rmSync(root,{recursive:true,force:true}));
  const input=join(root,'data.tar'),key=join(root,'transport.key'),archive=join(root,'transfer.sealed');
  writeFileSync(input,randomBytes(2*1024*1024),{mode:0o600});
  writeFileSync(key,randomBytes(32),{mode:0o600});
  await encryptContainer(input,archive,key);
  return {root,input,key,archive};
}
test('authenticated container round trip preserves exact bytes',async t=>{
  const f=await fixture(t),output=join(f.root,'opened.tar');
  await decryptContainer(f.archive,output,f.key);
  assert.deepEqual(readFileSync(output),readFileSync(f.input));
});
test('wrong key and changed ciphertext never produce a verified output',async t=>{
  const f=await fixture(t),wrong=join(f.root,'wrong.key'),output=join(f.root,'denied.tar');
  writeFileSync(wrong,randomBytes(32),{mode:0o600});
  await assert.rejects(decryptContainer(f.archive,output,wrong));assert.equal(existsSync(output),false);
  const changed=readFileSync(f.archive);changed[Math.floor(changed.length/2)]^=1;
  const corrupted=join(f.root,'corrupted.sealed');writeFileSync(corrupted,changed,{mode:0o600});
  await assert.rejects(decryptContainer(corrupted,output,f.key));assert.equal(existsSync(output),false);
});
test('existing destinations are never replaced',async t=>{
  const f=await fixture(t),output=join(f.root,'existing.tar');
  writeFileSync(output,'preserve',{mode:0o600});
  await assert.rejects(decryptContainer(f.archive,output,f.key));
  await assert.rejects(encryptContainer(f.input,f.archive,f.key));
  assert.equal(readFileSync(output,'utf8'),'preserve');
});
test('disaster recovery or unversioned format cannot enter normal transfer',async t=>{
  const f=await fixture(t),other=join(f.root,'other.sealed'),output=join(f.root,'denied.tar');
  writeFileSync(other,Buffer.concat([Buffer.from('BMSEALED1'),randomBytes(100)]),{mode:0o600});
  await assert.rejects(decryptContainer(other,output,f.key));assert.equal(existsSync(output),false);
});
