"""GameChanger video study and V3 scoring proposal. Static artifact only."""
from pathlib import Path
import base64
import json
import runpy
import shutil
import subprocess
from xml.sax.saxutils import escape

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'output/samples/scoring-flow-review/v3'
CHECK=ROOT/'output/validation/3.0/scoring-flow-review/v3'
VIDEO_REVIEW=ROOT/'output/validation/scoring-flow-review/gamechanger-2026-10-13'
NODE=Path('/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node')
BASE=runpy.run_path(str(Path(__file__).with_name('build-review-v2.py')))
for directory in [OUT,OUT/'screens',CHECK,CHECK/'pages']:
    directory.mkdir(parents=True,exist_ok=True)
SCREENS=[]
REFERENCES=[
    ('GameChanger：跑者推进','https://help.gc.com/hc/en-us/articles/30502355100813-Advancing-Baserunners'),
    ('GameChanger：失误记录','https://help.gc.com/hc/en-us/articles/30565065136013-Scoring-Errors'),
    ('Apple：拖放的替代操作、反馈与撤销','https://developer.apple.com/design/human-interface-guidelines/drag-and-drop'),
    ('Apple：反馈与状态提示','https://developer.apple.com/design/human-interface-guidelines/feedback')
]
CSS=BASE['CSS']+'''
html,body{overflow:hidden}.screen-bg{position:absolute;inset:0;width:402px;height:874px;object-fit:fill}.shade{position:absolute;inset:0;background:#102a4310}.sheet{position:absolute;left:0;right:0;bottom:0;background:#F5F2EA;border-radius:25px 25px 0 0;box-shadow:0 -12px 35px #102a4321;padding:10px 18px 25px;display:flex;flex-direction:column;gap:10px}.handle{width:37px;height:4px;border-radius:4px;background:#BAC4B9;margin:0 auto 4px}.sheet-head{display:flex;align-items:center;justify-content:space-between;gap:10px;height:35px}.sheet-head b{font-size:19px}.sheet .choice{min-height:50px;font-size:15px;padding:9px}.sheet .choice small{font-size:11px}.sheet .listrow{min-height:43px;padding:10px 12px;font-size:13px}.sheet .grid{gap:8px}.sheet .contextline{font-size:12px;color:#607284;line-height:1.5}.sheet .helper{font-size:11px}.sheet .outcome{padding:10px 12px;font-size:13px}.sheet .button{min-height:47px;font-size:15px}.sheet .step{font-size:11px;line-height:1.4;color:#607284}.preview-row{min-height:37px;border-bottom:1px solid #E4E9DF;display:flex;justify-content:space-between;align-items:center;font-size:13px;padding:8px 0;gap:5px}.preview-row:last-child{border:0}.preview-row strong{font-size:12px;color:#217A57;font-weight:600}.positions{display:flex;gap:6px}.position{flex:1;min-height:40px;background:#FFFEFA;border:1px solid #D3DDD1;border-radius:9px;font-size:12px;display:flex;align-items:center;justify-content:center;text-align:center}.position.selected{background:#217A57;border-color:#217A57;color:white}.draft-badge{position:absolute;left:20px;top:187px;background:#FFFEFAF2;border:1px solid #DADFD9;padding:7px 10px;border-radius:12px;font-size:11px;line-height:1.4;color:#607284}.mainbar{position:absolute;bottom:7px;left:136px;width:130px;height:5px;background:#142b3c;border-radius:6px}.sheet .catalogue-link{color:#217A57;font-size:13px;min-height:35px;display:flex;align-items:center}.route-arrow{position:absolute;left:0;top:0;width:402px;height:874px;pointer-events:none}
'''
def choice(t,s=''):return BASE['choice'](t,s)
def grid(*items):return BASE['grid'](*items)
def row(t,s='›'):return BASE['row'](t,s)

def mock(id,title,body,height,badge='本球尚未提交'):
    reference=ROOT/'docs/design/3.0/reference-scoring-2.3.png'
    uri='data:image/png;base64,'+base64.b64encode(reference.read_bytes()).decode()
    html=f'<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>{title} · V3 静态示意</title><style>{CSS}</style><img class="screen-bg" src="{uri}" alt="现有主页面基准原图"><div class="shade"></div><div class="draft-badge">V3 界面示意 · {badge}</div><main class="sheet" style="height:{height}px"><div class="handle"></div>{body}</main><div class="mainbar"></div></html>'
    html=html.replace('top:187px','top:223px')
    (OUT/'screens'/f'{id}.html').write_text(html)
    SCREENS.append({'id':id,'title':title,'width':402,'height':874})

def author_screens():
    for second,title in [(79,'击球类型'),(82,'球场中的守备处理'),(132,'跑者推进原因'),(152,'投球后的相关原因'),(169,'按打席组织记录'),(175,'更正范围说明')]:
        id=f'gc-{second}'
        source=VIDEO_REVIEW/'frames'/f'frame-{second:03d}.jpg'
        from PIL import Image
        Image.open(source).save(OUT/'screens'/f'{id}.png')
        SCREENS.append({'id':id,'title':title,'source':str(source),'time':second})
    mock('01-entry','原入口打开常用面板','<div class="sheet-head"><b>记录击球</b><span class="catalogue-link">全部击球 ›</span></div><div class="contextline">1 局上 · 0 出局 · 满垒　｜　打者 #5 许嘉树</div>'+grid(choice('一垒安打'),choice('二垒安打'),choice('高飞接杀'),choice('滚地出局'),choice('守备失误'),choice('双杀'))+'<div class="list">'+row('按击球过程记录','球型 → 处理 ›')+row('没看清，先记局面','待确认 ›')+'</div><div class="helper">有跑者时同屏核对；空垒且结果明确时直接完成。</div>',435)
    mock('02-route','球场和常见路线共同辅助','<div class="sheet-head"><b>滚地出局 · 谁处理？</b><span class="link">返回</span></div><div class="step">已选结果保留；选择后核对原有跑者。</div>'+grid(choice('游击 → 一垒','6-3'),choice('二垒 → 一垒','4-3'),choice('三垒 → 一垒','5-3'),choice('投手 → 一垒','1-3'))+'<div class="list">'+row('其他路线','点球场上的守备人 ›')+'</div><div class="helper">常见路线直接点选；记录落点与拖动均为选填。</div>',335)
    mock('03-runners','整球预览与跑者调整','<div class="sheet-head"><b>核对本球</b><span class="link">改选结果</span></div><div class="outcome">一垒安打 · 打者 #5 到一垒 <span class="link">修改</span></div><div class="card" style="padding:5px 12px"><div class="preview-row"><span>原一垒 #18 林子轩</span><strong>到二垒 · 改</strong></div><div class="preview-row"><span>原二垒 #7 周子墨</span><strong>到三垒 · 改</strong></div><div class="preview-row"><span>原三垒 #23 陈昊</span><strong>得分 · 改</strong></div></div><div class="contextline">原一垒 #18 · 按现场修改建议去向</div><div class="positions"><div class="position">留垒</div><div class="position selected">二垒</div><div class="position">三垒</div><div class="position">得分</div><div class="position">出局</div></div><div class="outcome">记录后：1 : 0 · 满垒 · 0 出局</div><div class="button">确认本球</div>',447)
    (OUT/'screens.json').write_text(json.dumps(SCREENS,ensure_ascii=False,indent=2))
    findings={
      'input_video':'/Users/JasonChan/Downloads/ScreenRecording_10-13-2026 13-15-09_1.MP4',
      'duration_seconds':203.7183446712018,
      'method':'One frame per second, transition-sensitive contact review, selected full-size frames. Interface-state observation; not touch telemetry or an efficiency experiment.',
      'reviewed_scope':'Setup 00:00–01:10; scoring and baserunning 01:10–02:41; records and editing 02:44–03:21. Photos-library exit excluded.',
      'observations':[
        {'time':'01:19–01:23','observed':'Ball In Play → Hard Ground Ball → result menu → drag fielder; first single completes.','inference':'Keep the field visible, but make detailed ball/location input optional for quick known results.'},
        {'time':'01:27–01:36','observed':'Bunt branch, safe-sacrifice submenu exploration, fielding sequence. Plays later lists a sacrifice bunt.','inference':'Special outcomes are context related; a complete catalogue is still needed.'},
        {'time':'02:11–02:16','observed':'SAFE destination gives advancement-reason choices including On Last Play and On Throw.','inference':'An extra advance must stay distinct from the batting result.'},
        {'time':'02:32–02:34','observed':'After a recorded pitch, SAFE reason choices include Wild Pitch and Passed Ball.','inference':'Ask cause in relevant context, link to the correct pitch without counting another pitch.'},
        {'time':'02:49–02:50','observed':'Single card contains batter later out advancing to second, another runner scoring and another advancing.','inference':'Represent a plate appearance with its constituent actions, not only one terminal outcome.'},
        {'time':'02:54–02:56','observed':'Batter edit summary explicitly says for this play only.','inference':'Show the scope of a history edit and protect lineup and later records.'}
      ],
      'external_primary_sources':REFERENCES
    }
    (CHECK/'video-findings.json').write_text(json.dumps(findings,ensure_ascii=False,indent=2))

def author_pdf():
    from reportlab.pdfgen import canvas
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.lib.colors import HexColor
    from reportlab.platypus import Paragraph
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.lib.utils import ImageReader
    pdfmetrics.registerFont(TTFont('CJK','/System/Library/Fonts/Supplemental/Arial Unicode.ttf'))
    pdfmetrics.registerFont(TTFont('Arial','/System/Library/Fonts/Supplemental/Arial.ttf'))
    pdfmetrics.registerFont(TTFont('ArialBold','/System/Library/Fonts/Supplemental/Arial Bold.ttf'))
    W,H=841.89,595.28;page=1;layout=[]
    pdf=OUT/'BaseballMaster-局面记录优化-GameChanger研究与方案-V3.pdf'
    c=canvas.Canvas(str(pdf),pagesize=(W,H),pageCompression=1)
    c.setTitle('BaseballMaster 局面记录优化 - GameChanger 视频研究与 V3 方案')
    c.setAuthor('BaseballMaster')
    bg='#F5F2EA';paper='#FFFEFA';ink='#102A43';muted='#607284';green='#217A57';soft='#E4F2EA';line='#DADFD9'
    def rect(x,y,w,h,fill=paper,r=12):
        c.setFillColor(HexColor(fill));c.setStrokeColor(HexColor(fill));c.roundRect(x,H-y-h,w,h,r,fill=1,stroke=0)
    def text(t,x,y,size=11,color=ink,font='CJK'):
        c.setFont(font,size);c.setFillColor(HexColor(color));c.drawString(x,H-y-size,t)
    def para(t,x,y,w,size=11,color=ink,leading=None):
        p=Paragraph(t,ParagraphStyle('body',fontName='CJK',fontSize=size,leading=leading or size*1.6,textColor=HexColor(color),wordWrap='CJK'))
        _,h=p.wrap(w,1000);p.drawOn(c,x,H-y-h)
        layout.append({'page':page,'x':x,'y':y,'w':w,'h':h});return h
    def header(n,title,sub):
        nonlocal page
        page=n;rect(0,0,W,H,bg,0);text('V3',30,24,13,green,'ArialBold');text(title,77,18,23)
        para(sub,31,60,W-62,11,muted,17)
    def end():
        c.setStrokeColor(HexColor(line));c.line(31,39,W-31,39)
        text('BASEBALLMASTER  /  视频研究与局面记录 V3  /  研究提案，尚未实现',31,565,7.5,muted)
        text(f'2026-10-13    {page:02d} / 06',W-150,565,7.5,muted);c.showPage()
    def phone(id,x,y,w):
        path=OUT/'screens'/f'{id}.png';iw,ih=ImageReader(str(path)).getSize();h=w*ih/iw
        rect(x-3,y-3,w+6,h+6,'#153C36',18)
        c.saveState();p=c.beginPath();p.roundRect(x,H-y-h,w,h,15);c.clipPath(p,stroke=0,fill=0)
        c.drawImage(str(path),x,H-y-h,width=w,height=h,mask='auto');c.restoreState()
    def box(x,y,w,h,title,body,fill=paper):
        rect(x,y,w,h,fill);text(title,x+15,y+12,14,green if fill==soft else ink)
        para(body,x+15,y+39,w-30,11,ink,18)
    def steps(x,y,w,items):
        for n,(title,body) in enumerate(items,1):
            rect(x,y,24,24,soft,12);text(str(n),x+8,y+3,11,green,'ArialBold');text(title,x+36,y,14)
            h=para(body,x+36,y+25,w-36,11,muted,18);y+=max(65,h+42)
        return y

    header(1,'借鉴它的连续感，让每一步有上下文','视频约 3 分 24 秒。以下为原视频截帧；观察操作过程，不把这一次演示当作用户效率实验。')
    for id,x,cap in [('gc-79',34,'01:19 · 按球型展开结果'),('gc-82',201,'01:22 · 在球场中处理'),('gc-132',368,'02:12 · 进垒后再选原因')]:
        phone(id,x,127,151);text(cap,x,468,8.5,muted)
    steps(557,119,251,[('当前局面始终在场','菜单叠在球场上，比分、投打和人员仍可见，减少重新找上下文。'),('每次只问相关问题','击球后选择结果；移动跑者后再问为什么推进或出局。'),('实体就是入口','守备员与跑者都能直接操作，场上观察与录入动作更接近。')])
    rect(31,508,778,34,soft)
    para('借鉴：场景连续、相关问题逐步出现、直接操作球员。球型细分、落点与拖动是否必填，应按我们的用户需要取舍。',44,516,751,9.5,ink,15)
    end()

    header(2,'记录一整段过程，不能只留最后的结果','这段视频对“记录方式”的启发，比对按钮样式更重要：打者结果、跑者变化和责任需要分别表达。')
    for id,x,cap in [('gc-152',34,'02:32 · 投球后的推进原因'),('gc-169',201,'02:49 · 打席内的多个事件'),('gc-175',368,'02:55 · 指出更正作用范围')]:
        phone(id,x,126,151);text(cap,x,468,8.5,muted)
    box(557,116,252,121,'按发生原因归属','投球后的跑垒菜单出现暴投 / 捕逸；击球后的推进可归入上一比赛过程。<br/>不能仅靠当前打者或经过几秒来关联。',soft)
    box(557,254,252,121,'打击结果与终点分开','视频的 Single 记录同时写入打者后来跑垒出局、另一人得分、另一人到三垒。<br/>不能因为最后出局就抹掉安打。')
    box(557,392,252,115,'更正要告诉用户改什么','更正打者后，摘要明确“仅本次比赛过程”。<br/>我们的更正应显示影响范围，并检查后续局面。')
    para('官方说明补充：推进原因与此前投球有关；多名守备员参与的失误，需要单独确认实际责任人。资料链接见第 6 页。',32,521,776,9.5,muted,15)
    end()

    header(3,'保留主页面，用轻量面板完成记录','背景沿用现有主页面基准原图；底部面板为我们的静态示意。普通逐球按钮与主页面各区域位置保留。')
    phone('01-entry',38,109,190);phone('02-route',264,109,190)
    text('原击球入口 → 常用局面',59,528,9,muted);text('选路线时，上方球场仍可见',273,528,9,muted)
    steps(490,113,316,[('常用先给结果','安打、接杀、滚地出局、失误、双杀直接可见；“全部击球”在固定位置。'),('过程输入有同一条出口','按球型和处理展开也进入同一份本球草稿。已确定的内容不用重选。'),('球场帮助操作，不增加负担','常见路线点一次；其他路线可依次点守备员。落点与拖动作为选填。')])
    box(490,398,316,118,'简单与复杂的完成方式','空垒且结果明确：最后一次选择后直接记录，可撤销。<br/>有跑者或复杂处理：同屏预览后一次确认。<br/>未提交状态始终清楚。',soft)
    end()

    header(4,'跑者直接可改，原因只在需要时出现','主页面点跑者，可开始独立跑垒；正在记录本球时，则修改这份草稿。全部操作都能通过点选完成。')
    phone('03-runners',39,109,190);text('三名跑者同屏查看，选中一人后改目的地',34,528,8.6,muted)
    steps(271,112,537,[('先明确是哪一名跑者','点球场跑者或面板中的姓名行。目的地用“留垒 / 垒位 / 得分 / 出局”点选；拖动仅作可选加速。'),('已有原因自动带入','属于本次安打的普通推进不再问一次；额外进垒、跑垒出局或责任不明时，才展开相关原因。'),('保留原打击结果，再补变化','一垒安打后到二垒，仍保留一垒安打；之后被触杀，也记录这个出局与责任，不改成单纯打者出局。')])
    box(271,389,537,126,'补充上一球，要有明确归属','尚未录下一球、且关联明确：补回刚才这球的过程，避免重复投球、重复得分或错计打点。<br/>已经继续记分、归属不清或会影响后续局面：进入历史更正。<br/>看不清时允许保留待确认，不自动猜责任。',soft)
    end()

    header(5,'常用顺手，复杂情况仍有清楚的入口','树形逻辑用于缩小选项；完整分类用于找到例外。两者汇入同一套记录和校验。')
    rows=[
      ('全部击球','平飞 / 界外接杀、内野高飞必死、牺牲打、三杀、野手选择、待确认等具名可找。条件不满足时说明原因。'),
      ('独立跑垒与判罚','盗垒、牵制、夹杀过程、暴投 / 捕逸、申诉与妨碍等，按跑者事件和裁判判罚分类。触身、故意保送、三振未接住保留原入口。'),
      ('一球包含多个结果','安打后失误进垒、安打后跑垒出局、双杀中其他人得分：分开表达打击结果、处理责任、每名跑者终点与必要的先后。'),
      ('真正需要核对的地方','第三出局与得分、多人出局是谁、实际失误人、同垒冲突和超过三个出局，保留必要核对；不为少点一次而猜结果。'),
      ('改选、撤销与恢复','改选保留仍有效的信息，失效部分明确重选；撤销恢复整球及统计。未提交的草稿与已保存记录、比赛恢复和共享交接区分清楚。')
    ]
    y=111
    for n,(title,body) in enumerate(rows,1):
        rect(31,y,778,72,soft if n%2 else paper);text(f'{n:02d}',47,y+15,12,green,'ArialBold');text(title,85,y+13,13)
        para(body,277,y+12,513,11,ink,18);y+=85
    end()

    header(6,'下一版的取舍与验证标准','V2 的常用入口与同屏核对继续保留；V3 补足操作连续性、跑者直接操作和复合记录。尚未修改 App。')
    box(31,109,378,143,'优先落地','轻量面板与稳定的常用 / 全部入口。<br/>简单局面直接完成，有跑者统一核对。<br/>点选球员与守备路线；完整点选替代拖动。<br/>整球摘要、修改范围、撤销与待确认。',soft)
    box(426,109,383,143,'作为可选增强','精细落点、强弱球型、拖动和连续手势。<br/>这些输入可服务更详细的球路与防守记录，但不成为每球必填。<br/>三杀、夹杀、复合失误等复杂过程，需要验收记录表达后再开放相应快速路径。')
    rect(31,270,778,119,paper)
    text('判断好不好用，要看完整任务',47,285,14)
    para('点击数只是一个指标。还要比较连续记分的完成时间、回退次数、误操作、更正成本和记录准确性。不能从单次视频演示得出更快的结论。',48,316,744,11,ink,18)
    para('保留目标：空垒已知安打 2 次；普通接杀 / 滚地出局 3 次；有跑者只改差异后统一确认。全部路径能用点选完成，复杂情况允许必要的额外核对。',48,355,744,10,muted,16)
    text('研究依据',31,411,13,green)
    para('原始视频：ScreenRecording_10-13-2026 13-15-09_1.MP4。观察覆盖准备、记分、跑垒及更正；截图时间按视频起点计算。相册退出画面不纳入研究。',31,435,778,9.5,muted,15)
    x=31;y=474
    for i,(label,url) in enumerate(REFERENCES):
        xx=x+(i%2)*399;yy=y+(i//2)*26
        para(f'<link href="{escape(url)}" color="#217A57">{escape(label)} ↗</link>',xx,yy,378,10,green,16)
    end();c.save()
    (CHECK/'pdf-layout.json').write_text(json.dumps(layout,ensure_ascii=False,indent=2))
    assert all(i['y']+i['h']<554 for i in layout),'PDF text overlaps footer'
    print(json.dumps({'pdf':str(pdf),'pages':6,'screens':len(SCREENS)},ensure_ascii=False))

if __name__=='__main__':
    author_screens()
    subprocess.run([str(NODE),str(Path(__file__).with_name('render-review.cjs')),'v3'],check=True)
    author_pdf()
