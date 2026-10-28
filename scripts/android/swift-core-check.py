#!/usr/bin/env python3
"""Replay backups with original Swift GameStore/HistoryCorrection on Linux.

Only platform imports/adapters change. No Xcode, Core Data, UI, networking or
real-device result is implied. Inputs must be local modern-journal backups.
Run AFTER Kotlin/Room sample producers, never concurrently with them.
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
p.add_argument('--input-directory', required=True, type=Path)
a = p.parse_args()
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=False)
cache = out / 'sources'
cache.mkdir()
metadata = []


def extract(path, slices=None, imports=()):
    raw = (ROOT / path).read_text()
    parts = []
    for start, end in slices or [(None, None)]:
        text = raw
        if start:
            assert text.count(start) == 1, (path, start)
            text = text[text.index(start):]
        if end:
            assert text.count(end) == 1, (path, end)
            text = text[:text.index(end)]
        parts.append(text)
    text = 'import Foundation\n' + '\n'.join(parts)
    for module in imports:
        text = text.replace('import ' + module, '')
    target = cache / Path(path).name
    target.write_text(text)
    metadata.append(dict(path=path, sha256=hashlib.sha256(raw.encode()).hexdigest(),
                         slices=slices, removedImports=imports,
                         extractedSha256=hashlib.sha256(target.read_bytes()).hexdigest()))
    return target


sources = [
    extract('BaseballMaster/Models.swift'),
    extract('BaseballMaster/GameStore.swift', imports=('Combine',)),
    extract('BaseballMaster/HistoryCorrection.swift', imports=('CryptoKit',)),
    extract('BaseballMaster/Statistics.swift'),
    extract('BaseballMaster/PersistenceController.swift', [('struct RosterSnapshot:', '@MainActor')]),
    extract('BaseballMaster/LocalBackup.swift', [(None, 'struct TeamArchiveManifest:')], ('CryptoKit',)),
    extract('BaseballMaster/BattedBall.swift', [(None, 'struct RecordingMethodPicker:'),
        ('struct BatterContact:', 'struct BatterPerformanceSheet:')], ('SwiftUI',)),
    extract('BaseballMaster/SituationRecording.swift', [(None, 'enum SituationFieldMode:'),
        ('@MainActor\nextension GameStore {\n    func situationNeedsTimingDecision', 'struct SituationExistingPlay'),
        ('@MainActor\nextension GameStore {\n    /// A practice correction', None),
        ('func situationDefensivePlay(', '@MainActor\nextension GameStore {\n    func situationNeedsTimingDecision')], ('SwiftUI',)),
    extract('BaseballMaster/SharedMatches.swift', [(None, 'protocol MatchService {'),
        ('struct MatchPlayerLink:', 'struct HistoricalImportCandidate:')], ('Combine', 'CryptoKit')),
    extract('BaseballMaster/Components.swift', [('func statText(_ value: Double)', None)], ('SwiftUI',)),
]
for name in ('LinuxAdapters.swift', 'HarnessMain.swift'):
    source = ROOT / 'scripts/android/swift-core' / name
    target = cache / name
    target.write_bytes(source.read_bytes())
    metadata.append(dict(path=str(source.relative_to(ROOT)), sha256=hashlib.sha256(source.read_bytes()).hexdigest()))
    sources.append(target)
# SHA-256 is OpenSSL, with a known-answer check in the driver. No mock hashes.
header = Path('/usr/include/openssl/sha.h')
assert header.exists(), 'Install OpenSSL development headers; no reference check ran'
(cache / 'module.modulemap').write_text('module COpenSSL [system] { header "' + str(header) + '" link "crypto" export * }\n')
compiler = os.environ.get('SWIFTC', shutil.which('swiftc'))
assert compiler, 'Set SWIFTC to an installed Swift compiler'
(out / 'sources.json').write_text(json.dumps(metadata, indent=2))
(out / 'swift-version.txt').write_text(subprocess.check_output([compiler, '--version'], text=True))
binary = out / 'swift-core'
command = [compiler, '-swift-version', '5', '-parse-as-library', '-I', str(cache), *map(str, sources), '-o', str(binary)]
with (out / 'compile.log').open('w') as log:
    subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT)
inputs = sorted(a.input_directory.resolve().glob('*.bmbackup'))
assert inputs, 'No backups; no check ran'
results = []
checks = ('scoreMatches', 'operationIDsPreserved', 'sourceEventIDsPreserved',
          'sourceAppearanceIDsPreserved', 'eventsEqualWithinOneMillisecond', 'logsEqualWithinOneMillisecond', 'appearancesEqual',
          'journalEqual', 'checkpointPreserved')
for index, path in enumerate(inputs):
    source = path.read_bytes()
    envelope = json.loads(source)
    payload = base64.b64decode(envelope['payload'], validate=True)
    assert hashlib.sha256(payload).hexdigest() == envelope['checksum'], path
    name = f'{index:03}-{path.stem}'
    raw = out / f'{name}.snapshot.json'
    raw.write_bytes(payload)
    replay = out / f'{name}.swift.json'
    report = out / f'{name}.report.json'
    run = subprocess.run([str(binary), str(raw), str(replay), str(report)], capture_output=True, text=True)
    rows = json.loads(report.read_bytes()) if run.returncode == 0 else []
    passed = bool(rows) and all(row.get(key) is True for row in rows for key in checks)
    result = dict(input=str(path), sourceSha256=hashlib.sha256(source).hexdigest(),
                  passed=passed, exitCode=run.returncode, games=rows, stdout=run.stdout, stderr=run.stderr)
    if passed:
        encoded = replay.read_bytes()
        outer = dict(envelope, payload=base64.b64encode(encoded).decode(), checksum=hashlib.sha256(encoded).hexdigest())
        (out / f'{name}.bmbackup').write_text(json.dumps(outer, separators=(',', ':')))
    results.append(result)
(out / 'results.json').write_text(json.dumps(results, indent=2))
print(json.dumps(dict(files=len(results), passed=sum(r['passed'] for r in results))))
if not all(r['passed'] for r in results):
    raise SystemExit('Swift reference mismatch; see results.json (not an iOS App result)')
