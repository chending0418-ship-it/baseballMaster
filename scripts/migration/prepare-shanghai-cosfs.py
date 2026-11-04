#!/usr/bin/env python3
"""Install the pinned COSFS runtime on the Shanghai synthetic host; do not mount."""
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import urllib.request

CONTROL = Path('/var/lib/baseballmaster-staging-control')
PACKAGE = CONTROL / 'downloads/cosfs_1.0.25-ubuntu24.04_amd64.deb'
SHA256 = '1bc825b31b96e3be71ca597e96ab038afc8383362e59cfc121bc9a92b6bef9dc'
SOURCE = 'https://github.com/tencentyun/cosfs/releases/download/v1.0.25/' + PACKAGE.name


def run(args, timeout=20, **kwargs):
    result = subprocess.run(args, capture_output=True, timeout=timeout, **kwargs)
    if result.returncode:
        raise RuntimeError('Command failed: ' + args[0])
    return result.stdout


def main():
    assert os.geteuid() == 0
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    actual = opener.open('http://metadata.tencentyun.com/latest/meta-data/public-ipv4', timeout=3).read().decode().strip()
    assert actual == '101.43.112.8', 'Wrong host'
    assert json.loads((CONTROL / 'synthetic-only.json').read_text())['syntheticOnly'] is True
    assert hashlib.sha256(PACKAGE.read_bytes()).hexdigest() == SHA256
    assert run(['uname', '-m']).strip() == b'x86_64'
    assert 'VERSION_ID="24.04"' in Path('/etc/os-release').read_text()
    assert Path('/dev/fuse').exists(), 'FUSE device unavailable'

    # Inspect the exact package before executing the package manager as root.
    control = run(['dpkg-deb', '--ctrl-tarfile', str(PACKAGE)])
    with tarfile.open(fileobj=io.BytesIO(control), mode='r:') as archive:
        assert not any(m.name.removeprefix('./') in ['preinst', 'postinst', 'prerm', 'postrm'] for m in archive)
    payload = run(['dpkg-deb', '--fsys-tarfile', str(PACKAGE)])
    with tarfile.open(fileobj=io.BytesIO(payload), mode='r:') as archive:
        expected = {'./usr/local/bin/cosfs', './usr/share/doc/cosfs/changelog.gz'}
        assert {m.name for m in archive if m.isfile()} == expected
        assert not any(m.issym() or m.islnk() for m in archive)
        executable_digest = hashlib.sha256(archive.extractfile('./usr/local/bin/cosfs').read()).hexdigest()

    executable = Path('/usr/local/bin/cosfs')
    if executable.exists():
        assert hashlib.sha256(executable.read_bytes()).hexdigest() == executable_digest, 'Refusing to replace another COSFS'
    baseline = run(['ss', '-H', '-lnt'])
    units = ['baseballmaster-staging-' + name + '.service' for name in ['web', 'team', 'live', 'lifan']]
    assert all(run(['systemctl', 'is-active', unit]).strip() == b'active' for unit in units)
    # The upstream package declares "fuse", which Ubuntu's FUSE 3 may satisfy;
    # the binary itself still requires the separate FUSE 2 shared library.
    packages = [str(PACKAGE), 'libfuse2t64']
    proposed = run(['apt-get', '--simulate', 'install', '--no-install-recommends', *packages], timeout=45).decode()
    assert not any(line.startswith('Remv ') for line in proposed.splitlines()), 'Package removal proposed'
    assert all(line.split()[1] in ['cosfs', 'libfuse2t64'] for line in proposed.splitlines() if line.startswith('Inst ')), 'Unexpected dependency change'
    env = os.environ.copy()
    env.update(DEBIAN_FRONTEND='noninteractive', NEEDRESTART_MODE='l')
    log = CONTROL / 'cosfs-install.log'
    with open(log, 'wb', opener=lambda name, flags: os.open(name, flags, 0o600)) as output:
        result = subprocess.run(['apt-get', 'install', '-y', '--no-install-recommends', *packages],
                                stdout=output, stderr=subprocess.STDOUT, env=env, timeout=120)
    assert result.returncode == 0, 'Installation failed; private installation log retained'
    assert hashlib.sha256(executable.read_bytes()).hexdigest() == executable_digest
    assert b'not found' not in run(['ldd', str(executable)]), 'Unresolved runtime library'
    version = run([str(executable), '--version']).decode().strip()
    assert '1.0.25' in version
    assert run(['ss', '-H', '-lnt']) == baseline, 'Network listeners changed'
    assert all(run(['systemctl', 'is-active', unit]).strip() == b'active' for unit in units)
    receipt = {
        'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'host': actual, 'runtime': version, 'source': SOURCE,
        'packageSHA256': SHA256, 'executableSHA256': executable_digest,
        'requiredLibrary': 'libfuse2t64', 'unexpectedDependenciesInstalled': False, 'listenersUnchanged': True,
        'stagingServicesActive': True, 'credentialsConfigured': False,
        'bucketMounted': False, 'dataUploaded': False, 'productionDataCopied': False,
    }
    path = CONTROL / 'cosfs-runtime-receipt.json'
    path.write_text(json.dumps(receipt, indent=2) + '\n')
    path.chmod(0o600)
    print(json.dumps(receipt))


if __name__ == '__main__':
    main()
