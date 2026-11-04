#!/usr/bin/env python3
"""Download official archives, verify hashes, and locally extract accepted Apple bezels.

Standalone Apple files stay in ignored apple-originals/. Do not include them in
source/delivery ZIPs. --accept-apple-license must reflect the operator's explicit
acceptance of the Apple Design Resources license, not inferred task permission.
"""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import shutil
import subprocess
import tempfile


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accept-apple-license', action='store_true')
    parser.add_argument('--download-only', action='store_true')
    args = parser.parse_args()
    hardware = Path(__file__).resolve().parent
    root = next(p for p in hardware.parents if (p / 'BaseballMaster.xcodeproj').is_dir())
    cache = root / 'output/cache/3.1.2-device-bezels'
    cache.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((hardware / 'manifest.json').read_text())
    devices = json.loads((hardware / 'apple-provenance.json').read_text())['devices']
    for item in manifest['downloads']:
        archive = cache / Path(item['local_original']).name
        if not archive.exists() or sha256(archive) != item['sha256']:
            partial = archive.with_suffix('.dmg.partial')
            subprocess.run(['curl', '--fail', '--location', '--retry', '2',
                            '--output', str(partial), item['download_url']], check=True)
            if sha256(partial) != item['sha256']:
                raise RuntimeError('Apple archive hash differs from reviewed original: ' + archive.name)
            partial.replace(archive)
        if archive.stat().st_size != item['bytes']:
            raise RuntimeError('Unexpected byte count: ' + archive.name)
        print('Verified', archive.name)
    if args.download_only:
        return
    if not args.accept_apple_license:
        raise SystemExit('Extraction requires explicit acceptance. Read the Apple Design Resources license, then run with --accept-apple-license if you accept it.')
    originals = hardware / 'apple-originals'
    originals.mkdir(exist_ok=True)
    for item in manifest['downloads']:
        archive = cache / Path(item['local_original']).name
        current = plistlib.loads(subprocess.check_output(['hdiutil', 'info', '-plist']))
        if any(Path(i.get('image-path', '')).resolve() == archive.resolve() for i in current.get('images', [])):
            raise RuntimeError('Archive already mounted; leave it untouched and unmount it separately first: ' + str(archive))
        with tempfile.TemporaryDirectory(prefix='apple-bezel-mount-', dir=cache) as tmp:
            mounted = False
            try:
                result = subprocess.run(['hdiutil', 'attach', '-readonly', '-nobrowse',
                                         '-mountpoint', tmp, '-plist', str(archive)],
                                        input=b'Y\n', capture_output=True, check=True)
                start = result.stdout.index(b'<?xml')
                attached = plistlib.loads(result.stdout[start:])
                mount_points = [e['mount-point'] for e in attached['system-entities'] if 'mount-point' in e]
                if mount_points != [tmp]:
                    raise RuntimeError('Unexpected mount point; do not access unrelated volume: ' + repr(mount_points))
                mounted = True
                for device in devices:
                    if device['archive'] != archive.name:
                        continue
                    source = Path(tmp) / device['sourcePath']
                    destination = hardware / device['file']
                    if sha256(source) != device['sha256']:
                        raise RuntimeError('Original PNG hash mismatch: ' + device['id'])
                    shutil.copyfile(source, destination)
                    print('Copied original', device['id'])
                license_file = Path(tmp) / 'Apple Design Resources License.rtf'
                if license_file.exists():
                    shutil.copyfile(license_file, cache / (archive.stem + '-license.rtf'))
            finally:
                if mounted:
                    subprocess.run(['hdiutil', 'detach', tmp], check=True)
    subprocess.run(['node', str(hardware / 'measure-apple-bezels.cjs')], check=True)


if __name__ == '__main__':
    main()
