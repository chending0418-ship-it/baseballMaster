#!/usr/bin/env python3
"""Validate inactive configs and production guards using Shanghai synthetic clones."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import pwd
import secrets
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.request
import uuid

CONTROL = Path('/var/lib/baseballmaster-staging-control')
NODE = '/opt/baseballmaster-node/node-v24.21.0-linux-x64/bin/node'
FROZEN = Path('/opt/baseballmaster-staging/releases/synthetic-20261023T104511Z')
STAGING = Path('/etc/baseballmaster-staging')


def save(path, value, mode=0o600, owner=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n' if not isinstance(value, str) else value)
    path.chmod(mode)
    if owner:
        account = pwd.getpwnam(owner)
        os.chown(path, account.pw_uid, account.pw_gid)


def main():
    os.umask(0o077)
    assert os.geteuid() == 0
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    assert opener.open('http://metadata.tencentyun.com/latest/meta-data/public-ipv4', timeout=3).read().decode().strip() == '101.43.112.8'
    assert json.loads((CONTROL / 'synthetic-only.json').read_text())['syntheticOnly'] is True
    candidate = Path(sys.argv[1])
    assert candidate.is_relative_to(CONTROL / 'candidate-validation-bundles')
    manifest = json.loads((candidate / 'candidate-manifest.json').read_text())
    assert manifest['containsProductionData'] is False and manifest['containsCredentials'] is False
    for name, digest in manifest['files'].items():
        if name == 'sources.tar.gz':
            assert digest == '918bf049b02f15af3d3257fae4b06fbb92d99395b88722bf6022348721ecbe1d'
            continue  # Already independently installed and hash-checked; not uploaded again.
        assert hashlib.sha256((candidate / name).read_bytes()).hexdigest() == digest
    baseline = subprocess.check_output(['ss', '-H', '-lnt'])
    identifier = uuid.uuid4().hex[:12]
    public = Path('/etc/baseballmaster-candidate-validation') / identifier
    data = Path('/var/lib/baseballmaster-candidate-validation') / identifier
    for root in [public.parent, public, data.parent, data]:
        root.mkdir(mode=0o711, exist_ok=True)
        root.chmod(0o711)
    log_root = CONTROL / 'candidate-validation' / identifier
    log_root.mkdir(parents=True, mode=0o700)
    def execute(label, args, *, expected=0, env=None, timeout=60):
        result = subprocess.run(args, capture_output=True, timeout=timeout, env=env)
        (log_root / (label + '.log')).write_bytes(result.stdout + result.stderr)
        (log_root / (label + '.log')).chmod(0o600)
        assert (result.returncode == 0) if expected == 0 else (result.returncode != 0), label + ' failed; private log retained'
        return result

    # nginx -t parses the actual public listen directives but never binds them.
    cert = json.loads((CONTROL / 'public-certificate-receipt.json').read_text())
    nginx = (candidate / 'nginx/nginx.conf').read_text()
    nginx = nginx.replace('/opt/baseballmaster-production/current', str(FROZEN))
    nginx = nginx.replace('/etc/baseballmaster-production/tls/fullchain.pem', cert['certificateFile'])
    nginx = nginx.replace('/etc/baseballmaster-production/tls/privkey.pem', cert['privateKeyFile'])
    nginx = nginx.replace('/etc/baseballmaster-production/routes-', str(candidate / 'nginx/routes-'))
    nginx = nginx.replace('/var/lib/baseballmaster-production/nginx', str(log_root / 'nginx-temp'))
    for folder in ['body', 'proxy']:
        (log_root / 'nginx-temp' / folder).mkdir(parents=True, exist_ok=True)
    save(log_root / 'nginx-public-syntax.conf', nginx)
    execute('nginx-public-syntax', ['nginx', '-t', '-c', str(log_root / 'nginx-public-syntax.conf')])
    units = [str(p) for p in (candidate / 'systemd').iterdir()]
    execute('systemd-unit-syntax', ['systemd-analyze', 'verify', '--man=no', *units])

    runtime = json.loads((candidate / 'configuration/runtime.json').read_text())
    gate = json.loads((candidate / 'configuration/activation.json.example').read_text())
    assert all(gate[name] is False for name in ['dataCopyApproved', 'trafficActivationApproved', 'oldWritersStopped', 'productionTimersApproved', 'finalDataManifestVerified'])
    runtime.update(syntheticValidation=True, activationFile=str(public / 'activation.json'),
        codeRoot=str(FROZEN), sourceManifest=str(public / 'source-manifest.json'),
        teamDatabase=str(data / 'team/collaboration.sqlite'), lifanDatabase=str(data / 'lifan/registrations.sqlite'),
        teamBackupKey=str(public / 'team-backup.key'), lifanConfig=str(public / 'lifan.json'),
        lifanBackupKey=str(public / 'lifan-backup.key'), backupWork=str(data / 'backup-work'),
        teamAdminConfig=str(public / 'team-admin.json'), teamPort=8788, livePort=18088, lifanPort=18089,
        liveInternalBase='http://127.0.0.1:18088',
        teamMount='/mnt/baseballmaster-candidate-validation-' + identifier,
        liveMount='/run/baseballmaster-staging-live', cosfsPasswordFile=str(STAGING / 'passwd-cosfs'),
        cosCredentialsFile=str(STAGING / 'cos-credentials.json'), cosClientRoot=str(CONTROL / 'cos-client'),
        prestartScript=str(public / 'mainland-prestart.mjs'),
        finalDataManifest=str(public / 'final-data-manifest.json'),
        validationTeamPrefix='staging/candidate-validation/' + identifier + '/team',
        validationBackupPrefix='staging/candidate-validation/' + identifier + '/lifan/backups/')
    save(public / 'runtime.json', runtime, 0o644)
    save(public / 'activation.json', gate, 0o644)
    save(public / 'source-manifest.json', (FROZEN / 'source-manifest.json').read_text(), 0o644)
    save(public / 'mainland-prestart.mjs', (candidate / 'operations/mainland-prestart.mjs').read_text(), 0o644)
    for role in ['team', 'live', 'lifan', 'web', 'backup', 'check', 'lifan-backup']:
        execute('closed-gate-' + role, [NODE, runtime['prestartScript'], str(public / 'runtime.json'), role], expected=1)
    execute('closed-storage-gate', ['/usr/bin/python3', str(candidate / 'operations/mainland-storage-start.py'), str(public / 'runtime.json')], expected=1)

    sys.path.insert(0, str(CONTROL / 'migration-tools'))
    from state_transfer import snapshot_database, capture
    for role, source in [('team', '/var/lib/baseballmaster-staging/team/synthetic.sqlite'),
                         ('lifan', '/var/lib/baseballmaster-staging/lifan/synthetic.sqlite')]:
        folder = data / role
        folder.mkdir(mode=0o700)
        snapshot_database(source, folder / ('collaboration.sqlite' if role == 'team' else 'registrations.sqlite'))
        account = pwd.getpwnam('bmprod-' + role)
        os.chown(folder, account.pw_uid, account.pw_gid)
        for path in folder.iterdir():
            os.chown(path, account.pw_uid, account.pw_gid)
    (data / 'backup-work').mkdir(mode=0o700)
    for field, filename, owner in [('teamBackupKey', 'backup.key', 'bmprod-team'), ('lifanBackupKey', 'lifan-backup.key', None)]:
        path = Path(runtime[field])
        path.write_bytes((STAGING / filename).read_bytes())
        path.chmod(0o600)
        if owner:
            account = pwd.getpwnam(owner)
            os.chown(path, account.pw_uid, account.pw_gid)
    lifan = json.loads((STAGING / 'lifan.json').read_text())
    lifan['dbPath'] = runtime['lifanDatabase']
    save(Path(runtime['lifanConfig']), lifan, owner='bmprod-lifan')
    admin = json.loads((STAGING / 'team-admin.json').read_text())
    admin['legacyBase'] = runtime['liveInternalBase']
    save(Path(runtime['teamAdminConfig']), admin, owner='bmprod-team')
    db = sqlite3.connect('file:' + runtime['teamDatabase'] + '?mode=ro', uri=True)
    try:
        journal_id = db.execute("SELECT value FROM metadata WHERE key='safetyJournalID'").fetchone()[0]
        epoch = db.execute("SELECT value FROM metadata WHERE key='recoveryEpoch'").fetchone()[0]
    finally:
        db.close()
    offline_journal = data / 'offline-journal'
    offline_journal.mkdir(mode=0o700)
    for file in Path('/var/lib/baseballmaster-staging/journal').iterdir():
        assert file.name == 'identity.sealed' or (len(file.name) == 71 and file.name.endswith('.sealed'))
        shutil.copyfile(file, offline_journal / file.name)
    final_data = capture(runtime['teamDatabase'], runtime['lifanDatabase'], offline_journal,
                         data / 'final-data-reference', writers_stopped=True)
    precopy_data = capture(runtime['teamDatabase'], runtime['lifanDatabase'], offline_journal,
                           data / 'online-precopy-reference', online_precopy_only=True)
    canonical_digest = lambda value: hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    gate.update(dataCopyApproved=True, trafficActivationApproved=True, oldWritersStopped=True,
                productionTimersApproved=True, approvalReference='synthetic-only-validation',
                safetyJournalID=journal_id, recoveryEpoch=epoch, finalDataManifestVerified=True,
                adminBridgeSHA256=hashlib.sha256(admin['legacyToken'].encode()).hexdigest(),
                finalDataManifestCanonicalSHA256=canonical_digest(precopy_data))
    save(Path(runtime['finalDataManifest']), precopy_data, 0o644)
    save(public / 'activation.json', gate, 0o644)
    execute('precopy-start-denied', [NODE, runtime['prestartScript'], str(public / 'runtime.json'), 'team'], expected=1)
    gate['finalDataManifestCanonicalSHA256'] = canonical_digest(final_data)
    save(Path(runtime['finalDataManifest']), final_data, 0o644)
    save(public / 'activation.json', gate, 0o644)
    primer = '''import assert from 'node:assert/strict';import {readFileSync,readdirSync} from 'node:fs';import {createRequire} from 'node:module';
try {
 const c=JSON.parse(readFileSync(process.argv[2]));assert.equal(c.syntheticValidation,true);
 const key=JSON.parse(readFileSync(c.cosCredentialsFile));assert.equal(key.Prefix,'staging/');
 const COS=createRequire(c.cosClientRoot+'/package.json')('cos-nodejs-sdk-v5');
 const cos=new COS({SecretId:key.SecretId,SecretKey:key.SecretKey,Protocol:'https:',FollowRedirect:false,Timeout:15000,MaxRetryTimes:1});
 const put=(Key,Body)=>new Promise((resolve,reject)=>cos.putObject({Bucket:c.bucket,Region:'ap-shanghai',Key,Body,ContentLength:Body.length,ContentType:'application/octet-stream'},(e,d)=>e?reject(e):resolve(d)));
 const prefix=c.validationTeamPrefix;assert(prefix.startsWith('staging/candidate-validation/'));
 for(const suffix of ['/','/journal/','/backups/'])await put(prefix+suffix,Buffer.alloc(0));
 const files=readdirSync('/var/lib/baseballmaster-staging/journal');
 for(const name of files){assert(/^(identity|[a-f0-9]{64})\\.sealed$/.test(name));await put(prefix+'/journal/'+name,readFileSync('/var/lib/baseballmaster-staging/journal/'+name));}
 console.log(JSON.stringify({passed:true,syntheticJournalFiles:files.length}));
}catch(e){console.error(JSON.stringify({passed:false,errorType:e.name,detailsSuppressed:true}));process.exitCode=1;}
'''
    save(log_root / 'prime.mjs', primer)
    execute('prime-synthetic-storage', [NODE, str(log_root / 'prime.mjs'), str(public / 'runtime.json')])
    point = Path(runtime['teamMount'])
    point.mkdir(mode=0o700)
    log = open(log_root / 'cosfs.log', 'wb')
    child = None
    try:
        child = subprocess.Popen(['/usr/bin/python3', str(candidate / 'operations/mainland-storage-start.py'), str(public / 'runtime.json')], stdout=log, stderr=log)
        for _ in range(150):
            if os.path.ismount(point):
                break
            assert child.poll() is None, 'Test COSFS stopped; private log retained'
            time.sleep(0.2)
        assert os.path.ismount(point), 'Test COSFS mount timed out'
        for role, user in [('team', 'bmprod-team'), ('backup', 'bmprod-team'), ('check', 'bmprod-team'),
                           ('live', 'bmprod-live'), ('lifan', 'bmprod-lifan'), ('web', 'root'), ('lifan-backup', 'root')]:
            env = os.environ.copy()
            if role in ['team', 'backup', 'check']:
                env.update(BM_TEAM_DATABASE=runtime['teamDatabase'], BM_TEAM_PORT=str(runtime['teamPort']),
                    BM_TEAM_SAFETY_JOURNAL=runtime['teamMount'] + '/journal',
                    BM_TEAM_BACKUPS=runtime['teamMount'] + '/backups', BM_TEAM_BACKUP_KEY=runtime['teamBackupKey'])
            if role == 'live':
                env.update(BM_ADMIN_BRIDGE_TOKEN=admin['legacyToken'], HOST='127.0.0.1', PORT=str(runtime['livePort']),
                           LIVE_DB=runtime['liveMount'] + '/live.sqlite')
            execute('ready-gate-' + role, ['runuser', '-u', user, '--', NODE, runtime['prestartScript'], str(public / 'runtime.json'), role], env=env)
        execute('lifan-backup-roundtrip', [NODE, str(candidate / 'operations/mainland-lifan-backup.mjs'), str(public / 'runtime.json')])
        backup = json.loads((data / 'backup-work/lifan-last-backup.json').read_text())
        assert backup['verified'] is True and backup['key'].startswith(runtime['validationBackupPrefix'])
        assert not any(path.is_dir() for path in (data / 'backup-work').iterdir()), 'Temporary snapshots were not cleaned'
    finally:
        if os.path.ismount(point):
            subprocess.run(['umount', str(point)], check=True, timeout=20)
        if child:
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.terminate()
                child.wait(timeout=5)
        log.close()
    team_env = os.environ.copy()
    team_env.update(BM_TEAM_DATABASE=runtime['teamDatabase'], BM_TEAM_PORT=str(runtime['teamPort']),
        BM_TEAM_SAFETY_JOURNAL=runtime['teamMount'] + '/journal',
        BM_TEAM_BACKUPS=runtime['teamMount'] + '/backups', BM_TEAM_BACKUP_KEY=runtime['teamBackupKey'])
    denied = execute('missing-mount-denied', ['runuser', '-u', 'bmprod-team', '--', NODE, runtime['prestartScript'], str(public / 'runtime.json'), 'team'], expected=1, env=team_env)
    diagnostics = [json.loads(line) for line in denied.stderr.decode().splitlines() if line.startswith('{')]
    assert diagnostics[-1]['stage'] == 'independent-storage-mount'
    execute('backing-directory-denied', ['runuser', '-u', 'bmprod-team', '--', 'test', '-w', str(point)], expected=1)
    assert subprocess.check_output(['ss', '-H', '-lnt']) == baseline
    states = {name: subprocess.check_output(['systemctl', 'is-active', 'baseballmaster-staging-' + name + '.service'], text=True).strip()
              for name in ['web', 'team', 'live', 'lifan']}
    assert all(value == 'active' for value in states.values())
    receipt = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'passed': True,
               'host': '101.43.112.8', 'syntheticOnly': True, 'nginxPublicConfigSyntaxVerifiedWithoutStarting': True,
               'systemdUnitsVerified': len(units), 'closedActivationGuardsVerified': 8,
               'onlinePrecopyCandidateStartDenied': True, 'readyRoleGuardsVerified': 7,
               'guardsRunWithActualServiceUsers': True, 'cosfsApplicationUserAccessVerified': True,
               'backendPortMappingAndInternalBridgeConsistencyVerified': True,
               'missingMountAndLocalFallbackDenied': True, 'lifanJobEncryptedUploadReadbackVerified': True,
               'temporaryLifanSnapshotsCleaned': True, 'temporaryCosfsUnmounted': True,
               'networkListenersUnchanged': True, 'existingStagingServiceStates': states,
               'productionCandidateInstalled': False, 'productionGateApprovalsRemainFalse': True,
               'productionDataCopied': False, 'hongKongConnected': False, 'dnsChanged': False,
               'logDirectory': str(log_root), 'validationConfiguration': str(public / 'runtime.json')}
    save(CONTROL / 'candidate-validation-receipt.json', receipt)
    print(json.dumps(receipt))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'passed': False, 'errorType': type(error).__name__, 'detailsSuppressed': True}), file=sys.stderr)
        sys.exit(1)
