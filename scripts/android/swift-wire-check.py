#!/usr/bin/env python3
"""Compile original Swift Codable models for a non-UI, non-GameStore wire check.

SWIFTC must name an installed Swift compiler. Sources are copied to output/cache;
application files are never edited. No Xcode/iOS runtime result is implied.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output', required=True, type=Path)
p.add_argument('--input', action='append', type=Path, dest='inputs')
p.add_argument('--input-directory', type=Path)
a = p.parse_args()
a.output = a.output.resolve()
a.output.mkdir(parents=True, exist_ok=False)
cache = ROOT / 'output/cache/android/swift-wire'
cache.mkdir(parents=True, exist_ok=True)

# Anchored slices retain the exact declarations and Codable implementations.
# These anchors deliberately fail when source organization changes; no guessed stubs.
def source(path, start=None, end=None, replace_import=False):
    raw = (ROOT / path).read_text()
    text = raw
    if start:
        assert text.count(start) == 1, (path, start)
        text = text[text.index(start):]
    if end:
        assert text.count(end) == 1, (path, end)
        text = text[:text.index(end)]
    if replace_import:
        text = text.replace('import SwiftUI', 'import Foundation').replace('import CryptoKit', '')
    target = cache / Path(path).name
    target.write_text('import Foundation\n' + text)
    return target, {'path': path, 'sha256': hashlib.sha256(raw.encode()).hexdigest(), 'start': start, 'end': end,
                    'extractedSha256': hashlib.sha256(target.read_bytes()).hexdigest()}

specs = [('BaseballMaster/Models.swift', None, None, False),
         ('BaseballMaster/SharedMatches.swift', 'struct MatchPlayerLink:', 'struct HistoricalImportCandidate:', False),
         ('BaseballMaster/BattedBall.swift', None, 'struct RecordingMethodPicker: View {', True),
         ('BaseballMaster/SituationRecording.swift', None, 'enum SituationFieldMode:', True),
         ('BaseballMaster/HistoryCorrection.swift', None, 'struct HistoryCorrectionDraft:', True),
         ('BaseballMaster/PersistenceController.swift', 'struct RosterSnapshot:', '@MainActor', False)]
extracted = [source(*spec) for spec in specs]
compiler = os.environ.get('SWIFTC', shutil.which('swiftc'))
if not compiler:
    raise SystemExit('Set SWIFTC to an installed compiler; no Swift check was run')
binary = cache / 'swift-wire'
command = [compiler, *[str(f) for f, _ in extracted], str(ROOT / 'scripts/android/swift-wire/main.swift'), '-o', str(binary)]
(a.output / 'sources.json').write_text(json.dumps([m for _, m in extracted], indent=2))
with (a.output / 'compile.txt').open('w') as log:
    subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT)
(a.output / 'sources.json').write_text(json.dumps([m for _, m in extracted], indent=2))
(a.output / 'swift-version.txt').write_text(subprocess.check_output([compiler, '--version'], text=True))
assert not (a.inputs and a.input_directory), 'Use --input or --input-directory'
inputs = a.inputs or sorted((a.input_directory or ROOT / 'test-fixtures/android-parity/backup').glob('*.bmbackup'))
assert inputs, 'No backup inputs; no check was performed'
results = []
for index, input_path in enumerate(inputs):
    input_path = input_path.resolve()
    # Hash exactly the bytes passed to Swift, even if a producer regenerates the
    # path during this run. The return-leg check will reject a changed input.
    source_bytes = input_path.read_bytes()
    envelope = json.loads(source_bytes)
    payload = base64.b64decode(envelope['payload'], validate=True)
    assert hashlib.sha256(payload).hexdigest() == envelope['checksum'], input_path
    name = f'{index:03}-{input_path.stem}'
    raw_input = a.output / f'{name}.snapshot.json'
    raw_output = a.output / f'{name}.swift.json'
    raw_input.write_bytes(payload)
    run = subprocess.run([str(binary), str(raw_input), str(raw_output)], capture_output=True, text=True)
    result = {'input': str(input_path), 'passed': run.returncode == 0, 'stdout': run.stdout, 'stderr': run.stderr,
              'sourceSha256': hashlib.sha256(source_bytes).hexdigest()}
    if run.returncode == 0:
        encoded = raw_output.read_bytes()
        outer = dict(envelope, payload=base64.b64encode(encoded).decode(), checksum=hashlib.sha256(encoded).hexdigest())
        (a.output / f'{name}.bmbackup').write_text(json.dumps(outer, separators=(',', ':')))
    if run.returncode == 0:
        invalid = json.loads(payload)
        del invalid['teams']
        invalid_path = a.output / f'{name}.invalid-missing-teams.json'
        invalid_path.write_text(json.dumps(invalid))
        rejected = subprocess.run([str(binary), str(invalid_path), str(a.output / f'{name}.invalid-output.json')], capture_output=True, text=True)
        result['negativeExitCode'] = rejected.returncode
        result['missingRequiredFieldRejected'] = rejected.returncode == 1 and not (a.output / f'{name}.invalid-output.json').exists()
        result['passed'] = result['passed'] and result['missingRequiredFieldRejected']
    results.append(result)
(a.output / 'results.json').write_text(json.dumps(results, indent=2))
print(json.dumps({'files': len(results), 'passed': sum(r['passed'] for r in results)}))
if not all(r['passed'] for r in results):
    raise SystemExit('Swift model decode failed; see results.json')
