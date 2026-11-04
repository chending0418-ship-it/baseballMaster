#!/usr/bin/env python3
"""Verify the 0.8.1 product journeys and two genuine process-recovery flows.

Runs only on the dedicated, synthetic-data phone AVD. It does not clear data,
change display size/density, or operate any other emulator.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'cc.baseballmaster.scorekeeper.preview'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
CLASSES = [
    'Product081LineupFlowTest', 'Product081PlayFlowTest',
    'Product081WorkbenchFlowTest', 'Product081SpecialLineupFlowTest',
    'Product081RosterFlowTest', 'Product081LargeDraftRotationTest',
    'Product081RuleNoticeFlowTest', 'Product081SetupDraftFlowTest',
    'Product081StatisticsScopeFlowTest', 'Product081PlayerContactFlowTest',
    'Product081PlayerStatisticsLayoutFlowTest',
]


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--recovery-only', action='store_true', help='Repeat only the four process-recovery phases; never claim the product suite ran')
    parser.add_argument('--recovery-scope', choices=['all', 'current-team', 'history'], default='all')
    args = parser.parse_args()
    assert args.recovery_only or args.recovery_scope == 'all'
    assert args.serial.startswith('emulator-')
    assert args.apk.is_file() and args.test_apk.is_file()
    args.output.mkdir(parents=True, exist_ok=False)
    # Build output aliases may be replaced by a later visual-only candidate.
    # Install, verify and recheck immutable copies belonging to this run.
    artifacts = args.output / 'installed-apks'
    artifacts.mkdir()
    original_apk, original_harness = args.apk.resolve(), args.test_apk.resolve()
    shutil.copy2(original_apk, artifacts / 'app-release.apk')
    shutil.copy2(original_harness, artifacts / 'test.apk')
    args.apk, args.test_apk = artifacts / 'app-release.apk', artifacts / 'test.apk'
    adb = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')
    prefix = [adb, '-s', args.serial]
    checks = []
    receipt = dict(serial=args.serial, apk=str(args.apk.resolve()), apkSha256=digest(args.apk),
                   testApk=str(args.test_apk.resolve()), harnessSha256=digest(args.test_apk),
                   originalApk=str(original_apk), originalHarness=str(original_harness),
                   startedAtEpochMs=int(time.time() * 1000), passed=False, checks=checks)

    def write():
        (args.output / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n')

    def run(name, command, expected_tests=None):
        log = args.output / (name + '.log')
        with log.open('w') as stream:
            result = subprocess.run(prefix + command, stdout=stream, stderr=subprocess.STDOUT, text=True)
        output = log.read_text()
        assert result.returncode == 0, (name, result.returncode, output[-3000:])
        if expected_tests is not None:
            assert re.search(r'OK \(' + str(expected_tests) + r' tests?\)', output), (name, output[-5000:])
            assert 'FAILURES!!!' not in output and 'Process crashed' not in output
        checks.append(dict(id=name, count=expected_tests, passed=True, evidence=name + '.log'))
        write()
        return output

    def test(name, classes, expected):
        return run(name, ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', classes, RUNNER], expected)

    def stop(name):
        # API31 stops the target process when instrumentation finishes. Start a
        # real, live app process first so the external stop is an actual kill,
        # rather than a no-op on an already-finished instrumentation process.
        run(name + '-launch-live-app', ['shell', 'am', 'start', '-W', '-n', PACKAGE + '/cc.baseballmaster.scorekeeper.MainActivity'])
        live_pid = ''
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            process = subprocess.run(prefix + ['shell', 'pidof', PACKAGE], capture_output=True, text=True)
            live_pid = process.stdout.strip()
            if process.returncode == 0 and re.fullmatch(r'\d+(?:\s+\d+)*', live_pid):
                break
            time.sleep(.1)
        assert live_pid, 'The app must be alive before an external process kill'
        (args.output / (name + '-live-pid.txt')).write_text(live_pid + '\n')
        run(name, ['shell', 'am', 'force-stop', PACKAGE])
        process = subprocess.run(prefix + ['shell', 'pidof', PACKAGE], capture_output=True, text=True)
        (args.output / (name + '-pid.txt')).write_text(process.stdout + process.stderr)
        assert process.returncode == 1 and not process.stdout.strip(), 'An external process stop must actually remove the app process'

    write()
    try:
        avd = run('avd', ['emu', 'avd', 'name']).replace('\r', '').splitlines()
        assert avd[0] == 'bm-081-capture-api31', 'Use the dedicated synthetic-data phone, never a user AVD'
        run('install', ['install', '-r', str(args.apk)])
        run('install-tests', ['install', '-r', str(args.test_apk)])
        package = run('installed-package', ['shell', 'dumpsys', 'package', PACKAGE])
        assert 'versionName=0.8.1' in package and re.search(r'versionCode=9\b', package)
        flags = [line for line in package.splitlines() if re.search(r'\b(?:pkgFlags|flags)=', line)]
        assert flags and all('DEBUGGABLE' not in line for line in flags)
        installed = run('installed-path', ['shell', 'pm', 'path', PACKAGE]).strip().removeprefix('package:')
        installed_bytes = subprocess.run(prefix + ['exec-out', 'cat', installed], capture_output=True, check=True).stdout
        assert hashlib.sha256(installed_bytes).hexdigest() == receipt['apkSha256']
        source = ROOT / 'android/app/src/androidTest/java/cc/baseballmaster/scorekeeper'
        count = sum(len(re.findall(r'@Test\s+fun\s+', (source / (c + '.kt')).read_text())) for c in CLASSES)
        assert count >= 24, 'All product, personal-layout, contact and state-regression cases must be present'
        if args.recovery_only:
            count = 0
        else:
            test('product-journeys', ','.join('cc.baseballmaster.scorekeeper.' + c for c in CLASSES), count)
        recovery_count = 0
        if args.recovery_scope in ['all', 'current-team']:
            test('current-team-prepare', 'cc.baseballmaster.scorekeeper.CurrentTeamFlowTest#selectCurrentTeamAndRecord', 1)
            stop('external-stop-current-team')
            test('current-team-recover', 'cc.baseballmaster.scorekeeper.CurrentTeamFlowTest#selectedTeamSurvivesExternalProcessKill', 1)
            recovery_count += 2
        if args.recovery_scope in ['all', 'history']:
            test('history-prepare', 'cc.baseballmaster.scorekeeper.HistoryFlowTest#prepareCorrectionDraftWithoutChangingOfficialGame', 1)
            stop('external-stop-history')
            test('history-recover-save-withdraw-suspend', 'cc.baseballmaster.scorekeeper.HistoryFlowTest#recoverDraftSaveWithdrawAndSuspend', 1)
            recovery_count += 2
        run('pull-evidence', ['pull', '/sdcard/Android/data/' + PACKAGE + '/files', str(args.output / 'device-files')])
        assert receipt['apkSha256'] == digest(args.apk) and receipt['harnessSha256'] == digest(args.test_apk)
        receipt.update(passed=True, productTests=count, recoveryTests=recovery_count, recoveryScope=args.recovery_scope, finishedAtEpochMs=int(time.time() * 1000))
    except Exception as failure:
        receipt.update(failure=str(failure), finishedAtEpochMs=int(time.time() * 1000))
        raise
    finally:
        write()
    print(json.dumps({'passed': True, 'productTests': count, 'recoveryTests': recovery_count, 'output': str(args.output)}))


if __name__ == '__main__':
    main()
