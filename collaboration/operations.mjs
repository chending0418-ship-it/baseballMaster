import {readdirSync,statSync,statfsSync,rmSync,writeFileSync,renameSync,readFileSync} from 'node:fs';
import {join} from 'node:path';
import {createEncryptedBackup,readBackupKey} from './backup.mjs';
const database=process.env.BM_TEAM_DATABASE,directory=process.env.BM_TEAM_BACKUPS,status=process.env.BM_TEAM_BACKUP_STATUS;
if(!database||!directory||!status)throw new Error('Missing operations configuration');
process.umask(0o077);
const files=()=>readdirSync(directory).filter(n=>/^daily-\d{8}T\d{6}Z\.sealed$/.test(n)).map(name=>({name,mtime:statSync(join(directory,name)).mtimeMs}));
if(process.argv[2]==='backup'){
 const name='daily-'+new Date().toISOString().replace(/[-:]/g,'').replace(/\.\d{3}/,'')+'.sealed';
 const saved=await createEncryptedBackup({database,destination:join(directory,name),key:readBackupKey(process.env.BM_TEAM_BACKUP_KEY)});
 for(const file of files())if(file.mtime<Date.now()-30*86400000)rmSync(join(directory,file.name));
 const result={...saved,completedAt:Date.now(),file:name};
 writeFileSync(status+'.partial',JSON.stringify(result)+'\n',{mode:0o600});renameSync(status+'.partial',status);
 process.stdout.write(JSON.stringify(result)+'\n');
}else if(process.argv[2]==='check'){
 const disk=statfsSync(database),saved=JSON.parse(readFileSync(status));
 const response=await fetch('http://127.0.0.1:'+Number(process.env.BM_TEAM_PORT??8788)+'/api/v3/health',{signal:AbortSignal.timeout(5000)});
 const passed=response.ok && disk.bavail*disk.bsize>10*1024**3 && Date.now()-saved.completedAt<26*3600000
  && statSync(join(directory,saved.file)).size===saved.backupBytes;
 process.stdout.write(JSON.stringify({healthy:response.ok,freeBytes:disk.bavail*disk.bsize,lastBackupAgeHours:(Date.now()-saved.completedAt)/3600000,passed})+'\n');
 if(!passed)process.exitCode=1;
}else throw new Error('Use backup or check');
