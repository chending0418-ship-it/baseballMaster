import {createHash,randomUUID,randomBytes} from 'node:crypto';
const hash=v=>createHash('sha256').update(v).digest('hex');
const secret=v=>typeof v==='string' && /^[A-Za-z0-9_-]{43}$/.test(v) && Buffer.from(v,'base64url').toString('base64url')===v;
const uuid=v=>typeof v==='string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(v);
const label=v=>typeof v==='string' && v.trim().length>0 && v.length<=80 && !/[\u0000-\u001f\u007f]/.test(v);
const words=['松树','海风','星光','沙滩','山峰','竹林','白云','月亮','太阳','河流','蓝天','森林','露珠','雪花','青山','灯塔','草原','晨曦','枫叶','春雨','秋风','飞鸟','湖泊','清泉','山谷','麦田','彩虹','石桥','海浪','果园','银杏','萤火'];
export class TeamSecurity {
 constructor(store,check) {
  this.store=store;this.db=store.db;this.check=check;
  this.db.exec(`CREATE TABLE IF NOT EXISTS device_pairings(id TEXT PRIMARY KEY,token_hash TEXT UNIQUE NOT NULL,target_device TEXT NOT NULL REFERENCES devices(id),
   phrase TEXT NOT NULL,expires_at INTEGER NOT NULL,status TEXT NOT NULL,team_id TEXT,member_id TEXT,member_revision INTEGER,source_device TEXT);
   CREATE TABLE IF NOT EXISTS team_recovery(team_id TEXT PRIMARY KEY REFERENCES teams(id),code_hash TEXT UNIQUE NOT NULL,version INTEGER NOT NULL,created_at INTEGER NOT NULL);`);
  for(const [table,column,type] of [['member_devices','revision','INTEGER NOT NULL DEFAULT 1'],['teams','access_epoch','INTEGER NOT NULL DEFAULT 1'],['viewers','device_id','TEXT'],['team_recovery','needs_rotation','INTEGER NOT NULL DEFAULT 0']]) {
   if(!this.db.prepare(`PRAGMA table_info(${table})`).all().some(c=>c.name===column)) this.db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${type}`);
  }
 }
 initializeRecovery(team,token) {
  if(token===undefined)return 0;
  this.check(secret(token));this.check(!this.db.prepare('SELECT 1 FROM team_recovery WHERE code_hash=?').get(hash(token)),409,'token_reused','请生成新的恢复码。');
  this.db.prepare('INSERT INTO team_recovery(team_id,code_hash,version,created_at) VALUES(?,?,1,?)').run(team,hash(token),this.store.now());return 1;
 }
 pairingRow(pair,device) {
  const row=this.db.prepare('SELECT * FROM device_pairings WHERE id=?').get(pair);
  this.check(row && row.target_device===device,404,'not_found','配对请求不可用。');return row;
 }
 pairingResult(row) {
  const usable=['pending','offered'].includes(row.status) && row.expires_at<=this.store.now()?'expired':row.status;
  let details={};
  if(['offered','accepted'].includes(usable)) {
   const m=this.db.prepare('SELECT * FROM members WHERE id=? AND active=1').get(row.member_id);
   const source=this.db.prepare('SELECT active FROM member_devices WHERE team_id=? AND device_id=? AND member_id=?').get(row.team_id,row.source_device,row.member_id);
   const target=this.db.prepare('SELECT active FROM member_devices WHERE team_id=? AND device_id=? AND member_id=?').get(row.team_id,row.target_device,row.member_id);
   if(!m || (usable==='offered' && (!source?.active || m.revision!==row.member_revision)) || (usable==='accepted' && !target?.active)) return {id:row.id,status:'revoked',expiresAt:row.expires_at,phrase:row.phrase};
   details={teamID:row.team_id,teamName:this.db.prepare('SELECT name FROM teams WHERE id=?').get(row.team_id).name,memberID:m.id,nickname:m.nickname,role:m.role,memberRevision:m.revision};
  }
  return {id:row.id,status:usable,expiresAt:row.expires_at,phrase:row.phrase,targetLabel:this.db.prepare('SELECT label FROM devices WHERE id=?').get(row.target_device).label,...details};
 }
 createPair(device,body) {
  this.check(secret(body.token) && (body.label===undefined || label(body.label)));
  const result=this.store.operation(device,body,'createPair',()=>{},()=>{
   this.check(!this.db.prepare('SELECT 1 FROM device_pairings WHERE token_hash=?').get(hash(body.token)),409,'token_reused','请生成新的配对请求。');
   if(body.label)this.db.prepare('UPDATE devices SET label=? WHERE id=?').run(body.label.trim(),device);
   // A fresh request supersedes this device's unconsumed request only.
   this.db.prepare("UPDATE device_pairings SET status='cancelled' WHERE target_device=? AND status IN ('pending','offered')").run(device);
   const id=randomUUID(),expires=this.store.now()+600000,bytes=createHash('sha256').update(body.token+id+device).digest();
   const phrase=Array.from(bytes.subarray(0,4),n=>words[n%words.length]).join(' · ');
   this.db.prepare("INSERT INTO device_pairings(id,token_hash,target_device,phrase,expires_at,status) VALUES(?,?,?,?,?,'pending')").run(id,hash(body.token),device,phrase,expires);
   return {id};
  });return this.pairingResult(this.pairingRow(result.id,device));
 }
 previewPair(team,device,body) {
  const m=this.store.membership(team,device);this.check(secret(body.token));
  const row=this.db.prepare('SELECT * FROM device_pairings WHERE token_hash=?').get(hash(body.token));
  this.check(row && row.target_device!==device && row.status==='pending' && row.expires_at>this.store.now(),410,'pairing_invalid','配对已失效，或需要在另一台设备核对。');
  const linked=this.db.prepare('SELECT active FROM member_devices WHERE team_id=? AND device_id=?').get(team,row.target_device);
  this.check(!linked?.active,409,'already_joined','新设备已加入此队，不能通过配对合并不同成员。');
  return {...this.pairingResult(row),teamID:team,teamName:this.db.prepare('SELECT name FROM teams WHERE id=?').get(team).name,memberID:m.id,nickname:m.nickname,role:m.role,memberRevision:m.revision};
 }
 approvePair(team,pair,device,body) {
  this.check(secret(body.token) && typeof body.phrase==='string');
  const result=this.store.operation(device,body,`approvePair:${team}:${pair}`,()=>{
   const m=this.store.membership(team,device),row=this.db.prepare('SELECT * FROM device_pairings WHERE id=? AND token_hash=?').get(pair,hash(body.token));
   this.check(row && ['pending','offered','accepted'].includes(row.status) && (row.status==='accepted' || row.expires_at>this.store.now()),410,'pairing_invalid','配对已失效，请重新申请。');
   this.check(body.memberRevision===m.revision && row.phrase===body.phrase && (!row.member_id || row.member_id===m.id && row.source_device===device && row.team_id===team),409,'pairing_changed','成员或配对已有变化，请重新核对。');
  },()=>{
   const preview=this.previewPair(team,device,body);this.check(preview.id===pair,409,'pairing_changed','配对请求不一致。');
   this.db.prepare("UPDATE device_pairings SET status='offered',team_id=?,member_id=?,member_revision=?,source_device=? WHERE id=?").run(team,preview.memberID,preview.memberRevision,device,pair);
   this.store.audit(team,device,'devicePairingApproved',pair);return {id:pair};
  });return this.pairingResult(this.db.prepare('SELECT * FROM device_pairings WHERE id=?').get(result.id));
 }
 acceptPair(pair,device,body) {
  const result=this.store.operation(device,body,`acceptPair:${pair}`,()=>{
   const row=this.pairingRow(pair,device),value=this.pairingResult(row);
   this.check(['offered','accepted'].includes(value.status) && body.phrase===row.phrase && body.memberRevision===row.member_revision,410,'pairing_invalid','配对已失效或成员资料已变化，请重新申请。');
  },()=>{
   const row=this.pairingRow(pair,device);this.check(row.status==='offered',410,'pairing_invalid','配对已被使用。');
   const current=this.store.membership(row.team_id,row.source_device);
   this.check(current.id===row.member_id && current.revision===row.member_revision,409,'pairing_changed','原设备授权已有变化。');
   const linked=this.db.prepare('SELECT * FROM member_devices WHERE team_id=? AND device_id=?').get(row.team_id,device);
   this.check(!linked?.active,409,'already_joined','此设备已加入球队。');
   this.check(this.store.listTeams(device).teams.length<this.store.maxTeamsPerDevice,409,'capacity','当前设备球队已达上限。');
   this.check(this.db.prepare('SELECT COUNT(*) AS n FROM member_devices WHERE member_id=? AND active=1').get(row.member_id).n<5,409,'capacity','每位成员最多关联 5 台设备，请先移除不再使用的设备。');
   this.db.prepare(`INSERT INTO member_devices(team_id,member_id,device_id) VALUES(?,?,?) ON CONFLICT(team_id,device_id)
    DO UPDATE SET member_id=excluded.member_id,active=1,revision=member_devices.revision+1`).run(row.team_id,row.member_id,device);
   this.db.prepare("UPDATE device_pairings SET status='accepted' WHERE id=?").run(pair);
   this.store.audit(row.team_id,device,'devicePaired',device);return {id:pair};
  });return this.pairingResult(this.pairingRow(result.id,device));
 }
 cancelPair(pair,device,body) {
  return this.store.operation(device,body,`cancelPair:${pair}`,()=>{
   const row=this.db.prepare('SELECT * FROM device_pairings WHERE id=?').get(pair);
   this.check(row && (row.target_device===device || row.source_device===device),404,'not_found','配对请求不可用。');
   if(row.source_device===device)this.store.membership(row.team_id,device);
  },()=>{
   const row=this.db.prepare('SELECT * FROM device_pairings WHERE id=?').get(pair);
   this.check(row.status!=='accepted',409,'pairing_completed','配对已完成，请到设备列表移除。');
   this.db.prepare("UPDATE device_pairings SET status='cancelled' WHERE id=?").run(pair);
   if(row.team_id)this.store.audit(row.team_id,device,'devicePairingCancelled',pair);return {cancelled:true};
  });
 }
 devices(team,device) {
  const m=this.store.membership(team,device);
  return {devices:this.db.prepare(`SELECT d.id,d.label,m.id AS memberID,m.nickname,m.role,md.revision,d.id=? AS isCurrent FROM member_devices md
   JOIN members m ON m.id=md.member_id JOIN devices d ON d.id=md.device_id WHERE md.team_id=? AND md.active=1 AND m.active=1 AND d.revoked=0
   AND (?='admin' OR m.id=?) ORDER BY m.nickname,d.created_at,d.id`).all(device,team,m.role,m.id)};
 }
 removeDevice(team,target,device,body) {
  this.check(uuid(body.memberID) && Number.isSafeInteger(body.baseRevision));
  // Self-removal has a terminal, device-bound receipt. It never restores membership.
  const prior=this.db.prepare('SELECT result FROM operations WHERE device_id=? AND operation_id=?').get(device,body.operationID);
  return this.store.operation(device,body,`removeDevice:${team}:${target}`,()=>{
   if(target===device && prior) {const r=JSON.parse(prior.result);this.check(r.removed===true && r.teamID===team && r.deviceID===target,404,'not_found','设备不可用。');return;}
   const m=this.store.membership(team,device);this.check(m.role==='admin' || m.id===body.memberID,403,'forbidden','只能移除自己的设备。');
  },()=>{
   const row=this.db.prepare(`SELECT md.*,m.role FROM member_devices md JOIN members m ON m.id=md.member_id WHERE md.team_id=? AND md.device_id=? AND md.active=1 AND m.active=1`).get(team,target);
   this.check(row && row.member_id===body.memberID,404,'not_found','设备不可用。');this.check(row.revision===body.baseRevision,409,'revision_conflict','设备授权已更新，请重新核对。');
   const admins=this.db.prepare(`SELECT COUNT(*) n FROM member_devices md JOIN members m ON m.id=md.member_id JOIN devices d ON d.id=md.device_id WHERE md.team_id=? AND md.active=1 AND m.active=1 AND m.role='admin' AND d.revoked=0`).get(team).n;
   this.check(row.role!=='admin' || admins>1,409,'last_admin_device','这是最后一台有效管理设备，请先配对新设备或指定其他管理员。');
   this.db.prepare('UPDATE member_devices SET active=0,revision=revision+1 WHERE team_id=? AND device_id=?').run(team,target);
   if(!this.db.prepare('SELECT 1 FROM member_devices WHERE member_id=? AND active=1').get(row.member_id)) this.db.prepare('UPDATE members SET active=0,revision=revision+1 WHERE id=?').run(row.member_id);
   this.db.prepare("UPDATE device_pairings SET status='revoked' WHERE team_id=? AND ((source_device=? AND status IN ('pending','offered')) OR target_device=?)").run(team,target,target);
   for(const g of this.db.prepare('SELECT * FROM shared_games WHERE team_id=? AND writer_device=?').all(team,target)) {
    this.db.prepare('UPDATE shared_games SET epoch=epoch+1 WHERE id=?').run(g.id);this.db.prepare('DELETE FROM handoffs WHERE game_id=?').run(g.id);this.store.games.changed(g,device,'writerDeviceRemoved');
   }
   const affected=this.db.prepare('SELECT g.* FROM shared_games g JOIN handoffs h ON h.game_id=g.id WHERE g.team_id=? AND h.target_device=?').all(team,target);
   for(const g of affected) {this.db.prepare('DELETE FROM handoffs WHERE game_id=?').run(g.id);this.store.games.changed(g,device,'handoffDeviceRemoved');}
   this.db.prepare("UPDATE viewers SET status='revoked' WHERE game_id IN (SELECT id FROM shared_games WHERE team_id=?) AND (device_id=? OR (member_id=? AND device_id IS NULL))").run(team,target,row.member_id);
   this.db.prepare('DELETE FROM member_view_tickets WHERE device_id=? AND game_id IN (SELECT id FROM shared_games WHERE team_id=?)').run(target,team);
   if(row.role==='admin')this.invalidateRecovery(team,device);
   this.store.audit(team,device,'teamDeviceRemoved',target);return {removed:true,teamID:team,deviceID:target};
  });
 }
 invalidateRecovery(team,device) {
  const row=this.db.prepare('SELECT version FROM team_recovery WHERE team_id=?').get(team);
  if(!row)return;
  this.db.prepare('UPDATE team_recovery SET code_hash=?,version=version+1,needs_rotation=1,created_at=? WHERE team_id=?').run(hash(randomBytes(32)),this.store.now(),team);
  this.store.audit(team,device,'recoveryCodeInvalidated',team);
 }
 readPair(pair,device) {
  const row=this.db.prepare('SELECT * FROM device_pairings WHERE id=?').get(pair);
  this.check(row && (row.target_device===device || row.source_device===device),404,'not_found','配对请求不可用。');
  if(row.source_device===device) { const member=this.store.membership(row.team_id,device);this.check(member.id===row.member_id,404,'not_found','配对请求不可用。'); }
  return this.pairingResult(row);
 }
 recoveryStatus(team,device) {
  this.store.membership(team,device,'manageRecovery');const r=this.db.prepare('SELECT version,created_at AS createdAt,needs_rotation FROM team_recovery WHERE team_id=?').get(team);
  return {teamID:team,teamName:this.db.prepare('SELECT name FROM teams WHERE id=?').get(team).name,version:r?.version??0,createdAt:r?.createdAt??null,hasCode:Boolean(r && !r.needs_rotation)};
 }
 rotateRecovery(team,device,body) {
  this.check(secret(body.newToken) && Number.isSafeInteger(body.baseVersion) && body.confirmReset===true);
  return this.store.operation(device,body,`rotateRecovery:${team}`,()=>this.store.membership(team,device,'manageRecovery'),()=>{
   const current=this.recoveryStatus(team,device);this.check(current.version===body.baseVersion,409,'revision_conflict','恢复码已更新，请刷新。');
   this.check(!this.db.prepare('SELECT 1 FROM team_recovery WHERE code_hash=?').get(hash(body.newToken)),409,'token_reused','请生成新的恢复码。');
   this.db.prepare(`INSERT INTO team_recovery(team_id,code_hash,version,created_at) VALUES(?,?,?,?) ON CONFLICT(team_id) DO UPDATE SET code_hash=excluded.code_hash,version=excluded.version,created_at=excluded.created_at,needs_rotation=0`).run(team,hash(body.newToken),current.version+1,this.store.now());
   this.store.audit(team,device,'recoveryCodeRotated',team);return {...current,version:current.version+1,createdAt:this.store.now(),hasCode:true};
  });
 }
 recoveryPreview(body) {
  this.check(uuid(body.teamID) && secret(body.token));
  this.check(!this.store.lifecycle?.deleted(body.teamID.toLowerCase()),404,'recovery_invalid','球队已解散，请从原管理员设备的已解散球队入口恢复。');
  const row=this.db.prepare('SELECT r.version,t.id AS teamID,t.name AS teamName FROM team_recovery r JOIN teams t ON t.id=r.team_id WHERE r.team_id=? AND r.code_hash=? AND r.needs_rotation=0').get(body.teamID.toLowerCase(),hash(body.token));
  this.check(row,404,'recovery_invalid','恢复资料无效或已更换，请核对保存的恢复卡。');return row;
 }
 recover(device,body) {
  this.check(uuid(body.teamID) && secret(body.token) && secret(body.newToken) && body.token!==body.newToken && label(body.nickname) && body.confirmReset===true);
  const team=body.teamID.toLowerCase();
  return this.store.operation(device,body,`recoverManagement:${team}`,()=>{
   const prior=this.db.prepare('SELECT result FROM operations WHERE device_id=? AND operation_id=?').get(device,body.operationID);
   if(prior) {const result=JSON.parse(prior.result),m=this.store.membership(team,device,'manageRecovery');this.check(result.recovered===true && result.teamID===team && result.memberID===m.id,404,'recovery_invalid','原恢复请求已失效。');}
   else {const preview=this.recoveryPreview(body);this.check(preview.version===body.baseVersion,409,'recovery_changed','恢复码已更新，请重新核对。');}
  },()=>{
   const preview=this.recoveryPreview(body);this.check(preview.version===body.baseVersion,409,'recovery_changed','恢复码已更新。');
   this.check(!this.db.prepare('SELECT 1 FROM team_recovery WHERE code_hash=?').get(hash(body.newToken)),409,'token_reused','请生成新的恢复码。');
   const count=this.store.listTeams(device).teams;this.check(count.some(t=>t.id===team)||count.length<this.store.maxTeamsPerDevice,409,'capacity','当前设备球队已达上限。');
   this.db.prepare('UPDATE members SET active=0,revision=revision+1 WHERE team_id=?').run(team);
   this.db.prepare('UPDATE member_devices SET active=0,revision=revision+1 WHERE team_id=?').run(team);
   const memberID=randomUUID();this.db.prepare("INSERT INTO members(id,team_id,nickname,role) VALUES(?,?,?,'admin')").run(memberID,team,body.nickname.trim());
   this.db.prepare(`INSERT INTO member_devices(team_id,member_id,device_id) VALUES(?,?,?) ON CONFLICT(team_id,device_id) DO UPDATE SET member_id=excluded.member_id,active=1,revision=member_devices.revision+1`).run(team,memberID,device);
   this.db.prepare('UPDATE invitations SET revoked=1 WHERE team_id=?').run(team);
   this.db.prepare("UPDATE device_pairings SET status='revoked' WHERE team_id=?").run(team);
   for(const g of this.db.prepare('SELECT * FROM shared_games WHERE team_id=?').all(team)) {
    this.db.prepare("UPDATE shared_games SET epoch=epoch+1,access_mode='closed',access_version=access_version+1,live_enabled=0,view_code=? WHERE id=?").run(randomBytes(24).toString('base64url'),g.id);
    this.db.prepare('DELETE FROM handoffs WHERE game_id=?').run(g.id);this.store.games.revokeAll(g.id);this.store.games.changed(g,device,'managementRecovered');
   }
   this.db.prepare('UPDATE teams SET revision=revision+1,access_epoch=access_epoch+1 WHERE id=?').run(team);
   this.db.prepare('UPDATE team_recovery SET code_hash=?,version=version+1,created_at=?,needs_rotation=0 WHERE team_id=?').run(hash(body.newToken),this.store.now(),team);
   this.store.audit(team,device,'managementRecovered',memberID);
   return {recovered:true,teamID:team,teamName:preview.teamName,memberID,role:'admin',version:preview.version+1};
  });
 }
}
