#!/usr/bin/env python3
"""Explicitly authorized, encrypted OFFLINE precopy; never activates a service."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import urllib.request

import state_transfer

HK = '124.156.173.204'
SH = '101.43.112.8'
HK_BASE = Path('/root/baseballmaster-migration-precopy')
SH_BASE = Path('/var/lib/baseballmaster-production-precopy')
ROLES = {
    'team': '/www/server/baseballmaster-collaboration/current',
    'live': '/www/server/baseballmaster-live/current',
    'lifan': '/opt/lifan-registration/current',
    'voice-lab': '/www/server/baseballmaster-voice-lab/current',
    'voice-assistant': '/www/server/baseballmaster-voice-assistant/current',
    'website': '/www/wwwroot/baseballmaster.cc',
}
CONFIG_FILES = [
    '/etc/baseballmaster-collaboration.env', '/etc/baseballmaster-admin-live.env',
    '/www/server/baseballmaster-collaboration-data/admin-auth.json',
    '/www/server/baseballmaster-collaboration-data/backup.key',
    '/etc/lifan-registration/config.json',
    '/www/server/baseballmaster-voice-lab/config.env',
    '/www/server/baseballmaster-voice-assistant/config.env',
    '/www/server/panel/vhost/nginx/baseballmaster.cc.conf',
    '/www/server/panel/vhost/nginx/baseballmaster-v3.inc',
    '/www/server/panel/vhost/nginx/baseballmaster-voice-lab.inc',
    '/www/server/panel/vhost/nginx/baseballmaster-voice-assistant.inc',
]
UNITS = [
    'baseballmaster-collaboration.service', 'baseballmaster-live.service',
    'lifan-registration.service', 'baseballmaster-voice-lab.service',
    'voice-scoring-assistant.service', 'baseballmaster-team-backup.service',
    'baseballmaster-team-backup.timer', 'baseballmaster-team-monitor.service',
    'baseballmaster-team-monitor.timer',
]


def guard(host):
    assert os.geteuid() == 0
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    address = opener.open('http://metadata.tencentyun.com/latest/meta-data/public-ipv4', timeout=4).read().decode().strip()
    assert address == host, 'Wrong host'


def digest(path):
    return state_transfer.digest_file(path)


def save(path, value):
    with open(path, 'x', opener=lambda name, flags: os.open(name, flags, 0o600)) as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        f.write('\n')
        f.flush()
        os.fsync(f.fileno())


def inventory(root):
    return {str(p.relative_to(root)): digest(p) for p in sorted(root.rglob('*')) if p.is_file()}


def copy_code(source, destination):
    source = Path(source).resolve()
    for p in source.rglob('*'):
        if p.is_symlink():
            assert p.resolve().is_relative_to(source), 'External source symlink requires separate review'
        assert p.is_file() or p.is_dir(), 'Unexpected source file type'
        assert '.git' not in p.relative_to(source).parts
        assert p.suffix not in ['.sqlite', '.db'], 'Mutable database found inside code; review scope'
        assert p.name not in ['.env', 'config.env'], 'Private config found inside code; review scope'
    before = inventory(source)
    shutil.copytree(source, destination, symlinks=False)
    after, copied = inventory(source), inventory(destination)
    assert before == after == copied, 'Code changed during capture'
    return {'sourceResolved': str(source), 'files': copied}


def make_tar(payload, destination):
    assert not destination.exists()
    with tarfile.open(destination, 'x', format=tarfile.PAX_FORMAT) as tar:
        for p in sorted(payload.rglob('*')):
            assert not p.is_symlink()
            if p.is_file():
                info = tar.gettarinfo(str(p), arcname=str(p.relative_to(payload)))
                info.uid = info.gid = info.mtime = 0
                info.uname = info.gname = ''
                info.mode = 0o600
                with p.open('rb') as f:
                    tar.addfile(info, f)
    destination.chmod(0o600)


def extract_private(archive, destination):
    assert not destination.exists()
    destination.mkdir(mode=0o700)
    with tarfile.open(archive, 'r:') as tar:
        members = tar.getmembers()
        assert len(members) <= 100000
        assert sum(m.size for m in members) <= 4 * 1024**3
        names = [m.name for m in members]
        assert len(names) == len(set(names))
        for m in members:
            relative = Path(m.name)
            assert m.isfile() and not m.linkname and not relative.is_absolute()
            assert '..' not in relative.parts and '\x00' not in m.name
            assert 0 <= m.size <= 2 * 1024**3
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            with tar.extractfile(m) as source, open(target, 'xb', opener=lambda name, flags: os.open(name, flags, 0o600)) as output:
                shutil.copyfileobj(source, output)


def verify_payload(payload):
    manifest = json.loads((payload / 'manifest.json').read_text())
    assert manifest['purpose'] == 'authorized-offline-precopy'
    assert manifest['activationEligible'] is False
    assert inventory(payload) == {**manifest['files'], 'manifest.json': digest(payload / 'manifest.json')}
    if manifest['kind'] == 'data':
        state_transfer.verify(payload / 'business-state')
        ledger = json.loads((payload / 'voice-assistant/access.json').read_text())
        assert ledger['version'] == 1 and isinstance(ledger['pepper'], str)
        for key in ['invites', 'entitlements', 'operations', 'sessions', 'modelCalls']:
            assert isinstance(ledger[key], list)
    return {'kind': manifest['kind'], 'files': len(manifest['files']), 'manifestSHA256': digest(payload / 'manifest.json')}


def transform(node, helper, action, source, target, key):
    result = subprocess.run([str(node), str(helper), action, str(source), str(target), str(key)], capture_output=True, timeout=180)
    assert result.returncode == 0, 'Authenticated container operation failed; details suppressed'


def capture(batch):
    guard(HK)
    assert re.fullmatch(r'precopy-[0-9TZ]+-[a-f0-9]{8}', batch)
    job = HK_BASE / batch
    assert (job / 'authorization.json').is_file()
    permission = json.loads((job / 'authorization.json').read_text())
    assert permission['realDataOfflinePrecopyApproved'] is True
    assert permission['preserveAllPersistentRecords'] is True
    assert permission['includeBothVoiceServices'] is True
    assert permission['trafficActivationApproved'] is False
    assert permission['dnsChangeApproved'] is False
    assert not (job / 'capture-receipt.json').exists()
    payloads = job / 'payloads'
    payloads.mkdir(mode=0o700)
    before_jingsen = digest(Path('/www/server/panel/vhost/nginx/jingsen.cc.conf'))
    initial_sources = {role: str(Path(path).resolve()) for role, path in ROLES.items()}

    code = payloads / 'code'
    code.mkdir(mode=0o700)
    versions = {role: copy_code(path, code / role) for role, path in ROLES.items()}
    data = payloads / 'data'
    data.mkdir(mode=0o700)
    state = state_transfer.capture(
        '/www/server/baseballmaster-collaboration-data/collaboration.sqlite',
        '/var/lib/lifan-registration/registrations.sqlite',
        '/lhcos-data/baseballmaster-private-v3/journal', data / 'business-state',
        online_precopy_only=True)
    (data / 'voice-assistant').mkdir(mode=0o700)
    ledger_source = Path('/www/server/baseballmaster-voice-assistant/data/access.json')
    # Atomic source rename means this opened inode is a complete old or new ledger.
    body = ledger_source.read_bytes()
    ledger = json.loads(body)
    assert ledger['version'] == 1 and isinstance(ledger['pepper'], str)
    (data / 'voice-assistant/access.json').write_bytes(body)
    (data / 'voice-assistant/access.json').chmod(0o600)

    config = payloads / 'configuration'
    config.mkdir(mode=0o700)
    paths = set(CONFIG_FILES)
    for unit in UNITS:
        r = subprocess.run(['systemctl', 'show', unit, '-p', 'FragmentPath', '--value'], capture_output=True, timeout=10)
        path = r.stdout.decode().strip()
        if path:
            assert path.startswith(('/etc/systemd/system/', '/usr/lib/systemd/system/'))
            paths.add(path)
        dropin = Path('/etc/systemd/system') / (unit + '.d')
        if dropin.is_dir():
            paths.update(str(p) for p in dropin.glob('*.conf'))
    site = Path('/www/server/panel/vhost/nginx/baseballmaster.cc.conf').read_text()
    for name in re.findall(r'^\s*ssl_certificate(?:_key)?\s+([^;]+);', site, re.M):
        p = Path(name.strip().strip('"').strip("'"))
        assert p.is_relative_to('/www/server/panel/vhost/cert/baseballmaster.cc')
        paths.add(str(p))
    for name in sorted(paths):
        source = Path(name)
        assert source.is_file(), 'Required configuration missing'
        target = config / 'filesystem' / name.lstrip('/')
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        shutil.copyfile(source, target)
        target.chmod(0o600)
        assert digest(source) == digest(target), 'Configuration changed during capture'

    receipts = {}
    for kind in ['code', 'data', 'configuration']:
        payload = payloads / kind
        manifest = {'schemaVersion': 1, 'purpose': 'authorized-offline-precopy',
                    'kind': kind, 'activationEligible': False,
                    'capturedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    'files': inventory(payload)}
        save(payload / 'manifest.json', manifest)
        verify_payload(payload)
        archive, sealed = job / (kind + '.tar'), job / (kind + '.sealed')
        make_tar(payload, archive)
        node = '/www/server/baseballmaster-node/node-v24.21.0-linux-x64/bin/node'
        helper, key = job / 'tools/state-archive.mjs', job / 'keys' / (kind + '.key')
        transform(node, helper, 'encrypt', archive, sealed, key)
        opened, restored = job / (kind + '-restore.tar'), job / (kind + '-restore')
        transform(node, helper, 'decrypt', sealed, opened, key)
        assert digest(opened) == digest(archive)
        extract_private(opened, restored)
        verified = verify_payload(restored)
        receipts[kind] = {**verified, 'file': sealed.name, 'bytes': sealed.stat().st_size,
                          'sha256': digest(sealed), 'authenticatedRestoreVerified': True}
        # Only this job's verified temporary copies; originals and sealed backups remain.
        shutil.rmtree(restored)
        opened.unlink()
        archive.unlink()
    assert initial_sources == {role: str(Path(path).resolve()) for role, path in ROLES.items()}
    assert before_jingsen == digest(Path('/www/server/panel/vhost/nginx/jingsen.cc.conf'))
    units = ['baseballmaster-collaboration.service', 'baseballmaster-live.service',
             'lifan-registration.service', 'baseballmaster-voice-lab.service',
             'voice-scoring-assistant.service', 'learningcenter.service']
    assert all(subprocess.run(['systemctl', 'is-active', '--quiet', unit]).returncode == 0 for unit in units)
    result = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'batch': batch, 'sourceHost': HK, 'destinationHost': SH,
              'purpose': 'authorized-offline-precopy', 'activationEligible': False,
              'capsules': receipts,
              'codeReleases': {role: value['sourceResolved'] for role, value in versions.items()},
              'databaseSummary': {role: {'tables': len(v['tables']), 'rows': {k: x['rows'] for k, x in v['tables'].items()},
                                             'logicalSHA256': v['logicalSHA256']}
                                  for role, v in state['databases'].items()},
              'voiceLedgerCounts': {key: len(ledger[key]) for key in ['invites', 'entitlements', 'operations', 'sessions', 'modelCalls']},
              'jingsenVhostUnchanged': True, 'allOriginalServicesActive': True,
              'sourceRecordsModified': False, 'historicalBackupsDeleted': False,
              'temporaryLiveDatabaseIncluded': False, 'includesOtherSites': False,
              'secretValuesPrinted': False}
    save(job / 'capture-receipt.json', result)
    shutil.rmtree(payloads)
    return result


def verify_destination(batch, *, arrived_only=False):
    guard(SH)
    job = SH_BASE / batch
    assert job.is_dir() and job.stat().st_mode & 0o077 == 0
    source = json.loads((job / 'capture-receipt.json').read_text())
    assert source['batch'] == batch and source['activationEligible'] is False
    result = {}
    for kind, details in source['capsules'].items():
        assert kind in ['code', 'data', 'configuration']
        sealed = job / 'encrypted' / details['file']
        if arrived_only and not sealed.exists():
            continue
        assert sealed.stat().st_size == details['bytes'] and digest(sealed) == details['sha256']
        opened, restored = job / (kind + '-verify.tar'), job / 'verification' / kind
        restored.parent.mkdir(mode=0o700, exist_ok=True)
        if not restored.exists():
            transform('/opt/baseballmaster-node/node-v24.21.0-linux-x64/bin/node',
                      job / 'tools/state-archive.mjs', 'decrypt', sealed, opened,
                      job / 'keys' / (kind + '.key'))
            extract_private(opened, restored)
            opened.unlink()
        verification = verify_payload(restored)
        assert verification['manifestSHA256'] == details['manifestSHA256']
        result[kind] = {**verification, 'ciphertextSHA256Verified': True}
        # Verified copies remain root-only and are never used as serving data.
    receipt = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
               'batch': batch, 'passed': True, 'activationEligible': False,
               'capsulesVerified': result, 'sourceLogicalStatePreserved': True,
               'allCapsulesVerified': len(result) == len(source['capsules']),
               'productionServicesStarted': False, 'productionTimersEnabled': False,
               'publicListenerEnabled': False, 'dnsChanged': False,
               'readOnlyOfflineVerification': True, 'secretValuesPrinted': False}
    save(job / ('verification-arrived-receipt.json' if arrived_only else 'verification-receipt.json'), receipt)
    return receipt


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['capture', 'verify', 'verify-arrived'])
    parser.add_argument('batch')
    args = parser.parse_args()
    assert re.fullmatch(r'precopy-[0-9TZ]+-[a-f0-9]{8}', args.batch)
    result = capture(args.batch) if args.action == 'capture' else verify_destination(args.batch, arrived_only=args.action == 'verify-arrived')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'passed': False, 'errorType': type(error).__name__, 'detailsSuppressed': True}), file=sys.stderr)
        sys.exit(1)
