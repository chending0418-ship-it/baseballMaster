// Site-owner inspection only; team permissions and business records are never changed here.
import { readFileSync } from 'node:fs';
import { randomBytes, scrypt, timingSafeEqual, createHash } from 'node:crypto';
import { promisify } from 'node:util';
import { isIP } from 'node:net';
import { exportTeam } from './admin-export.mjs';
const derive=promisify(scrypt),digest=v=>createHash('sha256').update(v).digest('hex');
const uid=/^[a-f0-9-]{36}$/i;
class AdminError extends Error{constructor(status,message){super(message);this.status=status;}}
const check=(ok,status=400,message='请求内容不正确。')=>{if(!ok)throw new AdminError(status,message);};
const parsed=(value,fallback={})=>{try{return JSON.parse(value);}catch{return fallback;}};
const page=params=>{const offset=Number(params.get('offset')??0),limit=Number(params.get('limit')??25);check(Number.isSafeInteger(offset)&&offset>=0&&offset<=1000000&&Number.isSafeInteger(limit)&&limit>=1&&limit<=100);return {offset,limit};};
const search=params=>{const q=(params.get('q')??'').trim();check(q.length<=80);return q;};
const dateSQL="COALESCE(json_extract(g.projection,'$.playedAt'),g.updated_at)";
const gameColumns=`g.id,g.team_id AS teamID,t.name AS teamName,g.revision,g.updated_at AS updatedAt,g.deleted,g.purged,g.access_mode AS accessMode,
 ${dateSQL} AS playedAt,json_extract(g.payload,'$.status') AS status,
 json_extract(g.projection,'$.snapshot.away.name') AS awayName,json_extract(g.projection,'$.snapshot.home.name') AS homeName,
 json_extract(g.projection,'$.snapshot.away.runs') AS awayRuns,json_extract(g.projection,'$.snapshot.home.runs') AS homeRuns,
 json_extract(g.projection,'$.snapshot.inning') AS inning,json_extract(g.projection,'$.snapshot.isTop') AS isTop,
 json_extract(g.projection,'$.snapshot.isFinal') AS isFinal,length(CAST(g.payload AS BLOB))+length(CAST(g.projection AS BLOB)) AS bytes`;

export function createAdminHandler(store,options){
 if(!options)return null;
 const {salt,passwordHash,legacyToken,legacyBase='http://127.0.0.1:18088',allowLocal=false,trustProxy=false}=options;
 check(/^[a-f0-9]{32}$/.test(salt)&&/^[a-f0-9]{128}$/.test(passwordHash)&&/^[a-f0-9]{64}$/.test(legacyToken),500,'后台配置无效。');
 const upstream=new URL(legacyBase);check(upstream.protocol==='http:'&&['127.0.0.1','[::1]'].includes(upstream.hostname),500,'直播读取仅允许回环地址。');
 const sessions=new Map(),attempts=new Map();let exporting=0;
 const now=()=>store.now(),cookieName=allowLocal?'bm_admin':'__Secure-bm_admin';
 const cookie=(value,age)=>`${cookieName}=${value}; Path=/admin; HttpOnly; ${allowLocal?'':'Secure; '}SameSite=Strict; Max-Age=${age}`;
 function client(req){const peer=req.socket.remoteAddress,ip=req.headers['x-real-ip'];return trustProxy&&['127.0.0.1','::1','::ffff:127.0.0.1'].includes(peer)&&typeof ip==='string'&&isIP(ip)?ip:peer;}
 function rate(key,max,span){
  for(const [k,v]of attempts)if(v.until<=now())attempts.delete(k);
  check(attempts.has(key)||attempts.size<10000,429,'访问过于频繁，请稍后再试。');
  const bucket=attempts.get(key)??{count:0,until:now()+span};attempts.set(key,bucket);check(++bucket.count<=max,429,'尝试过于频繁，请稍后再试。');
 }
 function authorize(req){
  for(const [k,v]of sessions)if(v.until<=now())sessions.delete(k);
  const raw=(req.headers.cookie??'').split(';').map(v=>v.trim()).find(v=>v.startsWith(cookieName+'='))?.slice(cookieName.length+1);
  check(raw&&/^[A-Za-z0-9_-]{43}$/.test(raw)&&sessions.has(digest(raw)),401,'请先登录管理后台。');return digest(raw);
 }
 async function legacy(path){try{const r=await fetch(legacyBase+'/internal/admin-live'+path,{headers:{Authorization:'Bearer '+legacyToken},signal:AbortSignal.timeout(4000)});
  if(r.status===404)throw new AdminError(404,'直播已关闭或到期。');check(r.ok,503,'临时直播服务暂不可用，请刷新重试。');return await r.json();
 }catch(e){if(e instanceof AdminError)throw e;throw new AdminError(503,'临时直播服务暂不可用，请刷新重试。');}}
 function teams(params){
  const {offset,limit}=page(params),q=search(params),state=params.get('state')??'active';check(['active','dissolved','all'].includes(state));
  const where=`instr(lower(t.name),lower(?))>0 AND (?='all' OR (?='active' AND d.team_id IS NULL) OR (?='dissolved' AND d.team_id IS NOT NULL))`;
  const join='FROM teams t LEFT JOIN team_deletions d ON d.team_id=t.id AND d.restored_at IS NULL',args=[q,state,state,state];
  return {items:store.db.prepare(`SELECT t.id,t.name,t.revision,t.created_at AS createdAt,d.deleted_at AS dissolvedAt,
   (SELECT COUNT(*) FROM members m WHERE m.team_id=t.id AND m.active=1) AS members,
   (SELECT COUNT(*) FROM shared_games g WHERE g.team_id=t.id AND g.purged=0 AND g.deleted=0) AS games,
   (SELECT COALESCE(json_array_length(p.profile,'$.players'),0) FROM team_profiles p WHERE p.team_id=t.id) AS players
   ${join} WHERE ${where} ORDER BY t.created_at DESC,t.id LIMIT ? OFFSET ?`).all(...args,limit,offset),
   total:store.db.prepare(`SELECT COUNT(*) AS n ${join} WHERE ${where}`).get(...args).n,offset,limit};
 }
 function games(params){
  const {offset,limit}=page(params),q=search(params),team=params.get('team')??'',state=params.get('state')??'all';check(!team||uid.test(team));check(['all','ongoing','completed','scheduled','deleted'].includes(state));
  const clauses=['g.purged=0'],args=[];if(team){clauses.push('g.team_id=?');args.push(team);}
  if(state==='deleted')clauses.push('g.deleted=1');else clauses.push('g.deleted=0','NOT EXISTS(SELECT 1 FROM team_deletions d WHERE d.team_id=t.id AND d.restored_at IS NULL)');
  if(['ongoing','completed','scheduled'].includes(state)){clauses.push("json_extract(g.payload,'$.status')=?");args.push(['ongoing','completed','scheduled'].indexOf(state));}
  clauses.push("instr(lower(t.name||' '||COALESCE(json_extract(g.projection,'$.snapshot.away.name'),'')||' '||COALESCE(json_extract(g.projection,'$.snapshot.home.name'),'')),lower(?))>0");args.push(q);
  const from='FROM shared_games g JOIN teams t ON t.id=g.team_id WHERE '+clauses.join(' AND ');
  return {items:store.db.prepare(`SELECT ${gameColumns} ${from} ORDER BY ${dateSQL} DESC,g.id LIMIT ? OFFSET ?`).all(...args,limit,offset),total:store.db.prepare('SELECT COUNT(*) AS n '+from).get(...args).n,offset,limit};
 }
 function teamDetail(id){
  const row=store.db.prepare(`SELECT t.id,t.name,t.created_at AS createdAt,t.revision,p.profile,d.deleted_at AS dissolvedAt FROM teams t
   LEFT JOIN team_profiles p ON p.team_id=t.id LEFT JOIN team_deletions d ON d.team_id=t.id AND d.restored_at IS NULL WHERE t.id=?`).get(id);
  check(row,404,'球队不存在。');const {profile,...team}=row;
  return {team:{...team,...parsed(profile)},members:store.db.prepare(`SELECT m.id,m.nickname,m.role,m.active,
   (SELECT COUNT(*) FROM member_devices md JOIN devices d ON d.id=md.device_id WHERE md.member_id=m.id AND md.active=1 AND d.revoked=0) AS devices
   FROM members m WHERE m.team_id=? ORDER BY m.active DESC,m.role,m.nickname`).all(id),
   storage:store.db.prepare('SELECT COUNT(*) AS games,COALESCE(SUM(length(CAST(payload AS BLOB))+length(CAST(projection AS BLOB))),0) AS bytes FROM shared_games WHERE team_id=? AND purged=0').get(id)};
 }
 function gameDetail(id){const row=store.db.prepare(`SELECT ${gameColumns},g.payload,g.projection,m.nickname AS recorder FROM shared_games g JOIN teams t ON t.id=g.team_id
  LEFT JOIN members m ON m.id=g.responsible_member WHERE g.id=? AND g.purged=0`).get(id);check(row,404,'比赛不存在或正文已清理。');const {payload,projection,...game}=row;return {game,payload:parsed(payload),...parsed(projection)};}
 async function liveList(params){
  const {offset,limit}=page(params),q=search(params),state=params.get('state')??'ongoing';check(['ongoing','completed','all'].includes(state));
  const shared=store.db.prepare(`SELECT ${gameColumns},g.live_expires_at AS expiresAt FROM shared_games g JOIN teams t ON t.id=g.team_id
   WHERE g.live_enabled=1 AND g.live_expires_at>? AND g.deleted=0 AND g.purged=0 AND NOT EXISTS(SELECT 1 FROM team_deletions d WHERE d.team_id=t.id AND d.restored_at IS NULL)`)
   .all(now()).map(row=>({...row,source:'shared'}));
  let old=[],warning=null;try{old=(await legacy('')).items.map(row=>({...row,source:'temporary'}));}catch{warning='临时直播服务暂不可用，当前仅显示共享比赛直播。';}
  const all=[...shared,...old],counts={ongoing:all.filter(v=>!v.isFinal).length,completed:all.filter(v=>v.isFinal).length};
  const rows=all.filter(v=>(state==='all'||Boolean(v.isFinal)===(state==='completed'))&&[v.teamName,v.awayName,v.homeName].join(' ').toLowerCase().includes(q.toLowerCase())).sort((a,b)=>b.updatedAt-a.updatedAt||a.id.localeCompare(b.id));
  return {items:rows.slice(offset,offset+limit),total:rows.length,offset,limit,counts,warning};
 }
 async function requestBody(req){check((req.headers['content-type']??'').split(';')[0]==='application/json',415);let n=0;const chunks=[];
  for await(const part of req){n+=part.length;check(n<=2048,413);chunks.push(part);}let body;try{body=JSON.parse(Buffer.concat(chunks).toString());}catch{throw new AdminError(400,'登录资料格式不正确。');}check(body&&typeof body==='object'&&!Array.isArray(body));return body;}
 return async function(req,res){
  const url=new URL(req.url,'http://localhost'),path=url.pathname;
  res.setHeader('Cache-Control','no-store');res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','no-referrer');res.setHeader('X-Robots-Tag','noindex, nofollow, noarchive');res.setHeader('X-Frame-Options','DENY');
  res.setHeader('Content-Security-Policy',"default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
  const send=(status,data)=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify(data));};
  const download=(name,data)=>{res.setHeader('Content-Disposition',`attachment; filename="BaseballMaster-${name}.json"`);send(200,{format:'BaseballMaster Admin Document',version:1,exportedAt:now(),...data});};
  const archive=async(teamID,session)=>{check(store.databasePath!==':memory:',503,'下载服务尚未配置。');check(exporting<2,429,'已有资料正在导出，请稍后再试。');rate('export:'+session,5,60000);exporting++;try{return await exportTeam(store.databasePath,teamID,res,now());}finally{exporting--;}};
  try{
   rate('read:'+client(req),300,60000);const origin=allowLocal?'http://'+req.headers.host:'https://baseballmaster.cc';
   check(!req.headers.origin||req.headers.origin===origin,403,'请从管理后台操作。');check(req.headers['sec-fetch-site']!=='cross-site',403,'请从管理后台操作。');
   if(path==='/admin'&&req.method==='GET'){res.writeHead(308,{Location:'/admin/'});res.end();return;}
   const asset=/^\/admin\/(index.html|app.js|style.css|app-icon.png)?$/.exec(path);
   if(asset&&req.method==='GET'){const name=asset[1]||'index.html',type=name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':name.endsWith('.png')?'image/png':'text/html';res.writeHead(200,{'Content-Type':type+'; charset=utf-8'});res.end(readFileSync(new URL('./admin/public/'+name,import.meta.url)));return;}
   if(path==='/admin/api/login'&&req.method==='POST'){
    check(req.headers.origin===origin,403,'请从登录页面操作。');rate('login:'+client(req),8,15*60000);rate('login:global',60,60000);
    const body=await requestBody(req);check(typeof body.password==='string'&&body.password.length<=256);
    const key=await derive(body.password,salt,64);check(timingSafeEqual(key,Buffer.from(passwordHash,'hex')),401,'密码不正确。');
    for(const [key,value]of sessions)if(value.until<=now())sessions.delete(key);check(sessions.size<100,429,'登录会话过多，请稍后再试。');
    const token=randomBytes(32).toString('base64url');sessions.set(digest(token),{until:now()+3600000});attempts.delete('login:'+client(req));res.setHeader('Set-Cookie',cookie(token,3600));return send(200,{expiresAt:now()+3600000});
   }
   const session=authorize(req);
   if(path==='/admin/api/logout'&&req.method==='POST'){check(req.headers.origin===origin,403);sessions.delete(session);res.setHeader('Set-Cookie',cookie('',0));return send(200,{ok:true});}
   check(req.method==='GET',405,'管理后台仅支持查看和下载。');
   if(path==='/admin/api/session')return send(200,{expiresAt:sessions.get(session).until});
   if(path==='/admin/api/teams')return send(200,teams(url.searchParams));
   if(path==='/admin/api/games')return send(200,games(url.searchParams));
   if(path==='/admin/api/live')return send(200,await liveList(url.searchParams));
   if(path==='/admin/api/export')return await archive(null,session);
   if(path==='/admin/api/overview'){const live=await liveList(new URLSearchParams('limit=1'));return send(200,{teams:teams(new URLSearchParams('limit=1')).total,games:games(new URLSearchParams('limit=1')).total,live:live.counts,warning:live.warning,updatedAt:now(),bytes:store.db.prepare('SELECT COALESCE(SUM(length(CAST(payload AS BLOB))+length(CAST(projection AS BLOB))),0) AS n FROM shared_games WHERE purged=0').get().n});}
   const team=/^\/admin\/api\/teams\/([a-f0-9-]{36})(\/download)?$/i.exec(path);
   if(team){if(!team[2])return send(200,teamDetail(team[1]));return await archive(team[1],session);}
   const game=/^\/admin\/api\/games\/([a-f0-9-]{36})(\/download)?$/i.exec(path);if(game){const data=gameDetail(game[1]);return game[2]?download('Game-'+game[1],data):send(200,data);}
   const live=/^\/admin\/api\/live\/(shared|temporary)\/([a-zA-Z0-9-]+)(\/download)?$/.exec(path);
   if(live){let data;if(live[1]==='temporary')data=await legacy('/'+encodeURIComponent(live[2]));else{check(uid.test(live[2]));const row=store.db.prepare(`SELECT g.live_enabled,g.live_expires_at,g.deleted,g.purged FROM shared_games g WHERE g.id=? AND NOT EXISTS(SELECT 1 FROM team_deletions d WHERE d.team_id=g.team_id AND d.restored_at IS NULL)`).get(live[2]);check(row?.live_enabled&&!row.deleted&&!row.purged&&row.live_expires_at>now(),404,'直播已关闭或到期。');data=gameDetail(live[2]);}return live[3]?download('Live-'+live[2],data):send(200,data);}
   throw new AdminError(404,'页面不存在。');
  }catch(e){if(res.headersSent){res.destroy();return;}send(e instanceof AdminError?e.status:503,{error:e instanceof AdminError?e.message:'后台服务暂不可用，请稍后刷新。'});}
 };
}
