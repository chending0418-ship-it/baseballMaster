import {createHash} from 'node:crypto';
import {statfsSync} from 'node:fs';

export const LAUNCH_PATH='/api/v3/app/launch-screen';
export const ASSET_PATH='/api/v3/app/launch-assets/';
export const MAX_IMAGE_BYTES=3*1024*1024;
export class LaunchScreenError extends Error {
 constructor(status,message){super(message);this.status=status;}
}
const check=(ok,status=400,message='开屏配置格式不正确。')=>{if(!ok)throw new LaunchScreenError(status,message);};
export const defaultLaunchConfig=()=>({schemaVersion:1,revision:0,updatedAt:null,enabled:true,message:'每一球 都有迹可循',displayDurationMs:800,startsAt:null,endsAt:null,backgroundImage:null});

// Inspect only bounded PNG/JPEG inputs. Native clients also decode the image
// before adopting a configuration; an invalid image never replaces their cache.
function dimensions(data,mime){
 let width,height;
 if(mime==='image/png'){
  check(data.length>=57&&data.subarray(0,8).equals(Buffer.from('89504e470d0a1a0a','hex'))&&data.readUInt32BE(8)===13&&data.toString('ascii',12,16)==='IHDR',415,'请选择完整的 PNG 或 JPEG 图片。');
  width=data.readUInt32BE(16);height=data.readUInt32BE(20);
  let at=8,hasData=false,ended=false;
  while(at+12<=data.length){const length=data.readUInt32BE(at),type=data.toString('ascii',at+4,at+8);check(at+12+length<=data.length,415,'PNG 图片不完整。');check(type!=='acTL',415,'背景图暂不支持动画，请上传静态 PNG 或 JPEG。');if(type==='IDAT'&&length)hasData=true;at+=length+12;if(type==='IEND'){check(length===0&&at===data.length,415);ended=true;break;}}
  check(hasData&&ended,415,'PNG 图片不完整。');
 }else{
  check(mime==='image/jpeg'&&data.length>=12&&data[0]===255&&data[1]===216&&data.at(-2)===255&&data.at(-1)===217,415,'请选择完整的 PNG 或 JPEG 图片。');
  let at=2;
  while(at+4<=data.length){check(data[at++]===255,415,'JPEG 图片格式不正确。');while(data[at]===255)at++;const marker=data[at++];if(marker===218||marker===217)break;if(marker===1||(marker>=208&&marker<=215))continue;check(at+2<=data.length,415);const length=data.readUInt16BE(at);check(length>=2&&at+length<=data.length,415);if([192,193,194].includes(marker)){check(length>=8,415);height=data.readUInt16BE(at+3);width=data.readUInt16BE(at+5);break;}at+=length;}
 }
 check(Number.isInteger(width)&&Number.isInteger(height)&&width>0&&height>0&&width<=4096&&height<=8192&&width*height<=16000000,415,'图片尺寸过大或无法读取，最多支持 1600 万像素。');
 return {width,height};
}

export async function readLaunchUpload(req){
 const mime=(req.headers['content-type']??'').split(';')[0];check(['image/png','image/jpeg'].includes(mime),415,'只支持 PNG 或 JPEG 图片。');
 const declared=Number(req.headers['content-length']);check(!Number.isFinite(declared)||declared<=MAX_IMAGE_BYTES,413,'背景图不能超过 3 MB。');
 let length=0;const chunks=[];for await(const part of req){length+=part.length;check(length<=MAX_IMAGE_BYTES,413,'背景图不能超过 3 MB。');chunks.push(part);}check(length>0,400,'请选择背景图片。');return {data:Buffer.concat(chunks),mime};
}

export class LaunchScreenService {
 constructor(store){
  this.store=store;this.db=store.db;
  this.db.exec(`CREATE TABLE IF NOT EXISTS app_launch_settings(id INTEGER PRIMARY KEY CHECK(id=1), config TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS app_launch_assets(sha TEXT PRIMARY KEY,mime TEXT NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,created_at INTEGER NOT NULL,body BLOB NOT NULL);`);
 }
 get(){const row=this.db.prepare('SELECT config FROM app_launch_settings WHERE id=1').get();return row?JSON.parse(row.config):defaultLaunchConfig();}
 writable(extraBytes=0){
  check(this.db.prepare("SELECT value FROM metadata WHERE key='restoreQuarantine'").get()?.value!=='1',503,'服务正在核对恢复资料，暂不能修改开屏。');
  if(this.store.databaseDirectory&&this.store.minimumFreeBytes>0){const disk=statfsSync(this.store.databaseDirectory);check(disk.bavail*disk.bsize>=this.store.minimumFreeBytes+extraBytes,507,'服务存储空间不足，暂不能修改开屏。');}
 }
 descriptor(row){return {path:ASSET_PATH+row.sha+(row.mime==='image/png'?'.png':'.jpg'),sha256:row.sha,mimeType:row.mime,byteCount:row.body.length,width:row.width,height:row.height};}
 upload(data,mime){
  this.writable(data.length);check(data.length<=MAX_IMAGE_BYTES,413,'背景图不能超过 3 MB。');const {width,height}=dimensions(data,mime),sha=createHash('sha256').update(data).digest('hex');
  const existing=this.db.prepare('SELECT * FROM app_launch_assets WHERE sha=?').get(sha);if(existing)return this.descriptor(existing);
  this.db.exec('BEGIN IMMEDIATE');try{
   this.db.prepare('INSERT INTO app_launch_assets VALUES(?,?,?,?,?,?)').run(sha,mime,width,height,this.store.now(),data);
   // Keep the published image and the eight most recently uploaded candidates.
   const protectedSHA=this.get().backgroundImage?.sha256??'';
   this.db.prepare('DELETE FROM app_launch_assets WHERE sha<>? AND sha NOT IN (SELECT sha FROM app_launch_assets ORDER BY created_at DESC,rowid DESC LIMIT 8)').run(protectedSHA);
   this.db.exec('COMMIT');
  }catch(e){this.db.exec('ROLLBACK');throw e;}
  return this.descriptor({sha,mime,width,height,body:data});
 }
 save(body){
  this.writable();check(body&&typeof body==='object'&&!Array.isArray(body));
  check(Object.keys(body).every(k=>['revision','enabled','message','displayDurationMs','startsAt','endsAt','backgroundImage'].includes(k)),400,'只可配置背景、文案、启停、有效期与时长。');
  check(Number.isSafeInteger(body.revision)&&body.revision>=0&&typeof body.enabled==='boolean');
  check(typeof body.message==='string'&&[...body.message].length<=40&&!/[\u0000-\u0009\u000b-\u001f\u007f]/.test(body.message)&&body.message.split('\n').length<=2,400,'文案最多 40 字、两行。');
  check(Number.isInteger(body.displayDurationMs)&&body.displayDurationMs>=300&&body.displayDurationMs<=2000,400,'显示时长须为 0.3 至 2 秒。');
  for(const value of [body.startsAt,body.endsAt])check(value===null||(Number.isSafeInteger(value)&&value>=0&&value<=4102444800000),400,'请选择有效的生效时间。');
  check(body.startsAt===null||body.endsAt===null||body.endsAt>body.startsAt,400,'结束时间应晚于开始时间。');
  let backgroundImage=null;
  if(body.backgroundImage!==null){
   check(body.backgroundImage&&/^[a-f0-9]{64}$/.test(body.backgroundImage.sha256),400,'请先上传背景图片。');
   const row=this.db.prepare('SELECT * FROM app_launch_assets WHERE sha=?').get(body.backgroundImage.sha256);check(row,400,'背景图片已失效，请重新上传。');backgroundImage=this.descriptor(row);
  }
  this.db.exec('BEGIN IMMEDIATE');try{
   const current=this.get();check(current.revision===body.revision,409,'配置已被其他窗口更新，请刷新后重试。');
   const config={schemaVersion:1,revision:current.revision+1,updatedAt:this.store.now(),enabled:body.enabled,message:body.message.trim(),displayDurationMs:body.displayDurationMs,startsAt:body.startsAt,endsAt:body.endsAt,backgroundImage};
   this.db.prepare('INSERT INTO app_launch_settings VALUES(1,?) ON CONFLICT(id) DO UPDATE SET config=excluded.config').run(JSON.stringify(config));this.db.exec('COMMIT');return config;
  }catch(e){this.db.exec('ROLLBACK');throw e;}
 }
 public(req,res,path){
  if(path!==LAUNCH_PATH&&!path.startsWith(ASSET_PATH))return false;
  check(req.method==='GET'||req.method==='HEAD',405,'此接口只支持读取。');
  if(path===LAUNCH_PATH){res.setHeader('Cache-Control','no-store');res.setHeader('Content-Type','application/json; charset=utf-8');res.writeHead(200);res.end(req.method==='HEAD'?undefined:JSON.stringify(this.get()));return true;}
  const match=/^\/api\/v3\/app\/launch-assets\/([a-f0-9]{64})\.(png|jpg)$/.exec(path);check(match,404,'图片不存在。');
  const row=this.db.prepare('SELECT * FROM app_launch_assets WHERE sha=?').get(match[1]);check(row&&((row.mime==='image/png')===(match[2]==='png')),404,'图片不存在。');
  res.setHeader('Cache-Control','public, max-age=31536000, immutable');res.setHeader('ETag','"'+row.sha+'"');res.setHeader('Content-Type',row.mime);res.setHeader('Content-Length',row.body.length);
  if(req.headers['if-none-match']==='"'+row.sha+'"'){res.writeHead(304);res.end();}else{res.writeHead(200);res.end(req.method==='HEAD'?undefined:row.body);}return true;
 }
}
