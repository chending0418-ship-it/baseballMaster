#!/usr/bin/env python3
"""Admit an approved static publication without changing any backend digest."""
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import sys
import urllib.request
import uuid


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as file:
        for block in iter(lambda: file.read(1024 * 1024), b''): h.update(block)
    return h.hexdigest()


def main():
    os.umask(0o077)
    assert os.geteuid() == 0
    assert urllib.request.urlopen('http://metadata.tencentyun.com/latest/meta-data/public-ipv4', timeout=4).read().decode().strip() == '101.43.112.8'
    request = json.load(sys.stdin)
    with open('/run/baseballmaster-website-manifest.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        config = Path('/etc/baseballmaster-production/runtime.json')
        runtime = json.loads(config.read_text())
        root = Path(runtime['codeRoot'])
        website = root / 'website'
        assert str(website) == request['root']
        old_path = Path(runtime['codeManifest'])
        old = json.loads(old_path.read_text())
        assert sha(old_path) == runtime['codeManifestSHA256']
        actual = {str(p.relative_to(root)): p for p in root.rglob('*') if p.is_file()}
        backend_expected = {n: h for n, h in old['files'].items() if not n.startswith('website/')}
        assert {n for n in actual if not n.startswith('website/')} == set(backend_expected)
        assert all(sha(actual[n]) == h for n, h in backend_expected.items())
        approved = request['approvedManifest']
        for name, path in actual.items():
            assert not path.is_symlink() and path.stat().st_uid == 0 and path.stat().st_mode & 0o022 == 0
            if name.startswith('website/') and old['files'].get(name) != sha(path):
                relative = str(path.relative_to(website))
                assert approved.get(relative) == sha(path), 'Unapproved static drift'
        assert not any(n not in actual for n in old['files']), 'Static deletion requires separate review'
        for name, digest in approved.items():
            relative = Path(name)
            assert not relative.is_absolute() and '..' not in relative.parts
            assert sha(website / relative) == digest
        updated = dict(old)
        updated['files'] = {n: sha(p) for n, p in actual.items()}
        updated['approvedWebsitePublication'] = request['batch']
        name = 'code-manifest-website-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8] + '.json'
        new_path = old_path.parent / name
        with new_path.open('x') as file: json.dump(updated, file, indent=2); file.write('\n')
        new_path.chmod(0o644)
        runtime.update(codeManifest=str(new_path), codeManifestSHA256=sha(new_path))
        pending = config.with_name('runtime-website-' + uuid.uuid4().hex + '.partial')
        pending.write_text(json.dumps(runtime, indent=2) + '\n'); pending.chmod(0o644)
        os.replace(pending, config)
        print(json.dumps({'passed': True, 'approvedStaticFiles': len(approved),
                          'backendCodeHashesUnchanged': True, 'immutableManifest': str(new_path)}))


if __name__ == '__main__':
    try: main()
    except Exception as error:
        print(json.dumps({'passed': False, 'errorType': type(error).__name__, 'detailsSuppressed': True}), file=sys.stderr)
        sys.exit(1)
