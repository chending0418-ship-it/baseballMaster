"""Refresh the existing 3.0 review package without including archives or credentials."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile
import argparse

parser = argparse.ArgumentParser()
parser.add_argument('--validation-task', default='roster-permissions')
args = parser.parse_args()
assert args.validation_task and Path(args.validation_task).name == args.validation_task

root = Path(__file__).resolve().parents[2]
docs = root / 'docs/releases/3.0'
release = root / 'output/releases/3.0'
packet = release / 'BaseballMaster-3.0-AppStore'
assert packet.is_dir(), 'Render and prepare the original release package first.'
for target in packet.iterdir():
    if target.suffix in ('.md', '.txt'):
        source = docs / target.name
        assert source.is_file(), source
        shutil.copy2(source, target)
creative = root / 'output/marketing/3.0/app-store-creative'
creative_guide = docs / 'CREATIVE-ASSETS.md'
if creative_guide.is_file():
    shutil.copy2(creative_guide, packet / creative_guide.name)
    shutil.copytree(creative / 'upload', packet / 'creative-assets/upload', dirs_exist_ok=True)
    shutil.copy2(creative / 'manifest.json', packet / 'creative-assets/manifest.json')
    assert len(list((packet / 'creative-assets/upload').rglob('*.png'))) == 5
pdf = root / 'output/releases/3.0/guides/BaseballMaster-3.0-更新与使用说明.pdf'
for destination in (packet / pdf.name, docs / pdf.name):
    shutil.copy2(pdf, destination)
archive = json.loads((root / 'output/validation/3.0/roster-permissions/final-archive.json').read_text())
manifest_path = packet / 'manifest.json'
manifest = json.loads(manifest_path.read_text())
manifest.update({
    'updated': '2026-10-13',
    'appVersion': '3.0 (1)',
    'appBinaryUUID': archive['binaryUUID'],
    'permissions': {
        'playerAddEdit': ['admin', 'scorer'],
        'maximumActiveAdministratorsPerTeam': 2,
        'administratorCountBy': 'member identity, not paired devices',
    },
})
if creative_guide.is_file():
    manifest['creativeAssets'] = {
        'guide': 'CREATIVE-ASSETS.md', 'files': 5,
        'manifest': 'creative-assets/manifest.json',
        'headerSizes': [[5244, 2950], [3840, 1646]],
        'searchSizes': [[5244, 2950], [3840, 2560], [1920, 1280]],
        'format': '8-bit RGB sRGB PNG without alpha',
        'connectUploaded': False,
    }
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
checks = {str(p.relative_to(packet)): sha(p) for p in sorted(packet.rglob('*'))
          if p.is_file() and p.name != 'checksums.json'}
assert not any(Path(p).suffix in ('.key', '.sqlite', '.db') for p in checks)
(packet / 'checksums.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n')
zip_path = release / 'BaseballMaster-3.0-AppStore.zip'
with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as zipped:
    for p in sorted(packet.rglob('*')):
        if p.is_file():
            zipped.write(p, p.relative_to(packet.parent))
with zipfile.ZipFile(zip_path) as zipped:
    assert zipped.testzip() is None
    for name, expected in checks.items():
        actual = hashlib.sha256(zipped.read(packet.name + '/' + name)).hexdigest()
        assert actual == expected, name
shutil.copy2(zip_path, docs / zip_path.name)
assert sha(zip_path) == sha(docs / zip_path.name)
result = {'files': len(checks) + 1, 'bytes': zip_path.stat().st_size,
          'sha256': sha(zip_path), 'appBinaryUUID': archive['binaryUUID'],
          'checkedEveryFileAndZipCRC': True, 'documentationCopyIdentical': True}
validation = root / 'output/validation/3.0' / args.validation_task
validation.mkdir(parents=True, exist_ok=True)
(validation / 'package.json').write_text(json.dumps(result, indent=2))
print(json.dumps(result, indent=2))
