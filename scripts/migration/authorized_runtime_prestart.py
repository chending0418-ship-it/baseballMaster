#!/usr/bin/env python3
"""Read-only, fail-closed production activation checks. Never imports application code."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import urllib.request


STAGE = 'arguments'
ROLES = {'team', 'live', 'lifan', 'voice-lab', 'voice-assistant', 'web',
         'storage', 'backup', 'check', 'lifan-backup'}
OLD_UNITS = ['baseballmaster-collaboration.service', 'baseballmaster-live.service',
             'lifan-registration.service', 'baseballmaster-voice-lab.service',
             'voice-scoring-assistant.service', 'baseballmaster-team-backup.timer',
             'baseballmaster-team-monitor.timer', 'baseballmaster-team-backup.service',
             'baseballmaster-team-monitor.service']


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def private(path):
    path = Path(path)
    assert path.is_file() and not path.is_symlink()
    assert path.stat().st_mode & 0o077 == 0
    return path.read_bytes()


def trusted_json(path):
    path = Path(path)
    assert path.is_file() and not path.is_symlink()
    assert path.stat().st_uid == 0 and path.stat().st_mode & 0o022 == 0
    return json.loads(path.read_text())


def inspect_mount(path):
    value = json.loads(subprocess.check_output(
        ['findmnt', '-J', '--mountpoint', path], stderr=subprocess.DEVNULL, timeout=8))
    result = value['filesystems'][0]
    assert result['target'] == path
    return result


def main():
    global STAGE
    config_file, role = sys.argv[1:]
    assert role in ROLES
    config = trusted_json(config_file)
    assert config['host'] == '101.43.112.8'
    assert config['configurationRoot'] == '/etc/baseballmaster-production'
    assert Path(config_file) == Path(config['configurationRoot']) / 'runtime.json'
    STAGE = 'host'
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    assert opener.open('http://metadata.tencentyun.com/latest/meta-data/public-ipv4',
                       timeout=4).read().decode().strip() == config['host']
    STAGE = 'activation'
    gate = trusted_json(config['activationFile'])
    for name in ['dataCopyApproved', 'trafficActivationApproved', 'oldWritersStopped',
                 'finalDataManifestVerified']:
        assert gate[name] is True
    assert gate['activeHost'] == config['host'] and gate['codeRelease'] == config['release']
    assert isinstance(gate['approvalReference'], str) and gate['approvalReference']
    if role in ['backup', 'check', 'lifan-backup']:
        assert gate['productionTimersApproved'] is True
    STAGE = 'old-writer-fence'
    evidence = trusted_json(config['oldWriterFenceEvidence'])
    assert sha(config['oldWriterFenceEvidence']) == gate['oldWriterFenceEvidenceSHA256']
    assert evidence['sourceHost'] == '124.156.173.204'
    assert evidence['baseballDomainMaintenanceEnabled'] is True
    assert evidence['sameCutoverReference'] == gate['approvalReference']
    for unit in OLD_UNITS:
        state = evidence['units'][unit]
        assert state['active'] in ['inactive', 'failed']
        assert state['refuseManualStart'] == 'yes'
        assert state['persistentStartConditionFenced'] is True
    STAGE = 'final-data-manifest'
    final = trusted_json(config['finalDataManifest'])
    assert final['purpose'] in ['state-preserving-migration', 'authorized-final-transfer']
    assert final['writersStoppedConfirmed'] is True and final['activationEligible'] is True
    assert final['onlinePrecopyOnly'] is False and final['recoveryFencesApplied'] is False
    assert sha(config['finalDataManifest']) == gate['finalDataManifestSHA256']
    STAGE = 'code-manifest'
    manifest = trusted_json(config['codeManifest'])
    assert manifest['release'] == config['release']
    assert sha(config['codeManifest']) == config['codeManifestSHA256']
    code_root = Path(config['codeRoot'])
    assert code_root.is_dir() and not code_root.is_symlink()
    expected = manifest['files']
    actual = {str(p.relative_to(code_root)) for p in code_root.rglob('*') if p.is_file()}
    assert actual == set(expected)
    for name, digest in expected.items():
        relative = Path(name)
        assert not relative.is_absolute() and '..' not in relative.parts
        p = code_root / relative
        assert not p.is_symlink() and p.stat().st_uid == 0 and p.stat().st_mode & 0o022 == 0
        assert sha(p) == digest
    if role in ['team', 'backup', 'check']:
        STAGE = 'team-environment'
        for name, value in config['teamEnvironment'].items():
            assert os.environ.get(name) == str(value)
        key = private(config['teamBackupKey'])
        assert len(key) == 32
        admin = json.loads(private(config['teamAdminConfig']))
        assert admin['legacyBase'] == 'http://127.0.0.1:19088'
        assert hashlib.sha256(admin['legacyToken'].encode()).hexdigest() == gate['adminBridgeSHA256']
        STAGE = 'independent-storage-mount'
        mount = inspect_mount(config['teamMount'])
        assert 'cosfs' in mount['fstype']
        STAGE = 'team-database-identity'
        database = Path(config['teamDatabase'])
        assert database.is_file() and not database.is_symlink()
        db = sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True, timeout=5)
        try:
            db.execute('PRAGMA query_only=ON')
            journal = db.execute("SELECT value FROM metadata WHERE key='safetyJournalID'").fetchone()[0]
            epoch = db.execute("SELECT value FROM metadata WHERE key='recoveryEpoch'").fetchone()[0]
            assert journal == gate['safetyJournalID'] and epoch == gate['recoveryEpoch']
        finally:
            db.close()
        assert (Path(config['teamMount']) / 'journal/identity.sealed').is_file()
    elif role == 'live':
        STAGE = 'temporary-live-memory'
        assert os.environ.get('HOST') == '127.0.0.1' and os.environ.get('PORT') == '19088'
        assert os.environ.get('LIVE_DB') == config['liveDatabase']
        token = os.environ.get('BM_ADMIN_BRIDGE_TOKEN', '')
        assert token and hashlib.sha256(token.encode()).hexdigest() == gate['adminBridgeSHA256']
        mount = inspect_mount(config['liveMount'])
        assert mount['fstype'] == 'tmpfs' and 'noswap' in mount['options'].split(',')
    elif role in ['lifan', 'lifan-backup']:
        STAGE = 'lifan-private-configuration'
        settings = json.loads(private(config['lifanConfig']))
        assert settings['dbPath'] == config['lifanDatabase']
        assert settings['port'] == 19089 and settings['trustProxy'] is True
        assert settings['origin'] == 'https://baseballmaster.cc'
        assert re.fullmatch(r'[a-f0-9]{64}', settings['encryptionKey'])
        assert settings['passwordHash'] and Path(config['lifanDatabase']).is_file()
        if role == 'lifan-backup':
            assert len(private(config['lifanBackupKey'])) == 32
    elif role in ['voice-lab', 'voice-assistant']:
        STAGE = 'voice-environment'
        assert os.environ.get('HOST') == '127.0.0.1'
        assert os.environ.get('PORT') == str(config['roles'][role]['port'])
        assert os.environ.get('PUBLIC_ORIGIN') == 'https://baseballmaster.cc'
        assert os.environ.get('PUBLIC_BASE_PATH') == config['roles'][role]['basePath']
        if role == 'voice-assistant':
            assert os.environ.get('VOICE_DATA_DIR') == config['voiceAssistantData']
            assert os.environ.get('VOICE_ADMIN_TOKEN')
            ledger = json.loads(private(Path(config['voiceAssistantData']) / 'access.json'))
            assert ledger['version'] == 1
            assert hashlib.sha256(ledger['pepper'].encode()).hexdigest() == gate['voiceLedgerPepperSHA256']
    elif role == 'storage':
        STAGE = 'storage-prerequisites'
        assert os.geteuid() == 0
        credentials = json.loads(private(config['cosCredentialsFile']))
        assert credentials['Bucket'] == config['bucket'] and credentials['Region'] == 'ap-shanghai'
        assert credentials['Prefix'] == 'production/'
        private(config['cosfsPasswordFile'])
        mount = Path(config['teamMount'])
        assert mount.is_dir() and mount.stat().st_uid == 0 and mount.stat().st_mode & 0o077 == 0
        assert not mount.is_symlink() and not os.path.ismount(mount) and not any(mount.iterdir())
    print(json.dumps({'passed': True, 'role': role, 'credentialsPrinted': False}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'passed': False, 'blockedBeforeStart': True,
                          'stage': STAGE, 'errorType': type(error).__name__,
                          'detailsSuppressed': True}), file=sys.stderr)
        sys.exit(1)
