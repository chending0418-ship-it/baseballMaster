"""V2 specification and static screens. Preserves V1 and all product files."""
from pathlib import Path
import hashlib
import json
import runpy
import shutil
import subprocess
from xml.sax.saxutils import escape

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'output/samples/scoring-flow-review/v2'
CHECK=ROOT/'output/validation/3.0/scoring-flow-review/v2'
RUNTIME=Path('/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies')
NODE=RUNTIME/'node/bin/node'
BASE=runpy.run_path(str(Path(__file__).with_name('build-review.py')))
for directory in [OUT,OUT/'screens',CHECK,CHECK/'pages']:
    directory.mkdir(parents=True,exist_ok=True)
CSS=BASE['CSS']+'''
.content{height:675px;gap:12px}.proposal{font-size:10px;color:#7D8D82;letter-spacing:.3px}.context{padding:10px 13px;font-size:12px}.context b{font-size:15px}.title{font-size:22px}.tabs{display:flex;background:#E6EBE2;border-radius:12px;padding:4px;gap:4px}.tab{flex:1;text-align:center;font-size:14px;padding:9px 0;border-radius:9px;color:var(--muted)}.tab.active{background:var(--paper);color:var(--green);font-weight:650;box-shadow:0 1px 4px #142b3c0b}.choice{min-height:63px}.choice small{font-size:11px}.choice.disabled{background:#EBEDE7;color:#899389}.choice.disabled small{color:#899389}.list{border:1px solid var(--line);border-radius:15px;background:var(--paper);overflow:hidden}.listrow{padding:12px 14px;min-height:51px;display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #E7EAE2;font-size:15px;gap:8px}.listrow:last-child{border:0}.chev{color:#819487;font-size:17px}.outcome{display:flex;align-items:center;justify-content:space-between;padding:13px 14px;background:var(--soft);border-radius:14px;font-size:14px;font-weight:650}.helper{font-size:12px;line-height:1.7;color:var(--muted)}.footer{bottom:29px;gap:6px}.footer .helper{text-align:center}.compact .context{font-size:12px;padding:8px 12px}.compact .context b{font-size:14px}.compact.content{gap:8px}.compact .tabs{padding:3px}.compact .tab{padding:8px 0}.catalog-section{display:flex;flex-direction:column;gap:6px}.catalog-title{font-size:13px;font-weight:650;color:var(--muted);line-height:1.4}.catalog-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:6px}.catalog-grid.four{grid-template-columns:repeat(4,1fr)}.catalog-option{background:var(--paper);border:1px solid var(--line);border-radius:10px;font-size:12px;line-height:1.35;min-height:44px;display:flex;align-items:center;justify-content:center;text-align:center;padding:5px}.catalog-option.disabled{background:#E9ECE5;color:#849188}.catalog-option small{font-size:10px;display:block;margin-top:3px}.catalog-section.pending .catalog-option{background:#FFF4E6;border-color:#EAD8C0;color:#8E571F}.routing{font-size:12px;background:var(--paper);border-radius:12px;padding:11px 12px;line-height:1.65;color:var(--muted)}
'''
SCREENS=[]
def choice(t,s='',disabled=False):
    return '<div class="choice '+('disabled' if disabled else '')+'"><div>'+t+('<small>'+s+'</small>' if s else '')+'</div></div>'
def grid(*items):return '<div class="grid">'+''.join(items)+'</div>'
def tabs(all=False):return '<div class="tabs"><div class="tab '+('' if all else 'active')+'">常用击球</div><div class="tab '+('active' if all else '')+'">全部击球</div></div>'
def row(t,s='›'):return f'<div class="listrow"><span>{t}</span><span class="chev">{s}</span></div>'
def outcome(t):return f'<div class="outcome"><span>{t}</span><span class="link">改选</span></div>'
def runner(name,origin,dest):
    labels=['留垒','二垒','三垒','得分','出局']
    return f'<div class="runner"><div class="row"><div><div class="mini">{origin}</div><b>{name}</b></div><span class="link">{dest}</span></div><div class="destinations">'+''.join(f'<div class="dest {"active" if t==dest else ""} {"out" if t=="出局" else ""}">{t}</div>' for t in labels)+'</div></div>'
def context(runners='无人上垒',outs=0,compact=False):
    if compact:return f'<div class="context">3 局上 · {outs} 出局 · {runners}</div>'
    return f'<div class="context"><div class="row"><b>海浪 2 : 1 飞鹰</b><span>3 局上</span></div><div>{outs} 出局 · {runners}　｜　打者 #5 许嘉树</div></div>'
def screen(id,title,body,runners='无人上垒',outs=0,button=None,help='',compact=False):
    footer=(f'<div class="button">{button}</div>' if button else '')+f'<div class="helper">{help}</div>'
    html=f'<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>{title} · V2</title><style>{CSS}</style><div class="status"><span>9:41</span><div class="island"></div><span>▮▮▮　⌁　▰</span></div><div class="nav"><span class="link">取消</span><strong>{title}</strong><span class="link">返回</span></div><main class="content {"compact" if compact else ""}"><div class="proposal">V2 · 界面示意，尚未实现</div>{context(runners,outs,compact)}{body}</main><div class="footer">{footer}</div><div class="homebar"></div></html>'
    (OUT/'screens'/f'{id}.html').write_text(html)
    SCREENS.append({'id':id,'title':title,'width':402,'height':874})
def catalog(title,items,four=False,pending=False):
    html=''
    for item in items:
        if isinstance(item,tuple):
            label,reason=item
            html+=f'<div class="catalog-option disabled"><div>{label}<small>{reason}</small></div></div>'
        else:html+=f'<div class="catalog-option">{item}</div>'
    return f'<div class="catalog-section {"pending" if pending else ""}"><div class="catalog-title">{title}</div><div class="catalog-grid {"four" if four else ""}">{html}</div></div>'

def author_screens():
    source=ROOT/'docs/design/3.0/reference-scoring-2.3.png'
    shutil.copyfile(source,OUT/'screens/00-main.png')
    SCREENS.append({'id':'00-main','title':'现有主页面基准原图','source':str(source)})
    screen('01-common','记录击球',tabs()+'<div class="title">选你看到的局面</div>'+grid(choice('一垒安打','打者到一垒'),choice('二垒安打','打者到二垒'),choice('高飞接杀','选择接球人'),choice('滚地出局','选择处理路线'),choice('守备失误','确认责任与最终位置'),choice('双杀','选择路线与出局者'))+'<div class="list">'+row('按击球过程记录','球型 → 处理 ›')+row('没看清，先记局面','待确认 ›')+'</div><div class="helper">三垒安打、本垒打、牺牲打等，在顶部“全部击球”。</div>',runners='一垒 #18 林子轩',help='已选信息自动带入；有跑者时一起核对。')
    screen('02-ground','滚地出局',outcome('已选：滚地出局')+'<div class="title">选择守备处理路线</div>'+grid(choice('游击 → 一垒','6-3 · 打者出局'),choice('二垒 → 一垒','4-3 · 打者出局'),choice('三垒 → 一垒','5-3 · 打者出局'),choice('投手 → 一垒','1-3 · 打者出局'))+'<div class="list">'+row('其他处理路线','逐个选守备人 ›')+row('处理人不清楚','标记待确认 ›')+'</div><div class="outcome">打者出局 · 无原有跑者</div>',help='点选明确路线后直接记录，可在主页面撤销。')
    screen('03-fly','高飞接杀',outcome('已选：高飞接杀')+'<div class="title">谁接住了球？</div>'+grid(*[choice(t) for t in ['左外野手','中外野手','右外野手','游击手','二垒手','三垒手','一垒手','投手','捕手','没看清']]),help='无人上垒：点接球人后直接记录，可撤销。')
    screen('04-runners','确认本球局面',outcome('一垒安打 · 1B')+'<div class="title">只改与现场不同的地方</div><div class="card green"><div class="row"><b>打者 #5 许嘉树</b><span class="link">到一垒 · 修改</span></div></div>'+runner('#18 林子轩','原一垒跑者','三垒')+'<div class="card"><div class="section">记录后：一、三垒有人 · 0 出局</div><div class="sub">安打种类与额外推进分开；实际不同可点修改。</div></div><div class="list">'+row('补充球型 / 处理细节','选填 ›')+'</div>',runners='一垒 #18 林子轩',button='确认记录',help='一次提交打者与全部原有跑者。')
    screen('05-double','确认双杀',outcome('双杀 · 游击 → 二垒 → 一垒')+'<div class="title">核对这次出的两个人</div><div class="card green"><div class="row"><b>打者 #5 许嘉树</b><span class="link">出局 · 修改</span></div></div>'+runner('#18 林子轩','原一垒跑者','出局')+'<div class="card"><div class="section">记录后：2 出局 · 垒上无人</div><div class="sub">本例仅有一垒跑者，路线与两名出局者可以一起带入。</div></div><div class="helper">有多名跑者时，明确选择实际出局者；不能仅按路线猜是谁出局。</div>',runners='一垒 #18 林子轩',button='确认记录',help='选路线后自动展开核对；不增加“下一步”。')
    sections=[
      ('安打',['一垒安打','二垒安打','三垒安打','本垒打'],True),
      ('接杀与出局',['滚地出局','高飞接杀','平飞接杀','界外飞球接杀',('内野高飞必死','需一、二垒有人'),'待确认出局'],False),
      ('战术与多人出局',['牺牲触击',('牺牲高飞','需三垒有人'),'双杀',('三杀','需至少两名跑者')],False),
      ('其他上垒与待确认',['守备失误','野手选择','触杀其他跑者','待确认上垒','其他 / 待补'],False)
    ]
    screen('06-all','全部击球',tabs(True)+''.join(catalog(t,items,four) for t,items,four in sections),runners='一垒 #18 林子轩',help='位置固定；不适用项保留名称与原因。',compact=True)
    screen('07-special','特殊 / 判罚','<div class="title">投球例外与裁判宣判</div><div class="list">'+row('触身球','打者被球击中 ›')+row('故意保送','裁判直接示意 ›')+row('三振未接住','按当前条件处理 ›')+row('两好球后触击界外','按本场规则 ›')+'</div><div class="section">裁判判罚 <span>展开一类，查看完整列表</span></div><div class="list">'+row('投手','犯规 / 违规投球 ›')+row('打者','干扰 / 打击次序等 ›')+row('捕手','干扰打击 / 本垒阻挡 ›')+row('防守','阻挡跑垒 / 装备等 ›')+row('进攻','跑者与教练干扰等 ›')+'</div>',runners='一垒 #18 林子轩',help='按裁判最终宣判核对；修正与 TB 沿用现有入口。')
    screen('08-timing','需要补充核对',outcome('本球出现第三出局与得分')+'<div class="title">得分与出局的先后是什么？</div><div class="sub">当前判定需要确认先后。只在影响得分的局面里询问。</div><div class="list">'+row('得分在先','按有效得分处理 ›')+row('出局在先','按无效得分处理 ›')+row('不确定','保留待确认 / 按宣判 ›')+'</div><div class="card orange"><div class="section">必要问题保留</div><div class="sub">不靠默认值猜得分，也不把第三出局后的跑者推进当作有效结果。</div></div>',runners='三垒有人',outs=2,button='核对后确认记录',help='本页为复杂局面的核对示意，按实际判罚处理。')
    (OUT/'screens.json').write_text(json.dumps(SCREENS,ensure_ascii=False,indent=2))
    # Every existing batted-ball outcome retains a visible named path.
    mapping={
      'single':'一垒安打','double':'二垒安打','triple':'三垒安打','homeRun':'本垒打',
      'groundOut':'滚地出局','flyOut':'高飞接杀','lineOut':'平飞接杀','foulFlyOut':'界外飞球接杀',
      'infieldFly':'内野高飞必死','error':'守备失误','fieldersChoice':'野手选择','runnerTagOut':'触杀其他跑者',
      'sacrificeBunt':'牺牲触击','sacrificeFly':'牺牲高飞','doublePlay':'双杀','triplePlay':'三杀',
      'pendingOut':'待确认出局','pending':'待确认上垒','other':'其他 / 待补'
    }
    import re
    model=(ROOT/'BaseballMaster/Models.swift').read_text()
    raw=model.split('enum PlayOutcome:')[1].split('var id:')[0]
    cases=re.findall(r'case\s+(\w+)\s*=',raw)
    assert set(cases)==set(mapping)
    all_html=(OUT/'screens/06-all.html').read_text()
    assert all(escape(label) in all_html or label in all_html for label in mapping.values())
    (CHECK/'outcome-coverage.json').write_text(json.dumps({'covered':len(mapping),'mapping':mapping},ensure_ascii=False,indent=2))

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
    dest=OUT/'BaseballMaster-局面记录优化-流程说明-V2.pdf'
    c=canvas.Canvas(str(dest),pagesize=(W,H),pageCompression=1)
    c.setTitle('BaseballMaster 局面记录优化 - V2 实施说明')
    c.setAuthor('BaseballMaster')
    bg='#F5F2EA';paper='#FFFEFA';ink='#102A43';muted='#607284';green='#217A57';soft='#E4F2EA';line='#DADFD9'
    page=1;measurements=[]
    def rect(x,y,w,h,fill=paper,r=12):
        c.setFillColor(HexColor(fill));c.setStrokeColor(HexColor(fill));c.roundRect(x,H-y-h,w,h,r,fill=1,stroke=0)
    def text(t,x,y,size=11,color=ink,font='CJK'):
        c.setFont(font,size);c.setFillColor(HexColor(color));c.drawString(x,H-y-size,t)
    def para(t,x,y,w,size=11,color=ink,leading=None):
        p=Paragraph(t,ParagraphStyle('body',fontName='CJK',fontSize=size,leading=leading or size*1.6,textColor=HexColor(color),wordWrap='CJK'))
        _,h=p.wrap(w,1000);p.drawOn(c,x,H-y-h)
        measurements.append({'page':page,'x':x,'y':y,'w':w,'h':h})
        return h
    def header(n,title,sub):
        nonlocal page
        page=n;rect(0,0,W,H,bg,0)
        text('V2',30,24,13,green,'ArialBold');text(title,77,18,23)
        para(sub,31,60,W-62,11,muted,17)
    def end():
        c.setStrokeColor(HexColor(line));c.line(31,39,W-31,39)
        text('BASEBALLMASTER  /  局面记录优化 V2  /  实施说明与静态界面示意',31,565,7.6,muted)
        text(f'2026-10-12    {page:02d} / 06',W-150,565,7.6,muted)
        c.showPage()
    def phone(id,x,y,w=190):
        path=OUT/'screens'/f'{id}.png';iw,ih=ImageReader(str(path)).getSize();h=w*ih/iw
        rect(x-3,y-3,w+6,h+6,'#153C36',18)
        c.saveState();p=c.beginPath();p.roundRect(x,H-y-h,w,h,15);c.clipPath(p,stroke=0,fill=0)
        c.drawImage(str(path),x,H-y-h,width=w,height=h,mask='auto');c.restoreState()
    def box(x,y,w,h,title,body,fill=paper):
        rect(x,y,w,h,fill)
        text(title,x+15,y+12,14,green if fill==soft else ink)
        para(body,x+15,y+39,w-30,11,ink,18)
    def steps(x,y,w,items):
        for n,(title,body) in enumerate(items,1):
            rect(x,y,24,24,soft,12);text(str(n),x+8,y+3,11,green,'ArialBold')
            text(title,x+36,y,14);h=para(body,x+36,y+25,w-36,11,muted,18)
            y+=max(65,h+41)
        return y

    header(1,'页面照旧，记录更快','常用要顺手，不常用要能找到。V2 保留现有主页面，把优化集中在打开后的记录面板。')
    phone('00-main',38,110,190);phone('01-common',264,110,190)
    text('现有 App 主页面基准原图',43,528,9,muted);text('现有击球按钮打开后的第一屏',266,528,9,muted)
    steps(490,111,316,[('主页面布局保留','比分、球场、投打、逐球按钮与底部操作位置不变，不新增快捷按钮行。'),('常用结果第一屏可见','直接选安打、接杀、滚地出局、失误、双杀；不先问打者最后到哪里。'),('完整情况有固定入口','“全部击球”始终可见；跑垒和判罚仍从原有主页面入口进入。')])
    box(490,385,316,130,'V2 的收敛','简单局面：最后一次明确选择后直接完成。<br/>有跑者或复杂处理：同一面板核对后一次提交。<br/>按过程记录作为辅助路径。',soft)
    end()

    header(2,'明确的简单局面，选完就记好','沿用现有空垒时直接记录的习惯；省掉重复确认，完成后仍可通过主页面“撤销”恢复。')
    phone('02-ground',38,109,190);phone('03-fly',264,109,190)
    text('滚地出局：直接选常见路线',42,528,9,muted);text('高飞接杀：直接选接球人',278,528,9,muted)
    box(490,110,316,145,'无人上垒：2–3 次点击','一垒安打：击球 → 一垒安打，2 次。<br/>滚地出局：击球 → 滚地出局 → 游击传一垒，3 次。<br/>高飞接杀：击球 → 高飞接杀 → 中外野手，3 次。',soft)
    box(490,273,316,114,'只跳过已确定的信息','守备路线包含打者出局，无需再选一次。<br/>垒上无人，无需跑者确认页。<br/>上垒原因或责任不清楚，转入待确认。')
    box(490,405,316,109,'过程路径仍然可用','球型 → 主要处理人 → 处理结果。<br/>每次选择保留已填内容，只展开下一项；球型与落点不作为所有局面的必填项。')
    end()

    header(3,'有跑者：同屏核对，只改不同的地方','已确定的打者结果用一行摘要表示；原有跑者直接点目的地，最后统一确认，不逐人提交。')
    phone('04-runners',38,109,190);phone('05-double',264,109,190)
    text('安打后推进：只修改原跑者',43,528,9,muted);text('双杀：路线带入后核对出局者',265,528,9,muted)
    steps(490,111,316,[('选择结果后直接展开','安打带入打者垒位；双杀选择路线后自动展开出局者，不再点“下一步”。'),('按现场修改跑者','建议目的地仅作起点。多名跑者时，明确实际出局者，不能只靠路线猜。'),('一次确认整球结果','同屏展示记录后的出局数和垒况，再提交打者、跑者与守备责任。')])
    box(490,396,316,119,'信息只填一次','改选时保留仍有效的内容；仅失效的选择需要重选。<br/>一垒安打后趁传到二垒：保留一垒安打，额外推进另记原因。',soft)
    end()

    header(4,'不常用情况，有完整而固定的去处','“常用”是快捷入口；“全部”是完整目录。按类别找，不靠猜某个前置结果才能看到。')
    phone('06-all',38,109,190);phone('07-special',264,109,190)
    text('全部击球：覆盖当前全部 19 类结果',35,528,9,muted);text('投球例外与裁判判罚：保持原入口',263,528,9,muted)
    box(490,110,316,113,'击球 → 全部击球','平飞、界外接杀、内野高飞必死；牺牲触击 / 高飞、三杀、野手选择、待确认等。<br/>完整 19 类结果均有具名入口。',soft)
    box(490,240,316,121,'跑者 / 出局 → 完整分类','主动进垒、漏球与投手犯规、跑者出局。<br/>覆盖双重 / 延迟盗垒、牵制、触杀、封杀、申诉、漏踩垒、离垒过早与妨碍等现有情况。')
    box(490,378,316,139,'特殊 / 判罚 → 投球例外与判罚','触身球、故意保送、三振未接住、触击界外。<br/>裁判判罚按投手、打者、捕手、防守、进攻分类；按最终宣判核对。<br/>不适用项保留名称与原因，便于查找。')
    end()

    header(5,'少问重复问题，保留真正必要的核对','复杂情况沿用现有裁判判定、技术统计与纠错能力；简化交互不删事件、不猜责任。')
    rows=[
      ('同球伴随跑垒','击球内的推进随本球提交；普通投球伴随盗垒、暴投 / 捕逸等，需关联原球，投球次数不重复增加。两球之间的事件仍单独记录。'),
      ('第三出局与得分','影响计分的时间局面核对先后；封杀等按现有逻辑处理。第三出局后不继续询问没有意义的推进。'),
      ('垒位冲突、多人出局','同垒两人、重复球员、超过三个出局等阻止提交；多人出局明确人名和路线，不用不可靠的默认值替代核对。'),
      ('没看清、误触与改选','明确结果直接记入后可撤销；尚未提交的改选只改草稿。没看清可先保存确定局面，标注结果或责任待确认。'),
      ('规则差异与后续记录','触击、慢垒 Illegal、界外策略、教练投手、TB 沿用本场规则与现有入口。历史记录、直播与统计使用同一正式事件。')
    ]
    y=111
    for n,(title,body) in enumerate(rows,1):
        rect(31,y,778,72,soft if n%2 else paper)
        text(f'{n:02d}',47,y+15,12,green,'ArialBold');text(title,85,y+13,13)
        para(body,278,y+12,512,11,ink,18)
        y+=85
    end()

    header(6,'实施标准：流程更短，功能保持完整','V2 按保留主页面的约束重新计算目标点击数；以下是设计目标，开发后需在模拟器逐项验证。')
    widths=[348,133,137,161];y=111
    table=[['具体局面','当前路径','V2 目标','处理方式'],['空垒，一垒安打','3 次','2 次','最后选择即记录'],['空垒，中外野高飞接杀','4 次','3 次','最后选择即记录'],['空垒，游击传一垒出局','4 次','3 次','最后选择即记录'],['一垒安打，原一垒跑者到三垒','5 次','4 次','同屏修改后确认'],['0 出局，一垒有人，6-4-3 双杀','5 次','4 次','选路线后同屏确认']]
    for n,row_ in enumerate(table):
        x=31;h=34 if n==0 else 39
        for w,cell in zip(widths,row_):
            rect(x,y,w-2,h-2,soft if n==0 else paper,6)
            para(escape(cell),x+11,y+9,w-22,10.5,green if n==0 else ink,15);x+=w
        y+=h
    para('计数含主页面击球入口、必要选择、跑者修改与最终确认；不含首次开赛阵容核对。复杂情况允许多一步必要核对，不为追求点击数省略判断。',32,356,776,9.5,muted,15)
    box(31,409,378,131,'交互验收','主页面无新增行，无球场或投打区域缩减。<br/>常用入口第一屏可见，位置稳定。<br/>已填信息不重复选择；改选不丢有效内容。<br/>完整目录、待确认和撤销都可找到。',soft)
    box(425,409,384,131,'业务验收与交付范围','核对球数、打序、得分、出局与守备统计。<br/>覆盖多跑者、第三出局、规则差异与恢复。<br/>本文件为已确认方向的 V2 实施说明；截图为静态示意，本轮未改 App 业务。')
    end();c.save()
    (CHECK/'pdf-layout.json').write_text(json.dumps(measurements,ensure_ascii=False,indent=2))
    assert all(item['y']+item['h']<554 for item in measurements),'PDF text overlaps footer'
    print(json.dumps({'pdf':str(dest),'pages':6,'screens':len(SCREENS)},ensure_ascii=False))

if __name__=='__main__':
    author_screens()
    subprocess.run([str(NODE),str(Path(__file__).with_name('render-review.cjs')),'v2'],check=True)
    author_pdf()
