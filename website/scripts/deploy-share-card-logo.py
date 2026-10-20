"""Deploy the four share-page HTML files, preserving running services and data."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import tarfile


ROOT = Path(__file__).resolve().parents[2]
FILES = [
    'live/public/index.html',
    'website/public/watch/index.html',
    'website/public/join/index.html',
    'website/public/pair/index.html',
]


def ssh(script):
    result = subprocess.run(
        ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', 'jingsen-prod', 'python3 -'],
        input=script, text=True, capture_output=True, check=True, timeout=45,
    )
    return json.loads(result.stdout)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', type=Path, required=True)
    args = parser.parse_args()
    baseline = json.loads(args.baseline.read_text())
    assert set(baseline) == set(FILES)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    output = ROOT / 'output/deployments/website' / ('share-card-logo-' + stamp)
    output.mkdir(parents=True, exist_ok=False)
    def public(path, user_agent='BaseballMaster deployment check'):
        headers = output / 'public-response.headers'
        response = subprocess.run(
            ['curl', '--fail', '--silent', '--show-error', '--location', '--max-redirs', '3',
             '--max-time', '15', '--user-agent', user_agent, '--dump-header', str(headers),
             'https://baseballmaster.cc' + path], capture_output=True, check=True, timeout=20,
        )
        lines = headers.read_text().splitlines()
        status = int([line for line in lines if line.startswith('HTTP/')][-1].split()[1])
        content_type = [line.split(':', 1)[1].strip().split(';')[0]
                        for line in lines if line.lower().startswith('content-type:')][-1]
        return status, content_type, response.stdout
    assert public('/livestreaming/novideo/health')[0] == 200
    remote = '/root/baseballmaster-deploy/share-card-logo-' + stamp
    manifest = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in FILES}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    with tarfile.open(output / 'pages.tar.gz', 'w:gz') as archive:
        for name in FILES:
            archive.add(ROOT / name, arcname=name)
    subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
                    'jingsen-prod', 'mkdir -m 700 ' + shlex.quote(remote)], check=True, timeout=30)
    subprocess.run(['scp', str(output / 'pages.tar.gz'),
                    'jingsen-prod:' + remote + '/pages.tar.gz'], check=True, timeout=30)
    installer = '''
import pathlib, hashlib, json, os, stat, subprocess, tarfile
base = pathlib.Path(REMOTE)
expected = MANIFEST
baseline = BASELINE
site = pathlib.Path('/www/wwwroot/baseballmaster.cc')
live = pathlib.Path('/www/server/baseballmaster-live/current').resolve()
assert str(live).startswith('/www/server/baseballmaster-live/releases/')
targets = {'live/public/index.html': live/'public/index.html',
           'website/public/watch/index.html': site/'watch/index.html',
           'website/public/join/index.html': site/'join/index.html',
           'website/public/pair/index.html': site/'pair/index.html'}
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def pids():
    return {s: subprocess.check_output(['systemctl', 'show', s, '--property=MainPID', '--value'], text=True).strip()
            for s in ['baseballmaster-live.service', 'baseballmaster-collaboration.service']}
protected = {str(p): digest(p) for p in [site/'index.html', site/'privacy.html', site/'support.html',
             site/'assets/app-icon.png', live/'server.mjs']}
before_pids = pids()
assert all(pid != '0' for pid in before_pids.values())
for name, target in targets.items():
    assert digest(target) == baseline[name], 'Production file changed: ' + name
previous = base/'previous'
previous.mkdir()
stage = base/'stage'
stage.mkdir()
modes = {}
for name, target in targets.items():
    dest = previous/name
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(target.read_bytes())
    modes[name] = stat.S_IMODE(target.stat().st_mode)
with tarfile.open(base/'pages.tar.gz') as archive:
    members = archive.getmembers()
    assert {m.name for m in members} == set(expected) and len(members) == len(expected)
    for member in members:
        assert member.isfile()
        dest = stage/member.name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(archive.extractfile(member).read())
for name in expected:
    assert digest(stage/name) == expected[name]
installed = []
try:
    assert pathlib.Path('/www/server/baseballmaster-live/current').resolve() == live
    for name, target in targets.items():
        assert digest(target) == baseline[name], 'Concurrent file change: ' + name
        temp = target.with_name(target.name + '.share-logo-new')
        temp.write_bytes((stage/name).read_bytes())
        temp.chmod(modes[name])
        os.replace(temp, target)
        installed.append(name)
    assert all(digest(targets[name]) == expected[name] for name in expected)
    assert all(digest(pathlib.Path(path)) == value for path, value in protected.items())
    assert pids() == before_pids, 'Service process changed during HTML deployment'
except Exception:
    for name in installed:
        target = targets[name]
        assert digest(target) == expected[name], 'Refusing to overwrite a subsequent deployment'
        temp = target.with_name(target.name + '.share-logo-restore')
        temp.write_bytes((previous/name).read_bytes())
        temp.chmod(modes[name])
        os.replace(temp, target)
    raise
result = {'deployedAt': DEPLOYED_AT, 'backup': str(base), 'liveRelease': str(live),
          'targets': {name: str(path) for name,path in targets.items()},
          'before': baseline, 'installed': expected, 'modes': modes,
          'protected': protected, 'servicePIDs': before_pids,
          'servicesRestarted': False, 'databaseTouched': False, 'appModified': False}
(base/'installed.json').write_text(json.dumps(result,indent=2)+'\\n')
print(json.dumps(result))
'''
    installer = installer.replace('REMOTE', repr(remote)).replace('MANIFEST', repr(manifest))
    installer = installer.replace('BASELINE', repr(baseline)).replace(
        'DEPLOYED_AT', repr(datetime.datetime.now(datetime.timezone.utc).isoformat()))
    (output / 'install.py').write_text(installer)
    installed = ssh(installer)
    (output / 'installed.json').write_text(json.dumps(installed, indent=2) + '\n')
    try:
        checks = []
        urls = {
            'live/public/index.html': '/livestreaming/novideo/',
            'website/public/watch/index.html': '/watch/',
            'website/public/join/index.html': '/join/',
            'website/public/pair/index.html': '/pair/',
        }
        for name, path in urls.items():
            status, content_type, body = public(path, 'Mozilla/5.0 MicroMessenger/8.0')
            assert status == 200 and content_type == 'text/html'
            assert hashlib.sha256(body).hexdigest() == manifest[name], name
            assert b'property="og:image"' in body
            checks.append({'path': path, 'status': status, 'matchesSource': True})
        status, content_type, image = public('/assets/app-icon.png?v=20261014')
        assert status == 200 and content_type == 'image/png'
        assert hashlib.sha256(image).hexdigest() == hashlib.sha256((ROOT/'website/public/assets/app-icon.png').read_bytes()).hexdigest()
        checks.append({'path': '/assets/app-icon.png?v=20261014', 'status': status, 'matchesBrandIcon': True})
        for path in ['/livestreaming/novideo/health', '/api/v3/health']:
            status, _, _ = public(path)
            assert status == 200
            checks.append({'path': path, 'status': status})
        (output / 'public-checks.json').write_text(json.dumps(checks, indent=2) + '\n')
    except Exception:
        rollback = '''
import pathlib,json,hashlib,os
base=pathlib.Path(REMOTE)
record=json.loads((base/'installed.json').read_text())
for name,path in record['targets'].items():
    assert hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()==record['installed'][name], 'Concurrent deployment; refusing rollback'
for name,path in record['targets'].items():
    target=pathlib.Path(path)
    temp=target.with_name(target.name+'.share-logo-restore')
    temp.write_bytes((base/'previous'/name).read_bytes())
    temp.chmod(record['modes'][name])
    os.replace(temp,target)
print(json.dumps({'rolledBack':True}))
'''.replace('REMOTE', repr(remote))
        (output / 'rollback.py').write_text(rollback)
        ssh(rollback)
        raise
    print(json.dumps({'output': str(output), 'backup': remote, 'publicChecks': len(checks),
                      'servicesRestarted': False, 'appModified': False}))


if __name__ == '__main__':
    main()
