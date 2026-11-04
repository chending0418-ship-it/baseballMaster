"""Deploy only launch configuration to the existing Hong Kong service; no DNS/migration.

Default prepares a reviewable package. --execute requires a fresh read-only baseline.
Credentials stay on the server. Rollback changes code only, never restores an old DB.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
RELEASE = '3.1.1-launch-20261102'
OUT = ROOT / 'output/deployments/collaboration' / RELEASE
FILES = [
    'collaboration/server.mjs', 'collaboration/admin.mjs', 'collaboration/launch-screen.mjs',
    *['collaboration/admin/public/' + name for name in [
        'index.html', 'app.js', 'style.css', 'launch.js', 'launch-identity.png', 'launch-background.png']],
]

REMOTE_SCRIPT = r'''
from pathlib import Path
import datetime,hashlib,json,os,shutil,sqlite3,subprocess,tarfile,time
stage=Path(STAGE); expected=EXPECTED; baseline=BASELINE
root=Path('/www/server/baseballmaster-collaboration'); old=(root/'current').resolve(); new=root/'releases'/RELEASE
node='/www/server/baseballmaster-node/node-v24.21.0-linux-x64/bin/node'
service='baseballmaster-collaboration.service';dbpath=Path('/www/server/baseballmaster-collaboration-data/collaboration.sqlite')
def run(args,**kw):return subprocess.check_output(args,text=True,**kw).strip()
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def pid(name):return run(['systemctl','show',name,'--property=MainPID','--value'])
def backup():
 subprocess.run(['systemctl','start','baseballmaster-team-backup.service'],check=True,timeout=180)
 assert run(['systemctl','show','baseballmaster-team-backup.service','--property=Result','--value'])=='success'
 return run(['systemctl','show','baseballmaster-team-backup.service','--property=ExecMainStartTimestamp','--value'])
assert str(old)==baseline['release'] and not new.exists(), 'Deployment baseline/release changed; inspect before retry'
assert all(sha(old/p)==h for p,h in baseline['files'].items()), 'Running source differs from audited baseline'
assert all(sha(p)==h for p,h in baseline['protected'].items()), 'Protected production configuration changed'
assert pid(service)!='0'
peers={name:pid(name) for name in ['baseballmaster-live.service','learningcenter.service']}
assert all(value!='0' for value in peers.values())
protected=dict(baseline['protected'])
site=Path('/www/wwwroot/baseballmaster.cc')
for name in ['index.html','scorekeeper.html','support.html','privacy.html']:
 p=site/name
 if p.is_file():protected[str(p)]=sha(p)
for folder in ['watch','join','pair','recording/demoReview','livestreaming/novideo']:
 for p in (site/folder).rglob('*'):
  if p.is_file() and not p.is_symlink():protected[str(p)]=sha(p)
unchanged={str(p.relative_to(old)):sha(p) for p in old.rglob('*') if p.is_file() and str(p.relative_to(old)) not in expected}
prebackup=backup()
extract=stage/'source';extract.mkdir(mode=0o700)
with tarfile.open(stage/'overlay.tar.gz') as archive:
 members=archive.getmembers()
 assert {m.name for m in members}==set(expected) and all(m.isfile() for m in members)
 for member in members:
  p=extract/member.name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(archive.extractfile(member).read())
assert all(sha(extract/p)==h for p,h in expected.items())
shutil.copytree(old,new)
for p in expected:shutil.copy2(extract/p,new/p)
subprocess.run(['chmod','-R','go-w',str(new)],check=True)
assert all(sha(new/p)==h for p,h in unchanged.items())
for p in expected:
 if p.endswith('.mjs') or p.endswith('.js'):subprocess.run([node,'--check',str(new/p)],check=True,capture_output=True)
# Verify the complete candidate with a fresh synthetic memory database first.
preflight="import {createCollaborationServer} from '"+(new/'collaboration/server.mjs').as_uri()+"'; const {server,store}=createCollaborationServer({database:':memory:'});if(store.launchScreen.get().schemaVersion!==1)throw Error('launch preflight');server.close();store.close();"
subprocess.run(['runuser','-u','baseballmaster-team','--',node,'--input-type=module'],input=preflight,text=True,check=True,capture_output=True)
def snapshot(tables=None):
 c=sqlite3.connect('file:'+str(dbpath)+'?mode=ro',uri=True)
 try:
  assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
  names=tables if tables is not None else [r[0] for r in c.execute("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name")]
  digest=hashlib.sha256()
  for name in names:
   sql=c.execute('select sql from sqlite_master where type=\'table\' and name=?',(name,)).fetchone()[0]
   digest.update(json.dumps([name,sql]).encode())
   quoted='"'+name.replace('"','""')+'"'
   for row in c.execute('select * from '+quoted+' order by rowid'):
    digest.update(json.dumps(row,ensure_ascii=True,separators=(',',':'),default=str).encode())
  return names,digest.hexdigest()
 finally:c.close()
def switch(target,suffix):
 temporary=root/('current.'+suffix)
 assert not temporary.exists() and not temporary.is_symlink()
 os.symlink(target,temporary);os.replace(temporary,root/'current')
stopped=False;switched=False
try:
 stopped=True
 subprocess.run(['systemctl','stop',service],check=True,timeout=40)
 assert pid(service)=='0'
 tables,before=snapshot();start=time.monotonic()
 # Add the two presentation-only tables while there is no production writer.
 init="import {DatabaseSync} from 'node:sqlite'; import {LaunchScreenService} from '"+(new/'collaboration/launch-screen.mjs').as_uri()+"';const db=new DatabaseSync("+json.dumps(str(dbpath))+");const service=new LaunchScreenService({db});if(service.get().schemaVersion!==1)throw Error('launch schema');db.close();"
 subprocess.run(['runuser','-u','baseballmaster-team','--',node,'--input-type=module'],input=init,text=True,check=True,capture_output=True)
 assert snapshot(tables)[1]==before, 'Pre-existing schema or business rows changed during additive initialization'
 added=set(snapshot()[0])-set(tables)
 assert added == {'app_launch_settings','app_launch_assets'}, 'Expected exactly two additive launch tables'
 switch(new,'launch-next');switched=True
 subprocess.run(['systemctl','start',service],check=True,timeout=40)
 for attempt in range(40):
  result=subprocess.run(['curl','-fsS','--max-time','2','http://127.0.0.1:8788/api/v3/app/launch-screen'],capture_output=True,text=True)
  if result.returncode==0 and json.loads(result.stdout).get('schemaVersion')==1:break
  time.sleep(0.5)
 else:raise RuntimeError('Launch health did not recover')
 downtime=round(time.monotonic()-start,3)
 cap=json.loads(run(['curl','-fsS','--max-time','5','http://127.0.0.1:8788/api/v3/capabilities']))
 assert cap['protocol']==1 and cap['matchFormat']==2
 assert all(pid(name)==value for name,value in peers.items()), 'Independent service unexpectedly restarted'
 assert all(sha(p)==h for p,h in protected.items()), 'Protected website or configuration changed'
 assert all(sha(new/p)==h for p,h in expected.items())
 postbackup=backup()
 record={'deployedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'oldRelease':str(old),'newRelease':str(new),'installed':expected,'preExistingSchemaAndRowsPreservedDuringInitialization':True,'addedTables':sorted(added),'codeOnlyRollback':True,'databaseRestored':False,'productionDataMigrated':False,'dnsChanged':False,'nginxChanged':False,'independentServicePIDsUnchanged':True,'protectedFilesVerified':len(protected),'unchangedRuntimeFilesVerified':len(unchanged),'protocol':cap['protocol'],'matchFormat':cap['matchFormat'],'serviceStartSeconds':downtime,'preBackupAt':prebackup,'postBackupAt':postbackup,'publicURL':'https://baseballmaster.cc/api/v3/app/launch-screen','adminURL':'https://baseballmaster.cc/admin/#launch'}
 (stage/'installed.json').write_text(json.dumps(record,indent=2)+'\n')
 # Reviewable rollback: retain all data, including new launch tables and post-deployment writes.
 rollback="from pathlib import Path\nimport os,subprocess\nr=Path("+repr(str(root))+")\nold=Path("+repr(str(old))+")\nassert old.is_dir()\nassert (r/'current').resolve()==Path("+repr(str(new))+")\np=r/'current.launch-manual-rollback'\nassert not p.exists() and not p.is_symlink()\ntry:\n subprocess.run(['systemctl','stop',"+repr(service)+"],check=True,timeout=40)\n os.symlink(old,p);os.replace(p,r/'current')\nfinally:\n subprocess.run(['systemctl','start',"+repr(service)+"],check=True,timeout=40)\n"
 (stage/'rollback-code-only.py').write_text(rollback)
 print(json.dumps(record))
except Exception:
 if stopped:
  try:
   subprocess.run(['systemctl','stop',service],check=False,timeout=40)
   if switched:switch(old,'launch-rollback')
  finally:
   subprocess.run(['systemctl','start',service],check=True,timeout=40)
 raise
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    baseline = json.loads((OUT / 'baseline.json').read_text())
    manifest = {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in FILES}
    (OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    with tarfile.open(OUT / 'overlay.tar.gz', 'w:gz') as archive:
        for p in FILES:
            archive.add(ROOT / p, arcname=p)
    stage = '/root/baseballmaster-deploy/' + RELEASE
    script = REMOTE_SCRIPT.replace('STAGE', repr(stage)).replace('EXPECTED', repr(manifest)).replace('BASELINE', repr(baseline)).replace('RELEASE', repr(RELEASE))
    compile(script, 'remote-install.py', 'exec')
    (OUT / 'install.py').write_text(script)
    if not args.execute:
        print(json.dumps({'prepared': True, 'runtimeFiles': len(FILES), 'publicURL': 'https://baseballmaster.cc/api/v3/app/launch-screen'}))
        return
    ssh = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', '-o', 'StrictHostKeyChecking=yes', '-o', 'LogLevel=ERROR', 'jingsen-prod']
    subprocess.run(ssh + ['install -d -m 700 ' + stage], check=True, timeout=30)
    subprocess.run(['scp', '-q', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', str(OUT / 'overlay.tar.gz'), 'jingsen-prod:' + stage + '/overlay.tar.gz'], check=True, timeout=60)
    result = subprocess.run(ssh + ['python3 -'], input=script, text=True, capture_output=True, timeout=480)
    (OUT / 'install.stderr.log').write_text(result.stderr)
    if result.returncode:
        raise RuntimeError('Deployment failed; inspect install.stderr.log. Code rollback is attempted by the installer.')
    record = json.loads(result.stdout)
    (OUT / 'installed.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps({key: record[key] for key in ['newRelease', 'serviceStartSeconds', 'addedTables', 'independentServicePIDsUnchanged', 'publicURL']}))

if __name__ == '__main__':
    main()
