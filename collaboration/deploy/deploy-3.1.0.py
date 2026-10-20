"""Install only format-2 compatibility; keep viewer assets and independent live service."""
from pathlib import Path
import hashlib, json, subprocess, tarfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'output/deployments/collaboration/3.1.0-20261020'
OUT.mkdir(parents=True, exist_ok=True)
FILES = ['collaboration/server.mjs', 'collaboration/games.mjs', 'collaboration/archive.mjs']
manifest = {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in FILES}
baseline = json.loads((OUT / 'baseline.json').read_text())
with tarfile.open(OUT / 'overlay.tar.gz', 'w:gz') as tar:
    for p in FILES:
        tar.add(ROOT / p, arcname=p)
(OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
remote = '/root/baseballmaster-deploy/3.1.0-20261020'
ssh = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', '-o', 'LogLevel=ERROR', 'jingsen-prod']
subprocess.run(ssh + ['install -d -m 700 ' + remote], check=True, timeout=30)
subprocess.run(['scp', '-q', str(OUT / 'overlay.tar.gz'), 'jingsen-prod:' + remote + '/overlay.tar.gz'], check=True, timeout=30)
script = r'''
from pathlib import Path
import hashlib,json,subprocess,tarfile,os,shutil,time,sqlite3,datetime
stage=Path(REMOTE);expected=MANIFEST;baseline=BASELINE
root=Path('/www/server/baseballmaster-collaboration');old=(root/'current').resolve();new=root/'releases/3.1.0-20261020'
node='/www/server/baseballmaster-node/node-v24.21.0-linux-x64/bin/node'
db=Path('/www/server/baseballmaster-collaboration-data/collaboration.sqlite')
service='baseballmaster-collaboration.service';live='baseballmaster-live.service'
def run(args):return subprocess.check_output(args,text=True).strip()
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def pid(s):return run(['systemctl','show',s,'--property=MainPID','--value'])
assert str(old)==baseline['release'] and not new.exists()
assert all(sha(old/p)==h for p,h in baseline['runtime'].items())
protected_paths=[Path('/etc/baseballmaster-collaboration.env'),Path('/etc/systemd/system')/service,Path('/www/server/panel/vhost/nginx/baseballmaster-v3.inc')]
site=Path('/www/wwwroot/baseballmaster.cc')
protected_paths += [p for folder in ['watch','join','pair'] for p in (site/folder).rglob('*') if p.is_file()]
protected={str(p):sha(p) for p in protected_paths};live_pid=pid(live);assert live_pid!='0'
live_release=str(Path('/www/server/baseballmaster-live/current').resolve())
unchanged={str(p.relative_to(old)):sha(p) for p in old.rglob('*') if p.is_file() and str(p.relative_to(old)) not in expected}
subprocess.run(['systemctl','start','baseballmaster-team-backup.service'],check=True,timeout=120)
assert run(['systemctl','show','baseballmaster-team-backup.service','--property=Result','--value'])=='success'
extract=stage/'source';extract.mkdir(mode=0o700)
with tarfile.open(stage/'overlay.tar.gz') as tar:
    members=tar.getmembers();assert {m.name for m in members}==set(expected) and all(m.isfile() for m in members)
    for m in members:
        p=extract/m.name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(tar.extractfile(m).read())
assert all(sha(extract/p)==h for p,h in expected.items())
shutil.copytree(old,new)
for p in expected:shutil.copy2(extract/p,new/p)
subprocess.run(['chmod','-R','go-w',str(new)],check=True)
for p in expected:subprocess.run([node,'--check',str(new/p)],check=True)
assert all(sha(new/p)==h for p,h in unchanged.items())
def snapshot():
    c=sqlite3.connect('file:'+str(db)+'?mode=ro',uri=True)
    try:
        assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
        digest=hashlib.sha256()
        for t,sql in c.execute("select name,sql from sqlite_master where type='table' and name not like 'sqlite_%' order by name"):
            digest.update(json.dumps([t,sql]).encode())
            for row in c.execute('select * from "'+t+'" order by rowid'):
                digest.update(json.dumps(row,ensure_ascii=True,separators=(',',':'),default=str).encode())
        return digest.hexdigest()
    finally:c.close()
stopped=False;switched=False
try:
    subprocess.run(['systemctl','stop',service],check=True);stopped=True
    before=snapshot();start=time.monotonic()
    tmp=root/'current.310-next';os.symlink(new,tmp);os.replace(tmp,root/'current');switched=True
    subprocess.run(['systemctl','start',service],check=True)
    for i in range(30):
        p=subprocess.run(['curl','-fsS','--max-time','2','http://127.0.0.1:8788/api/v3/capabilities'],capture_output=True,text=True)
        if p.returncode==0:
            cap=json.loads(p.stdout)
            if cap.get('protocol')==1 and cap.get('matchFormat')==2:break
        time.sleep(0.5)
    else:raise RuntimeError('New service health did not recover')
    downtime=round(time.monotonic()-start,3)
    assert before==snapshot(),'Existing database schema/rows changed'
    assert all(sha(p)==h for p,h in protected.items())
    assert pid(live)==live_pid and str(Path('/www/server/baseballmaster-live/current').resolve())==live_release
    assert all(sha(new/p)==h for p,h in expected.items())
    record={'deployedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'oldRelease':str(old),'newRelease':str(new),'installed':expected,'preExistingSchemaAndRowsUnchanged':True,'unchangedRuntimeFilesVerified':len(unchanged),'viewerInvitationAndConfigUnchanged':True,'originalLivePIDUnchanged':True,'originalLiveRelease':live_release,'protocol':cap['protocol'],'matchFormat':cap['matchFormat'],'minimumAppVersion':cap['minimumAppVersion'],'serviceStartSeconds':downtime,'databaseRestored':False,'databaseCopied':False,'encryptedBackupSucceeded':True,'scope':'shared live/new match compatibility; App text generation not deployed'}
    (stage/'installed.json').write_text(json.dumps(record,indent=2)+'\n')
    print(json.dumps(record))
except Exception:
    if stopped:
        subprocess.run(['systemctl','stop',service],check=False)
        if switched:
            tmp=root/'current.310-rollback';os.symlink(old,tmp);os.replace(tmp,root/'current')
        subprocess.run(['systemctl','start',service],check=True)
    raise
'''.replace('REMOTE', repr(remote)).replace('MANIFEST', repr(manifest)).replace('BASELINE', repr(baseline))
(OUT / 'install.py').write_text(script)
result = subprocess.run(ssh + ['python3 -'], input=script, text=True, capture_output=True, timeout=240)
(OUT / 'install.stderr.log').write_text(result.stderr)
if result.returncode:
    raise RuntimeError('Deployment failed; inspect ' + str(OUT / 'install.stderr.log'))
record = json.loads(result.stdout)
(OUT / 'installed.json').write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps({k: record[k] for k in ['newRelease', 'preExistingSchemaAndRowsUnchanged', 'originalLivePIDUnchanged', 'protocol', 'matchFormat', 'serviceStartSeconds']}))
