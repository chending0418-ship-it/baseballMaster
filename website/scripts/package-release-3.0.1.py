"""Package the 3.0.1 review material, excluding archives and private deployment data."""
from pathlib import Path
import json,hashlib,shutil,zipfile,plistlib
ROOT=Path(__file__).resolve().parents[2]
docs=ROOT/'docs/releases/3.0.1';release=ROOT/'output/releases/3.0.1';packet=release/'BaseballMaster-3.0.1-AppStore'
packet.mkdir(exist_ok=True)
for p in docs.iterdir():
 if p.suffix in ('.md','.txt'):shutil.copy2(p,packet/p.name)
art=release/'app-store'
for size in ['iphone-6.3','iphone-6.9','iphone-6.5']:
 dest=packet/'screenshots'/size;dest.mkdir(parents=True,exist_ok=True)
 sources=sorted((art/size).glob('*.png'));assert len(sources)==6
 for p in sources:shutil.copy2(p,dest/p.name)
shutil.copytree(art/'raw',packet/'source-screenshots',dirs_exist_ok=True)
shutil.copy2(art/'overview.png',packet/'overview.png')
shutil.copy2(art/'manifest.json',packet/'artwork-manifest.json')
archive=json.loads((ROOT/'output/validation/3.0.1/release-2026-10-19/final-archive.json').read_text())
assert archive['codeSignVerified'] and archive['CFBundleShortVersionString']=='3.0.1'
shutil.copy2(ROOT/'output/validation/3.0.1/release-2026-10-19/final-archive.json',packet/'archive-verification.json')
manifest={'created':'2026-10-19','appVersion':'3.0.1','build':'1','teamID':'JUTVG7XR9H','bundleID':'com.jasonchen.baseballmaster','appBinaryUUID':archive['binaryUUID'],'screenshots':{'sets':3,'perSet':6,'files':18},'serverDeployed':True,'invitationCompatibility':'App 3.0 single-use and App 3.0.1 fixed invitations coexist','uploadedToApple':False,'submittedForReview':False,'archiveIncluded':False,'secretsIncluded':False}
(packet/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
for name,limit in [('promotional-text-zh-Hans.txt',170),('description-zh-Hans.txt',4000),('whats-new-zh-Hans.txt',4000),('keywords-zh-Hans.txt',100),('review-notes-en.txt',4000)]:assert len((packet/name).read_text().strip())<=limit,name
assert not any(p.suffix in ('.key','.sqlite','.db','.sealed','.mobileprovision') or p.name.startswith('.') for p in packet.rglob('*') if p.is_file())
assert 'Version: 3.0.1 (Build 1)' in (packet/'review-notes-en.txt').read_text()
assert '最多两名管理员' not in (packet/'description-zh-Hans.txt').read_text()
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
checks={str(p.relative_to(packet)):sha(p) for p in sorted(packet.rglob('*')) if p.is_file() and p.name!='checksums.json'}
(packet/'checksums.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2)+'\n')
zip_path=release/'BaseballMaster-3.0.1-AppStore.zip'
with zipfile.ZipFile(zip_path,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for p in sorted(packet.rglob('*')):
  if p.is_file():z.write(p,p.relative_to(packet.parent))
with zipfile.ZipFile(zip_path) as z:
 assert z.testzip() is None
 for name,h in checks.items():assert hashlib.sha256(z.read(packet.name+'/'+name)).hexdigest()==h
result={'files':len(checks)+1,'bytes':zip_path.stat().st_size,'sha256':sha(zip_path),'allChecksumsAndZipCRCPassed':True,'appVersion':'3.0.1 (1)','screenshots':18}
(ROOT/'output/validation/3.0.1/release-2026-10-19/package.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
