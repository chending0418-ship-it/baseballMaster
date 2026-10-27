#!/usr/bin/env python3
"""AN02 E2E on owned emulators. --fresh clears only the development preview package."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--fresh', action='store_true', help='Clear preview data and seed through the UI')
a = p.parse_args()
assert a.serial.startswith('emulator-'), 'This script is restricted to emulators'
adb = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')
pkg = 'cc.baseballmaster.scorekeeper.preview'
a.output.mkdir(parents=True, exist_ok=False)
checks = []
def command(name, arguments, instrument=False):
    r = subprocess.run([adb, '-s', a.serial, *arguments], capture_output=True, text=True)
    output = r.stdout + r.stderr
    (a.output / (name + '.log')).write_text(output)
    assert r.returncode == 0, (name, output)
    if instrument:
        assert re.search(r'OK \(\d+ tests?\)', output) and 'FAILURES!!!' not in output, (name, output)
    checks.append({'check': name, 'passed': True})
    return output
command('install-app', ['install', '-r', str(a.apk)])
command('install-tests', ['install', '-r', str(root / 'output/cache/android/build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk')])
def test(name, target):
    command(name, ['shell','am','instrument','-w','-r','-e','class','cc.baseballmaster.scorekeeper.'+target,
                  pkg+'.test/androidx.test.runner.AndroidJUnitRunner'], True)
if a.fresh:
    command('clear-preview', ['shell','pm','clear',pkg])
    test('first-flow','NativeFlowTest#createTeamStartGameAndRecordFirstPitch')
command('launch-before-kill',['shell','am','start','-W','-n',pkg+'/cc.baseballmaster.scorekeeper.MainActivity'])
command('process-before-kill',['shell','pidof',pkg])
command('force-stop',['shell','am','force-stop',pkg])
assert not subprocess.run([adb,'-s',a.serial,'shell','pidof',pkg],capture_output=True,text=True).stdout.strip()
test('recovery','NativeFlowTest#restoredGameIsAvailableAfterExternalProcessKillOrUpgrade')
test('storage','StorageTest')
command('device',['shell','getprop','ro.build.version.release'])
command('density',['shell','wm','density'])
command('resolution',['shell','wm','size'])
remote='/sdcard/Android/data/'+pkg+'/files/'
names=command('list-screens',['shell','ls',remote]).splitlines()
(a.output/'screenshots').mkdir()
for name in names:
    if name.endswith('.png') or name=='storage-receipt.json':
        command('pull-'+name,['pull',remote+name,str(a.output/'screenshots'/name)])
(a.output/'result.json').write_text(json.dumps({'serial':a.serial,'checks':checks,'passed':True},indent=2)+'\n')
print(json.dumps({'serial':a.serial,'passed':True,'checks':len(checks),'output':str(a.output)}))
