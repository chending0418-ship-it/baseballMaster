#!/usr/bin/env python3
"""AN04 instrumented regression on an already booted, isolated synthetic-data AVD.

Does not install packages, clear data, dismiss ANRs, or treat adb exit code alone as a pass.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--class-name', action='append', dest='classes')
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=False)
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb') if os.environ.get('ANDROID_HOME') else 'adb'
package = 'cc.baseballmaster.scorekeeper.preview'

def call(*args, timeout=90):
    return subprocess.run([adb, '-s', a.serial, *args], capture_output=True, timeout=timeout)

def evidence():
    for name, args in [('logcat.txt', ['logcat', '-d', '-t', '3000']),
                       ('activity.txt', ['shell', 'dumpsys', 'activity', 'activities'])]:
        (a.output / name).write_bytes(call(*args).stdout)
    (a.output / 'screen.png').write_bytes(call('exec-out', 'screencap', '-p').stdout)
    call('pull', f'/sdcard/Android/data/{package}/files', str(a.output / 'device-files'))

results = []
try:
    metadata = {key: call('shell', 'getprop', key).stdout.decode().strip() for key in
                ['ro.build.version.sdk', 'ro.build.fingerprint', 'sys.boot_completed']}
    metadata['window'] = call('shell', 'wm', 'size').stdout.decode() + call('shell', 'wm', 'density').stdout.decode()
    (a.output / 'device.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2))
    if metadata['sys.boot_completed'] != '1':
        raise RuntimeError('AVD not booted')
    # Warm the installed app, retaining the actual first-launch evidence. A bounded readiness
    # check permits slow software emulation, but a visible ANR is a failure, never auto-dismissed.
    start = call('shell', 'am', 'start', '-W', '-n', f'{package}/cc.baseballmaster.scorekeeper.MainActivity', timeout=180)
    (a.output / 'startup.txt').write_bytes(start.stdout + start.stderr)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        call('shell', 'uiautomator', 'dump', '/sdcard/an04-ready.xml')
        xml = call('shell', 'cat', '/sdcard/an04-ready.xml').stdout.decode(errors='replace')
        (a.output / 'startup.xml').write_text(xml)
        if 'aerr_' in xml or "isn’t responding" in xml or "isn't responding" in xml:
            raise RuntimeError('System/app ANR visible; see startup.xml')
        if 'tab-2' in xml and 'tab-3' in xml:
            break
        time.sleep(2)
    else:
        raise RuntimeError('App readiness timed out')
    classes = a.classes or ['StatisticsFlowTest', 'PlayerStatisticsFlowTest', 'TeamArchiveUIFlowTest',
                             'ReportExportTest', 'DeviceBackupTest', 'BackupInteropTest', 'RulesFlowTest']
    for name in classes:
        result = call('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                      f'cc.baseballmaster.scorekeeper.{name}',
                      f'{package}.test/androidx.test.runner.AndroidJUnitRunner', timeout=1200)
        raw = result.stdout + result.stderr
        (a.output / f'{name}.txt').write_bytes(raw)
        text = raw.decode(errors='replace')
        match = re.search(r'OK \((\d+) tests?\)', text)
        passed = result.returncode == 0 and match is not None and not any(
            token in text for token in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=', 'Process crashed'])
        results.append({'class': name, 'passed': passed, 'tests': int(match[1]) if match else None})
        print(json.dumps(results[-1]), flush=True)
        if not passed:
            raise RuntimeError(f'{name} failed; see raw instrumentation log')
finally:
    (a.output / 'results.json').write_text(json.dumps(results, indent=2))
    evidence()
