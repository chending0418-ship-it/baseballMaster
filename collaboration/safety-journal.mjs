import { createCipheriv,createDecipheriv,createHash,randomBytes } from 'node:crypto';
import { mkdirSync,readFileSync,writeFileSync,renameSync,readdirSync,openSync,closeSync,fsyncSync,existsSync } from 'node:fs';
import { join } from 'node:path';

const digest = bytes => createHash('sha256').update(bytes).digest('hex');
export function seal(bytes,key) {
  const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,iv);cipher.setAAD(Buffer.from('BaseballMaster private archive v1'));
  const encrypted=Buffer.concat([cipher.update(bytes),cipher.final()]);
  return Buffer.concat([Buffer.from('BMSEALED1'),iv,cipher.getAuthTag(),encrypted]);
}
export function unseal(bytes,key) {
  if(bytes.length<37 || bytes.subarray(0,9).toString()!=='BMSEALED1')throw new Error('Unsupported private archive');
  const decipher=createDecipheriv('aes-256-gcm',key,bytes.subarray(9,21));decipher.setAAD(Buffer.from('BaseballMaster private archive v1'));decipher.setAuthTag(bytes.subarray(21,37));
  return Buffer.concat([decipher.update(bytes.subarray(37)),decipher.final()]);
}
export function durableWrite(path,bytes) {
  const pending=path+'.'+randomBytes(8).toString('hex')+'.partial';
  writeFileSync(pending,bytes,{mode:0o600,flag:'wx'});
  const fd=openSync(pending,'r');try{fsyncSync(fd);}finally{closeSync(fd);}
  renameSync(pending,path);
}

// A denial is durably written to independent storage BEFORE the DB commit and HTTP acknowledgement.
// A failed commit may leave a conservative denial. It must never revive access during a restore.
// Records contain identifiers and revisions, not match bodies, names, bearer tokens or recovery codes.
export class SafetyJournal {
  constructor({directory,key,journalID,create=false}) {
    if(key.length!==32 || !journalID)throw new Error('Invalid safety journal configuration');
    this.directory=directory;this.key=key;this.journalID=journalID;this.seen=new Set();
    const identity=join(directory,'identity.sealed');
    if(create && !existsSync(identity)) {mkdirSync(directory,{recursive:true,mode:0o700});durableWrite(identity,seal(Buffer.from(JSON.stringify({journalID})),key));}
    if(JSON.parse(unseal(readFileSync(identity),key)).journalID!==journalID)throw new Error('Safety journal identity does not match');
    for(const record of this.records())this.seen.add(digest(Buffer.from(JSON.stringify(record))));
  }
  records() {
    const result=[];
    for(const file of readdirSync(this.directory))if(/^[a-f0-9]{64}\.sealed$/.test(file)) {
      const bytes=unseal(readFileSync(join(this.directory,file)),this.key);
      if(digest(bytes)+'.sealed'!==file)throw new Error('Safety journal record does not match');
      const decoded=JSON.parse(bytes);
      if(decoded.version===1 && Array.isArray(decoded.denials))result.push(...decoded.denials);
      else result.push(decoded);
    }
    return result;
  }
  record(store) {
    const db=store.db,records=[];
    for(const r of db.prepare('SELECT id,revision,updated_at AS deletedAt FROM shared_games WHERE deleted=1').all())records.push({kind:'game',...r});
    for(const r of db.prepare("SELECT id,revision FROM members WHERE active=0 OR role<>'admin'").all())records.push({kind:'member',...r});
    for(const r of db.prepare('SELECT team_id AS teamID,device_id AS deviceID,revision FROM member_devices WHERE active=0').all())records.push({kind:'device',...r});
    for(const r of db.prepare('SELECT team_id AS teamID,version FROM team_recovery WHERE version>1 OR needs_rotation=1').all())records.push({kind:'recovery',...r});
    for(const r of db.prepare('SELECT t.id,t.revision,d.deleted_at AS deletedAt FROM teams t JOIN team_deletions d ON d.team_id=t.id WHERE d.restored_at IS NULL').all())records.push({kind:'team',...r});
    for(const r of db.prepare('SELECT credential_hash AS credentialHash,device_id AS deviceID,retired_at AS retiredAt FROM retired_credentials').all())records.push({kind:'credential',...r});
    // Validate the mount/identity even when no new denial is needed, but reads never enter transactions.
    const identity=JSON.parse(unseal(readFileSync(join(this.directory,'identity.sealed')),this.key));
    if(identity.journalID!==this.journalID)throw new Error('Safety journal unavailable');
    const fresh=records.filter(r=>!this.seen.has(digest(Buffer.from(JSON.stringify(r)))));
    if(!fresh.length)return;
    // One independent-storage write for the entire transaction, including a large team dissolution.
    const bytes=Buffer.from(JSON.stringify({version:1,denials:fresh})),name=digest(bytes)+'.sealed';
    durableWrite(join(this.directory,name),seal(bytes,this.key));
    for(const record of fresh)this.seen.add(digest(Buffer.from(JSON.stringify(record))));
  }
}
