import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash,randomBytes} from 'node:crypto';
import {join} from 'node:path';
import {createEncryptedBackup,restoreEncryptedBackup} from '../backup.mjs';
import {adminFixture} from './admin-fixture.mjs';
import {CollaborationStore} from '../server.mjs';
import {LaunchScreenService,defaultLaunchConfig,MAX_IMAGE_BYTES} from '../launch-screen.mjs';
const png=readFileSync(new URL('./fixtures/launch-background.png',import.meta.url));
const jpeg=readFileSync(new URL('./fixtures/launch-background.jpg',import.meta.url));
const editable=c=>({revision:c.revision,enabled:c.enabled,message:c.message,displayDurationMs:c.displayDurationMs,startsAt:c.startsAt,endsAt:c.endsAt,backgroundImage:c.backgroundImage});
async function setup(t){const a=await adminFixture();t.after(a.close);const auth=await a.login();const owner=(path,options={})=>a.request('api/launch-screen'+path,{...options,headers:{Cookie:auth.cookie.split(';')[0],...options.headers}});return {...a,owner,auth};}
test('public configuration has safe bundled defaults and never exposes owner credentials',async t=>{
 const a=await setup(t),r=await fetch(a.origin+'/api/v3/app/launch-screen');assert.equal(r.status,200);assert.equal(r.headers.get('cache-control'),'no-store');assert.deepEqual(await r.json(),defaultLaunchConfig());
 assert.equal((await a.request('api/launch-screen')).status,401);assert.equal((await fetch(a.origin+'/api/v3/app/launch-screen',{method:'PUT',body:'{}'})).status,405);
});
test('owner uploads PNG/JPEG, public assets are content-addressed and immutable',async t=>{
 const a=await setup(t);
 for(const [data,mime]of [[png,'image/png'],[jpeg,'image/jpeg']]){
  const r=await a.owner('/image',{method:'POST',headers:{'Content-Type':mime},body:data});assert.equal(r.status,201);const image=await r.json();assert.equal(image.sha256,createHash('sha256').update(data).digest('hex'));assert.deepEqual([image.width,image.height],[64,128]);
  const file=await fetch(a.origin+image.path);assert.equal(file.status,200);assert.equal(file.headers.get('content-type'),mime);assert.match(file.headers.get('cache-control'),/immutable/);assert.deepEqual(Buffer.from(await file.arrayBuffer()),data);
  const etag=file.headers.get('etag');assert.equal((await fetch(a.origin+image.path,{headers:{'If-None-Match':etag}})).status,304);
 }
 assert.equal((await fetch(a.origin+'/api/v3/app/launch-assets/%2e%2e%2fserver.mjs')).status,404);
});
test('publication is authenticated, exact-origin checked, revision guarded and separate from team data',async t=>{
 const a=await setup(t),body={...editable(defaultLaunchConfig()),message:'记录每一场精彩',displayDurationMs:1200,startsAt:a.clock(),endsAt:a.clock()+60000},before=a.store.db.prepare('SELECT revision FROM teams WHERE id=?').get(a.team).revision;
 assert.equal((await a.request('api/launch-screen',{method:'PUT',body:JSON.stringify(body)})).status,401);
 assert.equal((await a.owner('',{method:'PUT',headers:{Origin:'https://evil.example'},body:JSON.stringify(body)})).status,403);
 const result=await a.owner('',{method:'PUT',body:JSON.stringify(body)});assert.equal(result.status,200);const saved=await result.json();assert.equal(saved.revision,1);assert.equal(saved.message,body.message);
 assert.equal((await a.owner('',{method:'PUT',body:JSON.stringify(body)})).status,409);
 assert.equal(a.store.db.prepare('SELECT revision FROM teams WHERE id=?').get(a.team).revision,before);
 assert.equal((await fetch(a.origin+'/api/v3/app/launch-screen').then(r=>r.json())).endsAt,body.endsAt);
});
test('invalid settings, image uploads and out-of-bounds images do not replace published config',async t=>{
 const a=await setup(t);
 for(const change of [{enabled:'yes'},{message:'长'.repeat(41)},{message:'第一行\n第二行\n第三行'},{message:'\u0000'},{displayDurationMs:2001},{displayDurationMs:0},{startsAt:2,endsAt:1},{startsAt:'tomorrow'},{brandLogo:'https://evil.example/logo'},{backgroundImage:{sha256:'a'.repeat(64)}}]){
  const r=await a.owner('',{method:'PUT',body:JSON.stringify({...editable(defaultLaunchConfig()),...change})});assert.equal(r.status,400,JSON.stringify(change));
 }
 for(const [data,mime,status]of [[Buffer.from('<svg/>'),'image/svg+xml',415],[png.subarray(0,40),'image/png',415],[Buffer.alloc(MAX_IMAGE_BYTES+1),'image/png',413]])assert.equal((await a.owner('/image',{method:'POST',headers:{'Content-Type':mime},body:data})).status,status);
 assert.equal((await a.owner('').then(r=>r.json())).revision,0);
});
test('published image descriptor is server-owned; disable and reset remain available',async t=>{
 const a=await setup(t),image=await a.owner('/image',{method:'POST',headers:{'Content-Type':'image/png'},body:png}).then(r=>r.json());
 let c=await a.owner('',{method:'PUT',body:JSON.stringify({...editable(defaultLaunchConfig()),backgroundImage:{...image,path:'https://evil.example/image.png'}})}).then(r=>r.json());assert.equal(c.backgroundImage.path,image.path);
 c=await a.owner('',{method:'PUT',body:JSON.stringify({...editable(c),enabled:false})}).then(r=>r.json());assert.equal(c.enabled,false);
 c=await a.owner('',{method:'PUT',body:JSON.stringify({...editable(defaultLaunchConfig()),revision:c.revision})}).then(r=>r.json());assert.equal(c.backgroundImage,null);assert.equal(c.enabled,true);
});
test('configuration and images survive SQLite reopen, and restoration quarantine prevents publishing',async t=>{
 const a=await setup(t);const image=await a.owner('/image',{method:'POST',headers:{'Content-Type':'image/png'},body:png}).then(r=>r.json());
 await a.owner('',{method:'PUT',body:JSON.stringify({...editable(defaultLaunchConfig()),backgroundImage:image})});
 const other=new CollaborationStore({database:a.store.databasePath});try{const service=new LaunchScreenService(other);assert.equal(service.get().backgroundImage.sha256,image.sha256);}finally{other.close();}
 a.store.db.prepare("INSERT INTO metadata VALUES('restoreQuarantine','1')").run();
 assert.equal((await a.owner('',{method:'PUT',body:JSON.stringify({...editable(defaultLaunchConfig()),revision:1})})).status,503);
 assert.equal((await a.owner('/image',{method:'POST',headers:{'Content-Type':'image/png'},body:png})).status,503);
 assert.equal((await fetch(a.origin+'/api/v3/app/launch-screen')).status,503);
});
test('disk reserve and upload retention keep launch assets bounded without deleting the published image',async t=>{
 const a=await setup(t),service=a.store.launchScreen;
 const active=service.upload(png,'image/png');service.save({...editable(defaultLaunchConfig()),backgroundImage:active});
 for(let i=0;i<12;i++){
  // Distinct ancillary PNG chunks are not needed: valid JPEG trailing comment
  // markers produce distinct, bounded candidates while keeping real image data.
  const comment=Buffer.from([255,254,0,3,i]);const candidate=Buffer.concat([jpeg.subarray(0,2),comment,jpeg.subarray(2)]);
  service.upload(candidate,'image/jpeg');
 }
 assert.ok(a.store.db.prepare('SELECT COUNT(*) AS n FROM app_launch_assets').get().n<=9);
 assert.ok(a.store.db.prepare('SELECT 1 FROM app_launch_assets WHERE sha=?').get(active.sha256));
 a.store.minimumFreeBytes=Number.MAX_SAFE_INTEGER;
 assert.throws(()=>service.upload(png,'image/png'),e=>e.status===507);
 assert.throws(()=>service.save({...editable(service.get()),message:'不能覆盖'}),e=>e.status===507);
 assert.equal(service.get().message,defaultLaunchConfig().message);
});

test('encrypted whole-database backup preserves launch configuration and exact image bytes without bypassing recovery quarantine',async t=>{
 const a=await setup(t),service=a.store.launchScreen,key=randomBytes(32);
 const image=service.upload(png,'image/png');
 const published=service.save({...editable(defaultLaunchConfig()),message:'备份中的开屏配置',displayDurationMs:1500,startsAt:a.clock()+60000,endsAt:a.clock()+120000,backgroundImage:image});
 const counts=Object.fromEntries(['teams','members','shared_games'].map(table=>[table,a.store.db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get().n]));
 const archive=join(a.folder,'launch-full.sealed'),destination=join(a.folder,'launch-restored.sqlite');
 assert.equal((await createEncryptedBackup({database:a.store.databasePath,destination:archive,key})).verified,true);
 const sealed=readFileSync(archive);assert.equal(sealed.includes(Buffer.from(published.message)),false);assert.equal(sealed.includes(png),false);
 service.save({...editable(service.get()),enabled:false,message:'备份之后的变更',backgroundImage:null});
 const result=await restoreEncryptedBackup({archive,destination,key});assert.equal(result.quarantined,true);
 const restored=new CollaborationStore({database:destination});
 try{
  const recovered=new LaunchScreenService(restored);
  assert.deepEqual(recovered.get(),published);
  const asset=restored.db.prepare('SELECT body FROM app_launch_assets WHERE sha=?').get(image.sha256);
  assert.deepEqual(Buffer.from(asset.body),png);
  assert.deepEqual(Object.fromEntries(Object.keys(counts).map(table=>[table,restored.db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get().n])),counts);
  assert.equal(restored.db.prepare("SELECT value FROM metadata WHERE key='restoreQuarantine'").get().value,'1');
  assert.throws(()=>recovered.save({...editable(recovered.get()),message:'隔离中不得发布'}),e=>e.status===503);
  assert.throws(()=>recovered.upload(jpeg,'image/jpeg'),e=>e.status===503);
 }finally{restored.close();}
});
