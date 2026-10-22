"""Static scoring-flow proposal. Does not modify the App or real match data."""
from pathlib import Path
import json
import shutil
import subprocess
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'output/samples/scoring-flow-review'
CHECK = ROOT / 'output/validation/3.0/scoring-flow-review'
RUNTIME = Path('/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies')
NODE = RUNTIME / 'node/bin/node'
for directory in [OUT, OUT/'screens', CHECK, CHECK/'pages']:
    directory.mkdir(parents=True, exist_ok=True)

CSS = '''
:root{--bg:#F5F2EA;--paper:#FFFEFA;--ink:#102A43;--muted:#607284;--green:#217A57;--soft:#E4F2EA;--line:#DADFD9;--orange:#A35513;--orangebg:#FFF0DE}
*{box-sizing:border-box}html,body{margin:0;width:402px;height:874px;background:var(--bg);color:var(--ink);font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif;-webkit-font-smoothing:antialiased}
.status{height:50px;padding:18px 25px 0;display:flex;justify-content:space-between;font-size:15px;font-weight:650}.island{position:absolute;left:146px;top:9px;width:110px;height:28px;background:#152a2a;border-radius:20px}.nav{height:54px;display:flex;align-items:center;justify-content:space-between;padding:0 19px}.nav strong{font-size:18px}.link{font-size:14px;color:var(--green)}
.content{height:662px;padding:8px 18px;display:flex;flex-direction:column;gap:13px}.proposal{color:var(--orange);font-size:11px;line-height:1.5;letter-spacing:.4px}.context{background:var(--paper);border:1px solid var(--line);padding:13px 14px;border-radius:16px;font-size:13px;line-height:1.7}.context b{font-size:16px}.title{font-size:23px;font-weight:750;line-height:1.35}.sub{font-size:13px;line-height:1.7;color:var(--muted)}.section{font-size:15px;font-weight:650;line-height:1.5}.section span{font-size:12px;font-weight:400;color:var(--muted);float:right}.grid{display:grid;grid-template-columns:repeat(2,1fr);gap:9px}.grid.four{grid-template-columns:repeat(4,1fr);gap:6px}.choice{min-height:64px;padding:12px;border:1px solid var(--line);border-radius:14px;background:var(--paper);font-size:17px;font-weight:650;display:flex;justify-content:center;align-items:center;text-align:center;line-height:1.4}.choice small{font-size:12px;color:var(--muted);font-weight:400;display:block;margin-top:3px}.choice.selected{background:var(--soft);border:1.5px solid #79AA92;color:var(--green)}.choice.small{min-height:47px;font-size:13px;padding:9px 5px}.card{padding:14px;border:1px solid var(--line);border-radius:16px;background:var(--paper)}.card.green{background:var(--soft);border-color:#BED8C8}.card.orange{background:var(--orangebg);border-color:#E8CFB0}.row{display:flex;justify-content:space-between;align-items:center;gap:8px}.row b{font-size:16px}.mini{font-size:12px;color:var(--muted);line-height:1.6}.chips{display:flex;gap:6px;flex-wrap:wrap}.chip{padding:6px 9px;border-radius:9px;background:#E9EEE7;color:var(--muted);font-size:12px;line-height:1.4}.chip.active{background:var(--soft);color:var(--green);font-weight:600}.runner{border:1px solid var(--line);border-radius:15px;background:var(--paper);padding:12px}.runner .destinations{display:flex;gap:5px;margin-top:9px}.dest{flex:1;background:#EEF0EA;border-radius:9px;padding:9px 2px;font-size:12px;text-align:center;white-space:nowrap}.dest.active{background:var(--green);color:white;font-weight:650}.dest.out.active{background:#AE4C38}.footer{position:absolute;left:18px;right:18px;bottom:28px;display:flex;flex-direction:column;gap:8px}.button{min-height:51px;border-radius:14px;background:var(--green);color:white;font-size:16px;font-weight:650;display:flex;align-items:center;justify-content:center;padding:10px;text-align:center}.button.secondary{background:var(--paper);border:1px solid #B7CEC0;color:var(--green)}.button.muted{background:#E2E6DD;color:#869187}.homebar{position:absolute;bottom:8px;left:136px;width:130px;height:5px;background:#142b3c;border-radius:6px}.note{font-size:12px;color:var(--muted);line-height:1.65}.field{height:184px;position:relative;background:#2D7458;border-radius:16px;overflow:hidden}.diamond{position:absolute;width:99px;height:99px;border:2px solid #F1E6CF;background:#DAB785;left:132px;top:62px;transform:rotate(45deg)}.diamond:after{content:'';position:absolute;inset:14px;background:#2D7458;border:1px solid white}.fielder{position:absolute;min-width:47px;padding:7px 4px;border-radius:20px;background:#F4F6ED;color:var(--ink);font-size:12px;text-align:center;border:2px solid transparent}.fielder.selected{background:#F6BE49;border-color:white;font-weight:700}.fieldnote{position:absolute;bottom:8px;left:10px;color:#E6F0E5;font-size:10px}.arrowtext{font-size:18px;color:var(--green);font-weight:700;text-align:center;padding:11px 0}.action-area{padding:18px;display:flex;flex-direction:column;gap:10px}.pitch{background:#217A57;color:white;min-height:48px;border-radius:12px;font-size:14px;display:flex;align-items:center;justify-content:center}.pitch.orange{background:#A35513}.pitch.navy{background:#102A43}.tool{background:var(--paper);border:1px solid var(--line);color:var(--green);min-height:48px;border-radius:12px;text-align:center;display:flex;align-items:center;justify-content:center;font-size:14px}.action-area .grid.four .tool{font-size:12px;font-weight:600}
'''

SCREENS=[]
def context(runners='无人上垒', outs=0):
    return f'<div class="context"><div class="row"><b>海浪 2 : 1 飞鹰</b><span>3 局上</span></div><div>{outs} 出局 · {runners}　｜　打者 #5 许嘉树</div></div>'
def card(body,cls=''):
    return f'<div class="card {cls}">{body}</div>'
def choice(title,sub='',selected=False,small=False):
    return f'<div class="choice {"selected" if selected else ""} {"small" if small else ""}"><div>{title}{"<small>"+sub+"</small>" if sub else ""}</div></div>'
def grid(*items):
    return '<div class="grid">'+''.join(items)+'</div>'
def chips(*items):
    return '<div class="chips">'+''.join(f'<span class="chip active">{i}</span>' for i in items)+'</div>'
def runner(name,origin,destination,active=None,out=False):
    labels=['留垒','一垒','二垒','三垒','得分','出局'] if origin!='打者' else ['一垒','二垒','三垒','得分','出局']
    for base in ['一垒','二垒','三垒']:
        if origin.startswith('原'+base):
            labels.remove(base)
    return f'<div class="runner"><div class="row"><div><div class="mini">{origin}</div><b>{name}</b></div><span class="link">{destination}</span></div><div class="destinations">'+''.join(f'<div class="dest {"active" if label==(active or destination) else ""} {"out" if label=="出局" else ""}">{label}</div>' for label in labels)+'</div></div>'
def field(selected=''):
    positions=[('左外野',54,17),('中外野',153,10),('右外野',264,17),('三垒手',47,98),('游击手',105,67),('二垒手',219,67),('一垒手',278,98),('投手',157,105),('捕手',157,147)]
    return '<div class="field"><div class="diamond"></div>'+''.join(f'<div class="fielder {"selected" if name==selected else ""}" style="left:{x}px;top:{y}px">{name}</div>' for name,x,y in positions)+'<div class="fieldnote">点选主要处理人</div></div>'
def screen(id,title,body,runners='无人上垒',outs=0,save='保存这一球',footer_note='合成示例 · 流程提案，尚未保存'):
    html=f'<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>{title} · 流程提案</title><style>{CSS}</style><div class="status"><span>9:41</span><div class="island"></div><span>▮▮▮　⌁　▰</span></div><div class="nav"><span class="link">取消</span><strong>记录本球</strong><span class="link">改选</span></div><main class="content"><div class="proposal">局面记录优化 · 界面示意，尚未实现</div>{context(runners,outs)}{body}</main><div class="footer"><div class="button {"muted" if save.startswith("请") else ""}">{save}</div><div class="note" style="text-align:center">{footer_note}</div></div><div class="homebar"></div></html>'
    (OUT/'screens'/f'{id}.html').write_text(html)
    SCREENS.append({'id':id,'title':title,'width':402,'height':874})

def author_screens():
    source=ROOT/'docs/design/3.0/reference-scoring-2.3.png'
    shutil.copyfile(source,OUT/'screens/00-existing.png')
    SCREENS.append({'id':'00-existing','title':'现有 2.3 App 基准原图','source':str(source)})
    action='<div class="action-area"><div class="proposal">记分操作区局部提案 · 尚未实现</div><div class="grid four">'+''.join(f'<div class="pitch {cl}">{title}</div>' for title,cl in [('坏球',''),('看振','orange'),('挥空','orange'),('界外','navy')])+'</div><div class="button">击球进入场内</div><div class="section">常用局面 <span>直接开始记录本球</span></div><div class="grid four">'+''.join(f'<div class="tool">{title}</div>' for title in ['一垒安打','接杀','滚地出局','双杀'])+'</div><div class="grid"><div class="tool">跑者 / 出局</div><div class="tool">特殊 / 判罚</div></div><div class="note">快捷入口固定位置；无适用条件时禁用并说明原因。</div></div>'
    (OUT/'screens/01-actions.html').write_text(f'<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>{CSS}html,body{{height:352px}}</style>{action}</html>')
    SCREENS.append({'id':'01-actions','title':'常用入口局部示意','width':402,'height':352})
    screen('02-entry','击球入口','<div class="title">球打出来以后，发生了什么？</div><div class="section">按过程记录</div>'+grid(choice('地滚球'),choice('高飞球'),choice('平飞球'),choice('触击'))+'<div class="section">已知道结果，直接选</div>'+grid(choice('一垒安打'),choice('二垒安打'),choice('三垒安打'),choice('本垒打'))+card('<div class="row"><b>没看清 / 判断不确定</b><span class="link">先记局面 ›</span></div><div class="mini">保存确定的垒况与出局；正式结果标为待确认。</div>','orange'),runners='一垒 #18 林子轩',save='请选择球型或已知结果')
    screen('03-fly','接杀选择',chips('高飞球')+'<div class="title">谁接住了球？</div><div class="sub">点“中外野接杀”，同时确定守备人和打者出局。</div>'+grid(choice('左外野接杀'),choice('中外野接杀','已选',True),choice('右外野接杀'),choice('内野接杀'))+grid(choice('球落地了','继续确认上垒'),choice('漏接 / 不确定','保留待确认结果'))+card('<div class="row"><b>打者</b><span class="link">出局</span></div><div class="mini">垒上无人，无需打开跑者确认页。</div>','green'))
    screen('04-fly-ready','接杀保存预览',chips('高飞球','中外野接杀')+'<div class="title">这一球已经填好了</div>'+card('<div class="section">建议结果：中外野高飞接杀</div><div class="sub">打者 #5 许嘉树出局 · 中外野手 #8 接杀</div>','green')+card('<div class="row"><b>打者</b><span class="link">出局</span></div><div class="mini">已由“中外野接杀”确定，可点改选。</div>')+card('<div class="row"><b>原有跑者</b><span class="mini">无人上垒</span></div><div class="mini">该步骤自动跳过。</div>')+card('<div class="section">保存后</div><div class="sub">出局数 0 → 1 · 垒况为空 · 进入下一位打者</div>')+'<div class="note">这是保存前预览；只有底部保存会写入正式记录。</div>')
    screen('05-ground','滚地球处理',chips('地滚球','游击手')+'<div class="title">游击手怎么处理？</div>'+field('游击手')+grid(choice('传一垒，打者出局','6-3',True),choice('传二垒','继续选封杀 / 双杀'),choice('打者安全上垒','确认安打或其他原因'),choice('漏接 / 传球失误','确认责任与垒况'))+card('<div class="row"><b>打者出局 · 垒上无人</b><span class="link">已带入</span></div>','green'))
    screen('06-ground-ready','滚地出局预览',chips('地滚球','游击 → 一垒')+'<div class="title">不用再次选择打者出局</div>'+card('<div class="section">建议结果：游击传一垒出局</div><div class="sub">游击手 #6 助杀 · 一垒手 #3 刺杀 · 6-3</div>','green')+runner('#5 许嘉树','打者','出局')+card('<div class="row"><b>原有跑者</b><span class="mini">无人上垒</span></div><div class="mini">跑者区域收起；保存后增加 1 个出局。</div>')+'<div class="note">若实际有失误或打者安全，点顶部“改选”回到处理选项。</div>')
    screen('07-single','安打与跑者',chips('一垒安打')+'<div class="title">这一球结束后，大家在哪？</div><div class="sub">打者结果已带入；只改与现场不同的跑者。</div>'+runner('#5 许嘉树','打者','一垒')+runner('#18 林子轩','原一垒跑者','三垒')+card('<div class="section">原一垒跑者已改为到三垒</div><div class="sub">以实际跑垒结果为准。</div>','green')+card('<div class="row"><b>补充球型 / 落点</b><span class="link">选填 ›</span></div>'),runners='一垒 #18 林子轩')
    screen('08-double','双杀路线',chips('双杀')+'<div class="title">选择这次双杀的处理路线</div>'+grid(choice('游击 → 二垒 → 一垒','6-4-3',True),choice('二垒 → 游击 → 一垒','4-6-3'),choice('三垒 → 二垒 → 一垒','5-4-3'),choice('其他路线','逐个选择处理人'))+card('<div class="section">本例：一垒跑者封杀，打者出局</div><div class="sub">选择路线后自动展开两名出局者核对。</div>','green')+card('<div class="row"><b>只出了一个人</b><span class="link">改选结果 ›</span></div>'),runners='一垒 #18 林子轩')
    screen('09-double-ready','双杀核对',chips('双杀','6-4-3')+'<div class="title">路线和出局者一起核对</div>'+runner('#18 林子轩','原一垒跑者','出局')+runner('#5 许嘉树','打者','出局')+card('<div class="section">本球 2 人出局 · 本半局 0 → 2 出局</div><div class="sub">游击手与二垒手助杀；二垒手与一垒手刺杀。</div>','green')+card('<div class="row"><b>还有其他跑者变化？</b><span class="link">补充 ›</span></div>')+'<div class="note">第三出局与得分先后不确定时，需要额外核对，不能直接略过。</div>',runners='一垒 #18 林子轩')
    screen('10-pitch-runner','同球附带跑垒',chips('坏球','暴投 / 捕逸')+'<div class="title">这一球还发生了跑垒变化</div><div class="sub">先记录观察到的推进；责任不清时可以待确认。</div>'+runner('#18 林子轩','原一垒跑者','二垒')+grid(choice('暴投'),choice('捕逸'),choice('责任待确认','已选',True),choice('只是盗垒','改选原因'))+card('<div class="section">同一球，一起保存</div><div class="sub">坏球 + 跑者到二垒；投球次数只增加一次。</div>','green'),runners='一垒 #18 林子轩')
    screen('11-unclear','先记确定的局面',chips('结果待确认')+'<div class="title">先把确定的结果记下来</div><div class="sub">不确定安打、失误或野手选择时，保留待确认标记。</div>'+runner('#5 许嘉树','打者','一垒')+runner('#18 林子轩','原一垒跑者','出局')+card('<div class="section">已知：打者上一垒，原跑者出局</div><div class="sub">尚未确认：上垒原因、守备责任与处理路线。</div>','orange')+'<div class="note">保存后比赛可继续；历史记录中保留待确认入口，复核后更新正式统计。</div>',runners='一垒 #18 林子轩',save='保存局面，标记待确认')
    (OUT/'screens.json').write_text(json.dumps(SCREENS,ensure_ascii=False,indent=2))

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
    W,H=841.89,595.28
    dest=OUT/'BaseballMaster-局面记录优化-流程说明-v1.pdf'
    c=canvas.Canvas(str(dest),pagesize=(W,H),pageCompression=1)
    c.setTitle('BaseballMaster 局面记录优化 - 流程说明与界面示意 v1')
    c.setAuthor('BaseballMaster')
    bg='#F5F2EA';paper='#FFFEFA';ink='#102A43';muted='#607284';green='#217A57';soft='#E4F2EA';line='#DADFD9';dark='#092A2A'
    layout=[]
    def rect(x,y,w,h,fill=paper,r=12,stroke=None):
        c.setFillColor(HexColor(fill));c.setStrokeColor(HexColor(stroke or fill));c.roundRect(x,H-y-h,w,h,r,fill=1,stroke=bool(stroke))
    def text(t,x,y,size=11,color=ink,font='CJK'):
        c.setFont(font,size);c.setFillColor(HexColor(color));c.drawString(x,H-y-size,t)
    def para(t,x,y,w,size=11,color=ink,leading=None):
        p=Paragraph(t,ParagraphStyle('body',fontName='CJK',fontSize=size,leading=leading or size*1.6,textColor=HexColor(color),wordWrap='CJK'))
        _,h=p.wrap(w,1000)
        p.drawOn(c,x,H-y-h)
        layout.append({'page':page,'x':x,'y':y,'w':w,'h':h,'bottom':y+h})
        return h
    def header(n,title,sub):
        nonlocal page
        page=n
        rect(0,0,W,H,bg,0)
        text(f'{n:02d}',29,23,14,green,'ArialBold')
        text(title,68,18,23)
        para(sub,30,58,W-60,10.5,muted,16)
    def footer():
        c.setStrokeColor(HexColor(line));c.line(30,39,W-30,39)
        text('BASEBALLMASTER  /  局面记录优化 v1  /  流程提案，尚未实现',30,566,7.5,muted)
        text(f'2026-10-12   {page:02d} / 10',W-143,566,7.5,muted)
    def finish():
        footer();c.showPage()
    def image(id,x,y,w,maxh=None,frame=True):
        path=OUT/'screens'/f'{id}.png'
        iw,ih=ImageReader(str(path)).getSize();h=w*ih/iw
        if maxh and h>maxh: h=maxh;w=h*iw/ih
        if frame: rect(x-3,y-3,w+6,h+6,'#153C36',18)
        c.saveState();p=c.beginPath();p.roundRect(x,H-y-h,w,h,15 if frame else 0);c.clipPath(p,stroke=0,fill=0)
        c.drawImage(str(path),x,H-y-h,width=w,height=h,mask='auto');c.restoreState()
        return w,h
    def box(x,y,w,h,title,body,fill=paper):
        rect(x,y,w,h,fill)
        text(title,x+15,y+12,14,green if fill==soft else ink)
        para(body,x+15,y+38,w-30,11,ink,17)
    def steps(x,y,w,items):
        for n,(title,body) in enumerate(items,1):
            rect(x,y,25,25,soft,12);text(str(n),x+8,y+4,11,green,'ArialBold')
            text(title,x+37,y,14)
            h=para(body,x+37,y+24,w-37,11,muted,17)
            y+=max(62,h+37)
        return y
    def flowbox(x,y,w,h,title,body,fill=paper):
        rect(x,y,w,h,fill,12,line)
        text(title,x+13,y+10,13,green if fill==soft else ink)
        para(body.replace('\n','<br/>'),x+13,y+33,w-26,10,muted,15)
    def arrow(x1,y1,x2,y2):
        c.setStrokeColor(HexColor(green));c.setLineWidth(1.5);c.line(x1,H-y1,x2,H-y2)
        import math
        a=math.atan2(y2-y1,x2-x1)
        for delta in [-.5,.5]:
            c.line(x2,H-y2,x2-6*math.cos(a+delta),H-(y2-6*math.sin(a+delta)))
    page=1
    rect(0,0,W,H,bg,0);rect(0,0,306,H,dark,0)
    icon=ROOT/'BaseballMaster/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png'
    c.drawImage(str(icon),29,H-66,width=35,height=35,mask='auto')
    word=c.beginText(75,H-47);word.setCharSpace(-.65);word.setFillColor(HexColor('#FFFFFF'));word.setFont('ArialBold',15);word.textOut('BASEBALL');word.setFont('Arial',15);word.textOut('MASTER');c.drawText(word)
    text('棒球比赛记录大师',75,53,8.3,'#C6D5CC')
    text('局面记录',30,126,37,'#FFFFFF')
    text('流程优化',30,178,37,'#FFFFFF')
    para('按看到的过程往下选。<br/>已确定的信息，自动带入。<br/>常用局面，直接开始。',31,258,240,15,'#D5E5DD',27)
    rect(30,407,246,94,'#16443B',12)
    para('讨论稿 v1 · 2026-10-12<br/>包含现有 App 原图与新流程示意截图。<br/>本文件用于评审，未改动 App 业务。',44,421,220,10.5,'#D5E5DD',18)
    text('流程说明与界面示意',337,31,23)
    para('从投球、击球到最终垒况，减少寻找和重复确认。',338,72,464,11,muted)
    for id,x,label in [('02-entry',341,'按过程选择'),('03-fly',500,'组合选项'),('07-single',659,'同屏确认垒况')]:
        image(id,x,125,140);text(label,x+18,445,11)
    rect(336,481,468,53,paper,12)
    para('阅读顺序：现状 → 总流程 → 入口 → 四个典型局面 → 跑垒与待确认 → 操作对照。',351,495,438,10.5,muted,17)
    text('BASEBALLMASTER  /  流程提案 v1',31,563,8,'#C6D5CC');text('01 / 10',759,563,8,muted)
    c.showPage()

    header(2,'当前流程：结果先行，常用情况分散','左侧为现有 2.3 App 的真实基准截图，原图未修改；右侧流程依据当前工作区记分代码梳理。')
    image('00-existing',39,100,195,maxh=424);text('现有 App 基准原图 · 合成比赛',40,528,9,muted)
    x=271
    box(x,104,532,117,'现有击球路径','击球进入场内 → 打者最终位置 → 发生原因 → 守备处理（需要时）→ 跑者确认（有跑者时）。<br/>垒上无人时，部分结果在选择后直接保存；并非所有局面都有同样多的步骤。')
    box(x,237,532,116,'繁琐来自两个地方','入口：双杀、牺牲打等选项需要先进入相应分支。<br/>过程：不同信息分在几页，已选的打者位置又出现在跑者确认区域；修改过程缺少统一的预览。')
    box(x,368,532,155,'本提案的优化重点','保留比分、球场、投打信息和逐球按钮的基本布局。<br/>在操作区补充固定的常用快捷入口；打开后，在同一面板内展开处理与最终垒况。<br/>过程式输入用于辅助判断；已知结果可以直达，没看清也能先保存确定的局面。',soft)
    finish()

    header(3,'总流程：一球一份草稿，确定后一起保存','坏球、看振、挥空、界外、击球并列。击球不用先记录一次好球；有跑垒变化时可附加在本球内。')
    flowbox(31,106,156,76,'开始记录本球','逐球按钮或常用快捷入口',soft)
    flowbox(231,102,260,85,'普通投球','坏球 / 看振 / 挥空 / 界外\n无附带事件：沿用现有快速记录')
    flowbox(532,102,277,85,'无附带事件：直接完成','普通投球保留一键记录\n不进入后续跑者核对',soft)
    flowbox(231,218,260,88,'击球：按过程或直达','地滚 / 高飞 / 平飞 / 触击\n或直接选安打、接杀、滚地出局、双杀',soft)
    flowbox(532,218,277,88,'根据已选内容补充','主要守备人 → 处理方式\n球型、落点只在需要时补充')
    flowbox(532,343,277,91,'同屏核对最终局面','打者与原有跑者一起显示\n自动带入建议，用户修改实际结果',soft)
    flowbox(231,344,260,91,'附带跑垒变化','盗垒 / 暴投 / 捕逸 / 跑垒出局\n责任不明时可标待确认')
    flowbox(532,467,277,65,'保存这一球','只保存一次；更新球数、出局、垒况与统计',soft)
    arrow(187,141,231,141);arrow(491,130,532,130);arrow(187,160,231,251);arrow(491,262,532,262);arrow(670,306,670,343);arrow(491,389,532,389);arrow(670,434,670,467)
    c.setStrokeColor(HexColor(green));c.setLineWidth(1.5)
    c.line(491,H-145,512,H-145);c.line(512,H-145,512,H-328);c.line(512,H-328,365,H-328)
    arrow(365,328,365,344)
    text('有跑垒变化时',390,312,8.5,muted)
    box(31,218,156,210,'自动跳过什么','无人上垒：省掉原有跑者确认。<br/>接杀 / 传杀已确定打者出局：不再问一次。<br/>结果已知：球型与落点可跳过。',paper)
    para('两球之间的牵制、跑垒或判罚仍有独立入口，不为了套用此流程而虚构一次投球。',31,469,455,11,muted,18)
    finish()

    header(4,'入口：保留习惯，让常用局面直接可见','左侧是记分操作区局部提案；右侧是点击“击球进入场内”后的面板。所有新界面均为静态示意。')
    image('01-actions',32,111,338,frame=False)
    box(32,425,338,104,'两条路，汇到同一份记录','熟悉现场结果：点常用局面。<br/>还在判断：点击球，沿球型与守备处理展开。<br/>两条路径共用最终垒况与一次保存。',soft)
    image('02-entry',401,101,193,maxh=426)
    steps(623,111,181,[('固定快捷入口','建议先放一垒安打、接杀、滚地出局、双杀。顺序固定。'),('按条件展示','触击按比赛规则；不满足双杀条件时，快捷项禁用并解释。'),('保留更多结果','其他安打、牺牲打、特殊判罚继续有清楚入口。')])
    finish()

    header(5,'例一：无人上垒，中外野高飞接杀','将“谁处理”和“怎么处理”合成一个可选项；“中外野接杀”一次带入守备人与打者出局。')
    image('03-fly',34,101,195,maxh=426);image('04-fly-ready',254,101,195,maxh=426)
    text('选择中外野接杀',62,529,10,muted);text('同一面板内展开保存预览',264,529,10,muted)
    steps(485,106,317,[('主页面点“接杀”','直接进入接杀选择；若从击球过程进入，则先选高飞球。'),('点“中外野接杀”','打者出局、主要守备人和接杀结果一起带入。'),('点“保存这一球”','确认后才写入；垒上无人，不再打开跑者页。')])
    box(485,353,317,160,'快捷路径：3 次点击','接杀 → 中外野接杀 → 保存。<br/>当前同类普通路径为 4 次选择后保存；本提案减少一个入口层级。<br/>两张图展示同一面板的前后状态，不要求再点一次“下一步”。',soft)
    finish()

    header(6,'例二：无人上垒，游击传一垒出局','按过程时选地滚球与处理人；已知结果时，从“滚地出局”直接进入常见守备路线。')
    image('05-ground',34,101,195,maxh=426);image('06-ground-ready',254,101,195,maxh=426)
    text('选择游击传一垒',62,529,10,muted);text('打者出局与守备路线已带入',265,529,10,muted)
    steps(485,106,317,[('点“滚地出局”','直接显示常见路线：游击传一垒、二垒传一垒、三垒传一垒等。'),('点“游击传一垒”','带入 6-3、打者出局及对应守备责任。左图也示意从球场选人的过程路径。'),('核对后保存','已确定的打者结果不用再次选择；无人上垒时收起跑者区域。')])
    box(485,371,317,142,'快捷路径：3 次点击','滚地出局 → 游击传一垒 → 保存。<br/>过程路径允许增加球型、处理人等细节，但不作为所有记录的必填步骤。',soft)
    finish()

    header(7,'例三：一垒有人，安打后跑者到三垒','打者与原有跑者在同一面板内确认。只修改与实际不同的建议位置，不重复填写已选的打者结果。')
    image('07-single',38,101,195,maxh=426)
    steps(273,108,526,[('点“一垒安打”','直接开始本球草稿；打者到一垒已带入，原有跑者显示建议目的地。'),('把原一垒跑者改到三垒','本例只需要改这一人。若建议正好符合现场，直接核对即可。'),('一次保存整球结果','同时保存安打、打者上一垒、原跑者到三垒；无需分别提交。')])
    box(273,358,526,154,'不能替用户猜最终跑垒结果','本例快捷路径：一垒安打 → 原跑者点三垒 → 保存，共 3 次点击。<br/>建议位置仅作为起点，必须能清楚查看和修改。<br/>安打种类与最终垒位分开：一垒安打后趁传到二垒，仍需保留原安打种类；额外推进另记原因。',soft)
    finish()

    header(8,'例四：一垒有人，形成 6-4-3 双杀','先确认守备路线，再把打者和原跑者的出局放在同一面板里核对；特殊情况才追加必要问题。')
    image('08-double',34,101,195,maxh=426);image('09-double-ready',254,101,195,maxh=426)
    text('选择双杀路线',66,529,10,muted);text('同屏核对两名出局者',279,529,10,muted)
    steps(485,106,317,[('点主页面“双杀”','一垒有人、0 出局，本例满足快捷项条件。'),('选“游击 → 二垒 → 一垒”','默认带入原一垒跑者封杀、打者出局，并显示对应人名。'),('核对并保存','两名出局者都清楚显示；如果实际不同，可以改选结果或路线。')])
    box(485,367,317,146,'目标快捷路径：3 次点击','双杀 → 6-4-3 → 保存。<br/>两张图为同一面板的前后状态；选择路线后自动展开出局者，不需要再点“继续”。<br/>第三出局与得分先后存在疑问时保留额外核对。',soft)
    finish()

    header(9,'跑垒变化与没看清：都能留在本次记录里','主线是投球；同球可以附带跑垒事件。观察信息不完整时，先记录确定的局面，再保留待确认内容。')
    image('10-pitch-runner',34,101,195,maxh=426);image('11-unclear',254,101,195,maxh=426)
    text('坏球伴随跑者推进',58,529,10,muted);text('先保存确定的最终局面',270,529,10,muted)
    box(485,109,317,179,'同一球附带跑垒变化','投球按钮旁提供清楚的“附带跑垒”操作。可先进入组合记录，再选择坏球与跑者推进。<br/>坏球与暴投 / 捕逸一起保存，投球次数只增加一次。<br/>普通坏球继续一键记录；若已保存再补跑垒，须关联原球并允许撤销。')
    box(485,306,317,204,'不确定时也能继续比赛','安打、失误或野手选择无法判断：先填打者与跑者最终位置，标记正式结果待确认。<br/>正式结果待确认时，相关技术统计应清楚标识；复核后再更新。<br/>仅知道最终垒况时，不强制补造球型、落点或守备路线。',soft)
    finish()

    header(10,'减少操作的依据与评审要点','点击数仅比较列出的具体路径，按当前代码与本提案静态流程计算；尚未实现，不能作为实测效率。')
    x=31;y=111;cw=[257,125,125,272]
    rows=[['具体局面','现有路径','目标快捷路径','减少在哪里'],['无人上垒，一垒安打','3 次','2 次','直接入口；同屏保存'],['无人上垒，中外野接杀','4 次','3 次','省打者最终位置入口'],['无人上垒，游击传一垒','4 次','3 次','直接路线；自动带入出局'],['一垒安打，原一垒跑者到三垒','5 次','3 次','省结果分类；只改一个跑者'],['0 出局，一垒有人，6-4-3 双杀','5 次','3 次','直接路线；核对后保存']]
    for ri,row in enumerate(rows):
        xx=x;hh=38 if ri==0 else 43
        for wi,cell in zip(cw,row):
            rect(xx,y,wi-2,hh-2,soft if ri==0 else paper,5)
            para(escape(cell),xx+10,y+10,wi-22,10.5,green if ri==0 else ink,15)
            xx+=wi
        y+=hh
    para('计数包含打开入口、必要选择、修改目的地与保存。现有空垒路径在最后一次选择后自动保存；新提案保留一次明确保存。双杀预览必须自动展开，才符合上表目标。',32,375,776,9.5,muted,15)
    box(31,428,382,110,'评审时重点看三件事','常用四个入口是否符合实际频率？<br/>组合选项是否能准确描述现场？<br/>同屏垒况是否容易核对、容易改错？',soft)
    box(429,428,380,110,'本文件的范围与依据','新图为静态 HTML 渲染，使用合成比赛数据。<br/>现状参考：ScorekeepingViews.swift、NoviceScoringViews.swift 与 GameStore.swift。<br/>未修改 App、数据库、同步协议或版本计划。')
    finish()
    c.save()
    (CHECK/'pdf-layout.json').write_text(json.dumps(layout,ensure_ascii=False,indent=2))
    if any(item['bottom']>553 for item in layout):
        raise RuntimeError('PDF text enters footer region')
    print(json.dumps({'pdf':str(dest),'pages':10,'screens':len(SCREENS)},ensure_ascii=False))

if __name__=='__main__':
    author_screens()
    subprocess.run([str(NODE),str(Path(__file__).with_name('render-review.cjs'))],check=True)
    author_pdf()
