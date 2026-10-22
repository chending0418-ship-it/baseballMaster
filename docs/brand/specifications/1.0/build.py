"""BaseballMaster brand/product standard 1.0. Generate one PDF and archive a copy."""
from pathlib import Path
import json, math, hashlib, shutil, re
from PIL import Image
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.colors import HexColor, Color, white
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
ASSETS = HERE / 'assets'
OUT = ROOT / 'output/marketing/brand/1.0'
QA = ROOT / 'output/validation/brand/1.0'
OUT.mkdir(parents=True, exist_ok=True)
QA.mkdir(parents=True, exist_ok=True)
NAME = 'BaseballMaster-品牌与产品设计规范-1.0.pdf'
W, H = landscape(A4)
M, GAP, TOTAL = 44, 22, 24
CW = W - 2*M
NAVY, MUTED, PAPER, LINE = '#0B1933', '#53647D', '#F7F8FB', '#DCE2EC'
GREEN, WINE, IVORY = '#092A2A', '#722F37', '#FFF8F0'
for name, file in [('CN','/System/Library/Fonts/STHeiti Light.ttc'),('CNB','/System/Library/Fonts/STHeiti Medium.ttc'),('AR','/System/Library/Fonts/Supplemental/Arial.ttf'),('ARB','/System/Library/Fonts/Supplemental/Arial Bold.ttf')]:
    pdfmetrics.registerFont(TTFont(name, file, subfontIndex=0))

c = canvas.Canvas(str(OUT/NAME), pagesize=(W,H), pageCompression=1)
c.setTitle('BaseballMaster 品牌与产品设计规范 1.0')
c.setAuthor('BaseballMaster')
c.setCreator('BaseballMaster Brand Standard 1.0')
c.setSubject('母品牌、棒球比赛记录大师、教练口袋的品牌与产品设计原则')
page_num = 0
checks, current = [], None

def color(v): return HexColor(v) if isinstance(v,str) else v
def log(kind, x, t, w, h, value=''):
    assert x >= -0.1 and t >= -0.1 and x+w <= W+0.1 and t+h <= H-41, (page_num,kind,x,t,w,h,value)
    current['elements'].append({'kind':kind,'x':round(x,2),'top':round(t,2),'width':round(w,2),'height':round(h,2),'text':value})

def wrapped(s, width, size, font):
    lines=[]
    for segment in str(s).split('\n'):
        line=''
        for ch in segment:
            if pdfmetrics.stringWidth(line+ch,font,size)>width and line:
                lines.append(line);line=ch
            else:line+=ch
        lines.append(line)
    return lines

def txt(s,x,t,width=None,size=11,font='CN',ink=NAVY,leading=None):
    leading=leading or size*1.55
    lines=wrapped(s,width,size,font) if width else str(s).split('\n')
    for i,line in enumerate(lines):
        for ch in line:
            if ord(ch)>127: assert ord(ch) in pdfmetrics.getFont(font).face.charToGlyph, ('missing glyph',ch,font)
        o=c.beginText(x,H-t-size-i*leading);o.setFillColor(color(ink));o.setFont(font,size);o.setCharSpace(0);o.setWordSpace(0);o.textOut(line);c.drawText(o)
    actual=max([pdfmetrics.stringWidth(line,font,size) for line in lines] or [0])
    log('text',x,t,actual,len(lines)*leading,str(s))
    return t+len(lines)*leading

def rect(x,t,w,h,fill=white,stroke=None,r=12):
    c.setFillColor(color(fill));c.setStrokeColor(color(stroke or fill))
    c.roundRect(x,H-t-h,w,h,r,fill=1,stroke=bool(stroke))
    log('box',x,t,w,h)

def line(x1,t1,x2,t2,ink=LINE,width=.7,dash=None):
    c.saveState();c.setStrokeColor(color(ink));c.setLineWidth(width)
    if dash:c.setDash(dash)
    c.line(x1,H-t1,x2,H-t2);c.restoreState()

def img(file,x,t,w,h=None):
    file=Path(file)
    iw,ih=Image.open(file).size
    h=h or w*ih/iw
    c.drawImage(str(file),x,H-t-h,w,h,mask='auto')
    log('image',x,t,w,h,str(file.relative_to(ROOT)))
    return h

def word(x,t,size=19,first='Baseball',last='Master.cc',ink=NAVY):
    px=x
    for segment,font in [(first,'ARB'),(last,'AR')]:
        for ch in segment:
            o=c.beginText(px,H-t-size);o.setFillColor(color(ink));o.setFont(font,size);o.setCharSpace(0);o.textOut(ch);c.drawText(o)
            px+=pdfmetrics.stringWidth(ch,font,size)-.045*size
    width=pdfmetrics.stringWidth(first,'ARB',size)+pdfmetrics.stringWidth(last,'AR',size)-.045*size*(len(first+last)-1)
    log('wordmark',x,t,width,size*1.3,first+last)
    return width

def rounded_clip(x,y,w,h,r):
    k=.5522847498;p=c.beginPath();p.moveTo(x+r,y);p.lineTo(x+w-r,y);p.curveTo(x+w-r+k*r,y,x+w,y+r-k*r,x+w,y+r);p.lineTo(x+w,y+h-r);p.curveTo(x+w,y+h-r+k*r,x+w-r+k*r,y+h,x+w-r,y+h);p.lineTo(x+r,y+h);p.curveTo(x+r-k*r,y+h,x,y+h-r+k*r,x,y+h-r);p.lineTo(x,y+r);p.curveTo(x,y+r-k*r,x+r-k*r,y,x+r,y);p.close();c.clipPath(p,stroke=0,fill=0)

def product(which,x,t,scale=1,ink=NAVY):
    shade='white' if ink==white else 'navy'
    file=ASSETS/f'product-{which}-{shade}.png'
    iw,ih=Image.open(file).size
    img(file,x,t,44*scale*iw/ih,44*scale)

def start(title,section,sub=''):
    global page_num,current
    page_num+=1;current={'page':page_num,'title':title,'section':section,'elements':[]};checks.append(current)
    c.bookmarkPage('p'+str(page_num));c.addOutlineEntry(title,'p'+str(page_num),level=0)
    c.setFillColor(color(PAPER));c.rect(0,0,W,H,fill=1,stroke=0)
    txt(section,M,22,size=8.2,font='AR',ink=MUTED)
    img(ASSETS/'parent-horizontal.png',W-204,16,160)
    line(M,57,W-M,57)
    txt(title,M,77,size=26,font='CNB')
    if sub:txt(sub,M,117,CW,size=10.2,ink=MUTED,leading=15)

def end():
    line(M,H-32,W-M,H-32)
    o=c.beginText(M,17);o.setFont('AR',7.1);o.setFillColor(color(MUTED));o.setCharSpace(0);o.textOut('BASEBALLMASTER / BRAND & PRODUCT STANDARD 1.0');c.drawText(o)
    c.setFont('AR',7.1);c.setFillColor(color(MUTED))
    c.drawRightString(W-M,17,f'{page_num:02d} / {TOTAL:02d}     2026.10.22')
    c.showPage()

def card(title,body,x,t,w,h=125,fill=white):
    rect(x,t,w,h,fill,LINE)
    txt(title,x+17,t+17,w-34,size=13.2,font='CNB')
    bottom=txt(body,x+17,t+48,w-34,size=10,ink=MUTED,leading=15.2)
    assert bottom<=t+h-8,(page_num,title,bottom,t+h)

def table(rows,x,t,widths,heads=None,size=10,lead=15):
    y=t
    if heads:
        for i,h in enumerate(heads):txt(h,x+sum(widths[:i])+10,y+7,widths[i]-20,size=8.8,font='CNB',ink=MUTED)
        y+=31;line(x,y,x+sum(widths),y)
    for row in rows:
        height=max(len(wrapped(str(v),widths[i]-20,size,'CN')) for i,v in enumerate(row))*lead+18
        for i,v in enumerate(row):txt(str(v),x+sum(widths[:i])+10,y+8,widths[i]-20,size=size,font='CNB' if i==0 else 'CN',ink=NAVY if i==0 else MUTED,leading=lead)
        y+=height;line(x,y,x+sum(widths),y)
    return y

def swatch(x,t,w,h,hexcode,label,extra='',textwhite=False):
    rect(x,t,w,h,hexcode,r=10)
    txt(label,x+15,t+16,w-30,size=12,font='CNB',ink=white if textwhite else NAVY)
    txt(hexcode,x+15,t+h-37,size=12,font='AR',ink=white if textwhite else NAVY)
    if extra:txt(extra,x,t+h+10,w,size=9.5,ink=MUTED,leading=14)

def pill(s,x,t,ink=NAVY):
    w=pdfmetrics.stringWidth(s,'CN',8)+18;rect(x,t,w,20,ink,r=10);txt(s,x+9,t+4,size=8,ink=white)

# 01 - cover
page_num=1;current={'page':1,'title':'品牌与产品设计规范','section':'Cover','elements':[]};checks.append(current)
c.bookmarkPage('p1');c.addOutlineEntry('品牌与产品设计规范','p1',level=0)
c.setFillColor(color(NAVY));c.rect(0,0,W,H,fill=1,stroke=0)
img(ASSETS/'parent-horizontal-reverse.png',M,42,430)
txt('BRAND & PRODUCT DESIGN STANDARD',M,168,size=10,font='AR',ink=white)
txt('品牌与产品\n设计规范',M,209,size=42,font='CNB',ink=white,leading=57)
txt('1.0',W-204,222,size=64,font='AR',ink=white)
txt('文档版本 / 2026.10.22',W-205,305,size=10,ink='#C4CEDD')
txt('为比赛留下过程\n为训练找到方向',M,366,size=17,ink='#CFD8E6',leading=27)
product('record',M,464,.88,white);product('coach',W/2+32,464,.88,white)
c.setFillColor(color(GREEN));c.rect(0,0,W/2,8,fill=1,stroke=0);c.setFillColor(color(WINE));c.rect(W/2,0,W/2,8,fill=1,stroke=0)
o=c.beginText(M,19);o.setFont('AR',7);o.setFillColor(color('#C4CEDD'));o.setCharSpace(0);o.textOut('BASEBALLMASTER.CC');c.drawText(o);c.setFont('AR',7);c.setFillColor(color('#C4CEDD'));c.drawRightString(W-M,19,'01 / 24');c.showPage()

# 02 - guide and contents
start('使用这份规范','01 / HOW TO USE','1.0 固化当前确认的品牌方向；应用建议与本地首页示例均有明确说明，不代表网站已部署。')
items=[('03','一个品牌，两条产品线'),('04','母品牌 Logo 组合'),('05','英文字重与间距'),('06','安全区与最小尺寸'),('07','母品牌颜色系统'),('08','标识的正确与错误用法'),('09','两款产品的统一签名'),('10-12','棒球比赛记录大师'),('13-15','教练口袋'),('16','共同排版与界面原则'),('17-18','品牌首页与响应式'),('19-21','宣传物料与渠道应用'),('22','命名、文案与能力表达'),('23','数字资产与交付'),('24','版本维护与复核')]
for i,(num,label) in enumerate(items):
    col=i//8;row=i%8;x=M+col*(CW/2+15);t=158+row*31
    txt(num,x,t,size=10,font='AR',ink=MUTED);txt(label,x+48,t,size=11)
    c.linkRect('',f'p{int(num.split("-")[0])}',(x,H-t-18,x+CW/2-22,H-t+2),relative=0,thickness=0)
rect(M,444,CW,66,'#E9EEF5',r=12)
txt('固定基准',M+16,455,size=10,font='CNB');txt('已确认的图形、命名、配色、800/400 字重与 -0.045em 字距。',M+98,455,CW-118,size=10,ink=MUTED)
txt('应用建议',M+16,481,size=10,font='CNB');txt('安全区、推荐尺寸与组件节奏是 1.0 执行建议；首页图为当前本地应用快照。',M+98,481,CW-118,size=10,ink=MUTED)
end()

# 03 - architecture
start('一个品牌，两条产品线','02 / BRAND ARCHITECTURE','母品牌提供统一识别；产品各自面向具体任务，保持独立名称、图标与产品色。')
img(ASSETS/'parent-horizontal.png',W/2-240,160,480)
line(W/2,254,W/2,280);line(229,280,W-229,280);line(229,280,229,297);line(W-229,280,W-229,297)
col=(CW-GAP)/2
rect(M,297,col,160,white,LINE);rect(M+col+GAP,297,col,160,white,LINE)
product('record',M+22,319,1.03);product('coach',M+col+GAP+22,319,1.03)
pill('深绿 / 比赛记录',M+22,387,GREEN);pill('酒红 / 教练工具',M+col+GAP+22,387,WINE)
txt('逐球过程、比赛统计、球队协作与分享。',M+22,422,col-44,size=10.2,ink=MUTED)
txt('从视频分析起步，逐步容纳更多教练常用工具。',M+col+GAP+22,422,col-44,size=10.2,ink=MUTED)
txt('同一品牌，两款独立 App。不能把母品牌标识替换成任一 App Icon，也不暗示产品已互通数据或共用帐号。',M,485,CW,size=10.2,ink=MUTED)
end()

# 04 - variants
start('母品牌 Logo 组合','03 / PARENT IDENTITY','采用用户选定的 BM 连字与棒球缝线图形。图形和英文统一使用深藏青 #0B1933。')
col=(CW-GAP)/2
rect(M,157,col,142,white,LINE);img(ASSETS/'parent-horizontal.png',M+15,192,col-30);txt('横版主组合 / 页头、页脚、横向品牌栏',M+17,268,col-34,size=9.2,ink=MUTED)
rect(M+col+GAP,157,col,142,NAVY);img(ASSETS/'parent-horizontal-reverse.png',M+col+GAP+15,192,col-30);txt('反白组合 / 深藏青背景',M+col+GAP+17,268,col-34,size=9.2,ink=white)
rect(M,317,col,197,white,LINE);img(ASSETS/'parent-stacked.png',M+70,333,col-140);txt('竖版 / 封面、居中陈列',M+17,484,col-34,size=9.2,ink=MUTED)
rect(M+col+GAP,317,col,197,white,LINE);img(ASSETS/'parent-compact.png',M+col+GAP+36,351,col-72);txt('紧凑版 / 狭窄版位；独立图形用于 favicon 等',M+col+GAP+17,484,col-34,size=9.2,ink=MUTED)
end()

# 05 - typography
start('字重和间距，是共同规范','04 / WORDMARK TYPOGRAPHY','母品牌与产品的英文字重关系保持一致。缩放时按字号折算字距，不使用手动空格补间距。')
rect(M,159,CW,118,white,LINE);word(M+28,185,48,'BASEBALL','MASTER')
txt('BASEBALL 800',M+30,249,size=9,font='AR',ink=MUTED);txt('MASTER 400',M+365,249,size=9,font='AR',ink=MUTED)
col=(CW-2*GAP)/3
card('Arial','沿用既有英文字体。\nBASEBALL 使用 800。\nMASTER 使用 400，保留对比。',M,298,col,117)
card('-0.045em','17 px 对应 -0.765 px\n19 px 对应 -0.855 px\n54 px 对应 -2.43 px',M+col+GAP,298,col,117)
card('精确拼写','母品牌：BASEBALLMASTER\n产品主文字：BaseballMaster.cc\n不要加入空格或更换后缀。',M+2*(col+GAP),298,col,117)
txt('母品牌采用全大写。产品使用已确认的域名组合与中文副标题；两者不互换名称格式，但共同遵守 800 / 400 和 -0.045em。',M,445,CW,size=11,ink=MUTED)
end()

# 06 - clear space
start('安全区与最小尺寸','05 / SPACING & SCALE','以下为 1.0 应用建议。优先保持完整组合与可读性，先换组合，再缩小到难以识别。')
rect(M,164,430,219,white,LINE)
img(ASSETS/'parent-horizontal.png',M+45,224,340)
line(M+28,202,M+402,202,MUTED,.7,[3,3]);line(M+28,202,M+28,335,MUTED,.7,[3,3]);line(M+402,202,M+402,335,MUTED,.7,[3,3]);line(M+28,335,M+402,335,MUTED,.7,[3,3])
txt('X',M+12,242,size=11,font='AR',ink=MUTED);txt('X = BM 可见高度的 1/4',M+70,351,size=10,ink=MUTED)
txt('外部安全区',M+458,171,size=16,font='CNB');txt('组合外侧建议至少留出 X。不要让导航、照片边缘或其他标识挤进安全区。',M+458,205,CW-458,size=11,ink=MUTED)
txt('保持原始比例',M+458,280,size=16,font='CNB');txt('整组等比例缩放。英文、图形与二者间距一起缩放，不单独拉宽或压扁。',M+458,314,CW-458,size=11,ink=MUTED)
table([('横版','建议宽度至少 220 px'),('紧凑版','建议宽度至少 176 px'),('独立图形','常规建议宽度至少 40 px；favicon 按 16/32 px 检查')],M,408,[150,CW-150],size=10)
end()

# 07 - palette
start('母品牌深藏青，产品各有自己的颜色','06 / COLOR SYSTEM','颜色有明确归属。母品牌的图形、英文和首页主体文字统一深藏青，产品区保留各自的识别色。')
swatch(M,161,250,190,NAVY,'母品牌 / 主文字','RGB 11 / 25 / 51',True)
x=M+274;ww=(CW-298)/2
swatch(x,161,ww,83,'#53647D','次级文字','说明与辅助信息',True);swatch(x+ww+24,161,ww,83,'#F7F8FB','浅色页面底','品牌官网的基础画布')
swatch(x,295,ww,83,'#DCE2EC','边框与分隔','帮助建立层级，不抢占主视觉');swatch(x+ww+24,295,ww,83,'#FFFFFF','白色','反白标识、卡片和留白')
rect(M,425,CW,77,white,LINE);rect(M+16,442,36,36,GREEN,r=8);txt('比赛记录深绿',M+66,443,size=12,font='CNB');txt('#092A2A',M+66,465,size=10,font='AR',ink=MUTED)
rect(M+CW/2+16,442,36,36,WINE,r=8);txt('教练口袋酒红',M+CW/2+66,443,size=12,font='CNB');txt('#722F37',M+CW/2+66,465,size=10,font='AR',ink=MUTED)
end()

# 08 - correct use
start('标识用法保持克制','07 / CORRECT USE','原始图形、统一字重和深藏青是一套识别。下方右列仅为错误演示，不作为可使用的素材。')
col=(CW-GAP)/2
for r in range(3):
    rect(M,159+r*116,col,99,white,LINE);rect(M+col+GAP,159+r*116,col,99,white,LINE)
    txt('正确',M+13,170+r*116,size=9,font='CNB');txt('避免',M+col+GAP+13,170+r*116,size=9,font='CNB',ink=MUTED)
img(ASSETS/'parent-horizontal.png',M+19,187,260);txt('完整组合，等比例呈现',M+19,239,size=9,ink=MUTED)
img(ASSETS/'parent-horizontal.png',M+col+GAP+19,197,285,27);txt('压扁或拉伸图形',M+col+GAP+19,235,size=9,ink=MUTED)
word(M+19,314,23,'BASEBALL','MASTER');txt('保留两段字重对比',M+19,351,size=9,ink=MUTED)
txt('BASEBALLMASTER',M+col+GAP+19,314,size=23,font='ARB');txt('整串同一字重或增加手动空格',M+col+GAP+19,351,size=9,ink=MUTED)
rect(M+14,417,col-28,48,NAVY,r=7);img(ASSETS/'parent-horizontal-reverse.png',M+22,419,270);txt('深藏青底使用反白',M+19,467,size=9,ink=MUTED)
rect(M+col+GAP+14,417,col-28,48,GREEN,r=7);img(ASSETS/'parent-horizontal.png',M+col+GAP+22,419,270);txt('让产品色承载母品牌并造成低对比',M+col+GAP+19,467,size=9,ink=MUTED)
end()

# 09 - product lockups
start('两个产品，一套签名结构','08 / PRODUCT SIGNATURES','既有 App Icon + BaseballMaster.cc 主文字 + 中文产品名。用图标和产品色识别，用统一排版建立家族关系。')
col=(CW-GAP)/2
rect(M,162,col,156,white,LINE);rect(M+col+GAP,162,col,156,white,LINE)
product('record',M+20,193,1.15);product('coach',M+col+GAP+20,193,1.15)
txt('比赛记录的球场图标',M+20,277,size=10,ink=MUTED);txt('酒红洞察之眼 + 棒球瞳孔',M+col+GAP+20,277,size=10,ink=MUTED)
table([('图标','44 x 44 px，圆角 11 px，保留完整图形'),('主文字','Arial，19 px，800 / 400，字距 -0.045em'),('中文副标题','11 px / 450 / 行高 1.3；字距 0.16em；顶部间距 7 px'),('图文关系','图文间距 12 px，图标与两行文字垂直居中')],M,341,[150,CW-150],size=10)
end()

# 10 - record identity
start('棒球比赛记录大师','09 / PRODUCT 01 - IDENTITY','把逐球过程、比赛统计和球队协作放在一起，让比赛留下可回看的过程。')
rect(M,161,CW,183,GREEN,r=15);product('record',M+24,184,1.2,white)
txt('每一球有经过\n每一场可回看',M+24,251,size=25,font='CNB',ink='#F4F5EF',leading=33)
txt('SCOREKEEPER',M+CW-193,192,size=12,font='AR',ink='#C6D5CC');txt('产品识别色\n#092A2A',M+CW-193,229,size=13,font='CNB',ink='#C6D5CC',leading=25)
col=(CW-2*GAP)/3
card('认真记录','先让记录员看清局面，再完成输入。比赛过程和结果都值得清楚保存。',M,365,col,135)
card('回看有依据','比分、逐打席过程和统计的范围要清楚，让球队知道数据从哪里来。',M+col+GAP,365,col,135)
card('共享由你选择','区分本机与共享资料。共享、交接、历史观看权限需要明确表达。',M+2*(col+GAP),365,col,135)
end()

# 11 - record visual language
start('深绿、米白与球场图形','10 / PRODUCT 01 - VISUAL LANGUAGE','球场、落点和守备路线是产品信息的表达方式。细节正确，比装饰更重要。')
img(ASSETS/'record-field.png',M,161,365)
txt('当前首页功能示意',M,341,size=9.5,ink=MUTED)
x=M+391;ww=(CW-413)/2
swatch(x,161,ww,83,'#082D28','宣传深底','记录产品宣传',True);swatch(x+ww+22,161,ww,83,'#F4F5EF','宣传浅底','统计、选择与回看')
swatch(x,296,ww,83,'#D7EDB7','深底强调','重点标题与路线');swatch(x+ww+22,296,ww,83,'#537947','浅底强调','浅底标题重点',True)
txt('绘制原则',M,405,size=15,font='CNB')
txt('场地轮廓、守备位置、垒位与传球顺序应对应真实棒球语义。草纹裁在球场内，线条统一，光影方向一致；姓名与守位、路线节点一一对应。',M,435,CW,size=11,ink=MUTED)
end()

# 12 - record product principles
start('把关键的比赛信息放在前面','11 / PRODUCT 01 - DESIGN PRINCIPLES','这些原则用于界面、导出与宣传表达，不代表本次已经改动 App 界面。')
col=(CW-2*GAP)/3
data=[('先看局面，再做输入','比分、局数、出局数与垒况优先；不要让装饰、推广或次要设置盖过现场判断。'),('核对后更正','更正前说明影响，保留核对过程。撤销、重做、接手与换投等重要状态不能含糊。'),('统计范围看得见','区分本场、年度和可查看的比赛。示例数据标清，不把缺失记录推断成完整表现。'),('共享有明确边界','本机、球队共享和网页观看是不同范围。状态、权限与同步进度要可理解。'),('过程和结果一起留下','比赛报告与分享视觉应能说明经过，不只放一个比分或漂亮图表。'),('两种记分方式同等清楚','基础版与 Pro 是记录方式。没有订阅或内购，不用付费升级的视觉暗示。')]
for i,(a,b) in enumerate(data):card(a,b,M+(i%3)*(col+GAP),160+(i//3)*169,col,147)
end()

# 13 - coach identity
start('教练口袋 / Coach Insights','12 / PRODUCT 02 - IDENTITY','中文名固定为“教练口袋”。英文产品名保留 Coach Insights，体现从观察到发现的方向。')
rect(M,161,CW,183,WINE,r=15);product('coach',M+24,184,1.2,white)
txt('把教练常用的工具\n放进口袋',M+24,251,size=25,font='CNB',ink=IVORY,leading=33)
txt('COACH INSIGHTS',M+CW-202,192,size=12,font='AR',ink='#E7C8CD');txt('产品识别色\n#722F37',M+CW-202,229,size=13,font='CNB',ink='#E7C8CD',leading=25)
col=(CW-2*GAP)/3
card('观察更具体','慢放、逐帧、标注与对比，帮助教练和家长看清动作细节并沟通。',M,365,col,135)
card('工具能继续生长','当前从视频分析起步。命名与品牌不局限于人体分析或某一种算法。',M+col+GAP,365,col,135)
card('判断留给教练','人体与角度是辅助估算，需要人工复核。工具呈现线索，不代替执教判断。',M+2*(col+GAP),365,col,135)
end()

# 14 - coach visuals
start('酒红、象牙白与清楚的动作','13 / PRODUCT 02 - VISUAL LANGUAGE','保留洞察之眼与棒球瞳孔。产品图标中不增加字母、人物、轨迹或额外装饰。')
img(ASSETS/'coach-pitcher.png',M,161,365)
txt('当前首页投手动作示意 / 非人体识别结果',M,341,size=9.5,ink=MUTED)
x=M+391;ww=(CW-413)/2
swatch(x,161,ww,83,WINE,'主产品色','固定识别色',True);swatch(x+ww+22,161,ww,83,IVORY,'图标象牙白','保留原始图形对比')
swatch(x,296,ww,83,'#D6A8AC','示意辅助色','1.0 应用建议');swatch(x+ww+22,296,ww,83,'#4F2028','示意深底','用于动作画布的示例',True)
txt('动作与图示原则',M,405,size=15,font='CNB')
txt('示意动作参考真实运动姿态，关节与肢体方向合理。分析结果、人工标注和装饰图形应有区分；涉及真实识别时保留“辅助估算”语义，不用示意图证明精度。',M,435,CW,size=11,ink=MUTED)
end()

# 15 - coach tools
start('从视频分析，延展到教练常用工具','14 / PRODUCT 02 - CAPABILITIES','已有能力与后续方向分别表达；本次没有设计教练口袋独立产品页，也未实现新增工具。')
table([('已实现','拍摄与导入、慢放与逐帧、画面标注、关键帧、双视频对比、骨架与二维角度辅助估算、截图及视频导出。'),('数据原则','视频与分析保存在当前设备，不要求登录，不使用 iCloud 同步；iPhone 与 iPad 分别保存。'),('复核原则','时间轴、画面、标注和辅助点位需对应同一时刻。对偏移、遮挡或缺失结果提供清楚说明。')],M,157,[116,CW-116],size=10.5,lead=16)
txt('后续扩展方向',M,369,size=16,font='CNB')
col=(CW-2*GAP)/3
card('秒表','后续方向。设计时明确开始、暂停、重置、计次与计时状态。',M,402,col,111)
card('距离测量','后续方向。先明确测量方法、单位、校准与误差，再做能力承诺。',M+col+GAP,402,col,111)
card('更多场边工具','后续方向。按任务组织入口，保持工具状态清楚，避免功能堆叠。',M+2*(col+GAP),402,col,111)
end()

# 16 - layout
start('共同的排版与界面原则','15 / TYPOGRAPHY & LAYOUT','品牌有共同语气，界面仍服务各自任务。网页尺寸不直接套用为原生 App 的字号。')
rect(M,159,350,350,white,LINE)
txt('标题先被看见',M+22,184,size=29,font='CNB');txt('一句收益，一层重点',M+22,237,size=20,font='CNB')
txt('正文说明具体动作和收益。每个区块只推进一个信息，不把全部功能挤进同一屏。',M+22,282,306,size=12,ink=MUTED,leading=20)
txt('正文建议 13-16 px\n说明建议 10-12 px\n中文优先 PingFang SC 与系统无衬线字体',M+22,384,306,size=10.3,ink=MUTED,leading=22)
x=M+376
table([('标题层级','桌面主标题 64-78 px，手机 44-58 px；按实际版位检查换行。'),('间距节奏','优先复用 4/8/12/16/20/24/32/48/64 的节奏，按内容选择。'),('容器与卡片','留白有秩序，边框轻、投影克制。网页卡片圆角示例 12-22 px。'),('可读与可操作','正文对比清楚，焦点可见；状态不只靠颜色区分。按钮与文字避免拥挤。'),('原生 App','保留系统交互与可读字号。重要控制持续可见，不用品牌装饰打断任务。')],x,158,[80,CW-456],size=9.9,lead=15)
end()

# 17 - homepage
start('品牌首页：先建立母品牌，再选择产品','16 / HOMEPAGE APPLICATION','当前本地设计快照。母品牌深藏青、两条产品线等权重，版本更新不占据全站唯一主入口。')
img(ASSETS/'homepage-first-screen.png',M,158,514)
x=M+538
for i,(a,b) in enumerate([('01 / 品牌','BM 图形 + 统一英文；从比赛与训练的共同价值开场。'),('02 / 双产品','两张同权重产品卡，各自保留 App 图标与产品色。'),('03 / 按需选择','从具体任务出发，帮助访客选对工具。'),('04 / 理念与帮助','说明资料去向与产品边界，帮助入口按产品分别设置。')]):
    tt=159+i*86;txt(a,x,tt,CW-538,size=11,font='CNB');txt(b,x,tt+25,CW-538,size=9.8,ink=MUTED,leading=15)
end()

# 18 - responsive
start('移动端也能看清两个产品','17 / RESPONSIVE APPLICATION','已检查 1440、1024、768、390、320 px 宽度。此页展示首页响应式应用，不是新增产品页。')
img(ASSETS/'homepage-mobile.png',M,156,139)
x=M+170
card('先保持识别，再调整排列','桌面并列展示双产品；手机按比赛记录、教练口袋顺序竖排。图文、按钮和留白一起调整，不只缩小桌面版。',x,161,CW-170,109)
card('窄屏使用紧凑组合','当前首页在 370 px 以下使用母品牌紧凑组合。完整图形和英文字重关系保留，避免导航挤压或横向溢出。',x,287,CW-170,109)
card('逐版位复核','检查标题断行、图标、产品名、导航和按钮。图形不可遮住文案；本地评审栏与灰色状态说明不属于未来官网内容。',x,413,CW-170,109)
end()

# 19 - shared marketing principles
start('大字、留白、一个视觉焦点','18 / PROMOTIONAL PRINCIPLES','沿用已确认的宣传设计原则。系列作品按渠道调整尺寸与数量，不靠重复页面凑数。')
refs=ROOT/'docs/brand/references/promotional-2026-10-22/images'
img(refs/'01-每一球都有迹可循.png',M,159,174)
img(refs/'02-两种方式一种顺手.png',M+193,159,174)
x=M+394
for i,(a,b) in enumerate([('标题第一层','两行简短标题，一句主题、一句收益。大标题不加句末标点。'),('主视觉第二层','一张图只保留一个焦点。球场、路线、数据或动作，要与主题直接有关。'),('文案第三层','一句引导加必要说明。用具体动作和收益表达，少用修饰词。'),('留白有秩序','品牌、标题、说明、主视觉与页脚各有空间；边缘安全区稳定。')]):
    tt=163+i*83;txt(a,x,tt,CW-394,size=13,font='CNB');txt(b,x,tt+28,CW-394,size=10,ink=MUTED,leading=15)
txt('左侧为已确认的比赛记录产品宣传参考。母品牌、产品色与频道限制分别判断，不混为一套全局颜色。',M,441,367,size=9.8,ink=MUTED,leading=15)
end()

# 20 - approved record posters
start('比赛记录宣传：四种已确认构图','19 / PRODUCT 01 - CAMPAIGN REFERENCES','这些图是构图与风格参考，不是固定模板。新主题按内容重新组织，不逐字复用标题或重复套用同一个球场。')
names=['01-每一球都有迹可循.png','02-两种方式一种顺手.png','03-从落点到传球.png','04-看见本场也看见进步.png']
labels=[('核心收益','深绿、大标题、单一球场焦点。'),('选择方式','米白、错落双卡、清楚比较。'),('关键过程','落点、路线、数字和姓名对应。'),('回看价值','本场与年度、表现与击球去向。')]
pw=(CW-3*18)/4
for i,(name,(title,desc)) in enumerate(zip(names,labels)):
    x=M+i*(pw+18);img(refs/name,x,160,pw);txt(title,x,409,pw,size=12,font='CNB');txt(desc,x,438,pw,size=9.8,ink=MUTED,leading=15)
txt('参考画布 1080 x 1440；标题约 91 px / 600 / 1.18，正文约 27 px / 1.7，安全边距左右至少 64 px。导出后按实际观看尺寸复核。',M,492,CW,size=9.4,ink=MUTED)
end()

# 21 - coach marketing
start('教练口袋宣传：让工具用途一眼可懂','20 / PRODUCT 02 - CAMPAIGN APPLICATION','沿用共同的留白、层级与单焦点原则，使用教练口袋自己的酒红、象牙白和动作语言。')
rect(M,161,365,314,WINE,r=16);product('coach',M+22,184,1.04,white)
txt('把动作看仔细\n把想法讲明白',M+22,257,size=22,font='CNB',ink=IVORY,leading=30)
picture_h=305*Image.open(ASSETS/'coach-pitcher.png').size[1]/Image.open(ASSETS/'coach-pitcher.png').size[0]
c.saveState();rounded_clip(M+30,H-331-picture_h,305,picture_h-2,9);img(ASSETS/'coach-pitcher.png',M+30,331,305);c.restoreState()
x=M+391
for i,(a,b) in enumerate([('动作分析主题','一个动作片段或一组清楚的对比。人工标注与辅助识别结果分别说明。'),('场边工具主题','计时、测距等工具实现后，才展示实际流程、读数、单位与使用收益。'),('真实与示意','示意图注明“功能示意”；真实视频与结果不得改写成不存在的能力。'),('发布语境','按实际商店、测试与服务状态写文案。不同渠道分别决定下载入口和网址。')]):
    tt=162+i*84;txt(a,x,tt,CW-391,size=12.5,font='CNB');txt(b,x,tt+28,CW-391,size=10,ink=MUTED,leading=15)
txt('左侧为已有首页元素的应用示例，不是独立产品页或已发布海报。',M,492,365,size=9,ink=MUTED)
end()

# 22 - language
start('名称准确，能力表达有边界','21 / NAMING & COPY','母品牌说共同价值，产品说具体任务。当前能力、示例数据和未来规划分别表达。')
table([('母品牌','BASEBALLMASTER / BaseballMaster','不把“棒球比赛记录大师”当作整个品牌的唯一含义。'),('比赛记录产品','棒球比赛记录大师','基础版与 Pro 是记录方式，不表达为订阅或付费层级。'),('教练工具产品','教练口袋 / Coach Insights','不改成“教练洞察”或“动作分析大师”，避免把长期范围锁窄。'),('分析能力','人体与二维角度为辅助估算，需人工复核','不宣传“100% 精确”“自动诊断”或未验证的准确率。'),('数据与示意','标注功能示意、示例姓名、示例数据与统计范围','不改写真实截图或将示例表现说成真实用户成绩。')],M,157,[110,265,CW-375],heads=['对象','推荐表达','避免的表达'],size=9.9,lead=15)
rect(M,466,CW,55,'#E9EEF5',r=10);txt('小红书沿用现行约定：默认不放版本号、预告状态、站外网址、下载提示、联系方式或二维码。官网等其他渠道按目的决定，不照搬该限制。',M+15,478,CW-30,size=10,ink=MUTED,leading=15)
end()

# 23 - assets and export
start('资产、格式与交付','22 / ASSET HANDOFF','品牌素材应可追溯、可复用。既有产品图标和当前母品牌原稿都保留，不依赖 OS 临时文件。')
table([('母品牌组合','横版、竖版、紧凑版、独立图形、反白横版；统一深藏青。'),('产品图标','比赛记录使用原球场图标；教练口袋使用已确认的棒球瞳孔图标。'),('交付格式','透明 PNG 用于日常投放；SVG 保存可编辑英文排版；PDF 用于规范查阅。'),('当前 SVG 边界','BM 图形为透明位图 alpha 轮廓嵌入，英文为可编辑文字；不是完全描摹的纯矢量标识。'),('印刷与放大','使用原始高分辨率资产，检查画面尺寸、有效分辨率与字体。颜色以 RGB/HEX 为当前数字基准。'),('复核与来源','保存原稿、渲染源稿、图标副本、机器可读参数与 SHA-256；发布包与总览图分开。')],M,155,[125,CW-125],size=10.5,lead=16)
rect(M,451,CW,64,white,LINE);txt('本项目内的固定入口',M+15,463,size=11,font='CNB');txt('docs/brand/ 规范与确认参考\noutput/marketing/brand/  本地交付包与首页评审稿',M+15,485,CW-30,size=9.5,font='CN',ink=MUTED,leading=14)
end()

# 24 - governance and sources
start('1.0 版本维护与交付复核','23 / VERSION & GOVERNANCE','文档版本 1.0 / 2026-10-22。此版本不是 App 版本号，不代表网站或新功能已经上线。')
col=(CW-GAP)/2
txt('发布前复核',M,160,size=16,font='CNB')
items=['母品牌图形与英文均为 #0B1933。','Arial、800/400、-0.045em 保持一致。','两个产品的图标、中文名、域名完整。','产品深绿和酒红只用于各自场景。','版位、换行、留白、图形裁切与对比清楚。','功能示意、示例数据、辅助估算标识准确。','实际截图、功能与公开状态相符。','单张发布图、总览与源稿归类保存。']
for i,s in enumerate(items):txt(f'{i+1:02d}  {s}',M,201+i*29,col,size=10.2,ink=MUTED,leading=15)
x=M+col+GAP
txt('规范来源与优先级',x,160,size=16,font='CNB')
sources=[('BRAND.md','既有记录产品标识与新母品牌分工。'),('PARENT-BRAND.md','BM 图形、深藏青及统一英文字重与字距。'),('COACH-POCKET.md','教练口袋产品组合、图标与排版参数。'),('PROMOTIONAL-DESIGN.md','已确认宣传图设计原则与四张参考。'),('specifications/1.0/','本版源稿、应用快照、PDF 与校验清单。')]
for i,(a,b) in enumerate(sources):
    tt=201+i*49;txt(a,x,tt,col,size=9.3,font='AR');txt(b,x,tt+17,col,size=9.5,ink=MUTED,leading=14)
rect(M,477,CW,47,'#E9EEF5',r=10);txt('后续修改：用户最新指令优先；新版本更新文档、源稿、参考与参数。既有线上页面与早期确认稿保留追溯，不冒充新版本已部署。',M+14,488,CW-28,size=9.5,ink=MUTED,leading=14)
end()

assert page_num==TOTAL,page_num
c.save()
shutil.copy2(OUT/NAME,HERE/NAME)
pdfhash=hashlib.sha256((OUT/NAME).read_bytes()).hexdigest()
manifest={'document':'BaseballMaster 品牌与产品设计规范','version':'1.0','date':'2026-10-22','pages':TOTAL,'pageSize':'A4 landscape','pdfSha256':pdfhash,'file':NAME,'parentNavy':NAVY,'wordmarkWeights':[800,400],'wordmarkLetterSpacingEm':-.045,'productColors':{'record':GREEN,'coach':WINE},'status':'brand/product standard with labeled application recommendations and local homepage examples','productionDeployed':False,'build':'reportlab; embedded STHeiti and Arial; no external fonts needed to read PDF','textAndImageBoundsChecked':True}
(HERE/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
(QA/'layout-checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2)+'\n')
print(f'{OUT/NAME}\n{TOTAL} pages; all drawn content bounds and Chinese glyph coverage verified; SHA256 {pdfhash}')
