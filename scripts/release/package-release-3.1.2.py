"""Validate and package local release materials; never deploy or upload."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib, json, re, struct, html, subprocess, shutil
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'output/releases/3.1.2'
RELEASE = ROOT / 'docs/releases/3.1.2'
DESIGN = ROOT / 'docs/design/marketing/3.1.2'
QA = ROOT / 'output/validation/3.1.2/release-materials-2026-11-04'
OUT.mkdir(parents=True, exist_ok=True); QA.mkdir(parents=True, exist_ok=True)
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def check_png(p, width, height):
    data = p.read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', p
    w,h,depth,color = struct.unpack('>IIBB', data[16:26])
    assert (w,h) == (width,height) and color == 2, (p,w,h,color)
    return dict(file=str(p.relative_to(ROOT)), width=w, height=h, sha256=sha(p))
def write_zip(dest, pairs):
    with ZipFile(dest,'w',compression=ZIP_DEFLATED) as z:
        for p,n in pairs:
            assert 'apple-originals' not in n and 'drafts/' not in n, n
            z.write(p,n)
    with ZipFile(dest) as z: assert z.testzip() is None
    return dict(file=str(dest.relative_to(ROOT)), entries=len(pairs), sha256=sha(dest))
report={'date':'2026-11-04','websiteStatus':'deployed-shanghai','status':'materials-ready-app-not-uploaded','appTestsRerun':False,'files':[],'archives':[],'text':{}}
app = json.loads((OUT/'app-store/manifest.json').read_text())['files']
assert len(app)==34
app_pairs=[]
for m in app:
    p=OUT/'app-store'/m['file']; assert sha(p)==m['sha256']
    report['files'].append(check_png(p,m['width'],m['height']))
    app_pairs.append((p,m['file']))
app_pairs += [(OUT/'app-store/manifest.json','manifest.json'),(RELEASE/'APPSTORE-ARTWORK.md','尺寸与来源.md')]
report['archives'].append(write_zip(OUT/'AppStore截图.zip', app_pairs))
master=[(p,'AppStore截图/'+n) for p,n in app_pairs]
for channel,expected in [('xiaohongshu',6),('wechat',1)]:
    folder=ROOT/'output/marketing/3.1.2'/channel/'2026-11-04'
    shutil.copyfile(DESIGN/'social/copy.md',folder/'文案与使用说明.md')
    manifest=json.loads((folder/'manifest.json').read_text())['files']; assert len(manifest)==expected
    assert len(list((folder/'images').glob('*.png')))==expected
    pairs=[]
    for m in manifest:
        p=folder/m['file']; assert sha(p)==m['sha256']
        report['files'].append(check_png(p,1080,1800 if channel=='wechat' else 1440))
        assert not re.search(r'\b\d+\.\d+\.\d+\b',m['text'])
        assert m['images']
        for ext in ['.png','.jpg']:
            f=p.with_suffix(ext); assert f.exists(); pairs.append((f,'images/'+f.name))
    copy=(DESIGN/'social/copy.md').read_text()
    heading='## 小红书正文' if channel=='xiaohongshu' else '## 微信传播正文'
    public=copy.split(heading,1)[1].split('\n## ',1)[0].strip()+'\n'
    ready=folder/'宣传文案-直接复制.txt';ready.write_text(public)
    pairs.append((ready,'宣传文案-直接复制.txt'))
    pairs.append((folder/'文案与使用说明.md','文案与使用说明.md'))
    z=folder/'宣传图片与文案.zip';report['archives'].append(write_zip(z,pairs))
    master += [(p,('小红书/' if channel=='xiaohongshu' else '微信/')+n) for p,n in pairs]
for name,limit,mode in [('promotional-text-zh-Hans.txt',170,'characters'),('description-zh-Hans.txt',4000,'characters'),('whats-new-zh-Hans.txt',4000,'characters'),('keywords-zh-Hans.txt',100,'utf8_bytes'),('review-notes-en.txt',4000,'utf8_bytes'),('review-notes-zh-Hans.txt',4000,'utf8_bytes')]:
    value=(RELEASE/name).read_text().rstrip('\n'); count=len(value.encode()) if mode=='utf8_bytes' else len(value)
    assert count<=limit,(name,count)
    report['text'][name]=dict(count=count,unit=mode,limit=limit)
for p in sorted(RELEASE.iterdir()):
    if p.is_file() and p.name!='DEVELOPMENT.md': master.append((p,'审核与发布文字/'+p.name))
for p in sorted((DESIGN/'web').glob('*.md')): master.append((p,'隐私支持与官网策划/'+p.name))
# Validate repository-local Markdown references in authored release and design documents.
links=[]
for p in list(RELEASE.glob('*.md'))+list(DESIGN.rglob('*.md')):
    if p.name=='DEVELOPMENT.md' or 'archive' in p.parts: continue
    for raw in re.findall(r'!?\[[^\]]*\]\(([^)]+)\)',p.read_text()):
        ref=raw.strip('<>').split('#')[0]
        if not ref or ':' in ref or ref.startswith('/'): continue
        target=(p.parent/ref).resolve(); assert target.exists(),(str(p),ref)
        links.append(dict(file=str(p.relative_to(ROOT)),target=ref))
report['localMarkdownLinksChecked']=len(links)
# Review index uses local links; does not create or modify the public website.
index='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="robots" content="noindex,nofollow"><title>棒球比赛记录大师 · 发布资料</title><style>*{box-sizing:border-box}body{margin:0;background:#f4f5ef;color:#0b1933;font:18px/1.7 -apple-system,'PingFang SC',sans-serif}main{max-width:1180px;margin:auto;padding:54px 32px}h1{font-size:44px;line-height:1.2}h2{font-size:27px;margin-top:42px}a{color:#246347}img{max-width:100%;height:auto;border-radius:12px}.notice{background:#e7eddc;padding:18px 24px;border-radius:14px}li{margin:8px 0}.wx{max-width:400px}</style><main><h1>棒球比赛记录大师<br>发布资料取用</h1><p class="notice">审核与宣传资料已准备 · 官网及新隐私／支持已在上海上线 · App和社交平台未代上传</p><p><a href="发布准备资料.zip">下载全部图片与文字</a> · <a href="../../../docs/releases/3.1.2/README-先看这里.md">交付说明与待办</a> · <a href="../../../docs/releases/3.1.2/APPSTORE-填写清单.md">App Store 逐字段填写清单</a></p><h2>小红书 · 六张双平台图片</h2><a href="../../marketing/3.1.2/xiaohongshu/2026-11-04/宣传图片与文案.zip">图片与文案 ZIP</a><p>四设备摄影主视觉与独立功能页。主视觉仅保留 Duo 为折叠设备，使用开机品牌画面与无球员资料的 Android 规则页，球员用演示标记；对外无版本号，Android 测试中。</p><img src="../../marketing/3.1.2/xiaohongshu/2026-11-04/overview.png"><h2>微信 · 一页图</h2><a href="../../marketing/3.1.2/wechat/2026-11-04/宣传图片与文案.zip">图片与文案 ZIP</a><p><img class="wx" src="../../marketing/3.1.2/wechat/2026-11-04/images/棒球比赛记录大师-微信一页图.png"></p><h2>App Store · 十一组尺寸</h2><p><a href="app-store/index.html">查看商店图片</a> · <a href="AppStore截图.zip">34 张 PNG ZIP</a> · <a href="../../../docs/releases/3.1.2/APPSTORE-ARTWORK.md">尺寸与来源</a></p><h2>审核文字</h2><p><a href="../../../docs/releases/3.1.2/APPSTORE-METADATA.md">字段与文案入口</a> · <a href="../../../docs/releases/3.1.2/REVIEW-HANDOFF.md">审核交接</a> · <a href="../../../docs/releases/3.1.2/APP-PRIVACY.md">隐私申报依据</a></p><h2>新隐私与支持内容</h2><p><a href="https://baseballmaster.cc/scorekeeper/privacy.html">中文隐私（已上线）</a> · <a href="https://baseballmaster.cc/scorekeeper/privacy-en.html">Privacy (live)</a> · <a href="https://baseballmaster.cc/scorekeeper/support.html">中文支持（已上线）</a> · <a href="https://baseballmaster.cc/scorekeeper/support-en.html">Support (live)</a></p><p>新地址已在上海上线并通过匿名 HTTPS；旧隐私／支持仍返回 200，保留原锚点。<a href="../../../docs/releases/3.1.2/WEB-CONTENT-HANDOFF.md">网址与上海环境交接</a></p><h2>官网更新</h2><p><a href="../../../docs/design/marketing/3.1.2/web/website-plan.md">页面编排与可用文案</a> · <a href="https://baseballmaster.cc/scorekeeper">查看已上线官网</a> · 四设备透明底主视觉已发布，首页底部备案可见。</p></main></html>'''
archive_receipt=ROOT/'output/validation/3.1.2/archive-2026-11-04/archive-receipt.json'
export_receipt=ROOT/'output/validation/3.1.2/archive-2026-11-04/export-receipt.json'
if archive_receipt.exists() and export_receipt.exists():
    report['formalArchive']=json.loads(archive_receipt.read_text())
    report['appStoreIPA']=json.loads(export_receipt.read_text())
    index=index.replace('</main>','<h2>正式 Release 归档与 App Store IPA</h2><p>3.1.2（Build 1）签名归档及 App Store 导出已完成，未上传或提交审核。</p><p><a href="archives/BaseballMaster-3.1.2-build1.xcarchive">打开 xcarchive 归档</a> · <a href="app-store-export/BaseballMaster.ipa">取用 App Store IPA</a> · <a href="../../../docs/releases/3.1.2/ARCHIVE.md">归档说明与校验</a></p></main>')
(OUT/'index.html').write_text(index)

report['archives'].append(write_zip(OUT/'发布准备资料.zip',master))
deployment=ROOT/'output/validation/3.1.2/website-publish-2026-11-04/deployment-receipt.json'
assert deployment.exists()
report['websiteDeployment']=json.loads(deployment.read_text())
backend=ROOT/'output/validation/3.1.2/bulk-backend-deploy-2026-11-04/deployment-receipt.json'
report['sharedBulkBackendDeployment']=json.loads(backend.read_text())
assert report['sharedBulkBackendDeployment']['bulkCapability'] is True
report['visualReview']='34 App Store images reviewed by source/layout groups; all 6 rebuilt social originals and 1 WeChat original opened and reviewed. Old illustrated draft rejected and excluded.'
(QA/'final-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'imagesValidated':len(report['files']),'archives':report['archives'],'text':report['text']},ensure_ascii=False,indent=2))
