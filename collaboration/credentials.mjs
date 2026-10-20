import { createHash } from 'node:crypto';
const hash=v=>createHash('sha256').update(v).digest('hex');
const secret=v=>typeof v==='string' && /^[A-Za-z0-9_-]{43}$/.test(v) && Buffer.from(v,'base64url').toString('base64url')===v;
const uuid=v=>typeof v==='string' && /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(v);
export class DeviceCredentials {
  constructor(store,check) {
    this.store=store;this.db=store.db;this.check=check;
    this.db.exec(`CREATE TABLE IF NOT EXISTS credential_rotations(id TEXT PRIMARY KEY,device_id TEXT NOT NULL REFERENCES devices(id),
      new_hash TEXT UNIQUE NOT NULL,old_hash TEXT NOT NULL,created_at INTEGER NOT NULL,committed INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS retired_credentials(credential_hash TEXT PRIMARY KEY,device_id TEXT NOT NULL,retired_at INTEGER NOT NULL);`);
  }
  prepare(device,body) {
    this.check(secret(body.newToken));
    return this.store.operation(device,body,'rotateDeviceCredential',()=>{},()=>{
      const current=this.db.prepare('SELECT credential_hash FROM devices WHERE id=? AND revoked=0').get(device),next=hash(body.newToken);
      this.check(current && current.credential_hash!==next,409,'credential_changed','设备授权已更新，请重新核对。');
      this.check(!this.db.prepare('SELECT 1 FROM devices WHERE credential_hash=?').get(next) && !this.db.prepare('SELECT 1 FROM retired_credentials WHERE credential_hash=?').get(next) && !this.db.prepare('SELECT 1 FROM credential_rotations WHERE new_hash=?').get(next),409,'token_reused','请生成新的设备授权。');
      this.db.prepare('INSERT INTO credential_rotations(id,device_id,new_hash,old_hash,created_at) VALUES(?,?,?,?,?)')
        .run(body.operationID.toLowerCase(),device,next,current.credential_hash,this.store.now());
      return {rotationID:body.operationID.toLowerCase()};
    });
  }
  commit(token,body) {
    this.check(secret(token) && uuid(body.rotationID),401,'unauthorized','设备授权无效。');
    return this.store.transaction(()=>{
      const row=this.db.prepare('SELECT * FROM credential_rotations WHERE id=? AND new_hash=?').get(body.rotationID.toLowerCase(),hash(token));
      this.check(row,404,'rotation_missing','设备授权更新尚未准备。');
      const device=this.db.prepare('SELECT * FROM devices WHERE id=? AND revoked=0').get(row.device_id);
      this.check(device,401,'unauthorized','设备授权已失效。');
      if(row.committed) {this.check(device.credential_hash===row.new_hash,401,'unauthorized','设备授权已再次更新。');return {deviceID:device.id,rotated:true};}
      if(this.db.prepare("SELECT value FROM metadata WHERE key='needsDeviceRecoveryReview'").get()?.value==='1') {
        this.check(this.db.prepare('SELECT epoch FROM device_recovery_reviews WHERE device_id=?').get(device.id)?.epoch===this.db.prepare("SELECT value FROM metadata WHERE key='recoveryEpoch'").get().value,409,'recovery_review','请先核对恢复后的球队资料。');
      }
      this.check(this.store.now()-row.created_at<86400000,410,'rotation_expired','本次更新已过期，请重新发起。');
      this.check(device.credential_hash===row.old_hash,409,'credential_changed','设备授权已更新。');
      this.db.prepare('INSERT INTO retired_credentials VALUES(?,?,?)').run(row.old_hash,device.id,this.store.now());
      this.db.prepare('UPDATE devices SET credential_hash=? WHERE id=?').run(row.new_hash,device.id);
      this.db.prepare('UPDATE credential_rotations SET committed=1 WHERE id=?').run(row.id);
      for(const team of this.store.listTeams(device.id).teams)this.store.audit(team.id,device.id,'deviceCredentialRotated',device.id);
      return {deviceID:device.id,rotated:true};
    });
  }
}
