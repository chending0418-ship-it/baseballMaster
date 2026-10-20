import { DatabaseSync,backup } from 'node:sqlite';
import { createCipheriv,createDecipheriv,randomBytes,randomUUID,createHash } from 'node:crypto';
import { createReadStream,createWriteStream,existsSync,mkdirSync,mkdtempSync,readFileSync,writeFileSync,openSync,closeSync,fsyncSync,writeSync,rmSync,renameSync,statSync } from 'node:fs';
import { dirname,join,resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { pipeline } from 'node:stream/promises';
import { createGzip,createGunzip } from 'node:zlib';
import { SafetyJournal } from './safety-journal.mjs';

const aad=Buffer.from('BaseballMaster private archive v1');
const ensure=(condition,message)=>{if(!condition)throw new Error(message);};
export function readBackupKey(path) {
  ensure((statSync(path).mode&0o077)===0,'Backup key permissions must be private');
  const key=readFileSync(path);ensure(key.length===32,'Invalid backup key');return key;
}
function integrity(path) {
  const db=new DatabaseSync(path,{readOnly:true});
  try {
    ensure(db.prepare('PRAGMA integrity_check').get().integrity_check==='ok','Database integrity check failed');
    ensure(db.prepare("SELECT value FROM metadata WHERE key='schema'").get()?.value==='1','Unsupported database schema');
    ensure(db.prepare('PRAGMA foreign_key_check').all().length===0,'Database relationship check failed');
  }finally{db.close();}
}
async function encryptFile(input,output,key) {
  const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,iv);cipher.setAAD(aad);
  writeFileSync(output,Buffer.concat([Buffer.from('BMSEALED1'),iv,Buffer.alloc(16)]),{flag:'wx',mode:0o600});
  await pipeline(createReadStream(input),createGzip({level:6}),cipher,createWriteStream(output,{flags:'a'}));
  const fd=openSync(output,'r+');try{writeSync(fd,cipher.getAuthTag(),0,16,21);fsyncSync(fd);}finally{closeSync(fd);}
}
async function decryptFile(input,output,key) {
  const fd=openSync(input,'r'),header=Buffer.alloc(37);
  try{const {readSync}=await import('node:fs');ensure(readSync(fd,header,0,37,0)===37,'Incomplete backup');}finally{closeSync(fd);}
  ensure(header.subarray(0,9).toString()==='BMSEALED1','Unsupported backup format');
  const decipher=createDecipheriv('aes-256-gcm',key,header.subarray(9,21));decipher.setAAD(aad);decipher.setAuthTag(header.subarray(21,37));
  await pipeline(createReadStream(input,{start:37}),decipher,createGunzip(),createWriteStream(output,{flags:'wx',mode:0o600}));
}
export async function createEncryptedBackup({database,destination,key}) {
  ensure(!existsSync(destination),'Backup destination already exists');
  mkdirSync(dirname(destination),{recursive:true,mode:0o700});
  const scratch=mkdtempSync(join(dirname(database),'.backup-')),snapshot=join(scratch,'snapshot.sqlite'),pending=destination+'.'+randomUUID()+'.partial';
  try {
    const source=new DatabaseSync(database,{readOnly:true});try{await backup(source,snapshot);}finally{source.close();}
    const compact=new DatabaseSync(snapshot);try{compact.exec('PRAGMA journal_mode=DELETE; VACUUM;');}finally{compact.close();}
    integrity(snapshot);await encryptFile(snapshot,pending,key);
    const verified=join(scratch,'verified.sqlite');await decryptFile(pending,verified,key);integrity(verified);
    ensure(statSync(snapshot).size===statSync(verified).size,'Backup round-trip size mismatch');
    renameSync(pending,destination);
    return {databaseBytes:statSync(snapshot).size,backupBytes:statSync(destination).size,verified:true};
  }finally{rmSync(scratch,{recursive:true,force:true});rmSync(pending,{force:true});}
}

export function applyRecoveryFences(store,journal) {
  const records=journal.records(),db=store.db;
  // The restored database knows which independent journal belongs to it.
  ensure(db.prepare("SELECT value FROM metadata WHERE key='safetyJournalID'").get()?.value===journal.journalID,'Restore journal does not match the database');
  return store.transaction(()=>{
    let denied=0;
    for(const r of records) {
      ensure(['game','member','device','team','recovery','credential'].includes(r.kind),'Unknown recovery safety record');
      if(r.kind==='credential') {
        db.prepare('INSERT OR IGNORE INTO retired_credentials VALUES(?,?,?)').run(r.credentialHash,r.deviceID,r.retiredAt);
        // Old credentials stay revoked even when their replacement was created after the backup.
        db.prepare('UPDATE devices SET revoked=1 WHERE credential_hash=?').run(r.credentialHash);denied++;
      } else if(r.kind==='member') {
        const row=db.prepare('SELECT revision FROM members WHERE id=?').get(r.id);
        if(row && row.revision<r.revision) {
          db.prepare('UPDATE members SET active=0,revision=? WHERE id=?').run(r.revision+1,r.id);
          db.prepare('UPDATE member_devices SET active=0,revision=revision+1 WHERE member_id=?').run(r.id);denied++;
        }
      } else if(r.kind==='device') {
        const row=db.prepare('SELECT revision FROM member_devices WHERE team_id=? AND device_id=?').get(r.teamID,r.deviceID);
        if(row && row.revision<r.revision){db.prepare('UPDATE member_devices SET active=0,revision=? WHERE team_id=? AND device_id=?').run(r.revision+1,r.teamID,r.deviceID);denied++;}
      } else if(r.kind==='recovery') {
        const row=db.prepare('SELECT version FROM team_recovery WHERE team_id=?').get(r.teamID);
        if(row && row.version<r.version){db.prepare('UPDATE team_recovery SET version=?,needs_rotation=1,code_hash=? WHERE team_id=?').run(r.version+1,createHash('sha256').update(randomBytes(32)).digest('hex'),r.teamID);denied++;}
      } else if(r.kind==='game') {
        const row=db.prepare('SELECT revision FROM shared_games WHERE id=?').get(r.id);
        if(row && row.revision<r.revision){db.prepare('UPDATE shared_games SET deleted=1,revision=?,updated_at=? WHERE id=?').run(r.revision+1,r.deletedAt,r.id);denied++;}
      } else if(r.kind==='team') {
        const row=db.prepare('SELECT revision FROM teams WHERE id=?').get(r.id);
        if(row && row.revision<r.revision) {
          db.prepare('UPDATE teams SET revision=? WHERE id=?').run(r.revision+1,r.id);
          db.prepare('UPDATE members SET active=0,revision=revision+1 WHERE team_id=?').run(r.id);
          db.prepare('UPDATE member_devices SET active=0,revision=revision+1 WHERE team_id=?').run(r.id);
          db.prepare('UPDATE shared_games SET deleted=1,revision=revision+1,updated_at=CASE WHEN deleted=1 THEN MIN(updated_at,?) ELSE ? END WHERE team_id=?').run(r.deletedAt,r.deletedAt,r.id);
          db.prepare('INSERT INTO team_deletions VALUES(?,?,NULL) ON CONFLICT(team_id) DO UPDATE SET deleted_at=excluded.deleted_at,restored_at=NULL').run(r.id,r.deletedAt);
          db.prepare('DELETE FROM team_restore_devices WHERE team_id=?').run(r.id);denied++;
        }
      }
    }
    // External links, invitations, pending pairings and old operation receipts never reopen.
    for(const {id:game} of db.prepare('SELECT id FROM shared_games').all()) {
      db.prepare("UPDATE shared_games SET revision=revision+1,epoch=epoch+1,access_mode='closed',access_version=access_version+1,live_enabled=0,live_code=NULL,view_code=?,updated_at=CASE WHEN deleted=1 THEN updated_at ELSE ? END WHERE id=?")
        .run(randomBytes(24).toString('base64url'),store.now(),game);store.games.revokeAll(game);
      const {team_id:team}=db.prepare('SELECT team_id FROM shared_games WHERE id=?').get(game);
      db.prepare('INSERT INTO game_changes(team_id,game_id) VALUES(?,?)').run(team,game);
    }
    db.exec("DELETE FROM handoffs; UPDATE invitations SET revoked=1; UPDATE device_pairings SET status='revoked'; UPDATE operations SET retired=1,result='{}'; DELETE FROM device_recovery_reviews; UPDATE teams SET revision=revision+1,access_epoch=access_epoch+1;");
    const epoch=randomUUID();
    db.prepare("INSERT OR REPLACE INTO metadata VALUES('recoveryEpoch',?)").run(epoch);
    db.prepare("INSERT OR REPLACE INTO metadata VALUES('needsDeviceRecoveryReview','1')").run();
    db.prepare("INSERT OR REPLACE INTO metadata VALUES('restoreQuarantine','0')").run();
    return {recoveryEpoch:epoch,appliedDenials:denied};
  });
}

export async function restoreEncryptedBackup({archive,destination,key,journalDirectory}) {
  ensure(!existsSync(destination),'Restore refuses to replace an existing database');
  mkdirSync(dirname(destination),{recursive:true,mode:0o700});
  const pending=destination+'.'+randomUUID()+'.partial';
  let store;
  try {
    await decryptFile(archive,pending,key);integrity(pending);
    const {CollaborationStore}=await import('./server.mjs');store=new CollaborationStore({database:pending});
    store.db.prepare("INSERT OR REPLACE INTO metadata VALUES('restoreQuarantine','1')").run();
    let result={quarantined:true};
    if(journalDirectory) {
      const journalID=store.db.prepare("SELECT value FROM metadata WHERE key='safetyJournalID'").get()?.value;
      const journal=new SafetyJournal({directory:journalDirectory,key,journalID});
      result={quarantined:false,...applyRecoveryFences(store,journal)};
    }
    store.db.exec('PRAGMA wal_checkpoint(TRUNCATE);');store.close();store=undefined;
    renameSync(pending,destination);integrity(destination);return result;
  }finally{store?.close();for(const suffix of ['','-wal','-shm'])rmSync(pending+suffix,{force:true});}
}

if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href) {
  const [action,input,output]=process.argv.slice(2),key=readBackupKey(process.env.BM_TEAM_BACKUP_KEY);
  ensure(['backup','restore'].includes(action) && input && output,'Usage: backup.mjs backup|restore INPUT OUTPUT');
  const result=action==='backup' ? await createEncryptedBackup({database:input,destination:output,key})
    : await restoreEncryptedBackup({archive:input,destination:output,key,journalDirectory:process.env.BM_TEAM_SAFETY_JOURNAL});
  process.stdout.write(JSON.stringify(result)+'\n');
}
