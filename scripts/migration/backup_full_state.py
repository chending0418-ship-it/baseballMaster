#!/usr/bin/env python3
"""Daily encrypted backup of team, lifan, journal and the complete voice ledger."""
import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid
import authorized_precopy as common
import state_transfer

STAGE = 'approval'


def main():
    global STAGE
    os.umask(0o077)
    common.guard(common.SH)
    config = json.loads(Path('/etc/baseballmaster-production/runtime.json').read_text())
    gate = json.loads(Path(config['activationFile']).read_text())
    assert gate['productionTimersApproved'] is True and gate['oldWritersStopped'] is True
    assert gate['trafficActivationApproved'] is True
    node = '/opt/baseballmaster-node/node-v24.21.0-linux-x64/bin/node'
    own = Path(__file__).resolve().parent
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    job = Path(config['backupWork']) / ('full-state-' + stamp)
    job.mkdir(mode=0o700)
    payload = job / 'payload'; payload.mkdir(mode=0o700)
    STAGE = 'consistent-snapshots'
    state = state_transfer.capture(config['teamDatabase'], config['lifanDatabase'], Path(config['teamMount']) / 'journal', payload / 'business-state', online_precopy_only=True)
    (payload / 'voice-assistant').mkdir(mode=0o700)
    ledger = Path(config['voiceAssistantData']) / 'access.json'
    (payload / 'voice-assistant/access.json').write_bytes(ledger.read_bytes())
    manifest = {'schemaVersion': 1, 'purpose': 'authorized-offline-precopy', 'kind': 'data', 'activationEligible': False,
                'capturedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'files': common.inventory(payload)}
    common.save(payload / 'manifest.json', manifest); common.verify_payload(payload)
    STAGE = 'authenticated-encryption'
    key = Path('/etc/baseballmaster-production/full-state-backup.key')
    assert key.stat().st_mode & 0o077 == 0 and key.stat().st_size == 32
    common.make_tar(payload, job / 'payload.tar')
    common.transform(node, own / 'state-archive.mjs', 'encrypt', job / 'payload.tar', job / 'data.sealed', key)
    STAGE = 'independent-COS-download'
    object_key = 'production/full-state/daily-' + stamp + '.sealed'
    p = subprocess.run([node, str(own / 'cos_archive_io.mjs'), str(job / 'data.sealed'), object_key, str(job / 'downloaded.sealed')], capture_output=True, timeout=120)
    assert p.returncode == 0, 'Cloud transfer failed; SDK details suppressed'
    cloud = json.loads(p.stdout)
    assert cloud['passed'] is True and cloud['anonymousReadDenied'] is True
    STAGE = 'downloaded-backup-restore'
    common.transform(node, own / 'state-archive.mjs', 'decrypt', job / 'downloaded.sealed', job / 'restore.tar', key)
    common.extract_private(job / 'restore.tar', job / 'restored')
    common.verify_payload(job / 'restored')
    assert common.inventory(job / 'restored') == common.inventory(payload)
    report = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'passed': True,
              'objectKey': object_key, 'encryptedBytes': cloud['bytes'], 'ciphertextSHA256': cloud['sha256'],
              'teamTables': len(state['databases']['team']['tables']), 'lifanTables': len(state['databases']['lifan']['tables']),
              'completeVoiceLedgerIncluded': True, 'sealedJournalIncluded': True,
              'cloudDownloadAndRestoreVerified': True, 'anonymousReadDenied': True,
              'normalMigrationRecoveryFencesApplied': False, 'automaticDeletionEnabled': False,
              'jobDirectory': str(job), 'secretValuesPrinted': False}
    common.save(job / 'receipt.json', report)
    last = Path(config['backupWork']) / 'full-state-last-backup.json'
    last.write_text(json.dumps(report, indent=2) + '\n'); last.chmod(0o600)
    # Remove only new verified plaintext scratch; keep both ciphertext copies.
    for name in ['payload', 'restored']: shutil.rmtree(job / name)
    for name in ['payload.tar', 'restore.tar']: (job / name).unlink()
    print(json.dumps(report))


if __name__ == '__main__':
    try: main()
    except Exception as error:
        print(json.dumps({'passed': False, 'stage': STAGE, 'errorType': type(error).__name__, 'detailsSuppressed': True}), file=sys.stderr)
        sys.exit(1)
