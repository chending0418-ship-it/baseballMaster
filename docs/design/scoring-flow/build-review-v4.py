"""V4: concrete modifications, 13 recording examples, screenshots of the working demo."""
from pathlib import Path
import json
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.colors import HexColor
from reportlab.platypus import Paragraph
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.utils import ImageReader

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'output/samples/scoring-flow-review/v4'
QA=ROOT/'output/validation/scoring-flow-review/v4'
OUT.mkdir(parents=True,exist_ok=True)
pdf=OUT/'BaseballMaster-局面记录-V4-操作方案与自由练习.pdf'
for name,file in [('CJK','Arial Unicode.ttf'),('Arial','Arial.ttf'),('Bold','Arial Bold.ttf')]:
    pdfmetrics.registerFont(TTFont(name,'/System/Library/Fonts/Supplemental/'+file))
W,H=841.89,595.28
c=canvas.Canvas(str(pdf),pagesize=(W,H),pageCompression=1)
c.setTitle('BaseballMaster 局面记录 V4 - 操作方案与自由练习')
c.setAuthor('BaseballMaster')
INK='#173647';GREEN='#23785B';MUTED='#657A7F';BG='#F3F3EA';PAPER='#FFFEF9';SOFT='#DFECDD';LINE='#D6DED0'
page=0;layout=[];used=[]
def rect(x,y,w,h,fill=PAPER,r=12):
    c.setFillColor(HexColor(fill));c.roundRect(x,H-y-h,w,h,r,fill=1,stroke=0)
def text(t,x,y,size=11,color=INK,font='CJK'):
    c.setFont(font,size);c.setFillColor(HexColor(color));c.drawString(x,H-y-size,t)
def para(t,x,y,w,size=11,color=INK,leading=None):
    p=Paragraph(t,ParagraphStyle('body',fontName='CJK',fontSize=size,leading=leading or size*1.6,textColor=HexColor(color),wordWrap='CJK'))
    _,h=p.wrap(w,1000);p.drawOn(c,x,H-y-h);layout.append({'page':page,'x':x,'y':y,'w':w,'h':h});return h
def header(n,title,sub):
    global page
    page=n;rect(0,0,W,H,BG,0);text('V4',31,25,13,GREEN,'Bold');text(title,77,20,23)
    para(sub,31,63,780,10.5,MUTED,17)
def end():
    c.setStrokeColor(HexColor(LINE));c.line(31,39,W-31,39)
    text('BASEBALLMASTER  /  V4 操作方案  /  截图来自可操作网页 demo',31,566,7.5,MUTED)
    text(f'2026-10-13     {page:02d} / 09',W-150,566,7.5,MUTED)
    c.showPage()
def phone(id,x,y,w=180):
    path=QA/'screens'/f'{id}.png';iw,ih=ImageReader(str(path)).getSize();h=w*ih/iw
    rect(x-3,y-3,w+6,h+6,'#193E33',17);c.saveState();p=c.beginPath();p.roundRect(x,H-y-h,w,h,14);c.clipPath(p,stroke=0,fill=0)
    c.drawImage(str(path),x,H-y-h,width=w,height=h,mask='auto');c.restoreState();used.append(id)
def block(x,y,w,title,body,fill=PAPER):
    h=45+max(36,len(body)//23*13);h=min(h,148)
    rect(x,y,w,h,fill);text(title,x+13,y+11,13,GREEN);para(body,x+13,y+37,w-26,10.3,INK,16)
def steps(x,y,w,items):
    for n,t in enumerate(items,1):
        rect(x,y,20,20,SOFT,10);text(str(n),x+6,y+2,10,GREEN,'Bold')
        h=para(t,x+29,y,w-29,10.5,INK,17);y+=max(35,h+15)
    return y
def case_column(x,id,num,title,setup,items,result,note=''):
    text(f'{num:02d}  {title}',x,107,15,GREEN);para(setup,x,134,373,9.5,MUTED,15)
    phone(id,x,173,158)
    y=steps(x+178,173,195,items)
    rect(x+178,y+6,195,76,SOFT);text('记录结果',x+190,y+15,11,GREEN);para(result,x+190,y+37,171,10,INK,16)
    if note:para(note,x,528,373,8.5,MUTED,13)

header(1,'我会这样修改局面记录','主页面保留现有区域与按钮顺序。常用结果直接可见，球种和落点必填，跑者只改差异。')
phone('02-entry',34,115,174);phone('25-marked-location',239,115,174)
steps(451,115,354,[
 '现有“击球进入场内”打开底部面板，常用结果放在第一屏。',
 '每次击球都记录球种与落点。滚地出局、高飞接杀等带入明确球种；其余手动选择。',
 '在球场点一下记录落点；接杀记接球点。落点与处理球的守备人独立保存。',
 '有跑者时同屏核对打者与原跑者，一次提交；简单空垒局面选完即完成。',
 '全部击球固定可找；撤销恢复整次记录，刚完成的球可以补充或修改。'
])
para('球种指地滚、高飞、平飞、触击等击球类型。<br/>网页还原记分界面供评审；正式 App 布局沿用。',451,487,354,9.5,MUTED,16)
end()

header(2,'三个普通局面，四次点选完成','从主页面开始计数。球种与落点已计入；不包含打开记录查看结果或事后修改。')
for x,id,title,body in [
 (36,'03-single','01  无人上垒，一垒安打','击球 → 一垒安打 → 地滚 → 点落点<br/>记录：地滚球一垒安打，打者到一垒。'),
 (310,'04-ground','02  游击传一垒，打者出局','击球 → 滚地出局 → 点落点 → 6-3<br/>地滚已带入；打者出局，记录传杀路线。'),
 (584,'05-fly','03  中外野手接杀高飞球','击球 → 高飞接杀 → 点接球点 → 中外野<br/>高飞已带入；接球点和接球人分别保存。')
]:
    text(title,x,104,12,GREEN);phone(id,x+28,134,172);para(body,x,520,232,9.4,INK,15)
end()

header(3,'有跑者：带入建议，只改不同的地方','每球先补齐球种和落点，再统一核对跑者。最终提交前，比分、出局和垒况都会显示预览。')
case_column(32,'06-advance',4,'安打，原一垒跑者到三垒','起始：0 出局，一垒有人。',['击球 → 一垒安打。','选地滚，点实际落点。','原一垒跑者已展开，点“三垒”。','点“确认本球”。'],'打者到一垒；原跑者到三垒。','共 6 次点选；打者无需重复选一垒。')
case_column(438,'07-doubleplay',5,'6-4-3 双杀','起始：0 出局，一垒有人。',['击球 → 双杀。','选地滚，点落点。','选“游击 → 二垒 → 一垒”。','核对两名出局者，确认本球。'],'2 出局，垒上无人。','有多名跑者时，必须核对实际出局的是谁。')
end()

header(4,'牺牲打和失误，入口清楚，责任明确','球种与落点都保留；常见路线可以直接选，失误由实际责任人承担。')
case_column(32,'08-sacfly',6,'牺牲高飞，三垒跑者得分','起始：1 出局，三垒有人。',['击球 → 全部击球 → 牺牲高飞。','高飞已带入；点接球点。','选“中外野手”。','核对打者出局、跑者得分，确认。'],'增加 1 分；2 出局，空垒。','本例按跑者合法再触垒后得分记录。')
case_column(438,'09-error',7,'游击手失误，打者上一垒','起始：0 出局，垒上无人。',['击球 → 守备失误。','选地滚，点实际落点。','选“游击手”作为失误人。','核对打者到一垒，确认。'],'打者一垒；保留游击手失误。','发生额外进垒时，再修改终点与对应原因。')
end()

header(5,'08  一垒安打后，打者冲二垒被触杀','起始：0 出局，二垒有人。平飞一垒安打送回原跑者，打者随后被中外野传二垒触杀。')
phone('11-compound',35,116,180);phone('12-compound-log',254,116,180)
steps(471,117,335,[
 '击球 → 一垒安打 → 平飞 → 点落点。',
 '把原二垒跑者的终点改为“得分”。',
 '点打者姓名 → 出局 → 进垒被触杀。',
 '补跑垒处理：中外野手 → 二垒手 → 完成路线。',
 '核对整球，点“确认本球”。'
])
block(471,406,335,'最后保留的是整球过程','一垒安打 + 打者进垒出局 + 原跑者得分。<br/>本球只增加 1 次投球、1 个出局和 1 分。',SOFT)
end()

header(6,'独立跑垒，直接点场上的跑者','盗垒与暴投不作为新的击球。正在录入击球时，跑者调整合并在该球里完成。')
case_column(32,'14-steal',9,'一垒跑者成功盗二垒','起始：一垒有人，本例没有新增投球。',['点球场上的一垒跑者。','点“二垒”。','原因选“盗垒”。','点“确认本球”。'],'跑者二垒；打者、球数保持。','也可从“跑者 / 出局”选择同一跑者。')
case_column(438,'16-wild',10,'坏球后暴投，跑者到二垒','起始：一垒有人，0 坏 0 好。',['先点“坏球”。','点一垒跑者 → 二垒。','原因选“暴投”。','确认本球，挂在刚才的投球之后。'],'1 坏球；总投球 1 次；跑者二垒。','“捕逸”使用相同路径，原因单独保存。')
end()

header(7,'投球例外，从原有“特殊 / 判罚”进入','这两例没有击球进入场内，因此不要求击球球种和落点；打者与跑者的结果仍一起核对。')
case_column(32,'18-hbp',11,'满垒触身球','起始：0 出局，满垒。',['特殊 / 判罚 → 触身球。','系统带入打者到一垒、原跑者依次被迫进垒。','核对四个人，点“确认本球”。'],'增加 1 分，仍为满垒。','本例 3 次点选；不重复选择每个人的垒位。')
case_column(438,'19-dropped',12,'三振未接住，打者上一垒','起始：2 出局、2 好球，空垒。',['特殊 / 判罚 → 三振未接住。','打者默认到一垒；按实际情况修改。','点“确认本球”。'],'打者一垒，仍 2 出局；本球 +1。','第三好球在此一并记录，无须先点一次“挥空”。')
end()

header(8,'复杂局面留必要核对，低频情况有固定入口','平飞接杀、野手选择、内野高飞必死、三杀、待确认等，均保留具名入口。')
case_column(32,'20-timing',13,'第三出局与得分同时出现','起始：1 出局，一、三垒有人。',['记高飞接杀，补接球点与接球人。','原一垒跑者改为触杀出局；原三垒跑者改为得分。','确认时核对第三出局的性质与先后。','本例选“其他触杀，得分在先”，再确认。'],'本例得分有效；三出局后换边。','前提为三垒跑者合法再触垒，且无其他影响得分的判罚。')
phone('21-catalog',439,173,158);text('不常用，也能找到',439,107,15,GREEN);para('击球面板右上角“全部击球”。',439,134,372,9.5,MUTED,15)
steps(617,173,191,['19 个击球结果按类别排列，也可以搜索名称。','条件不满足时，显示原因，保留可见的名称。','其他判罚沿用“特殊 / 判罚”分类；不会挤占常用操作。'])
para('此 demo 对复杂判罚展示入口；完整判罚统计沿用正式 App。',617,428,191,9.5,MUTED,16)
para('<link href="https://content.mlb.com/documents/2/2/4/305750224/2019_Official_Baseball_Rules_FINAL_.pdf" color="#23785B">规则核对：MLB Official Baseball Rules，5.05(a)(2)、5.08(a)。</link>',439,525,372,8,MUTED,12)
end()

header(9,'自由练习，自定局面，连续记分','公开 demo 无需登录。手机竖屏操作为主；本文案例是操作示例，不是 demo 的固定任务。')
phone('24-setup',35,113,183)
steps(255,115,550,[
 '点顶部“设置局面”，自定局数、攻守、比分、B/S/O、当前棒次与垒况。应用后开始一份新练习。',
 '之后自由逐球记录。打者按合成阵容自动轮换；三出局后点“继续练习”进入下一半局。',
 '刚记录错了：用撤销 / 恢复。要补充刚才的球：点“补充这球”，修改球种、落点或跑者后保存。',
 '所有练习仅存在当前浏览器内；重新开始或刷新会清空，不影响正式比赛。'
])
rect(254,413,552,108,SOFT)
text('手机打开即可尝试',270,427,14,GREEN)
url='https://baseballmaster.cc/recording/demoReview'
para(f'<link href="{url}" color="#173647">{url}</link>',270,458,513,13,INK,20)
para('落点可点选坐标，也可用区域按钮定位；均与守备人分开记录。',270,492,514,9.5,MUTED,15)
end()
c.save()
assert all(item['y']+item['h']<555 for item in layout),[i for i in layout if i['y']+i['h']>=555]
(QA/'pdf-layout.json').write_text(json.dumps({'pages':page,'screens':used,'text':layout},ensure_ascii=False,indent=2))
print(json.dumps({'pdf':str(pdf),'pages':page,'screens':len(used)},ensure_ascii=False))
