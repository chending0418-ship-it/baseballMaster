from pathlib import Path
import json,logging,re,subprocess,math,unicodedata,os
import pdfplumber
from PIL import Image,ImageDraw
logging.getLogger('pdfminer').setLevel(logging.ERROR)
source=Path(__file__).resolve().parent
root=next(p for p in source.parents if (p/'android/build.gradle.kts').is_file())
p=Path(os.environ.get('PDF_PARITY_OUTPUT', str(root/'output/validation/android/0.8.2/pdf-parity/native-harness'))).resolve()
renders=p/'renders';renders.mkdir(exist_ok=True)
checks=[];details={};all_images=[]
def check(name,condition):
 checks.append({'name':name,'passed':bool(condition)})
for f in sorted(p.glob('*.pdf')):
 with pdfplumber.open(f) as pdf:
  texts=[unicodedata.normalize('NFKC',page.extract_text() or '') for page in pdf.pages]
  joined='\n'.join(texts);compact=re.sub(r'\s+','',joined)
  details[f.name]={'pages':len(pdf.pages),'characters':len(joined)}
  for i,page in enumerate(pdf.pages):
   # Native font glyph bounds may include small overhangs; retain a two-point tolerance.
   chars=page.chars
   check(f'{f.name} page{i+1} text within printable horizontal area',all(c['x0']>=34 and c['x1']<=page.width-34 for c in chars))
   check(f'{f.name} page{i+1} no footer overlap',all(c['bottom']<=page.height-40 or c['top']>=page.height-27 for c in chars))
   if 'B/S/O=' in re.sub(r'\s+','',texts[i]):
    check(f'{f.name} page{i+1} legend stays with event table','序号/类型' in re.sub(r'\s+','',texts[i]))
   # Headers (navy fill) are physical rectangles, independent of font-text extraction.
   for w in page.extract_words():
    if '暂无符合' in unicodedata.normalize('NFKC',w['text']):
     rects=[r for r in page.rects if r.get('non_stroking_color') and isinstance(r['non_stroking_color'],tuple) and len(r['non_stroking_color'])==3 and r['non_stroking_color'][2]>.20 and r['non_stroking_color'][0]<.1 and r['bottom']<=w['bottom']]
     if rects:
      header=max(rects,key=lambda r:r['bottom'])
      check(f'{f.name} page{i+1} empty-message below table-header',w['top']>=header['bottom']+4)
  if f.name=='stress-30-innings.pdf':
   for token in ['1-12','13-24','25-30']:check('thirty innings '+token,token in compact)
  if f.name=='stress-64-players.pdf':
   check('64-player total kept with last player',any('合计' in t and '末尾姓名标记64' in re.sub(r'\s+','',t) for t in texts))
   for i in range(1,65):check('long roster preserved '+str(i),f'末尾姓名标记{i}' in compact)
  if f.name.startswith('stress-long-event'):check(f.name+' long-event terminal preserved','事件末尾保留标记' in compact)
  if f.name=='timed-report.pdf':check('timed innings exclude unplayed preallocated slots','(1-2局)' in compact and '(1-6局)' not in compact)
  if f.name=='slow-pitch-report.pdf':check('slow-pitch complete lineup and tenth player retained','完整打序与守备' in compact and '慢垒第十名' in compact)
  if f.name=='text-record.pdf':
   check('ordinary text records use single page',len(pdf.pages)==1)
   check('text pitcher retains multiple jersey numbers','#00/#2赵景行' in compact)
  if f.name=='single-appearance.pdf':check('single selected appearance uses one page',len(pdf.pages)==1)
  if f.name=='coach-report.pdf':check('coach pitcher not-applicable retained','教练投手模式' in compact and '投手数据不适用' in compact)
  subprocess.run(['pdftoppm','-scale-to','1100','-png',str(f),str(renders/f.stem)],check=True,capture_output=True)
  images=sorted(im for im in renders.glob(f.stem+'-*.png') if re.fullmatch(re.escape(f.stem)+r'-\d+\.png',im.name));all_images+=images
# Every page is included in one of the numbered review sheets.
for start in range(0,len(all_images),12):
 batch=all_images[start:start+12];canvas=Image.new('RGB',(1800,1305),'#ddd');d=ImageDraw.Draw(canvas)
 for i,f in enumerate(batch):
  im=Image.open(f).convert('RGB');im.thumbnail((440,400));x=i%4*450+(450-im.width)//2;y=i//4*435+25
  canvas.paste(im,(x,y));d.text((i%4*450+8,i//4*435+5),f.stem,fill='black')
 canvas.save(p/f'review-sheet-{start//12+1}.jpg')
(p/'pdf-validation.json').write_text(json.dumps({'files':details,'checks':checks,'total':len(checks),'failed':sum(not c['passed'] for c in checks),'reviewSheets':math.ceil(len(all_images)/12)},ensure_ascii=False,indent=2))
print(json.dumps({'files':details,'checks':len(checks),'failed':[c['name'] for c in checks if not c['passed']],'reviewSheets':math.ceil(len(all_images)/12)},ensure_ascii=False))

if any(not c["passed"] for c in checks): raise SystemExit(1)
