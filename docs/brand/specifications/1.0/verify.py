from pathlib import Path
import json,statistics
from PIL import Image,ImageDraw
from pypdf import PdfReader
import pdfplumber

ROOT=Path(__file__).resolve().parents[4]
QA=ROOT/'output/validation/brand/1.0'
PDF=ROOT/'output/marketing/brand/1.0/BaseballMaster-品牌与产品设计规范-1.0.pdf'
files=sorted((QA/'pages').glob('page-*.png'))
assert len(files)==24,len(files)
for n in range(6):
    sheet=Image.new('RGB',(1660,1230),'#E3E8F0');d=ImageDraw.Draw(sheet)
    for j,f in enumerate(files[n*4:n*4+4]):
        im=Image.open(f).convert('RGB');im.thumbnail((800,567))
        x=20+(j%2)*820;y=30+(j//2)*605
        d.text((x,y-18),f'PAGE {n*4+j+1:02d}',fill='#0B1933');sheet.paste(im,(x,y))
    sheet.save(QA/f'contact-{n+1:02d}.png')
reader=PdfReader(PDF);texts=[p.extract_text() or '' for p in reader.pages];alltext='\n'.join(texts)
for s in ['品牌与产品','棒球比赛记录大师','教练口袋','Coach Insights','#0B1933','800','400','-0.045em','秒表','距离测量','辅助估算','1.0']:
    assert s in alltext,s
assert '\ufffd' not in alltext
assert len(reader.pages)==24 and len(reader.outline)==24
fonts={}
for p in reader.pages:
    for _,v in p['/Resources']['/Font'].items():
        f=v.get_object();desc=f.get('/FontDescriptor');fonts[str(f.get('/BaseFont'))]=bool(desc and any(x in desc.get_object() for x in ['/FontFile','/FontFile2','/FontFile3']))
assert all(v for k,v in fonts.items() if 'STHeiti' in k or 'Arial' in k),fonts
with pdfplumber.open(PDF) as doc:
    bounds=[]
    for i,p in enumerate(doc.pages):
        outside=[ch for ch in p.chars if ch['x0']<0 or ch['x1']>p.width+.1 or ch['top']<0 or ch['bottom']>p.height+.1]
        assert not outside,(i+1,outside[:2])
        bounds.append({'page':i+1,'characters':len(p.chars),'pageBoundsPassed':True})
    letters=[ch for ch in doc.pages[4].chars if 180<ch['top']<239 and ch['fontname'].endswith(('ArialMT','Arial-BoldMT'))]
    word=''.join(ch['text'] for ch in letters)
    assert word=='BASEBALLMASTER',word
    assert all('Bold' in ch['fontname'] for ch in letters[:8])
    assert all('Bold' not in ch['fontname'] for ch in letters[8:])
    tracking=[letters[i+1]['x0']-letters[i]['x1'] for i in range(len(letters)-1)]
    assert all(abs(v+2.16)<.02 for v in tracking),tracking
result={'pages':24,'bookmarks':24,'embeddedFonts':fonts,'requiredNamesAndParametersPresent':True,'pageTextLengths':[len(t) for t in texts],'renderedWordmark':{'text':word,'segments':['BASEBALL / Arial Bold (CSS 800 equivalent)','MASTER / Arial Regular (400)'],'fontSize':48,'actualLetterSpacing':statistics.median(tracking),'em':statistics.median(tracking)/48},'characterBounds':bounds}
(QA/'pdf-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
(QA/'page-text.txt').write_text('\n\n'.join(f'PAGE {i+1:02d}\n'+s for i,s in enumerate(texts)))
print('PDF: 24 pages, embedded Chinese/Arial fonts, internal bookmarks, exact wordmark tracking and character bounds verified.')
