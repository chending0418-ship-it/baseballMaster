#!/usr/bin/env python3
"""Compile a plain DEX harness and execute existing production PDF classes via app_process.
Never assembles/installs an APK; original APK supplies unchanged app dependencies.
Run only after :app:compileDebugKotlin has succeeded for the desired source revision.
"""
from pathlib import Path
import subprocess, zipfile, hashlib, json, os, sys
source=Path(__file__).resolve().parent
root=next(p for p in source.parents if (p/'android/build.gradle.kts').is_file())
out=Path(os.environ.get('PDF_PARITY_OUTPUT', str(root/'output/validation/android/0.8.2/pdf-parity/native-harness'))).resolve()
out.mkdir(parents=True,exist_ok=True)
sdk=Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
jdk=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
gradle=Path(os.environ.get('GRADLE_USER_HOME', str(root/'output/cache/android/gradle-user')))
cache=gradle/'caches/modules-2/files-2.1'
classes=root/'output/cache/android/build/app/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
core=root/'output/cache/android/build/core/libs/core.jar'
apk=Path(os.environ.get('PDF_PARITY_DEPENDENCY_APK', str(root/'output/cache/android/build/app/outputs/apk/debug/app-debug.apk')))
android=Path(os.environ.get('ANDROID_PLATFORM_JAR', str(sdk/'platforms/android-37.0/android.jar')))
serial=os.environ.get('ANDROID_SERIAL','emulator-5562')
remote='/data/local/tmp/baseballmaster-pdf-parity'
java=str(jdk/'bin/java'); adb=str(sdk/'platform-tools/adb')
def run(args):
 p=subprocess.run(list(map(str,args)),text=True,capture_output=True)
 print(p.stdout,end=''); print(p.stderr,end='',file=sys.stderr)
 if p.returncode: raise RuntimeError(f'{args[0]} failed: exit {p.returncode}')
 return p.stdout
jars=list(cache.rglob('*.jar'))
compiler=next(cache.glob('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10/*/*.jar'))
stdlib=next(cache.glob('org.jetbrains.kotlin/kotlin-stdlib/2.2.10/*/*.jar'))
compiler_cp=os.pathsep.join(map(str,[compiler,stdlib]+[p for p in jars if p!=compiler and p!=stdlib]))
cp=os.pathsep.join(map(str,[classes,core,android,stdlib]+[p for p in jars if p!=stdlib and 'compiler' not in p.name]))
build=out/'classes';build.mkdir(exist_ok=True)
run([java,'-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xfriend-paths='+str(classes),'-classpath',cp,'-d',build,source/'NativePdfHarness.kt'])
if os.environ.get('HARNESS_COMPILE_ONLY') == '1':
 print('HARNESS_COMPILE_OK'); sys.exit(0)
patch=out/'patch-classes.jar'
with zipfile.ZipFile(patch,'w') as z:
 for folder in [build,classes]:
  paths=list(folder.rglob('*.class')) if folder==build else list((folder/'cc/baseballmaster/scorekeeper').glob('ReportExport*.class'))
  for p in paths:z.write(p,p.relative_to(folder))
 # Refresh the pure report projection as well; the installed dependency APK
 # intentionally remains the old version and cannot supply new core helpers.
 with zipfile.ZipFile(core) as corezip:
  for name in corezip.namelist():
   if name.startswith('cc/baseballmaster/core/ReportStatisticsProjection') and name.endswith('.class'):
    z.writestr(name,corezip.read(name))
dex=out/'dex';dex.mkdir(exist_ok=True)
run([java,'-cp',sdk/'build-tools/37.0.0/lib/d8.jar','com.android.tools.r8.D8','--min-api','31','--lib',android,'--output',dex,patch])
run([adb,'-s',serial,'shell','mkdir','-p',remote])
run([adb,'-s',serial,'push',dex/'classes.dex',remote+'/patch.dex'])
run([adb,'-s',serial,'push',apk,remote+'/dependencies.apk'])
# Classpath order makes the fresh ReportExport win over the old APK.
result=run([adb,'-s',serial,'shell','env','CLASSPATH='+remote+'/patch.dex:'+remote+'/dependencies.apk','app_process','/system/bin','cc.baseballmaster.scorekeeper.NativePdfHarness',remote+'/output'])
(out/'run.log').write_text(result)
for name in ['game-report.pdf','play-by-play.pdf','text-record.pdf','team-report.pdf','player-report.pdf','stress-30-innings.pdf','stress-64-players.pdf','stress-long-event.pdf','stress-long-event-text.pdf','single-appearance.pdf','empty-appearances.pdf','coach-report.pdf','timed-report.pdf','slow-pitch-report.pdf','empty-boxscore.pdf']:
 run([adb,'-s',serial,'pull',remote+'/output/'+name,out/name])
manifest={'method':'D8 standalone patch DEX + app_process (no APK assembly/install)','serial':serial,'reportClassSha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (classes/'cc/baseballmaster/scorekeeper').glob('ReportExport*.class')},'dependencyApkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'pdfs':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in out.glob('*.pdf')}}
with zipfile.ZipFile(core) as corezip:
 manifest['reportProjectionClassSha256']={name:hashlib.sha256(corezip.read(name)).hexdigest() for name in corezip.namelist() if name.startswith('cc/baseballmaster/core/ReportStatisticsProjection') and name.endswith('.class')}
manifest['coreJarSha256']=hashlib.sha256(core.read_bytes()).hexdigest()
(out/'manifest.json').write_text(json.dumps(manifest,indent=2))
