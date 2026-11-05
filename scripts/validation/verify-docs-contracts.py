#!/usr/bin/env python3
"""Validate stage-one moves against their recorded Git baseline, without touching services."""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args])


def links(text):
    text = re.sub(r'```.*?```', '', text, flags=re.S)
    for match in re.finditer(r'!?\[[^\]]*\]\((<[^>]*>|[^\n]*?)\)', text):
        raw = match[1].strip()
        raw = raw[1:-1] if raw.startswith('<') else re.split(r'\s+[\"\']', raw, maxsplit=1)[0]
        if raw and not raw.startswith('#') and not urlsplit(raw).scheme:
            yield unquote(raw.split('#', 1)[0])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, default=ROOT / 'output/validation/repo-stage1/layout.json')
    args = parser.parse_args()
    spec = json.loads((ROOT / 'docs/maintenance/2026-11-05-stage1-path-map.json').read_text())
    base, moves = spec['base'], spec['moves']
    entries = [os.fsdecode(p) for p in git('ls-tree', '-r', '--name-only', '-z', base).split(b'\0') if p]
    checks = []
    def check(name, ok, detail=None):
        checks.append({'name': name, 'passed': bool(ok), 'detail': detail})
    def original(name):
        return git('show', base + ':' + name)
    # Check bytes, not just Git rename inference. Application and server behavior must stay identical.
    errors = []
    frozen_count = 0
    protected = ('BaseballMaster/', 'BaseballMaster.xcodeproj/', 'BaseballMasterTests/',
                 'BaseballMasterUITests/', 'android/app/src/', 'android/core/src/',
                 'android/app/schemas/', 'live/', 'collaboration/')
    for name in entries:
        if name.startswith(protected) and not name.endswith('.md') and name not in spec['testPathAdjustments']:
            frozen_count += 1
            p = ROOT / name
            if not p.is_file() or p.read_bytes() != original(name):
                errors.append(name)
    check('unchanged-app-project-tests-service-and-db-schema', not errors,
          {'files': frozen_count, 'errors': errors})
    fixtures = [name for name in entries if name.startswith('test-fixtures/') and not name.endswith('.md')]
    errors = [name for name in fixtures if (ROOT / moves[name]).read_bytes() != original(name)]
    check('unchanged-frozen-fixture-and-manifest-bytes', not errors, {'files': len(fixtures), 'errors': errors})
    check('fixture-source-removed', not (ROOT / 'test-fixtures').exists())
    errors = [old for old, new in moves.items() if not (ROOT / new).is_file() or (ROOT / old).is_file()]
    check('single-authoritative-move-destinations', not errors, errors)
    for name in ['README.md', 'AGENTS.md', 'TODO.md', 'docs/CURRENT.md']:
        archived = 'docs/archive/layout-stage1-20261105/main-' + Path(name).name
        check('original-entry-snapshot:' + name, (ROOT / archived).read_bytes() == original(name))
    check('root-3.1.0-plan-unchanged',
          (ROOT / '3.1.0 发布功能计划.md').read_bytes() == original('3.1.0 发布功能计划.md'))
    for platform in ['ios', 'android']:
        check('platform-current:' + platform, (ROOT / platform / 'docs/CURRENT.md').is_file())
        check('platform-version-plan:' + platform,
              (ROOT / platform / 'docs/plans' / (('3.1.2' if platform == 'ios' else '0.8.2') + ' 发布功能计划.md')).is_file())
    check('no-root-current-copy', not (ROOT / 'docs/CURRENT.md').exists())
    # Only the fixture path may change in the two Gradle files.
    for name in ['android/app/build.gradle.kts', 'android/core/build.gradle.kts']:
        expected = original(name).replace(b'test-fixtures/android-parity', b'contracts/fixtures/android-parity')
        check('test-only-gradle-path-change:' + name, (ROOT / name).read_bytes() == expected)
    for name, replacement in spec['testPathAdjustments'].items():
        expected = original(name).replace(replacement['old'].encode(), replacement['new'].encode())
        check('test-only-repository-root-depth:' + name, (ROOT / name).read_bytes() == expected)
    # All newly introduced navigation and current version plans must have valid local links.
    pages = [ROOT / name for name in ['README.md', 'AGENTS.md', 'TODO.md', 'docs/README.md',
             'ios/docs/README.md', 'ios/docs/CURRENT.md', 'android/docs/README.md',
             'android/docs/CURRENT.md', 'contracts/README.md',
             'contracts/fixtures/android-parity/README.md',
             'docs/maintenance/2026-11-05-docs-contracts-stage1.md',
             'docs/archive/layout-stage1-20261105/README.md']]
    pages.extend(ROOT.glob('ios/docs/plans/*发布功能计划.md'))
    pages.extend(ROOT.glob('android/docs/plans/*发布功能计划.md'))
    errors = []
    evidence = []
    for page in pages:
        if not page.is_file():
            errors.append([str(page), 'missing page'])
            continue
        for target in links(page.read_text()):
            resolved = page.parent / target
            if not resolved.exists():
                normalized = Path(os.path.normpath(resolved))
                if normalized.is_relative_to(ROOT / 'output'):
                    relative = normalized.relative_to(ROOT)
                    candidates = [ROOT.parent / owner / relative for owner in
                                  ['baseball-scorekeeper', 'baseball-scorekeeper-android']]
                    found = next((str(p) for p in candidates if p.exists()), None)
                    evidence.append({'page': str(page.relative_to(ROOT)), 'target': target,
                                     'localEvidence': found, 'versioned': False})
                else:
                    errors.append([str(page.relative_to(ROOT)), target])
    check('current-platform-plan-and-contract-navigation', not errors, {'pages': len(pages), 'errors': errors, 'unbundledEvidence': evidence})
    errors = []
    for name in ['scripts/android/swift-core-check.py', 'scripts/android/swift-wire-check.py',
                 'scripts/release/package-release-3.1.2.py', 'scripts/validation/verify-docs-contracts.py']:
        try:
            ast.parse((ROOT / name).read_text())
        except SyntaxError as error:
            errors.append([name, str(error)])
    check('changed-python-syntax', not errors, errors)
    # Resolve all manifest digests recursively, including historical backup fixture entries.
    errors = []
    digest_count = 0
    def visit(value, directory):
        nonlocal digest_count
        if isinstance(value, dict):
            name = value.get('path') or value.get('file') or value.get('fixture')
            if name and isinstance(name, str) and isinstance(value.get('sha256'), str):
                file = directory / name
                digest_count += 1
                if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != value['sha256']:
                    errors.append(str(file))
            for child in value.values():
                visit(child, directory)
        elif isinstance(value, list):
            for child in value:
                visit(child, directory)
    for manifest in (ROOT / 'contracts/fixtures').rglob('manifest.json'):
        visit(json.loads(manifest.read_text()), manifest.parent)
    check('fixture-manifest-digests', not errors, {'digests': digest_count, 'errors': errors})
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps({'base': base, 'checks': checks}, ensure_ascii=False, indent=2) + '\n')
    for item in checks:
        if not item['passed']:
            print('FAIL', item['name'], json.dumps(item['detail'], ensure_ascii=False))
    passed = sum(item['passed'] for item in checks)
    print(f'{passed}/{len(checks)} checks passed. Report: {args.report}')
    return 0 if passed == len(checks) else 1


if __name__ == '__main__':
    raise SystemExit(main())
