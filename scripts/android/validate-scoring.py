#!/usr/bin/env python3
"""AN03 native scoring/field flow, external process death, and transactional storage on owned emulators."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--legacy', action='store_true', help='Verify the original AN02 single-pitch preview data before adding a synthetic AN03 match')
a = p.parse_args()
assert a.serial.startswith('emulator-'), 'Only owned emulators are supported'
assert a.apk.is_file()
apk_sha256 = hashlib.sha256(a.apk.read_bytes()).hexdigest()
a.output.mkdir(parents=True, exist_ok=False)
adb = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')
pkg = 'cc.baseballmaster.scorekeeper.preview'
checks = []
def run(name, args, instrument=False):
    result = subprocess.run([adb, '-s', a.serial, *args], capture_output=True, text=True)
    output = result.stdout + result.stderr
    (a.output / (name + '.log')).write_text(output)
    assert result.returncode == 0, (name, output)
    if instrument:
        assert re.search(r'OK \(\d+ tests?\)', output) and 'FAILURES!!!' not in output, (name, output)
    checks.append({'check': name, 'passed': True})
    return output

def test(name, method):
    return run(name, ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
               'cc.baseballmaster.scorekeeper.' + method, pkg + '.test/androidx.test.runner.AndroidJUnitRunner'], True)

run('install', ['install', '-r', str(a.apk)])
run('install-tests', ['install', '-r', str(root / 'output/cache/android/build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk')])
run('stop-before-test', ['shell', 'am', 'force-stop', pkg])
if a.legacy:
    test('legacy-an02-upgrade', 'PreviewUpgradeTest')
    run('stop-after-legacy', ['shell', 'am', 'force-stop', pkg])
test('native-scoring', 'ScoringFlowTest#recordHitRunnerProUndoCorrectionAndFinish')
run('stop-recorded-game', ['shell', 'am', 'force-stop', pkg])
assert not subprocess.run([adb, '-s', a.serial, 'shell', 'pidof', pkg], capture_output=True, text=True).stdout.strip()
test('recorded-game-recovery', 'ScoringFlowTest#completeRecordSurvivesExternalKill')
test('transactional-storage', 'StorageTest')
run('android-version', ['shell', 'getprop', 'ro.build.version.release'])
run('density', ['shell', 'wm', 'density'])
run('resolution', ['shell', 'wm', 'size'])
(a.output / 'screenshots').mkdir()
for name in ['an03-01-play-base.png', 'an03-02a-landing-field.png', 'an03-02-play-pro.png', 'an03-03-final.png', 'an03-04-restored-history.png']:
    run('pull-' + name, ['pull', '/sdcard/Android/data/' + pkg + '/files/' + name, str(a.output / 'screenshots' / name)])
assert hashlib.sha256(a.apk.read_bytes()).hexdigest() == apk_sha256, 'APK changed during validation'
(a.output / 'result.json').write_text(json.dumps({'serial': a.serial, 'apk': str(a.apk.resolve()), 'apk_sha256': apk_sha256, 'checks': checks, 'passed': True}, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'serial': a.serial, 'passed': True, 'output': str(a.output)}))
