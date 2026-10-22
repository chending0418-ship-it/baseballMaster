"""Deploy only the authorized public recording demo and its isolated Nginx locations."""
from pathlib import Path
import datetime,hashlib,json,subprocess,tarfile

root=Path(__file__).resolve().parents[2]
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
out=root/'output/deployments/website'/('recording-v4-'+stamp)
out.mkdir(parents=True)
source=root/'website/public/recording/demoReview'
names=['index.html','style.css','app.js','engine.js']
manifest={n:hashlib.sha256((source/n).read_bytes()).hexdigest() for n in names}
(out/'manifest.json').write_text(json.dumps(manifest,indent=2))
with tarfile.open(out/'demo.tar.gz','w:gz') as tar:
    for name in names:tar.add(source/name,arcname=name)
remote='/root/baseballmaster-deploy/recording-v4-'+stamp
def ssh(cmd,**kwargs):return subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','jingsen-prod',cmd],check=True,**kwargs)
ssh('mkdir -m 700 '+remote)
subprocess.run(['scp',str(out/'demo.tar.gz'),'jingsen-prod:'+remote+'/demo.tar.gz'],check=True)
script=r'''
import pathlib,tarfile,hashlib,json,subprocess,os,stat
base=pathlib.Path(REMOTE)
dest=pathlib.Path('/www/wwwroot/baseballmaster.cc/recording/demoReview')
config=pathlib.Path('/www/server/panel/vhost/nginx/baseballmaster.cc.conf')
expected=MANIFEST
before=config.read_bytes();mode=stat.S_IMODE(config.stat().st_mode)
(base/'nginx-before.conf').write_bytes(before)
original=json.loads('{}');backup=base/'previous';backup.mkdir()
site=pathlib.Path('/www/wwwroot/baseballmaster.cc')
baseline={n:hashlib.sha256((site/n).read_bytes()).hexdigest() for n in ['index.html','support.html','privacy.html']}
for n in expected:
    p=dest/n
    original[n]=p.exists()
    if p.exists():(backup/n).write_bytes(p.read_bytes())
stage=base/'stage';stage.mkdir()
with tarfile.open(base/'demo.tar.gz') as tar:
    assert {m.name for m in tar.getmembers()}==set(expected)
    for m in tar.getmembers():
        assert m.isfile() and '/' not in m.name
        (stage/m.name).write_bytes(tar.extractfile(m).read())
for n,h in expected.items():assert hashlib.sha256((stage/n).read_bytes()).hexdigest()==h
marker='# BEGIN recording demo V4'
block='''+repr('''    # BEGIN recording demo V4
    location = /recording/demoReview {
        try_files /recording/demoReview/index.html =404;
        add_header Cache-Control "no-store" always;
        add_header X-Content-Type-Options nosniff always;
    }
    location ^~ /recording/demoReview/ {
        try_files $uri $uri/ =404;
        autoindex off;
        add_header Cache-Control "no-store" always;
        add_header X-Content-Type-Options nosniff always;
    }
    # END recording demo V4

''')+r'''
raw=before.decode()
if marker not in raw:
    needle='    location / {\n        return 404;\n    }'
    assert raw.count(needle)==1,'Cannot uniquely locate final public-site fallback'
    updated=raw.replace(needle,block+needle).encode()
else:
    assert block.strip() in raw,'Existing demo route differs; inspect before editing'
    updated=before
installed=[]
try:
    dest.mkdir(parents=True,exist_ok=True)
    for n in expected:
        p=dest/n;t=dest/('.'+n+'.new');t.write_bytes((stage/n).read_bytes());t.chmod(0o644);os.replace(t,p);installed.append(n)
    assert config.read_bytes()==before,'Nginx config changed concurrently'
    if updated!=before:
        temp=config.with_name(config.name+'.recording-new');temp.write_bytes(updated);temp.chmod(mode);os.replace(temp,config)
    subprocess.run(['/www/server/nginx/sbin/nginx','-t'],check=True)
    if updated!=before:subprocess.run(['/www/server/nginx/sbin/nginx','-s','reload'],check=True)
    for n,h in expected.items():assert hashlib.sha256((dest/n).read_bytes()).hexdigest()==h
    assert all(hashlib.sha256((site/n).read_bytes()).hexdigest()==h for n,h in baseline.items())
except Exception:
    for n in installed:
        if original[n]:(dest/n).write_bytes((backup/n).read_bytes())
        else:(dest/n).unlink(missing_ok=True)
    if config.read_bytes()==updated:
        config.write_bytes(before);config.chmod(mode)
        subprocess.run(['/www/server/nginx/sbin/nginx','-t'],check=True)
        subprocess.run(['/www/server/nginx/sbin/nginx','-s','reload'],check=True)
    raise
result={'url':'https://baseballmaster.cc/recording/demoReview','backup':str(base),'files':expected,'original':original,'baseline':baseline,'nginxBefore':hashlib.sha256(before).hexdigest(),'nginxAfter':hashlib.sha256(updated).hexdigest()}
(base/'installed.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result))
'''
script=script.replace('REMOTE',repr(remote)).replace('MANIFEST',repr(manifest))
result=ssh('python3 -',input=script,text=True,capture_output=True)
(out/'installed.json').write_text(result.stdout)
print(result.stdout)
