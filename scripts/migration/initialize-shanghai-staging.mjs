// Generates isolated test secrets on Shanghai. Never prints credentials.
import {randomBytes, scryptSync} from 'node:crypto';
import {existsSync, writeFileSync} from 'node:fs';
import {passwordHash} from '/opt/baseballmaster-staging/current/lifan/server.mjs';

const directory='/etc/baseballmaster-staging';
if(existsSync(directory+'/qa.json')) throw new Error('Test configuration already exists; refusing to rotate secrets');
const lifanPassword=randomBytes(24).toString('base64url');
const adminPassword=randomBytes(24).toString('base64url');
const bridge=randomBytes(32).toString('hex'),salt=randomBytes(16).toString('hex');
const save=(name,value)=>writeFileSync(directory+'/'+name,value,{mode:0o600,flag:'wx'});
save('lifan.json',JSON.stringify({passwordHash:await passwordHash(lifanPassword),encryptionKey:randomBytes(32).toString('hex'),
 dbPath:'/var/lib/baseballmaster-staging/lifan/synthetic.sqlite',origin:'https://baseballmaster.cc',port:18089,trustProxy:true})+'\n');
save('team-admin.json',JSON.stringify({salt,passwordHash:scryptSync(adminPassword,salt,64).toString('hex'),legacyToken:bridge})+'\n');
save('backup.key',randomBytes(32));
save('team.env',[
 'NODE_ENV=production','BM_TEAM_DATABASE=/var/lib/baseballmaster-staging/team/synthetic.sqlite',
 'BM_TEAM_PORT=8788','BM_TEAM_TRUST_PROXY=1','BM_TEAM_REQUESTS_PER_MINUTE=600',
 'BM_TEAM_BACKUP_KEY=/etc/baseballmaster-staging/backup.key',
 'BM_TEAM_SAFETY_JOURNAL=/var/lib/baseballmaster-staging/journal',
 'BM_TEAM_BACKUPS=/var/lib/baseballmaster-staging/backups',
 'BM_TEAM_BACKUP_STATUS=/var/lib/baseballmaster-staging/team/backup-status.json',
 'BM_ADMIN_CONFIG=/etc/baseballmaster-staging/team-admin.json',''].join('\n'));
save('live.env',['NODE_ENV=production','HOST=127.0.0.1','PORT=18088','TRUST_PROXY=1',
 'LIVE_DB=/run/baseballmaster-staging-live/live.sqlite','BM_ADMIN_BRIDGE_TOKEN='+bridge,''].join('\n'));
save('qa.json',JSON.stringify({syntheticOnly:true,lifanPassword,adminPassword})+'\n');
console.log('Created new synthetic-only credentials and keys; no production configuration imported.');
