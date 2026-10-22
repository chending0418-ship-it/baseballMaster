"""Build the 3.0 static screen proposal and PDF. No product implementation."""
from pathlib import Path
import base64
import json
import subprocess
import shutil
import sys
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'output/samples/3.0-ui-review'
OUT.mkdir(parents=True, exist_ok=True)
(OUT / 'screens').mkdir(exist_ok=True)
ICON = ROOT / 'BaseballMaster/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png'
ICON_URI = 'data:image/png;base64,' + base64.b64encode(ICON.read_bytes()).decode()

CSS = '''
:root{--bg:#F5F2EA;--paper:#FFFEFA;--ink:#102A43;--muted:#607284;--green:#217A57;--soft:#E4F2EA;--line:#DADFD9;--orange:#A35513;--orangebg:#FFF0DE}
*{box-sizing:border-box}html,body{margin:0;width:390px;height:844px;overflow:hidden;background:var(--bg);color:var(--ink);font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif;-webkit-font-smoothing:antialiased}
.status{height:51px;display:flex;align-items:center;justify-content:space-between;padding:0 28px;color:#142b3c;font-size:15px;font-weight:650}.island{position:absolute;left:142px;top:10px;width:106px;height:27px;background:#16212a;border-radius:20px}.signals{display:flex;align-items:center;gap:5px;font-size:11px}.battery{width:21px;height:10px;border:1.2px solid #142b3c;border-radius:3px;padding:1px}.battery:before{content:'';display:block;background:#142b3c;width:15px;height:6px;border-radius:1px}
.nav{height:55px;display:flex;align-items:center;justify-content:center;position:relative;padding:0 18px}.nav strong{font-size:18px}.back{position:absolute;left:18px;font-size:16px;color:var(--green)}.right{position:absolute;right:18px;color:var(--green);font-size:14px}.content{height:648px;padding:13px 18px 0;display:flex;flex-direction:column;gap:13px}.content.long{height:694px}.content.tight{gap:10px}.tabbar{height:68px;position:absolute;bottom:20px;left:14px;right:14px;background:#fffefaeb;border:1px solid #e1e5df;border-radius:25px;display:flex;align-items:center;justify-content:space-around;box-shadow:0 4px 14px #102a4307}.tab{font-size:11px;color:#71818c;display:grid;gap:4px;justify-items:center;min-width:72px}.tab.active{color:var(--green);font-weight:650}.tab svg{width:23px;height:23px;stroke-width:1.8}.homebar{position:absolute;bottom:7px;width:128px;height:5px;background:#142b3c;border-radius:4px;left:131px}.card{background:var(--paper);border:1px solid var(--line);border-radius:18px;padding:16px}.card.green{background:var(--soft);border-color:#c6ddd0}.card.orange{background:var(--orangebg);border-color:#ebd2b3}.card.dark{background:#153f37;color:white;border:0}.card.flush{padding:0 16px}.card.compact{padding:13px 15px}.row{display:flex;align-items:center;justify-content:space-between;gap:10px}.stack{display:flex;flex-direction:column;gap:5px}.title{font-size:22px;font-weight:700;line-height:1.35;letter-spacing:-.4px}.h{font-size:16px;font-weight:650;line-height:1.5}.sub{font-size:13px;color:var(--muted);line-height:1.6}.tiny{font-size:11px;color:var(--muted);line-height:1.6}.body{font-size:14px;line-height:1.65}.tag{font-size:11px;line-height:1.4;border-radius:20px;padding:5px 9px;background:var(--soft);color:var(--green);white-space:nowrap}.tag.gray{background:#edf0ed;color:#607284}.tag.orange{background:var(--orangebg);color:var(--orange)}.tag.dark{background:#2e574e;color:#deeee4}.avatar{height:49px;width:49px;flex-shrink:0;border-radius:50%;background:var(--ink);color:white;display:grid;place-items:center;font-size:22px;font-weight:650}.avatar.small{width:36px;height:36px;font-size:15px;background:#e7eee8;color:var(--green)}.team{display:flex;align-items:center;gap:12px}.section{font-size:17px;font-weight:680;display:flex;align-items:center;justify-content:space-between}.section span{font-size:12px;font-weight:400;color:var(--muted)}.button{min-height:49px;border-radius:14px;background:var(--green);color:white;font-size:16px;font-weight:600;display:flex;justify-content:center;align-items:center;padding:10px 12px;text-align:center}.button.secondary{background:var(--paper);color:var(--green);border:1px solid #b6d0c0}.button.muted{background:#e5e8e2;color:#87918a}.button.small{min-height:44px;border-radius:10px;font-size:13px}.button.orange{background:var(--orange)}.two{display:grid;grid-template-columns:1fr 1fr;gap:10px}.statgrid{display:grid;grid-template-columns:repeat(3,1fr);text-align:center;padding:7px 0}.statgrid b{font-size:25px;display:block;margin-bottom:4px}.statgrid span{font-size:12px;color:var(--muted)}.divider{height:1px;background:#e2e5de;margin:12px 0}.listrow{min-height:58px;border-bottom:1px solid #e6e9e2;display:flex;align-items:center;justify-content:space-between;gap:10px}.listrow:last-child{border-bottom:0}.chev{font-size:20px;color:#8a989f}.input{border:1px solid #d5ddd4;border-radius:11px;min-height:46px;display:flex;align-items:center;padding:10px 12px;font-size:16px;background:white}.label{font-size:12px;color:var(--muted);margin-bottom:6px}.note{font-size:12px;color:var(--muted);line-height:1.6;padding:0 3px}.check{width:20px;height:20px;flex-shrink:0;border-radius:6px;display:grid;place-items:center;background:var(--green);color:white;font-size:13px}.radio{width:21px;height:21px;flex-shrink:0;border-radius:50%;border:1.5px solid #afbab0}.selected .radio{border:6px solid var(--green)}.choice{border:1px solid var(--line);border-radius:15px;padding:13px;background:var(--paper);display:flex;align-items:flex-start;gap:11px}.choice.selected{background:var(--soft);border-color:#7bad93}.choice .radio{margin-top:3px}.notice{display:flex;align-items:center;gap:8px;color:var(--green);font-size:12px;line-height:1.5}.notice.orange{color:var(--orange)}.dot{display:inline-block;width:7px;height:7px;background:currentColor;border-radius:50%;flex-shrink:0}.score{font-size:32px;font-weight:750;letter-spacing:2px}.segmented{display:flex;padding:4px;border-radius:12px;background:#e9ece4;gap:3px}.segment{flex:1;font-size:13px;padding:8px 0;text-align:center;color:#667969;border-radius:9px}.segment.active{background:var(--paper);color:var(--ink);box-shadow:0 1px 4px #102a4312;font-weight:600}.link{color:var(--green);font-size:14px}.center{text-align:center}.pill{border-radius:12px;padding:9px 12px;font-size:12px;background:var(--soft);color:var(--green);line-height:1.5}.pill.orange{background:var(--orangebg);color:var(--orange)}.spacer{flex:1;min-height:0}.stepcircle{width:21px;height:21px;border-radius:50%;background:var(--soft);color:var(--green);display:grid;place-items:center;font-size:11px;flex-shrink:0}.mini-steps{display:flex;gap:7px;align-items:center;font-size:12px;color:var(--muted);padding:4px 0}.badge-icon{width:54px;height:54px;background:var(--soft);color:var(--green);border-radius:18px;display:grid;place-items:center;font-size:26px;margin:0 auto 12px}.table{width:100%;border-collapse:collapse;font-size:12px;text-align:center}.table td,.table th{padding:8px 3px;border-bottom:1px solid #e7e9e2}.table th{color:var(--muted);font-weight:500}.table th:first-child,.table td:first-child{text-align:left}.browser{height:49px;display:grid;place-items:center;background:#e8ece5;font-size:13px;color:#44564d;border-bottom:1px solid #dce2d8}.webbrand{height:63px;padding:12px 17px;background:#092a2a;color:white;display:flex;gap:10px;align-items:center}.webbrand img{width:35px;height:35px;border-radius:8px}.wordmark{font-family:Arial,sans-serif;font-size:14px;font-weight:800;letter-spacing:-.045em}.wordmark span{font-weight:400}.wordmark small{display:block;font-family:-apple-system,'PingFang SC',sans-serif;font-size:9px;font-weight:450;letter-spacing:.2em;color:#c6d5cc;margin-top:4px}.webcontent{height:635px}.field{height:228px;border-radius:16px;background:#2E765A;position:relative;overflow:hidden}.field .diamond{position:absolute;width:135px;height:135px;background:#d7b785;transform:rotate(45deg);left:110px;top:70px;border:2px solid #f7ebd9}.field .diamond:after{content:'';position:absolute;inset:18px;background:#397859;border:2px solid white}.field .base{position:absolute;width:14px;height:14px;background:white;transform:rotate(45deg);z-index:2;border-radius:2px}.field .fielder{position:absolute;width:33px;height:33px;border-radius:50%;background:#f4f7ee;color:#102A43;font-size:10px;display:grid;place-items:center;text-align:center;font-weight:600;z-index:3}.pitchgrid{display:grid;grid-template-columns:repeat(4,1fr);gap:6px}.pitchgrid div{background:var(--green);color:white;text-align:center;font-size:14px;font-weight:600;padding:13px 0;border-radius:12px}.pitchgrid div:nth-child(2),.pitchgrid div:nth-child(3){background:#9f4c0d}.pitchgrid div:last-child{background:var(--ink)}
'''

ICONS = {
    '比赛':'<path d="m12 3 9 9-9 9-9-9Z"/><path d="m12 8 4 4-4 4-4-4Z"/>',
    '球队':'<circle cx="12" cy="7" r="3"/><path d="M6 21v-3a6 6 0 0 1 12 0v3M4 5a3 3 0 0 0 0 6M20 5a3 3 0 0 1 0 6M2 20v-3a4 4 0 0 1 2-3M22 20v-3a4 4 0 0 0-2-3"/>',
    '统计':'<path d="M4 21V11h4v10M10 21V5h4v16M16 21V2h4v19"/>',
    '我的':'<circle cx="12" cy="12" r="10"/><circle cx="12" cy="8" r="3"/><path d="M5 19a7 7 0 0 1 14 0"/>'
}
def tabs(active):
    return '<div class="tabbar">'+''.join(f'<div class="tab {"active" if t==active else ""}"><svg fill="none" stroke="currentColor" viewBox="0 0 24 24">{p}</svg>{t}</div>' for t,p in ICONS.items())+'</div>'
def card(body, cls=''):
    return f'<div class="card {cls}">{body}</div>'
def tag(t,cls=''):
    return f'<span class="tag {cls}">{t}</span>'
def btn(t,cls=''):
    return f'<div class="button {cls}">{t}</div>'
def row(a,b):
    return f'<div class="row">{a}{b}</div>'
def st(h,s=''):
    return f'<div class="stack"><div class="h">{h}</div><div class="sub">{s}</div></div>'
def section(h,s=''):
    return f'<div class="section">{h}<span>{s}</span></div>'
def notice(t,cls=''):
    return f'<div class="notice {cls}"><span class="dot"></span>{t}</div>'
def listing(h,s,b='›'):
    return f'<div class="listrow">{st(h,s)}<span class="chev">{b}</span></div>'
def choice(h,s,selected=False):
    return f'<div class="choice {"selected" if selected else ""}"><div class="radio"></div>{st(h,s)}</div>'
def team(role='管理员',sub='2026 秋季 · 18 名球员'):
    return f'<div class="team"><div class="avatar">海</div><div class="stack"><div class="title">青岛海浪</div><div class="sub">{sub}</div></div></div><div style="margin-top:12px">{tag("共享球队")} {tag(role,"gray")}</div>'
def gamecard(date='10 月 8 日 · 已结束',score='7 : 5'):
    return card(row(st('海浪 vs 飞鹰',date),f'<div class="score" style="font-size:25px">{score}</div>'))

SCREENS=[]
def screen(id,title,body,active=None,back='‹ 返回',right='',web=False,tight=False):
    if web:
        head=f'<div class="browser">⌑　baseballmaster.cc</div><div class="webbrand"><img src="{ICON_URI}"><div class="wordmark">BASEBALL<span>MASTER</span><small>棒球比赛记录大师</small></div></div>'
    else:
        head=f'<div class="nav"><span class="back">{back}</span><strong>{title}</strong><span class="right">{right}</span></div>'
    html=f'<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>{title} · 界面提案</title><style>{CSS}</style><div class="status"><span>9:41</span><div class="island"></div><div class="signals">▮▮▮　⌁<span class="battery"></span></div></div>{head}<main class="content {"" if active else "long"} {"webcontent" if web else ""} {"tight" if tight else ""}">{body}</main>{tabs(active) if active else ""}<div class="homebar"></div></html>'
    (OUT/'screens'/f'{id}.html').write_text(html)
    SCREENS.append({'id':id,'title':title,'web':web})

def author_screens():
    screen('01-team','球队',card(team())+notice('全部已同步 · 今天 09:40')+card('<div class="statgrid"><div><b>18</b><span>球员</span></div><div><b>12</b><span>比赛</span></div><div><b>8</b><span>成员</span></div></div>')+section('球队管理')+card(listing('球队名单','18 名球员 · 管理名单与背号')+listing('成员与权限','2 名管理员 · 2 名记录员 · 4 名成员')+listing('球队档案','赛季、比赛与共享设置'),'flush')+btn('邀请成员加入')+'<div class="note">队内比赛默认仅已加入的成员可看。<br>分享单场比赛，在比赛详情中单独设置。</div>',active='球队',back='',right='切换')
    screen('02-enable','开启球队共享','<div class="title">把这支球队交给全队一起记录</div><div class="sub">已有本机资料仍然保留，由你选择共享内容。</div>'+card('<div class="team"><div class="avatar">海</div>'+st('青岛海浪','当前仅保存在本机')+'</div>')+section('这次共享哪些资料')+card('<div class="listrow">'+st('球队资料与名单','18 名球员')+'<span class="check">✓</span></div><div class="listrow">'+st('已选历史比赛','12 场中的 8 场')+'<span class="link">选择 ›</span></div><div class="listrow">'+st('未来赛程','2 场')+'<span class="check">✓</span></div>','flush')+card(st('只有受邀成员能加入','你将成为管理员，可邀请记录员和普通成员。无需手机号、邮箱或密码。'),'green')+'<div class="note">下一步先核对球员对应和重复比赛，再开始共享。完成后请保存管理恢复卡，用于所有管理设备丢失时恢复球队。</div>'+btn('核对资料并继续'),back='‹ 球队')
    screen('03-invite','邀请加入球队',card(team(sub='选择对方加入后的角色'))+section('邀请角色')+choice('普通成员','查看球队比赛与统计，不能记分或修改。',True)+choice('记录员','查看全队资料和全部比赛；可记分，无球队管理权限。')+card(st('管理员在成员列表中设置','先邀请对方加入，再由管理员调整角色。'),'compact')+btn('生成邀请链接 / 二维码')+'<div class="note">邀请仅可使用一次，24 小时内有效，可随时撤销。这里是页面预览，尚未生成可用邀请。</div>',back='‹ 成员')
    screen('04-join','加入球队','<div class="badge-icon">＋</div><div class="center"><div class="title">陈教练邀请你加入</div><div class="sub" style="margin-top:8px">确认球队与身份，填个称呼就可以加入。</div></div>'+card(team('普通成员',sub='球队编号 BM-2048 · 示例'))+'<div><div class="label">大家怎么称呼你</div><div class="input">小林家长</div></div>'+card(st('加入后可以做什么','查看球队历史比赛、球员统计和已同步赛况。不能修改比赛或名单。'),'green')+btn('确认加入')+'<div class="note center">无需注册账号<br>新手机可由已有设备授权，或请管理员重新邀请。</div>',back='取消')
    screen('05-member','比赛',team('普通成员')+notice('已同步至今天 09:40')+'<div class="segmented"><div class="segment">未来</div><div class="segment">进行中</div><div class="segment active">历史比赛</div></div>'+row('<div class="h">2026 秋季</div>','<span class="link">筛选 ▾</span>')+gamecard()+gamecard('10 月 5 日 · 已结束','3 : 2')+gamecard('10 月 2 日 · 已结束','4 : 6')+card(st('全队记录，集中在这里','不同记录员的比赛会进入同一份球队档案。'),'green'),active='比赛',back='',right='切换')
    screen('06-detail','比赛详情',card('<div class="sub center">2026 秋季友谊赛 · 10 月 8 日</div><div class="row" style="margin:14px 0"><div class="h">青岛海浪</div><div class="score">7 : 5</div><div class="h">飞鹰</div></div><div class="center">'+tag('已结束')+'</div>')+notice('记录员：老周 · 最后同步 10 月 8 日 17:42')+'<div class="segmented"><div class="segment active">比分</div><div class="segment">记录</div><div class="segment">统计</div></div>'+card('<table class="table"><tr><th>球队</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th><th>R</th></tr><tr><td>海浪</td><td>1</td><td>0</td><td>3</td><td>1</td><td>2</td><td><b>7</b></td></tr><tr><td>飞鹰</td><td>0</td><td>2</td><td>0</td><td>1</td><td>2</td><td><b>5</b></td></tr></table>')+section('比赛记录')+card(st('5 局下 · 比赛结束','飞鹰最后一名打者击出内野滚地球，三出局。')+'<div class="divider"></div>'+st('5 局上 · 海浪再得 2 分','查看完整打席与历史垒况 ›'))+btn('预览与导出战报','secondary')+'<div class="note">普通成员可以查看和导出已获授权的比赛。</div>',back='‹ 历史比赛')
    source = Path(__file__).with_name('reference-scoring-2.3.png')
    shutil.copyfile(source, OUT/'screens/07-scoring.png')
    (OUT/'screens/07-scoring.html').write_text('<!doctype html><meta charset="utf-8"><title>现有记分页基准</title><style>body{margin:0;background:#F5F2EA}img{display:block;width:100%;height:auto}</style><img alt="现有 2.3 App 真实截图，原样保留" src="07-scoring.png">')
    SCREENS.append({'id':'07-scoring','title':'现有记分页基准','web':False,'source':str(source)})
    screen('08-handoff','交接记分',card(st('海浪 vs 飞鹰','5 局上 · 海浪 7 : 3 飞鹰')+'<div class="divider"></div>'+notice('本机记录已全部同步'))+section('交给谁继续记分')+choice('老周 · iPhone','记录员 · 设备最近在线',True)+choice('陈教练 · 备用 iPhone','管理员 · 设备最近在线')+card('<div class="h">交接时会发生什么</div><div class="body" style="margin-top:6px">1. 暂停本机记分<br>2. 对方下载最新比赛并确认<br>3. 对方继续，你的设备变为只读</div>','green')+btn('暂停记分并发起交接')+'<div class="note">需要联网完成。若交接结果不确定，先查询进度；收到明确结果后再继续操作。</div>',back='‹ 记分')
    screen('09-receive','接手比赛','<div class="badge-icon">⇄</div><div class="center"><div class="title">陈教练把比赛交给你</div><div class="sub" style="margin-top:6px">青岛海浪 · 当前设备：老周的 iPhone</div></div>'+gamecard('5 局上 · 1 出局','7 : 3')+card('<div class="listrow">'+st('最新记录','第 6 棒 · 一垒有人')+'<span class="check">✓</span></div><div class="listrow">'+st('同步时间','今天 09:40')+'<span class="check">✓</span></div>','flush')+card(st('最新比赛已下载并核对','确认接手后，本场由这台设备继续记分。原设备只能查看。'),'green')+btn('确认接手并继续记分')+btn('暂不接手','secondary')+'<div class="note center">接手完成前不会出现可操作的记分按钮。</div>',back='‹ 比赛')
    screen('10-access','历史直播开放',gamecard()+notice('历史记录已保存')+choice('不开放','关闭网页观看入口，比赛记录仍保留。',True)+choice('仅指定人员','选择队内成员，或单独邀请队外观看者。')+choice('永久开放','拿到单场链接的人都能看，不自动到期；可随时关闭。')+card(st('队内历史查看仍然保留','此设置只控制历史直播网页。已加入球队的成员仍可在 App 内查看比赛与统计。'),'green')+btn('保存设置')+'<div class="note">管理员可管理全队比赛；记录员管理负责场次。进行中的网页直播仍按原来的开播 / 关闭方式控制。</div>',back='‹ 比赛详情',tight=True)
    screen('11-viewers','指定观看者',card(row(st('海浪 vs 飞鹰','历史直播 · 仅指定人员'),tag('已保存')))+section('已授权','2 人')+card(listing('小林家长','队内成员','移除')+listing('王教练','队外观看者 · 仅限本场','移除'),'flush')+section('待确认申请','1 人')+card(row(st('小雨爸爸','队外观看申请'),tag('待确认','orange'))+'<div class="divider"></div><div class="two">'+btn('暂不通过','secondary small')+btn('确认授权','small')+'</div>')+'<div class="two">'+btn('选择队内成员','secondary')+btn('邀请队外观看','secondary')+'</div>'+card(st('转发链接不会转交权限','队外观看者需要你确认。换浏览器或换设备后，需要重新授权。'),'green')+'<div class="note">移除观看者只撤销本场网页观看资格，不删除比赛，也不改变其球队成员身份。</div>',back='‹ 开放设置',tight=True)
    screen('12-request','申请观看','<div class="badge-icon">⌑</div><div class="center"><div class="title">申请观看这场比赛</div><div class="sub" style="margin-top:8px">这是一份单场观看邀请。<br>发送称呼后，等待邀请人确认。</div></div>'+card('<div class="label">你的称呼</div><div class="input">小雨爸爸</div><div class="sub" style="margin-top:10px">只用于邀请人核对，不需要注册账号。</div>')+btn('发送观看申请')+card(st('通过后只开放这一场','不会加入球队，也不能查看球队的其他比赛。'),'green')+'<div class="note">提交后显示「等待邀请人确认」。确认前不展示队名、比分、名单或比赛记录。</div>',back='',web=True)
    screen('13-web','历史直播',row(tag('历史回看'),'<span class="tiny">永久开放 · 示例</span>')+card('<div class="sub center">2026 秋季友谊赛 · 10 月 8 日</div><div class="row" style="margin:14px 0"><div class="h">青岛海浪</div><div class="score">7 : 5</div><div class="h">飞鹰</div></div><div class="center">'+tag('已结束')+'</div>')+'<div class="segmented"><div class="segment active">实况</div><div class="segment">阵容</div><div class="segment">统计</div></div>'+card('<table class="table"><tr><th>球队</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th><th>R</th></tr><tr><td>海浪</td><td>1</td><td>0</td><td>3</td><td>1</td><td>2</td><td>7</td></tr><tr><td>飞鹰</td><td>0</td><td>2</td><td>0</td><td>1</td><td>2</td><td>5</td></tr></table>')+section('比赛实况')+card(st('5 局下 · 三出局，比赛结束','飞鹰第 9 棒击出内野滚地球，游击传一垒刺杀。')+'<div class="divider"></div>'+st('5 局上 · 海浪得到 2 分','二垒安打，两名跑者返回本垒得分。'))+'<div class="note center">最后更新 10 月 8 日 17:42<br>本页只展示这一场比赛，不展示球队档案。</div>',web=True,tight=True)
    screen('14-offline','同步状态',card('<div class="h">本机已保存，等待联网同步</div><div class="sub" style="margin-top:6px">海浪 vs 飞鹰 · 5 局上</div>','orange')+card('<div class="listrow">'+st('待同步记录','2 条')+tag('仅本机','orange')+'</div><div class="listrow">'+st('球队可见的最新状态','今天 09:40 · 5 局上，海浪 7 : 3')+'</div>','flush')+card(st('当前记分设备可以继续记录','重新联网后自动尝试上传。其他成员看到的仍是最后已同步的内容。'),'green')+btn('返回比赛，继续本机记分')+btn('立即重试同步','secondary')+'<div class="note">新设备不能在完全断网时取得记分权。交接需要联网；若管理员已接管，本机记录将保留为待核对草稿。</div>',back='‹ 记分')
    screen('15-conflict','待核对的记录',card(st('这场比赛已由另一台设备接管','你的未同步记录已保留，不会自动覆盖球队正式比赛。'),'orange')+section('球队正式记录')+card(row(st('老周 · 当前记分设备','今天 10:02 · 5 局下'),'<div class="score" style="font-size:24px">7 : 5</div>'))+section('这台设备的待同步草稿')+card(row(st('陈教练 · 2 条未同步','最后本机保存 09:44 · 5 局上'),tag('待核对','orange')))+card(st('由管理员核对并处理','比较两份记录，确认需要补录的内容；处理完成前保留原草稿。'),'green')+btn('比较两份记录')+btn('稍后处理，保留草稿','secondary')+'<div class="note">示例为管理员视角。记录员只看到自己的草稿，并可联系管理员处理。</div>',back='‹ 比赛')
    screen('16-devices','我的设备',card(st('陈教练 · 青岛海浪','管理员 · 设备仅对所选球队授权'))+section('已授权设备','2 台')+card(listing('我的 iPhone','当前设备 · 今天 09:40', '当前')+listing('备用 iPhone','最近使用 10 月 6 日','移除'),'flush')+btn('为新设备授权')+section('管理恢复')+card(listing('球队管理恢复卡','已保存 · 建议放在手机以外的位置'),'flush')+card(st('旧设备丢失时','先由其他管理员移除旧设备，再邀请新设备；所有管理设备都不可用时，使用管理恢复卡。'),'green')+'<div class="note">设备授权和比赛记分权分开。新设备获准加入球队后，接手进行中的比赛仍需要交接。</div>',back='‹ 我的')
    screen('17-pair','确认新设备','<div class="badge-icon">⇄</div><div class="center"><div class="title">这是你要授权的新设备吗</div><div class="sub" style="margin-top:8px">请在自己的两台设备上核对配对短语。</div></div>'+card('<div class="center"><div class="tiny">两台设备应显示相同短语</div><div class="title" style="margin:13px 0">松树 · 球场 · 海风</div><div class="sub">新 iPhone · 本次请求剩余 08:42</div></div>')+card('<div class="listrow">'+st('授权的球队','青岛海浪')+'<span class="check">✓</span></div><div class="listrow">'+st('关联身份','陈教练 · 管理员')+'</div>','flush')+btn('确认授权这台设备')+btn('不是我的设备','secondary')+'<div class="note">示例短语不可用于实际配对。授权后可撤销旧设备；其他球队不会自动转移到新设备。</div>',back='取消')
    screen('18-install','安装后加入球队','<div class="center"><img src="'+ICON_URI+'" style="width:70px;height:70px;border-radius:16px;margin:4px 0 14px"><div class="title">先安装棒球比赛记录大师</div><div class="sub" style="margin-top:8px">你收到了一份球队邀请。<br>安装 iOS App 后，返回此页继续加入。</div></div>'+card(st('简单三步','1. 前往 App Store 安装<br>2. 回到此邀请页面或重新扫码<br>3. 在 App 内确认球队、填写称呼'),'green')+btn('前往 App Store 下载')+btn('已安装，打开 App','secondary')+'<div class="note center">无需注册账号<br>若邀请过期，请邀请人重新发送。</div>'+card(st('安装完成后记得回来','本页保留加入入口。仅安装 App 不会自动加入球队。'),'compact'),web=True)
    (OUT/'screens.json').write_text(json.dumps(SCREENS,ensure_ascii=False,indent=2))
    selected=['01-team','04-join','05-member','08-handoff','10-access','11-viewers']
    items={s['id']:s for s in SCREENS}
    tiles=''.join(f'<figure><img src="screens/{id}.png"><figcaption>{i+1:02d}　{items[id]["title"]}</figcaption></figure>' for i,id in enumerate(selected))
    overview=f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>3.0 关键页面预览</title><style>*{{box-sizing:border-box}}body{{margin:0;background:#f5f2ea;padding:44px 48px;color:#102a43;font-family:-apple-system,'PingFang SC',sans-serif}}header{{display:flex;justify-content:space-between;align-items:end;margin-bottom:30px}}h1{{font-size:34px;margin:0 0 10px}}p{{margin:0;font-size:16px;color:#607284}}.mark{{font-size:13px;color:#217a57;background:#e4f2ea;padding:9px 14px;border-radius:20px}}.grid{{display:grid;grid-template-columns:repeat(6,1fr);gap:24px}}figure{{margin:0}}img{{width:100%;border-radius:24px;border:5px solid #153c36;box-shadow:0 10px 28px #092a2a18}}figcaption{{font-size:17px;font-weight:600;margin-top:16px}}footer{{margin-top:32px;font-size:14px;color:#607284}}</style><header><div><h1>3.0 球队协作 · 关键页面预览</h1><p>沿用现有记分习惯，增加球队身份、邀请、交接与历史观看设置。</p></div><div class="mark">界面提案 v2 · 按反馈修订</div></header><div class="grid">{tiles}</div><footer>2026-10-09　·　合成示例数据　·　共享页面为设计稿，业务功能尚未实现　·　iPad / Duo / Android 不在本轮范围</footer></html>'''
    (OUT/'overview.html').write_text(overview)
    gallery=''.join(f'<a href="screens/{s["id"]}.png"><figure><img src="screens/{s["id"]}.png"><figcaption>{s["id"]} · {s["title"]}</figcaption></figure></a>' for s in SCREENS)
    (OUT/'index.html').write_text(f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>BaseballMaster 3.0 界面提案</title><style>body{{background:#f5f2ea;color:#102a43;margin:40px;font-family:-apple-system,'PingFang SC',sans-serif}}.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:25px}}figure{{margin:0}}img{{width:100%;border-radius:20px;border:1px solid #dadfd9}}a{{color:inherit;text-decoration:none}}figcaption{{padding:12px 0}}p{{line-height:1.7;color:#607284}}</style><h1>BaseballMaster 3.0 · 界面与流程提案</h1><p>页面与流程提案 v2，按用户反馈修订。07 为现有 App 真实截图原样复用，其余为静态设计；全部为合成数据。<br><a href="BaseballMaster-3.0-页面与使用流程.pdf">打开完整 PDF</a> · 2026-10-09 · v2</p><div class="grid">{gallery}</div></html>''')

FLOWS = [
 {'n':'01','title':'开启球队共享','sub':'管理员只需选一次资料，球队就有了共同的档案。','screens':['01-team','02-enable'],'captions':['共享后的球队管理入口','已有本机球队开启共享'],
  'role':'管理员','steps':[('从「球队」进入','已有球队选择「开启球队共享」；新建球队可以选择本机使用或开启共享。'),('确认要共享的资料','选择名单、历史比赛与赛程；下一步核对球员对应和重复比赛，再确认上传。'),('保存恢复卡，邀请队友','完成后保存管理恢复卡；也可明确选择稍后保存，球队页持续提醒。')],
  'note':'球队名称相同不会自动合并。受邀加入的是同一份球队档案；其他本机球队与未选比赛不上传。','focus':'已确认：共享入口放在「球队」，保留纯本机使用方式。'},
 {'n':'02','title':'邀请加入，无需注册','sub':'管理员选角色，对方核对球队、填称呼即可加入。','screens':['03-invite','04-join'],'captions':['管理员选择邀请角色','收到邀请后的确认页'],
  'role':'管理员 → 新成员','steps':[('选择普通成员或记录员','普通成员查看比赛和统计；记录员可查看全队内容，与管理员查看范围一致，但没有球队管理权限。'),('发出一次性邀请','生成链接或二维码后，通过现有聊天工具分享。邀请默认 24 小时有效，可撤销。'),('对方确认加入','已有 App 打开确认页；未安装者看到 iOS 下载引导，前往 App Store 安装后回到邀请。无需注册。')],
  'note':'昵称只是方便辨认。管理员资格在成员列表中另行授予，不能靠一条通用邀请直接获得。','focus':'已确认：固定三种角色，不让用户逐项勾选复杂权限。'},
 {'n':'03','title':'成员直接回看球队历史','sub':'家长、球员和教练加入后，打开比赛列表就能看。','screens':['05-member','06-detail'],'captions':['普通成员的历史比赛列表','普通成员查看单场比赛'],
  'role':'普通成员','steps':[('打开「比赛」选择球队','查看未来、进行中和历史比赛。普通成员不会看到开始记分、改名单等编辑入口。'),('进入比赛看记录和统计','查看逐局比分、打席过程、历史垒况和 Box Score；球员累计表现仍在「统计」。'),('需要时导出战报','可导出有权查看的 PDF / TXT。成员看的是同一份正式档案，不需要记录员逐场发送。')],
  'note':'页面显示最后同步时间。记录员断网时，其他成员看到最后已同步的版本，不把缓存当作实时赛况。','focus':'已确认：延续「比赛 / 球队 / 统计 / 我的」四栏导航。'},
 {'n':'04','title':'换记录员，交接后继续','sub':'同一场比赛由一台设备记分，避免两台手机互相覆盖。','screens':['08-handoff','09-receive'],'captions':['原设备发起交接','接收设备确认后继续'],
  'role':'当前记录员 → 接手记录员','steps':[('从记分页点「交接」','先把本机记录全部同步，选择有记录权限的成员及其设备。'),('暂停录入，发送交接','原设备暂停记分。新设备取得最新局面并核对；传输途中不让两端同时记分。'),('接手方确认继续','服务端确认交接成功后，新设备继续记分，原设备转为只读。')],
  'note':'不同记录员可以同时记录不同比赛。若原设备失联，由管理员明确确认后接管，旧设备的离线内容保留待核对。','focus':'已确认：普通交接需要双方确认，不靠断线超时自动换人。'},
 {'n':'05','title':'历史直播，保存与开放分开','sub':'终场后记录保留；让谁看，由管理员或负责记录员决定。','screens':['10-access','11-viewers'],'captions':['三种开放方式，默认不开放','指定人员的授权管理'],
  'role':'管理员 / 本场负责记录员','steps':[('进入「历史直播开放」','比赛详情提供入口。管理员管理全队，记录员管理自己负责或已接手的比赛。'),('选开放方式并保存','不开放：网页关闭；仅指定人员：逐人授权；永久开放：持链接可看，无自动到期。'),('随时调整观看范围','可以移除某位观看者或关闭全部观看。关闭只改变权限，历史比赛和统计仍保留。')],
  'note':'三种设置只控制终场后的历史回看。进行中的实时直播仍按原方式开播、关闭及持链接观看；队内成员可在 App 查看历史。','focus':'已确认：网页「不开放」，不等于禁止队内成员在 App 查看。'},
 {'n':'06','title':'队外亲友，只看被邀请的单场','sub':'指定观看需要确认；永久开放则可直接打开链接。','screens':['12-request','13-web'],'captions':['指定观看：队外申请页','永久开放：单场历史回看'],
  'role':'队外观看者','steps':[('指定观看：打开单次邀请','填写称呼并发送申请；未获授权前不展示队名、比分和名单。无需安装 App 或注册。'),('邀请人核对后授权','确认后在当前浏览器查看该场。普通转发链接不会赋予新浏览器观看权，换设备需重新授权。'),('永久开放：链接直接回看','拿到链接即可看这场比分、实况、阵容与统计。不会因此加入球队或看到其他比赛。')],
  'note':'关闭或收紧权限后，后续联网访问按新设置执行。已经保存的截图和导出文件无法远程收回。','focus':'已确认：仅指定人员多一步核对，换来可逐人撤销的访问权限。'},
 {'n':'07','title':'断网能记，冲突有地方处理','sub':'让用户分清「本机已保存」和「全队已同步」。','screens':['14-offline','15-conflict'],'captions':['当前记录设备断网','管理员处理接管后的离线草稿'],
  'role':'记录员 / 管理员','steps':[('断网后先保存在本机','当前记录设备继续记分，界面提示待同步数量和全队最后可见的状态。'),('恢复网络后继续同步','服务器接收后才显示已同步。首次加入、接手已有比赛和交接都需要联网。'),('被接管的旧设备重新上线','保留未同步草稿；管理员比较两份记录，再决定补录或舍弃，不自动覆盖正式比赛。')],
  'note':'完全断网时新建的比赛先作为个人本机草稿，联网后再选择球队发布，并检查是否重复。','focus':'已确认：状态用普通语言说明，不把技术错误码直接给用户。'},
 {'n':'08','title':'换手机，延续身份和球队','sub':'免注册也能迁移设备，并在设备丢失后撤销授权。','screens':['16-devices','17-pair'],'captions':['我的设备与管理恢复入口','旧设备确认新设备授权'],
  'role':'所有成员；管理恢复仅管理员','steps':[('新手机发起设备配对','新设备展示一次性配对请求，用旧设备扫码或打开配对链接。'),('旧设备核对并授权','核对两端短语，选择球队后确认。配对默认 10 分钟有效，只能使用一次。'),('按需撤销旧设备','旧设备丢失时请其他管理员重新邀请；所有管理设备不可用时，用管理恢复卡恢复。')],
  'note':'使用管理恢复卡会撤销旧授权并关闭历史直播，比赛档案保留；成员需重新授权。恢复前必须明确提示。','focus':'已确认：使用配对短语；不使用 iCloud 或 Apple 账号。'}
]

def build_pdf():
    from reportlab.pdfgen import canvas
    from reportlab.lib.colors import HexColor, Color
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.platypus import Paragraph
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.lib.enums import TA_LEFT
    from reportlab.lib.utils import ImageReader
    font='/System/Library/Fonts/Supplemental/Arial Unicode.ttf'
    pdfmetrics.registerFont(TTFont('CJK',font))
    pdfmetrics.registerFont(TTFont('Arial','/System/Library/Fonts/Supplemental/Arial.ttf'))
    pdfmetrics.registerFont(TTFont('ArialBold','/System/Library/Fonts/Supplemental/Arial Bold.ttf'))
    W,H=841.89,595.28
    dest=OUT/'BaseballMaster-3.0-页面与使用流程.pdf'
    c=canvas.Canvas(str(dest),pagesize=(W,H),pageCompression=1)
    c.setTitle('BaseballMaster 3.0 页面与使用流程 · 界面提案 v2')
    c.setAuthor('BaseballMaster')
    ink='#102A43'; green='#217A57'; muted='#607284'; bg='#F5F2EA'; white='#FFFEFA'
    def rect(x,y,w,h,fill,r=0,stroke=None):
        c.setFillColor(HexColor(fill))
        c.setStrokeColor(HexColor(stroke or fill))
        c.roundRect(x,H-y-h,w,h,r,fill=1,stroke=int(stroke is not None))
    def text(t,x,y,size=11,color=ink,font='CJK'):
        c.setFillColor(HexColor(color));c.setFont(font,size);c.drawString(x,H-y-size,t)
    def para(t,x,y,w,size=10.5,color=ink,leading=None,measure=False):
        style=ParagraphStyle('body',fontName='CJK',fontSize=size,leading=leading or size*1.65,textColor=HexColor(color),wordWrap='CJK',spaceAfter=0)
        p=Paragraph(t,style); aw,ah=p.wrap(w,H)
        if not measure: p.drawOn(c,x,H-y-ah)
        return ah
    def footer(page):
        c.setStrokeColor(HexColor('#DADFD9'));c.line(30,31,W-30,31)
        text('BASEBALLMASTER  /  3.0 界面提案 v2  /  合成数据，业务未实现',30,H-24,7.5,muted)
        text(f'2026-10-09     {page:02d} / 12',W-145,H-24,7.5,muted)
    def phone(id,x,y,w=199):
        image_path=OUT/'screens'/f'{id}.png'
        iw,ih=ImageReader(str(image_path)).getSize()
        h=w*ih/iw
        rect(x-3,y-3,w+6,h+6,'#133B34',r=21)
        c.saveState()
        p=c.beginPath();p.roundRect(x,H-y-h,w,h,18);c.clipPath(p,stroke=0,fill=0)
        c.drawImage(str(OUT/'screens'/f'{id}.png'),x,H-y-h,width=w,height=h,mask='auto')
        c.restoreState()
    def brand(x,y):
        c.drawImage(str(ICON),x,H-y-34,width=34,height=34,mask='auto')
        c.setFillColor(HexColor('#FFFFFF'))
        word=c.beginText(x+44,H-y-18);word.setCharSpace(-.72)
        word.setFont('ArialBold',16);word.textOut('BASEBALL')
        word.setFont('Arial',16);word.textOut('MASTER');c.drawText(word)
        text('棒球比赛记录大师',x+44,y+23,8.5,'#C6D5CC')

    # Cover: a compact product story with three representative screens.
    rect(0,0,W,H,bg)
    rect(0,0,310,H,'#092A2A')
    brand(29,31)
    text('3.0',29,112,68,'#FFFFFF','Helvetica-Bold')
    text('球队协作',30,199,29,'#FFFFFF')
    text('页面与使用流程',30,244,21,'#FFFFFF')
    para('同一支球队，多位记录员。<br/>受邀加入，持续积累比赛档案。',31,300,244,13,'#D6E7DD',22)
    for i,t in enumerate(['01  开启共享与邀请加入','02  队内回看与记录交接','03  历史开放与设备恢复']):
        text(t,31,378+i*29,11,'#D6E7DD')
    rect(30,488,248,51,'#16443B',10)
    para('界面提案 v2 · 已记录反馈<br/>静态预览，示例邀请与配对均不可用。',43,500,222,9.5,'#D6E7DD',15)
    text('已确认流程，修正页面范围',339,32,21)
    para('记分页使用现有 App 原图，共享功能在此基础上增加。',339,69,460,11,muted)
    phone('01-team',342,122,143)
    phone('07-scoring',499,122,143)
    phone('10-access',656,122,143)
    text('球队共享',377,444,10)
    text('现有记分页基准',521,444,10)
    text('历史观看',694,444,10)
    rect(337,484,468,56,white,12)
    para('阅读方式：每页左侧看页面，右侧看操作步骤。<br/>第 2 页说明记分页保留范围；第 5 页补充 iOS 下载引导。',353,496,438,10,muted,17)
    text('2026-10-09  /  01',31,561,8,'#B3CDBF')
    c.showPage()
    # Page 2: unchanged existing scoring screen with proposed additions outside it.
    rect(0,0,W,H,bg)
    text('修订',30,22,12,green)
    text('记分页保留现状，不重新布局',78,17,23)
    text('v1 的重绘示意已撤下。左侧是现有 2.3 App 真实截图，原图没有修改。',30,54,10.5,muted)
    phone('07-scoring',39,99,195)
    text('现有 App 记分页 · 合成比赛',39,531,9,muted)
    x=274
    rect(x,100,532,94,white,12,stroke='#DADFD9')
    text('沿用现有页面',x+16,111,14,green)
    para('比分栏、球场比例、投打信息，以及坏球 / 看振 / 挥空 / 界外、击球、跑垒、判罚、撤销、修正、阵容的位置保持现有设计。',x+16,140,500,11,ink,18)
    rect(x,212,532,157,white,12,stroke='#DADFD9')
    text('共享比赛只补充必要入口',x+16,225,14,green)
    para('建议在导航标题区用短文字显示「已同步」或「待同步」，点击进入「同步与交接」面板。同步明细和交接操作放到二级页面，不在球场上方增加整块卡片。',x+16,255,500,11,ink,18)
    para('现有直播、纠错、海报和比赛结果功能继续保留；具体入口位置在现有页面上做最小改动。',x+16,321,500,10,muted,17)
    rect(x,388,532,82,'#E4F2EA',12)
    text('纯本机模式保持原样',x+16,400,13,green)
    para('没有开启共享的比赛，不显示球队同步和交接入口。这里先固定改动边界，不把新的球场或按钮排版带进 3.0。',x+16,426,500,10.5,ink,17)
    para('新增状态位置仍是小范围界面建议；现有 App 业务代码未改动。',x,495,532,10,muted)
    footer(2);c.showPage()
    for flow_index,f in enumerate(FLOWS):
        page=flow_index+3+(1 if flow_index>=2 else 0)
        rect(0,0,W,H,bg)
        text(f['n'],30,22,13,green,'Helvetica-Bold')
        text(f['title'],61,17,23)
        text(f['sub'],30,53,10.5,muted)
        for j,id in enumerate(f['screens']):
            x=34+j*213
            phone(id,x,101,190)
            text(f['captions'][j],x,523,9.5,muted)
        x=463; width=344
        rect(x,99,width,29,'#E4F2EA',8)
        text(f['role'],x+12,105,10,green)
        y=145
        for i,(h,b) in enumerate(f['steps'],1):
            rect(x,y+1,22,22,'#217A57',11)
            text(str(i),x+8,y+4,10,'#FFFFFF','Helvetica-Bold')
            text(h,x+33,y,12)
            ph=para(b,x+33,y+23,width-35,10.5,muted,17)
            y+=max(79,ph+42)
        y=max(y+2,405)
        nh=para(f['note'],x+13,y+11,width-26,9.5,ink,15.5,measure=True)
        rect(x,y,width,nh+24,white,10,stroke='#DADFD9')
        para(f['note'],x+13,y+11,width-26,9.5,ink,15.5)
        para(f['focus'],x,y+nh+35,width,9.5,green,15)
        footer(page);c.showPage()
        if flow_index == 1:
            rect(0,0,W,H,bg)
            text('补充',30,22,12,green)
            text('未安装 App，先引导 iOS 下载',78,17,23)
            text('扫码打开邀请网页，安装后回到邀请继续，不要求先注册。',30,54,10.5,muted)
            phone('18-install',34,101,190)
            phone('04-join',247,101,190)
            text('邀请网页：下载与打开 App',34,523,9.5,muted)
            text('安装后：确认加入球队',247,523,9.5,muted)
            x=463
            rect(x,99,344,29,'#E4F2EA',8);text('未安装 App 的受邀用户',x+12,105,10,green)
            y=145
            for i,(head,body) in enumerate([
                ('扫码打开邀请页','提供「前往 App Store 下载」和「已安装，打开 App」两个明确入口。'),
                ('下载安装 iOS App','下载按钮指向项目现有 App Store 页面，用户自行完成安装。'),
                ('回到邀请，确认加入','从原聊天链接或重新扫码回来，打开 App 核对球队、角色并填写称呼。')],1):
                rect(x,y+1,22,22,green,11);text(str(i),x+8,y+4,10,'#FFFFFF','Helvetica-Bold')
                text(head,x+33,y,12);para(body,x+33,y+23,309,10.5,muted,17);y+=84
            rect(x,411,344,73,white,10,stroke='#DADFD9')
            para('不承诺 App Store 安装过程自动保留邀请。邀请过期时，提示联系邀请人重新发送。仅安装 App 不会自动加入球队。',x+13,422,318,10,ink,17)
            para('已确认：对方没安装时，扫码后提示安装下载（iOS）。',x,500,344,9.5,green,15)
            footer(5);c.showPage()

    rect(0,0,W,H,bg)
    text('09',30,22,13,green,'Helvetica-Bold')
    text('已确认内容与本次修订',61,17,23)
    text('已确认的流程继续执行。本次纠正记分页改动，并补充角色说明与 iOS 下载引导。',30,54,10.5,muted)
    roles=[('管理员','管理球队、成员与设备；分配或接管比赛；管理全队历史开放。'),('记录员','查看全队内容，与管理员一致；可记分和处理负责场次，没有球队管理权限。'),('普通成员','查看队内历史与统计；导出有权查看的战报；没有记分和管理入口。')]
    for i,(r,b) in enumerate(roles):
        x=30+i*264
        rect(x,95,251,99,white,12,stroke='#DADFD9')
        text(r,x+15,107,14,green)
        para(b,x+15,136,221,10.5,muted,17)
    checks=[
        ('入口与现有记分页','四栏导航与球队共享入口已确认。记分页沿用现状，只补充同步状态与交接入口。'),
        ('观看权限','成员可看队内历史。实时直播照旧；终场后回看再选择不开放、指定人员或永久开放。'),
        ('交接规则','同一场一次只允许一台设备记分。正常交接双方确认；失联接管和离线草稿由管理员处理。'),
        ('免注册与设备授权','日常换手机走设备配对；无旧设备请管理员邀请；全部管理设备丢失时使用恢复卡。')
    ]
    y=216
    for i,(h,b) in enumerate(checks,1):
        rect(31,y+3,18,18,'#E4F2EA',4)
        text(str(i),36,y+5,9,green,'Helvetica-Bold')
        text(h,62,y,12)
        para(b,259,y,539,10.5,muted,17)
        y+=62
    rect(30,478,782,60,'#E4F2EA',12)
    para('本轮修订：撤下重绘记分页，补充 iOS 下载引导；实时直播照旧，终场回看使用新权限。<br/>范围保持：免注册、自有服务器、不使用 iCloud；iPad / Duo 适配、小组件及 3.1 Android 不纳入本轮。',44,492,754,10,green,17)
    footer(12);c.save()
    print(dest)

if __name__=='__main__':
    author_screens()
    subprocess.run(['/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node',str(Path(__file__).with_name('render-review.cjs'))],check=True)
    build_pdf()
