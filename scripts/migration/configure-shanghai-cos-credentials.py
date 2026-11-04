#!/usr/bin/env python3
"""Receive the dedicated staging credentials over verified SSH standard input."""
import datetime
import json
import os
from pathlib import Path
import re
import sys
import urllib.request

CONFIG = Path('/etc/baseballmaster-staging')
CONTROL = Path('/var/lib/baseballmaster-staging-control')
BUCKET = 'bm-backup-sh-1387380066'


def private_write(path, content):
    encoded = content.encode()
    if path.exists():
        assert not path.is_symlink() and path.read_bytes() == encoded, 'Refusing to rotate existing credentials'
    else:
        with open(path, 'xb', opener=lambda name, flags: os.open(name, flags, 0o600)) as file:
            file.write(encoded)
    path.chmod(0o600)


def main():
    assert os.geteuid() == 0
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    actual = opener.open('http://metadata.tencentyun.com/latest/meta-data/public-ipv4', timeout=3).read().decode().strip()
    assert actual == '101.43.112.8', 'Wrong target'
    assert json.loads((CONTROL / 'synthetic-only.json').read_text())['syntheticOnly'] is True
    payload = json.load(sys.stdin)
    assert payload['Username'] == 'bm-shanghai-backup', 'Unexpected account'
    assert re.fullmatch(r'AKID[A-Za-z0-9]{16,100}', payload['SecretId'])
    assert re.fullmatch(r'[A-Za-z0-9+/=_-]{20,128}', payload['SecretKey'])
    restricted = {name: payload[name] for name in ['Username', 'SecretId', 'SecretKey']}
    restricted.update(Bucket=BUCKET, Region='ap-shanghai', Prefix='staging/')
    private_write(CONFIG / 'cos-credentials.json', json.dumps(restricted) + '\n')
    private_write(CONFIG / 'passwd-cosfs', BUCKET + ':' + payload['SecretId'] + ':' + payload['SecretKey'] + '\n')
    receipt = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
               'host': actual, 'user': payload['Username'], 'bucket': BUCKET,
               'region': 'ap-shanghai', 'prefix': 'staging/', 'credentialFileMode': '0600',
               'secretTransport': 'SSH standard input', 'credentialsPrinted': False,
               'accessVerified': False, 'bucketMounted': False, 'dataUploaded': False}
    path = CONTROL / 'cos-credentials-receipt.json'
    if path.exists():
        existing = json.loads(path.read_text())
        assert all(existing.get(field) == receipt[field] for field in ['host', 'user', 'bucket', 'region', 'prefix'])
        receipt = existing
    else:
        private_write(path, json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('Staging credential configuration failed; secret details suppressed', file=sys.stderr)
        sys.exit(1)
