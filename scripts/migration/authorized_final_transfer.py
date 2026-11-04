#!/usr/bin/env python3
"""Capture/verify an explicitly approved final transfer. Never starts a service."""
import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import authorized_precopy as common
import state_transfer

UNITS = [*common.UNITS]


def verify(folder):
    manifest = json.loads((folder / 'manifest.json').read_text())
    assert manifest['purpose'] == 'authorized-final-transfer'
    assert manifest['writersStoppedConfirmed'] is True
    assert manifest['onlinePrecopyOnly'] is False
    assert manifest['recoveryFencesApplied'] is False
    assert common.inventory(folder) == {**manifest['files'], 'manifest.json': common.digest(folder / 'manifest.json')}
    state_transfer.verify(folder / 'business-state')
    ledger = json.loads((folder / 'voice-assistant/access.json').read_text())
    assert ledger['version'] == 1 and isinstance(ledger['pepper'], str)
    assert all(isinstance(ledger[k], list) for k in ['invites', 'entitlements', 'operations', 'sessions', 'modelCalls'])
    return manifest


def fence(maintenance):
    result = {}
    for unit in UNITS:
        p = subprocess.run(['systemctl', 'show', unit, '-p', 'ActiveState', '-p', 'RefuseManualStart'], capture_output=True, text=True, check=True)
        values = dict(line.split('=', 1) for line in p.stdout.splitlines())
        assert values['ActiveState'] in ['inactive', 'failed']
        drop = Path('/etc/systemd/system') / (unit + '.d/99-baseballmaster-migration-fence.conf')
        assert drop.is_file() and not drop.is_symlink()
        assert 'RefuseManualStart=yes' in drop.read_text()
        assert 'ConditionPathExists=' + str(maintenance / 'allow-hong-kong-writers') in drop.read_text()
        assert values['RefuseManualStart'] == 'yes'
        result[unit] = {'active': values['ActiveState'], 'refuseManualStart': 'yes', 'persistentStartConditionFenced': True, 'fenceFileSHA256': common.digest(drop)}
    assert not (maintenance / 'allow-hong-kong-writers').exists()
    return result


def main():
    os.umask(0o077)
    action, batch = sys.argv[1:]
    assert re.fullmatch(r'final-[0-9TZ]+-[a-f0-9]{8}', batch)
    host = common.HK if action == 'capture' else common.SH
    common.guard(host)
    job = Path('/root/baseballmaster-migration-final' if action == 'capture' else '/var/lib/baseballmaster-production-final') / batch
    permission = json.loads((job / 'authorization.json').read_text())
    assert permission['realFinalDataTransferApproved'] is True
    assert permission['sourceWriterPauseApproved'] is True
    if action == 'capture':
        maintenance = Path(permission['maintenanceDirectory'])
        # Persist only our new migration guard; original unit files stay intact.
        for unit in UNITS:
            src = Path('/run/systemd/system') / (unit + '.d/99-baseballmaster-migration-fence.conf')
            dst = Path('/etc/systemd/system') / (unit + '.d/99-baseballmaster-migration-fence.conf')
            dst.parent.mkdir(mode=0o755, exist_ok=True)
            assert not dst.exists()
            dst.write_bytes(src.read_bytes()); dst.chmod(0o644)
        subprocess.run(['systemctl', 'daemon-reload'], check=True, capture_output=True)
        states = fence(maintenance)
        site = Path('/www/server/panel/vhost/nginx/baseballmaster.cc.conf')
        assert '# BM_MIGRATION_MAINTENANCE_20261104' in site.read_text()
        assert common.digest(Path('/www/server/panel/vhost/nginx/jingsen.cc.conf')) == permission['jingsenVhostSHA256']
        payload = job / 'payload'; payload.mkdir(mode=0o700)
        state = state_transfer.capture('/www/server/baseballmaster-collaboration-data/collaboration.sqlite', '/var/lib/lifan-registration/registrations.sqlite', '/lhcos-data/baseballmaster-private-v3/journal', payload / 'business-state', writers_stopped=True)
        (payload / 'voice-assistant').mkdir(mode=0o700)
        original = Path('/www/server/baseballmaster-voice-assistant/data/access.json')
        body = original.read_bytes()
        (payload / 'voice-assistant/access.json').write_bytes(body)
        writer_fence = {'sourceHost': common.HK, 'baseballDomainMaintenanceEnabled': True, 'sameCutoverReference': permission['approvalReference'], 'units': states, 'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()}
        common.save(payload / 'writer-fence.json', writer_fence)
        manifest = {'schemaVersion': 1, 'purpose': 'authorized-final-transfer', 'writersStoppedConfirmed': True, 'onlinePrecopyOnly': False, 'activationEligible': True, 'recoveryFencesApplied': False, 'capturedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'files': common.inventory(payload), 'databases': state['databases']}
        common.save(payload / 'manifest.json', manifest); verify(payload)
        common.make_tar(payload, job / 'data.tar')
        common.transform('/www/server/baseballmaster-node/node-v24.21.0-linux-x64/bin/node', job / 'tools/state-archive.mjs', 'encrypt', job / 'data.tar', job / 'data.sealed', job / 'keys/data.key')
        common.transform('/www/server/baseballmaster-node/node-v24.21.0-linux-x64/bin/node', job / 'tools/state-archive.mjs', 'decrypt', job / 'data.sealed', job / 'restore.tar', job / 'keys/data.key')
        common.extract_private(job / 'restore.tar', job / 'restore'); verify(job / 'restore')
        assert original.read_bytes() == body and fence(maintenance) == states
        receipt = {'batch': batch, 'passed': True, 'finalStoppedWriterSnapshot': True, 'bytes': (job / 'data.sealed').stat().st_size, 'sha256': common.digest(job / 'data.sealed'), 'manifestSHA256': common.digest(payload / 'manifest.json'), 'tables': {k: len(v['tables']) for k, v in state['databases'].items()}, 'logicalSHA256': {k: v['logicalSHA256'] for k, v in state['databases'].items()}, 'sourceRestoreVerified': True, 'secretValuesPrinted': False}
        common.save(job / 'capture-receipt.json', receipt)
        for name in ['payload', 'restore']: shutil.rmtree(job / name)
        for name in ['data.tar', 'restore.tar']: (job / name).unlink()
    else:
        assert action == 'verify'
        receipt = json.loads((job / 'capture-receipt.json').read_text())
        assert common.digest(job / 'encrypted/data.sealed') == receipt['sha256']
        common.transform('/opt/baseballmaster-node/node-v24.21.0-linux-x64/bin/node', job / 'tools/state-archive.mjs', 'decrypt', job / 'encrypted/data.sealed', job / 'data.tar', job / 'keys/data.key')
        common.extract_private(job / 'data.tar', job / 'verification')
        verify(job / 'verification')
        assert common.digest(job / 'verification/manifest.json') == receipt['manifestSHA256']
        (job / 'data.tar').unlink()
        common.save(job / 'verification-receipt.json', {**receipt, 'destinationRestoreVerified': True, 'servicesStarted': False})
        receipt = {**receipt, 'destinationRestoreVerified': True, 'servicesStarted': False}
    print(json.dumps(receipt))


if __name__ == '__main__':
    try: main()
    except Exception as error:
        print(json.dumps({'passed': False, 'errorType': type(error).__name__, 'detailsSuppressed': True}), file=sys.stderr)
        sys.exit(1)
