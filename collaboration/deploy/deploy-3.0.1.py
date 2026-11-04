"""Compatible 3.0.1 service + dual-invitation landing page deployment."""
from pathlib import Path
import hashlib,json,subprocess,tarfile
ROOT=Path(__file__).resolve().parents[2]
WORKSPACE=ROOT.parents[1]
def local_source(name):
 return (WORKSPACE if name.startswith('website/') else ROOT)/name
OUT=ROOT/'output/deployments/collaboration/3.0.1-20261019'
FILES=['collaboration/server.mjs','collaboration/permissions.mjs','website/public/join/index.html']
manifest={p:hashlib.sha256(local_source(p).read_bytes()).hexdigest() for p in FILES}
baseline=json.loads((OUT/'baseline.json').read_text())
with tarfile.open(OUT/'overlay.tar.gz','w:gz') as tar:
 for p in FILES:tar.add(local_source(p),arcname=p)
(OUT/'manifest.json').write_text(json.dumps(manifest,indent=2))
remote='/root/baseballmaster-deploy/3.0.1-20261019'
subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','jingsen-prod','install -d -m 700 '+remote],check=True,timeout=30)
subprocess.run(['scp','-q',str(OUT/'overlay.tar.gz'),'jingsen-prod:'+remote+'/overlay.tar.gz'],check=True,timeout=30)
script=r'''
from pathlib import Path
import hashlib,json,subprocess,tarfile,os,shutil,time,sqlite3,datetime
stage=Path(REMOTE); expected=MANIFEST; baseline=BASELINE
root=Path('/www/server/baseballmaster-collaboration');old=(root/'current').resolve();new=root/'releases/3.0.1-20261019'
node='/www/server/baseballmaster-node/node-v24.21.0-linux-x64/bin/node'
site=Path('/www/wwwroot/baseballmaster.cc');join=site/'join/index.html';db=Path('/www/server/baseballmaster-collaboration-data/collaboration.sqlite')
service='baseballmaster-collaboration.service';live='baseballmaster-live.service'
def run(args,**kw):return subprocess.check_output(args,text=True,**kw).strip()
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def pid(s):return run(['systemctl','show',s,'--property=MainPID','--value'])
assert str(old)==baseline['release'] and not new.exists()
assert all(sha(old/p)==h for p,h in baseline['runtime'].items())
assert all(sha(site/'join'/p)==h for p,h in baseline['join'].items())
protected_paths=[Path('/etc/baseballmaster-collaboration.env'),Path('/etc/systemd/system')/(service),Path('/www/server/panel/vhost/nginx/baseballmaster-v3.inc'),site/'index.html',site/'privacy.html',site/'support.html',site/'assets/app-icon.png',site/'join/join.js',site/'join/join.css']
protected={str(p):sha(p) for p in protected_paths};live_pid=pid(live);assert live_pid!='0'
subprocess.run(['systemctl','start','baseballmaster-team-backup.service'],check=True,timeout=120)
assert run(['systemctl','show','baseballmaster-team-backup.service','--property=Result','--value'])=='success'
previous=stage/'previous';previous.mkdir(mode=0o700)
shutil.copy2(join,previous/'join-index.html')
extract=stage/'source';extract.mkdir()
with tarfile.open(stage/'overlay.tar.gz') as tar:
 members=tar.getmembers();assert {m.name for m in members}==set(expected) and all(m.isfile() for m in members)
 for m in members:
  p=extract/m.name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(tar.extractfile(m).read())
assert all(sha(extract/p)==h for p,h in expected.items())
shutil.copytree(old,new)
for p in ['collaboration/server.mjs','collaboration/permissions.mjs']:shutil.copy2(extract/p,new/p)
subprocess.run(['chmod','-R','go-w',str(new)],check=True)
for p in ['collaboration/server.mjs','collaboration/permissions.mjs']:subprocess.run([node,'--check',str(new/p)],check=True)
def snapshot(columns=None):
 c=sqlite3.connect('file:'+str(db)+'?mode=ro',uri=True)
 try:
  tables=sorted(r[0] for r in c.execute("select name from sqlite_master where type='table' and name not like 'sqlite_%'"))
  cols=columns or {t:[r[1] for r in c.execute('pragma table_info("'+t+'")')] for t in tables}
  hashes={}
  for t,names in cols.items():
   rows=c.execute('select '+','.join('"'+n+'"' for n in names)+' from "'+t+'" order by rowid').fetchall()
   hashes[t]=hashlib.sha256(json.dumps(rows,ensure_ascii=True,separators=(',',':'),default=str).encode()).hexdigest()
  assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
  return cols,hashes
 finally:c.close()
stopped=False;switched=False;page_installed=False;start=time.monotonic()
try:
 subprocess.run(['systemctl','stop',service],check=True);stopped=True
 columns,before=snapshot()
 shutil.copy2(db,previous/'collaboration.sqlite');os.chmod(previous/'collaboration.sqlite',0o600)
 migration="import {CollaborationStore} from './collaboration/server.mjs';const s=new CollaborationStore({database:'/www/server/baseballmaster-collaboration-data/collaboration.sqlite'});s.close();"
 subprocess.run(['runuser','-u','baseballmaster-team','--',node,'--input-type=module','-e',migration],cwd=new,check=True)
 _,after=snapshot(columns);assert before==after,'Pre-existing rows changed during schema migration'
 tmp=root/'current.301-next';os.symlink(new,tmp);os.replace(tmp,root/'current');switched=True
 temp=join.with_name('index.html.301-next');temp.write_bytes((extract/'website/public/join/index.html').read_bytes());temp.chmod(0o644);os.replace(temp,join);page_installed=True
 subprocess.run(['systemctl','start',service],check=True)
 for i in range(30):
  p=subprocess.run(['curl','-fsS','--max-time','2','http://127.0.0.1:8788/api/v3/health'],capture_output=True,text=True)
  if p.returncode==0 and json.loads(p.stdout).get('protocol')==1:break
  time.sleep(0.5)
 else:raise RuntimeError('New service health did not recover')
 downtime=round(time.monotonic()-start,3)
 assert all(sha(p)==h for p,h in protected.items())
 assert pid(live)==live_pid and pid(service)!='0'
 assert sha(join)==expected['website/public/join/index.html']
 assert all(sha(new/p)==h for p,h in expected.items() if p.startswith('collaboration/'))
 record={'deployedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'oldRelease':str(old),'newRelease':str(new),'backup':str(previous),'installed':expected,'preExistingRowsUnchanged':True,'protectedFilesUnchanged':True,'originalLivePIDUnchanged':True,'protocol':1,'serviceRestartSeconds':downtime,'databaseRestored':False,'legacyInvitationInterfaceRetained':True,'encryptedBackupSucceeded':True,'protectedHashes':protected}
 (stage/'installed.json').write_text(json.dumps(record,indent=2)+'\n')
 print(json.dumps(record))
except Exception:
 if stopped:
  subprocess.run(['systemctl','stop',service],check=False)
  if switched:
   tmp=root/'current.301-rollback';os.symlink(old,tmp);os.replace(tmp,root/'current')
  if page_installed and sha(join)==expected['website/public/join/index.html']:
   temp=join.with_name('index.html.301-rollback');shutil.copy2(previous/'join-index.html',temp);os.replace(temp,join)
  subprocess.run(['systemctl','start',service],check=True)
 raise
'''.replace('REMOTE',repr(remote)).replace('MANIFEST',repr(manifest)).replace('BASELINE',repr(baseline))
(OUT/'install.py').write_text(script)
r=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','-o','LogLevel=ERROR','jingsen-prod','python3 -'],input=script,text=True,capture_output=True,timeout=240)
(OUT/'install.stderr.log').write_text(r.stderr)
if r.returncode:raise RuntimeError('Deployment failed; see private install stderr log: '+str(OUT/'install.stderr.log'))
record=json.loads(r.stdout);(OUT/'installed.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps({k:record[k] for k in ['newRelease','preExistingRowsUnchanged','protocol','serviceRestartSeconds','encryptedBackupSucceeded']}))
