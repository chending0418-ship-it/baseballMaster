"""Package the five App Store creatives and verify their exact upload bytes."""
from pathlib import Path
import hashlib
import json
import shutil
import struct
import zipfile

root = Path(__file__).resolve().parents[2]
art = root / 'output/marketing/3.0/app-store-creative'
release = root / 'output/releases/3.0'
docs = root / 'docs/releases/3.0'
qa = root / 'output/validation/3.0/creative-assets'
packet = release / 'BaseballMaster-3.0-AppStore-Creative'
packet.mkdir(parents=True, exist_ok=True)
sha = lambda data: hashlib.sha256(data).hexdigest()
manifest = json.loads((art / 'manifest.json').read_text())
expected = {
    'header-5244x2950': (5244, 2950),
    'header-3840x1646': (3840, 1646),
    'search-5244x2950': (5244, 2950),
    'search-3840x2560': (3840, 2560),
    'search-1920x1280': (1920, 1280),
}
assert {f['id'] for f in manifest['files']} == set(expected)
verified = []
for f in manifest['files']:
    data = (art / f['file']).read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    width, height, depth, color = struct.unpack('>IIBB', data[16:26])
    assert (width, height) == expected[f['id']] == (f['w'], f['h'])
    assert depth == 8 and color == 2, 'Expected opaque 8-bit RGB PNG'
    assert sha(data) == f['sha256']
    chunks, pos = [], 8
    while pos < len(data):
        size = struct.unpack('>I', data[pos:pos + 4])[0]
        chunks.append(data[pos + 4:pos + 8])
        pos += size + 12
    assert b'tRNS' not in chunks and b'iCCP' in chunks
    assert f['colorSpace'] == 'srgb' and f['icc'] and not f['alpha']
    x, y, w, h = f['safe']
    for b in f['layout']['bounds']:
        assert b['x'] >= x and b['y'] >= y
        assert b['right'] <= x + w and b['bottom'] <= y + h
    verified.append({'file': f['file'], 'width': width, 'height': height,
                     'opaqueRGB8WithICC': True, 'safeAreaPassed': True,
                     'sha256': f['sha256']})
for relative, digest in manifest['sourceHashes'].items():
    assert sha((root / relative).read_bytes()) == digest

guide = docs / 'CREATIVE-ASSETS.md'
shutil.copy2(guide, art / 'README.md')
for folder in ('upload', 'preview', 'source'):
    shutil.copytree(art / folder, packet / folder, dirs_exist_ok=True)
for filename in ('index.html', 'overview.png', 'manifest.json', 'README.md'):
    shutil.copy2(art / filename, packet / filename)
checks = {str(p.relative_to(packet)): sha(p.read_bytes())
          for p in sorted(packet.rglob('*')) if p.is_file() and p.name != 'checksums.json'}
assert len(list((packet / 'upload').rglob('*.png'))) == 5
assert all(Path(p).suffix in ('.png', '.html', '.json', '.md') for p in checks)
(packet / 'checksums.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n')
zip_path = release / (packet.name + '.zip')
with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as zipped:
    for p in sorted(packet.rglob('*')):
        if p.is_file():
            zipped.write(p, p.relative_to(packet.parent))
with zipfile.ZipFile(zip_path) as zipped:
    assert zipped.testzip() is None
    for filename, digest in checks.items():
        assert sha(zipped.read(packet.name + '/' + filename)) == digest
shutil.copy2(zip_path, docs / zip_path.name)
assert sha(zip_path.read_bytes()) == sha((docs / zip_path.name).read_bytes())
result = {'uploadAssets': verified, 'files': len(checks) + 1,
          'bytes': zip_path.stat().st_size, 'sha256': sha(zip_path.read_bytes()),
          'allOriginalSourceHashesMatch': True, 'zipCRCAndEveryFileHashMatch': True,
          'connectUploaded': False, 'appleApproved': False}
(qa / 'creative-package.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({k: v for k, v in result.items() if k != 'uploadAssets'}, indent=2))
