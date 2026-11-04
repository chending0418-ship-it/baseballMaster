#!/usr/bin/env python3
"""Place verified final data and start ONLY loopback backends after explicit approval."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import sqlite3
import subprocess
import sys
import urllib.request
import authorized_precopy as common
import authorized_final_transfer as final
import state_transfer

STAGE = 'authorization'


def run(args, timeout=30):
    p = subprocess.run(args, capture_output=True, timeout=timeout)
    if p.returncode:
        error = {'command': args[0], 'exitCode': p.returncode,
                 'stdout': p.stdout.decode(errors='replace'), 'stderr': p.stderr.decode(errors='replace')}
        # Private diagnostics stay on-host and never print signed SDK requests.
        Path('/var/lib/baseballmaster-production/activation-error-private.json').write_text(json.dumps(error))
        raise RuntimeError('Command failed; details retained privately')
    return p.stdout


def main():
    global STAGE
    os.umask(0o077)
    request = json.load(sys.stdin)
    assert request['finalDataRestoreApproved'] is True
    assert request['loopbackBackendActivationApproved'] is True
    assert request['dnsChangeApproved'] is False
    common.guard(common.SH)
    job = Path('/var/lib/baseballmaster-production-final') / request['finalBatch']
    receipt = json.loads((job / 'verification-receipt.json').read_text())
    assert receipt['destinationRestoreVerified'] is True
    source = job / 'verification'
    manifest = final.verify(source)
    config = Path('/etc/baseballmaster-production')
    runtime = json.loads((config / 'runtime.json').read_text())
    gate = json.loads((config / 'activation.json').read_text())
    resume = request.get('resumeAfterMount', False)
    assert gate['trafficActivationApproved'] is resume
    assert request['codeRelease'] == runtime['release']
    release = Path('/opt/baseballmaster-production/releases') / runtime['release']
    for name, origin in [('final-data-manifest.json', source / 'manifest.json'),
                         ('hong-kong-writer-fence.json', source / 'writer-fence.json')]:
        if (config / name).exists():
            assert resume and common.digest(config / name) == common.digest(origin)
        else:
            shutil.copyfile(origin, config / name); (config / name).chmod(0o644)
    fence = json.loads((source / 'writer-fence.json').read_text())
    assert fence['sameCutoverReference'] == request['approvalReference']
    admin = json.loads((config / 'team-admin.json').read_text())
    ledger = json.loads((source / 'voice-assistant/access.json').read_text())
    db = sqlite3.connect((source / 'business-state/team.sqlite').as_uri() + '?mode=ro', uri=True)
    identity = dict(db.execute("SELECT key,value FROM metadata WHERE key IN ('safetyJournalID','recoveryEpoch')")); db.close()
    gate.update(trafficActivationApproved=True, oldWritersStopped=True,
                finalDataManifestVerified=True, productionTimersApproved=True,
                approvalReference=request['approvalReference'],
                finalDataManifestSHA256=common.digest(source / 'manifest.json'),
                oldWriterFenceEvidenceSHA256=common.digest(source / 'writer-fence.json'),
                adminBridgeSHA256=hashlib.sha256(admin['legacyToken'].encode()).hexdigest(),
                voiceLedgerPepperSHA256=hashlib.sha256(ledger['pepper'].encode()).hexdigest(),
                **identity)
    if not (release / 'activation-before-final-data.json').exists():
        common.save(release / 'activation-before-final-data.json', json.loads((config / 'activation.json').read_text()))
    else:
        assert resume
    (config / 'activation.json').write_text(json.dumps(gate, indent=2) + '\n'); (config / 'activation.json').chmod(0o644)
    STAGE = 'production-storage-prefix'
    node = '/opt/baseballmaster-node/node-v24.21.0-linux-x64/bin/node'
    javascript = "const fs=require('fs'),C=require('/var/lib/baseballmaster-staging-control/cos-client/node_modules/cos-nodejs-sdk-v5');const c=JSON.parse(fs.readFileSync('/etc/baseballmaster-production/cos-credentials.json'));const x=new C({SecretId:c.SecretId,SecretKey:c.SecretKey,Protocol:'https:',Timeout:15000,MaxRetryTimes:1});x.getBucket({Bucket:c.Bucket,Region:c.Region,Prefix:'production/team/',MaxKeys:5},(e,d)=>{if(e||d.Contents?.some(v=>!v.Key.endsWith('/')))process.exit(2);x.putObject({Bucket:c.Bucket,Region:c.Region,Key:'production/team/',Body:Buffer.alloc(0)},e=>process.exit(e?3:0))});"
    run([node, '-e', javascript], timeout=45)
    STAGE = 'unit-installation'
    for p in (release / 'operations-draft/systemd').iterdir():
        target = Path('/etc/systemd/system') / p.name
        assert not target.is_symlink()
        if target.exists():
            assert resume and common.digest(target) == common.digest(p)
        else:
            shutil.copyfile(p, target); target.chmod(0o644)
    run(['systemctl', 'daemon-reload'])
    STAGE = 'production-storage-mount'
    run(['systemctl', 'start', 'baseballmaster-mainland-storage.service'], timeout=45)
    mount = Path(runtime['teamMount'])
    import time
    for _ in range(75):
        if os.path.ismount(mount): break
        time.sleep(0.2)
    assert os.path.ismount(mount)
    STAGE = 'final-data-placement'
    for role, name in [('team', 'collaboration.sqlite'), ('lifan', 'registrations.sqlite')]:
        folder = Path('/var/lib/baseballmaster-production') / role
        assert not any(folder.iterdir())
        target = folder / name
        shutil.copyfile(source / ('business-state/' + role + '.sqlite'), target)
        target.chmod(0o600); user = pwd.getpwnam('bmprod-' + role); os.chown(target, user.pw_uid, user.pw_gid)
        assert state_transfer.inspect_database(target) == manifest['databases'][role]
    for name in ['journal', 'backups']:
        p = mount / name; assert not p.exists(); p.mkdir(mode=0o700)
    for p in (source / 'business-state/journal').iterdir():
        target = mount / 'journal' / p.name
        shutil.copyfile(p, target); target.chmod(0o600)
        assert common.digest(p) == common.digest(target)
    voice = Path(runtime['voiceAssistantData']) / 'access.json'
    assert not voice.exists()
    shutil.copyfile(source / 'voice-assistant/access.json', voice)
    voice.chmod(0o600); user = pwd.getpwnam('bmprod-voice-assistant'); os.chown(voice, user.pw_uid, user.pw_gid)
    assert common.digest(voice) == common.digest(source / 'voice-assistant/access.json')
    STAGE = 'loopback-backends'
    units = ['baseballmaster-mainland-' + r + '.service' for r in ['team', 'live', 'lifan', 'voice-lab', 'voice-assistant']]
    run(['systemctl', 'start', *units], timeout=75)
    import time
    time.sleep(2)
    health = {}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for role, port, path in [('team', 18788, '/api/v3/health'), ('live', 19088, '/livestreaming/novideo/health'), ('lifan', 19089, '/lifan/tryout/api/health'), ('voice-lab', 19104, '/health'), ('voice-assistant', 19105, '/health')]:
        response = opener.open('http://127.0.0.1:' + str(port) + path, timeout=8)
        assert response.status == 200
        health[role] = response.status
    assert run(['systemctl', 'is-active', *units]).decode().splitlines() == ['active'] * 5
    result = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'passed': True, 'finalBatch': request['finalBatch'], 'health': health,
              'dataPlacedAndPrestartHashesVerified': True, 'fiveLoopbackBackendsActive': True,
              'publicGatewayStarted': False, 'productionTimersEnabled': False,
              'dnsChanged': False, 'secretValuesPrinted': False}
    common.save(job / 'loopback-activation-receipt.json', result)
    print(json.dumps(result))


if __name__ == '__main__':
    try: main()
    except Exception as error:
        print(json.dumps({'passed': False, 'stage': STAGE, 'errorType': type(error).__name__, 'detailsSuppressed': True}), file=sys.stderr)
        sys.exit(1)
