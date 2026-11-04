"""Authorized two-file Shanghai rollout. Keeps all data and checks activation manifest."""
from pathlib import Path
import base64,datetime,hashlib,json,subprocess
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'output/validation/3.1.2/bulk-backend-deploy-2026-11-04'
SSH=['ssh','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=15','-i',str(Path.home()/'Downloads/baseballmaster_shanghai.pem'),'ubuntu@101.43.112.8']
REMOTE=r'''
from pathlib import Path
import base64,datetime,hashlib,json,os,shutil,sqlite3,subprocess,sys,time
p=json.load(sys.stdin); config=Path('/etc/baseballmaster-production/runtime.json');c=json.loads(config.read_text());root=Path(c['codeRoot']);manifest=Path(c['codeManifest']);m=json.loads(manifest.read_text());service='baseballmaster-mainland-team.service';node='/opt/baseballmaster-node/node-v24.21.0-linux-x64/bin/node'
def sha(b):return hashlib.sha256(b).hexdigest()
def inventory():return {str(x.relative_to(root)):sha(x.read_bytes()) for x in root.rglob('*') if x.is_file()}
def run(a,**kw):return subprocess.check_output(a,text=True,**kw).strip()
def pid(s):return run(['systemctl','show',s,'-p','MainPID','--value'])
def atomic(path,data,mode=0o600):
 path=Path(path);tmp=path.with_name(path.name+'.bulk-next');tmp.write_bytes(data);tmp.chmod(mode);os.replace(tmp,path)
def update_manifest(files):
 m['files']=files;atomic(manifest,(json.dumps(m,indent=2)+'\n').encode(),0o644);c['codeManifestSHA256']=sha(manifest.read_bytes());atomic(config,(json.dumps(c,indent=2)+'\n').encode(),0o644)
def dbhash():
 db=sqlite3.connect('file:'+c['teamDatabase']+'?mode=ro',uri=True,timeout=15)
 try:
  db.execute('BEGIN');assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok';h=hashlib.sha256();tables=[]
  for name,sql in db.execute("SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name").fetchall():
   h.update(json.dumps([name,sql]).encode());tables.append(name);quoted='"'+name.replace('"','""')+'"'
   for row in db.execute('SELECT * FROM '+quoted+' ORDER BY rowid'):h.update(json.dumps(row,default=str,separators=(',',':')).encode())
  return {'tableCount':len(tables),'digest':h.hexdigest()}
 finally:db.close()
assert c['host']=='101.43.112.8' and pid(service)!='0'
assert sha(manifest.read_bytes())==c['codeManifestSHA256']
actual=inventory();mismatches={k for k in actual.keys()|m['files'].keys() if actual.get(k)!=m['files'].get(k)}
allowed={'website/'+k:v for k,v in p['websiteReceipt']['manifest'].items()};assert mismatches<=allowed.keys(), 'Unexpected code drift'
assert all(actual[k]==allowed[k] for k in mismatches),'Website mismatch is not the authorized deployment'
backup=Path('/var/backups/baseballmaster-bulk')/p['batch'];backup.mkdir(parents=True,mode=0o700,exist_ok=False)
shutil.copy2(manifest,backup/'manifest-before-website-sync.json');shutil.copy2(config,backup/'runtime-before-website-sync.json')
update_manifest(actual)
shutil.copy2(manifest,backup/'manifest-rollback.json');shutil.copy2(config,backup/'runtime-rollback.json')
old={};expected={}
for rel,b64 in p['files'].items():
 assert rel in ['team/collaboration/server.mjs','team/collaboration/roster.mjs'];dst=root/rel;old[rel]=dst.read_bytes();assert sha(old[rel])==p['baseline'][rel]
 saved=backup/rel;saved.parent.mkdir(parents=True,exist_ok=True);saved.write_bytes(old[rel]);expected[rel]=sha(base64.b64decode(b64))
# Candidate copy lives outside guarded production tree, never reads real configuration/data.
candidate=backup/'candidate';shutil.copytree(root/'team',candidate)
for rel,b64 in p['files'].items():
 target=candidate/Path(rel).relative_to('team');target.write_bytes(base64.b64decode(b64));subprocess.run([node,'--check',str(target)],check=True,capture_output=True)
preflight="import {createCollaborationServer} from '"+(candidate/'collaboration/server.mjs').as_uri()+"'; const {server,store}=createCollaborationServer({database:':memory:'});server.close();store.close();"
subprocess.run([node,'--input-type=module'],input=preflight,text=True,check=True,capture_output=True,env={'PATH':'/usr/bin:/bin'})
subprocess.run(['systemctl','start','baseballmaster-mainland-backup.service'],check=True,timeout=600)
assert run(['systemctl','show','baseballmaster-mainland-backup.service','-p','Result','--value'])=='success'
peers={s:pid('baseballmaster-mainland-'+s+'.service') for s in ['live','lifan','voice-lab','voice-assistant','web']}
stopped=False;start=time.monotonic()
try:
 subprocess.run(['systemctl','stop',service],check=True,timeout=45);stopped=True;before=dbhash()
 for rel,b64 in p['files'].items():atomic(root/rel,base64.b64decode(b64),0o644)
 now=inventory();assert all(now[k]==v for k,v in actual.items() if k not in p['files']);assert set(now)==set(actual)
 update_manifest(now);assert dbhash()==before,'Database changed while team writer stopped'
 subprocess.run(['systemctl','start',service],check=True,timeout=45);stopped=False
 for _ in range(60):
  try:
   cap=json.loads(run(['curl','--fail','--silent','http://127.0.0.1:18788/api/v3/capabilities']));assert 'bulkRosterImport' in cap['capabilities'];break
  except Exception:time.sleep(.25)
 else:raise RuntimeError('New capability did not become healthy')
 assert all(pid('baseballmaster-mainland-'+s+'.service')==v for s,v in peers.items()),'Protected service restarted'
 after=dbhash();assert inventory()==now
except BaseException:
 subprocess.run(['systemctl','stop',service],check=True,timeout=45)
 for rel,data in old.items():atomic(root/rel,data,0o644)
 atomic(manifest,(backup/'manifest-rollback.json').read_bytes(),0o644);atomic(config,(backup/'runtime-rollback.json').read_bytes(),0o644)
 subprocess.run(['systemctl','start',service],check=True,timeout=45)
 raise
receipt={'batch':p['batch'],'checkedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'host':c['host'],'backup':str(backup),'files':expected,'websiteManifestReconciled':len(mismatches),'databaseSchemaMigration':False,'databaseUntouchedWhileStopped':True,'databaseIntegrityAfterStart':True,'databaseTables':after['tableCount'],'unavailabilitySeconds':round(time.monotonic()-start,2),'protectedServicePIDsUnchanged':list(peers),'protocol':cap['protocol'],'minimumAppVersion':cap['minimumAppVersion'],'bulkCapability':True,'preDeployEncryptedBackupSucceeded':True,'runtimeManifestVerified':True}
(backup/'receipt.json').write_text(json.dumps(receipt,indent=2));print(json.dumps(receipt,indent=2))
'''
def main():
 import argparse
 ap=argparse.ArgumentParser();ap.add_argument('--execute',action='store_true');a=ap.parse_args()
 files={f'team/collaboration/{name}':base64.b64encode((ROOT/'collaboration'/name).read_bytes()).decode() for name in ['server.mjs','roster.mjs']}
 baseline={f'team/collaboration/{name}':hashlib.sha256((OUT/('before-'+name)).read_bytes()).hexdigest() for name in ['server.mjs','roster.mjs']}
 p={'batch':datetime.datetime.now(datetime.timezone.utc).strftime('bulk-3.1.2-%Y%m%dT%H%M%SZ'),'files':files,'baseline':baseline,'websiteReceipt':json.loads((ROOT/'output/validation/3.1.2/website-publish-2026-11-04/deployment-receipt.json').read_text())}
 if not a.execute:print(json.dumps({'files':list(files),'baseline':baseline,'execute':False}));return
 code=base64.b64encode(REMOTE.encode()).decode();r=subprocess.run(SSH+["sudo -n python3 -c \"import base64;exec(base64.b64decode('"+code+"'))\""],input=json.dumps(p),text=True,capture_output=True)
 if r.returncode:raise RuntimeError(r.stderr[-5000:])
 receipt=json.loads(r.stdout);(OUT/'deployment-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
