#!/usr/bin/env python3
"""Current-team UI and external process-death recovery; never clears existing app data.

Build/install the matching app and test APKs first. This runner deliberately invokes
one test method at a time and force-stops the preview app between them.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'Owned emulator required'
args.output.mkdir(parents=True, exist_ok=False)
adb = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb'
package = 'cc.baseballmaster.scorekeeper.preview'
checks = []


def command(name, arguments, instrument=False, allow_empty_process=False):
    result = subprocess.run([str(adb), '-s', args.serial, *arguments], capture_output=True, text=True)
    output = result.stdout + result.stderr
    (args.output / f'{name}.log').write_text(output)
    if not allow_empty_process:
        assert result.returncode == 0, (name, output)
    if instrument:
        assert re.search(r'OK \(1 test\)', output) and 'FAILURES!!!' not in output, (name, output)
    checks.append({'name': name, 'passed': True})
    return result.stdout.strip()


def test(name, method):
    command(name, ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                  f'cc.baseballmaster.scorekeeper.CurrentTeamFlowTest#{method}',
                  package + '.test/androidx.test.runner.AndroidJUnitRunner'], instrument=True)


test('select-current-team', 'selectCurrentTeamAndRecord')
# An instrumentation runner may finish its target process. Relaunch the same saved
# activity before killing it, so the external death has a concrete live PID.
command('launch-before-kill', ['shell', 'am', 'start', '-W', '-n',
                             package + '/cc.baseballmaster.scorekeeper.MainActivity'])
before = command('pid-before-kill', ['shell', 'pidof', package])
assert before and all(pid.isdigit() for pid in before.split()), 'No live target process before force-stop'
command('external-force-stop', ['shell', 'am', 'force-stop', package])
after = command('pid-after-kill', ['shell', 'pidof', package], allow_empty_process=True)
assert not after, f'Target survived force-stop: {after}'
test('restore-current-team', 'selectedTeamSurvivesExternalProcessKill')
command('pull-evidence', ['pull', '/sdcard/Android/data/' + package + '/files/', str(args.output / 'device-files')])
(args.output / 'result.json').write_text(json.dumps({
    'serial': args.serial, 'passed': True, 'externalForceStop': True,
    'pidBeforeKill': before.split(), 'pidAfterKill': after, 'checks': checks,
}, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'passed': True, 'serial': args.serial, 'output': str(args.output)}))
