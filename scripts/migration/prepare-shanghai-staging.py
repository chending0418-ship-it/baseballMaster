#!/usr/bin/env python3
"""Shanghai-only runtime preparation. Never starts a public web listener."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

CONTROL = Path('/var/lib/baseballmaster-staging-control')
NODE_VERSION = '24.21.0'
NODE_SHA256 = 'fd8e59d5a511510f6a298afb548f18c7d2b1be404d8b4a27d94fbe49f56cb2d6'


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def main():
    assert os.geteuid() == 0, 'Requires root on the Shanghai test server'
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for field, expected in [('public-ipv4', '101.43.112.8'), ('placement/region', 'ap-shanghai')]:
        actual = opener.open('http://metadata.tencentyun.com/latest/meta-data/' + field, timeout=3).read().decode().strip()
        assert actual == expected, 'Wrong target; refusing to prepare'
    CONTROL.mkdir(mode=0o700, exist_ok=True)
    marker = CONTROL / 'synthetic-only.json'
    if marker.exists():
        assert json.loads(marker.read_text())['syntheticOnly'] is True
    else:
        marker.write_text(json.dumps({'syntheticOnly': True, 'host': '101.43.112.8', 'productionDataPermitted': False}) + '\n')
        marker.chmod(0o600)
        (CONTROL / 'packages-before.txt').write_text(subprocess.check_output(['dpkg-query', '-W', '-f', '${Package} ${Version}\n'], text=True))
        (CONTROL / 'listeners-before.txt').write_text(subprocess.check_output(['ss', '-lntupH'], text=True))
    if not shutil.which('nginx'):
        policy = Path('/usr/sbin/policy-rc.d')
        assert not policy.exists(), 'Existing package-start policy requires review'
        run(['systemctl', 'mask', 'nginx.service'])
        policy.write_text('#!/bin/sh\nexit 101\n')
        policy.chmod(0o755)
        try:
            env = {**os.environ, 'DEBIAN_FRONTEND': 'noninteractive', 'NEEDRESTART_MODE': 'l'}
            print('Updating package index; nginx automatic startup is blocked.', flush=True)
            run(['apt-get', 'update', '-qq'], env=env)
            run(['apt-get', 'install', '-y', '--no-install-recommends', 'nginx-core'], env=env)
        finally:
            policy.unlink()
    assert subprocess.run(['systemctl', 'is-active', '--quiet', 'nginx.service']).returncode != 0
    node_root = Path('/opt/baseballmaster-node')
    node_root.mkdir(mode=0o755, exist_ok=True)
    node = node_root / f'node-v{NODE_VERSION}-linux-x64/bin/node'
    if not node.exists():
        archive = CONTROL / f'node-v{NODE_VERSION}-linux-x64.tar.xz'
        if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != NODE_SHA256:
            print('Downloading pinned Node runtime from nodejs.org.', flush=True)
            run(['curl', '--fail', '--silent', '--show-error', '--location', '--continue-at', '-', '--connect-timeout', '10', '--max-time', '300',
                 '--output', str(archive), f'https://nodejs.org/download/release/v{NODE_VERSION}/{archive.name}'])
        assert hashlib.sha256(archive.read_bytes()).hexdigest() == NODE_SHA256, 'Node checksum mismatch'
        run(['tar', '-xJf', str(archive), '-C', str(node_root), '--no-same-owner'])
    print('NODE_RUNTIME', subprocess.check_output([str(node), '--version'], text=True).strip())
    listeners = subprocess.check_output(['ss', '-lntH'], text=True)
    assert not any(line.split()[3].rsplit(':', 1)[-1] in ['80', '443'] for line in listeners.splitlines())
    print('Prepared runtime; public 80/443 remain unbound.', flush=True)


if __name__ == '__main__':
    main()
