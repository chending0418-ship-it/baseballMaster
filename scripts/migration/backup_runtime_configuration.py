#!/usr/bin/env python3
"""Seal only this application's runtime configuration with a separate recovery key."""
import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid
import authorized_precopy as common


def main():
    os.umask(0o077); common.guard(common.SH)
    own = Path(__file__).resolve().parent
    request = json.load(sys.stdin)
    key = Path(request['configurationRecoveryKeyFile'])
    assert str(key).startswith('/var/lib/baseballmaster-production-precopy/')
    assert key.name == 'configuration.key' and key.stat().st_mode & 0o077 == 0
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    job = Path('/var/lib/baseballmaster-production/backup-work') / ('configuration-' + stamp)
    payload = job / 'payload'; payload.mkdir(parents=True, mode=0o700)
    files = [p for p in Path('/etc/baseballmaster-production').rglob('*') if p.is_file()]
    files += list(Path('/etc/systemd/system').glob('baseballmaster-mainland*'))
    files += [Path('/etc/systemd/system/run-baseballmaster\\x2dproduction\\x2dlive.mount')]
    runtime = json.loads(Path('/etc/baseballmaster-production/runtime.json').read_text())
    active_manifest = Path(runtime['codeManifest'])
    assert active_manifest.parent == Path(runtime['codeRoot']).parent
    files.append(active_manifest)
    metadata = {}
    for p in files:
        assert p.is_file() and not p.is_symlink()
        target = payload / str(p).lstrip('/')
        target.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
        before = common.digest(p); shutil.copyfile(p, target); target.chmod(0o600)
        assert before == common.digest(target) == common.digest(p)
        metadata[str(p)] = {'mode': oct(p.stat().st_mode & 0o777), 'uid': p.stat().st_uid, 'gid': p.stat().st_gid}
    common.save(payload / 'permissions.json', metadata)
    manifest = common.inventory(payload)
    common.save(payload / 'manifest.json', {'purpose': 'encrypted-runtime-configuration-backup', 'files': manifest})
    common.make_tar(payload, job / 'configuration.tar')
    node = '/opt/baseballmaster-node/node-v24.21.0-linux-x64/bin/node'
    common.transform(node, own / 'state-archive.mjs', 'encrypt', job / 'configuration.tar', job / 'configuration.sealed', key)
    object_key = 'production/configuration/daily-' + stamp + '.sealed'
    p = subprocess.run([node, str(own / 'cos_archive_io.mjs'), str(job / 'configuration.sealed'), object_key, str(job / 'downloaded.sealed')], capture_output=True, timeout=120)
    assert p.returncode == 0
    common.transform(node, own / 'state-archive.mjs', 'decrypt', job / 'downloaded.sealed', job / 'restored.tar', key)
    common.extract_private(job / 'restored.tar', job / 'restored')
    assert common.inventory(job / 'restored') == common.inventory(payload)
    result = {'passed': True, 'configurationFiles': len(files), 'objectKey': object_key,
              'downloadDecryptAndFileHashesVerified': True, 'independentMacRecoveryKeyRequired': True,
              'containsOnlyScopedRuntimeConfiguration': True, 'secretValuesPrinted': False}
    common.save(job / 'receipt.json', result)
    for name in ['payload', 'restored']: shutil.rmtree(job / name)
    for name in ['configuration.tar', 'restored.tar']: (job / name).unlink()
    print(json.dumps(result))


if __name__ == '__main__':
    try: main()
    except Exception as error:
        print(json.dumps({'passed': False, 'errorType': type(error).__name__, 'detailsSuppressed': True}), file=sys.stderr)
        sys.exit(1)
