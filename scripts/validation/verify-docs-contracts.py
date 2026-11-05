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


def archive_checks(spec, candidate_tree=None):
    """Compare to approved digests, never take the baseline from an incoming tree."""
    errors = []
    protected = spec['archiveProtection']['files']
    for item in protected:
        name = item['path']
        try:
            content = (git('show', candidate_tree + ':' + name) if candidate_tree
                       else (ROOT / name).read_bytes())
            if hashlib.sha256(content).hexdigest() != item['sha256']:
                errors.append({'path': name, 'reason': 'protected archive changed'})
        except (OSError, subprocess.CalledProcessError):
            errors.append({'path': name, 'reason': 'protected archive missing'})
    return [{'name': 'protected-archive-bytes', 'passed': not errors,
             'detail': {'files': len(protected), 'candidateTree': candidate_tree, 'errors': errors}}]


def finish(checks, report, base):
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({'base': base, 'checks': checks}, ensure_ascii=False, indent=2) + '\n')
    for item in checks:
        if not item['passed']:
            print('FAIL', item['name'], json.dumps(item['detail'], ensure_ascii=False))
    passed = sum(item['passed'] for item in checks)
    print(f'{passed}/{len(checks)} checks passed. Report: {report}')
    return 0 if passed == len(checks) else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, default=ROOT / 'output/validation/repo-stage1/layout.json')
    parser.add_argument('--archive-only', action='store_true',
                        help='Check frozen archives after restoring reviewed work; allow unrelated source changes.')
    parser.add_argument('--layout-only', action='store_true',
                        help='Check layout, navigation, fixtures and archives after restoring existing source changes.')
    parser.add_argument('--archive-tree', metavar='REF',
                        help='Check only archives in a prospective Git tree; do not update refs, index or files.')
    args = parser.parse_args()
    spec = json.loads((ROOT / 'docs/maintenance/2026-11-05-stage1-path-map.json').read_text())
    base, moves = spec['base'], spec['moves']
    if args.archive_only or args.archive_tree:
        return finish(archive_checks(spec, args.archive_tree), args.report, base)
    entries = [os.fsdecode(p) for p in git('ls-tree', '-r', '--name-only', '-z', base).split(b'\0') if p]
    checks = []
    def check(name, ok, detail=None):
        checks.append({'name': name, 'passed': bool(ok), 'detail': detail})
    def original(name):
        return git('show', base + ':' + name)
    # Full PR audit freezes runtime bytes; adoption may restore explicitly preserved source changes.
    errors = []
    frozen_count = 0
    protected = ('BaseballMaster/', 'BaseballMaster.xcodeproj/', 'BaseballMasterTests/',
                 'BaseballMasterUITests/', 'android/app/src/', 'android/core/src/',
                 'android/app/schemas/', 'live/', 'collaboration/')
    if not args.layout_only:
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
    plan310 = ROOT / 'ios/docs/plans/3.1.0 发布功能计划.md'
    check('single-ios-3.1.0-plan', plan310.is_file()
          and not (ROOT / '3.1.0 发布功能计划.md').exists())
    if plan310.is_file() and not args.layout_only:
        def narrative(text):
            return re.sub(r'(!?\[[^\]]*\])\((<[^>]*>|[^\n]*?)\)', r'\1', text)
        check('3.1.0-plan-narrative-preserved',
              narrative(plan310.read_text()) == narrative(original('3.1.0 发布功能计划.md').decode()))
    checks.extend(archive_checks(spec))
    routing = spec['currentContentRouting']
    check('old-current-is-manually-routed',
          spec['mappingPurpose'].startswith('Historical file relocation only')
          and routing['mode'] == 'manual-semantic-routing'
          and routing['archiveDestinationIsWritable'] is False
          and routing['targets'] == {'ios': 'ios/docs/CURRENT.md',
                                     'services': 'ios/docs/CURRENT.md',
                                     'android': 'android/docs/CURRENT.md'})
    for platform in ['ios', 'android']:
        check('platform-current:' + platform, (ROOT / platform / 'docs/CURRENT.md').is_file())
        check('platform-version-plan:' + platform,
              (ROOT / platform / 'docs/plans' / (('3.1.2' if platform == 'ios' else '0.8.2') + ' 发布功能计划.md')).is_file())
    check('no-root-current-copy', not (ROOT / 'docs/CURRENT.md').exists())
    # Only the fixture path may change in the two Gradle files.
    if not args.layout_only:
        for name in ['android/app/build.gradle.kts', 'android/core/build.gradle.kts']:
            expected = original(name).replace(b'test-fixtures/android-parity', b'contracts/fixtures/android-parity')
            check('test-only-gradle-path-change:' + name, (ROOT / name).read_bytes() == expected)
        for name, replacement in spec['testPathAdjustments'].items():
            expected = original(name).replace(replacement['old'].encode(), replacement['new'].encode())
            check('test-only-repository-root-depth:' + name, (ROOT / name).read_bytes() == expected)
    # Include unchanged inbound navigation and new pages, not just the files moved by this PR.
    names = git('ls-files', '-z') + git('ls-files', '--others', '--exclude-standard', '-z')
    pages = sorted({ROOT / os.fsdecode(name) for name in names.split(b'\0') if name
                    and name.endswith(b'.md') and b'/archive/' not in name})
    pages = [page for page in pages if page.is_file()]
    # Frozen entry snapshots retain historical links; their explanatory index is live navigation.
    pages.append(ROOT / 'docs/archive/layout-stage1-20261105/README.md')
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
    return finish(checks, args.report, base)


if __name__ == '__main__':
    raise SystemExit(main())
