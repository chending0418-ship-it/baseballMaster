#!/usr/bin/env python3
"""Run correction drafting/recovery against a specific APK on an owned emulator."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

root=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('--apk',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
assert a.serial.startswith('emulator-') and a.apk.is_file()
a.output.mkdir(parents=True,exist_ok=False)
adb=str(Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))/'platform-tools/adb')
pkg='cc.baseballmaster.scorekeeper.preview'
sha=hashlib.sha256(a.apk.read_bytes()).hexdigest()
checks=[]
def run(name,args,instrument=False):
    r=subprocess.run([adb,'-s',a.serial,*args],capture_output=True,text=True)
    output=r.stdout+r.stderr
    (a.output/(name+'.log')).write_text(output)
    assert r.returncode==0,(name,output)
    if instrument: assert re.search(r'OK \(\d+ tests?\)',output) and 'FAILURES!!!' not in output,(name,output)
    checks.append(name)
    return output

def test(name,method):return run(name,['shell','am','instrument','-w','-r','-e','class','cc.baseballmaster.scorekeeper.'+method,pkg+'.test/androidx.test.runner.AndroidJUnitRunner'],True)
run('install',['install','-r',str(a.apk)])
run('install-tests',['install','-r',str(root/'output/cache/android/build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk')])
run('stop-before',['shell','am','force-stop',pkg])
test('prepare-draft','HistoryFlowTest#prepareCorrectionDraftWithoutChangingOfficialGame')
run('external-kill',['shell','am','force-stop',pkg])
assert not subprocess.run([adb,'-s',a.serial,'shell','pidof',pkg],capture_output=True,text=True).stdout.strip()
test('recover-save-revert-suspend','HistoryFlowTest#recoverDraftSaveWithdrawAndSuspend')
for name in ['01-draft-preview','02-restored-draft','03-withdraw-preview','04-suspended']:
    run('pull-'+name,['pull','/sdcard/Android/data/'+pkg+'/files/history-'+name+'.png',str(a.output/(name+'.png'))])
assert sha==hashlib.sha256(a.apk.read_bytes()).hexdigest()
(a.output/'result.json').write_text(json.dumps({'passed':True,'serial':a.serial,'apk':str(a.apk.resolve()),'sha256':sha,'checks':checks},indent=2)+'\n')
print(json.dumps({'passed':True,'serial':a.serial,'output':str(a.output)}))
