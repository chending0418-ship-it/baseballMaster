from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import Paragraph
from reportlab.lib.colors import HexColor

pdfmetrics.registerFont(TTFont('Chinese', '/System/Library/Fonts/Supplemental/Arial Unicode.ttf'))
out = Path('output/pdf/BaseballMaster-3.0-更新与使用说明.pdf')
out.parent.mkdir(parents=True, exist_ok=True)
c = canvas.Canvas(str(out), pagesize=(595.28, 841.89))
c.setTitle('BaseballMaster 3.0 更新与使用说明')
c.setAuthor('BaseballMaster')
W, H = 595.28, 841.89
TOTAL_PAGES = 3

def para(text, x, y, w, size=11, color='#345047'):
    style = ParagraphStyle('body', fontName='Chinese', fontSize=size, leading=size*1.65,
                           textColor=HexColor(color), wordWrap='CJK')
    p = Paragraph(text, style)
    _, h = p.wrap(w, H)
    p.drawOn(c, x, y-h)
    return y-h

def page(number, title, subtitle):
    c.setFillColor(HexColor('#f5f6ef')); c.rect(0, 0, W, H, fill=1, stroke=0)
    c.setFillColor(HexColor('#092a2a')); c.rect(0, H-110, W, 110, fill=1, stroke=0)
    c.drawImage('BaseballMaster/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png',36,H-69,36,36,mask='auto')
    c.setFillColor(HexColor('#ffffff')); c.setFont('Helvetica-Bold',14); c.drawString(84,H-48,'BASEBALL')
    width=pdfmetrics.stringWidth('BASEBALL','Helvetica-Bold',14)
    c.setFont('Helvetica',14); c.drawString(84+width,H-48,'MASTER')
    c.setFont('Chinese',8); c.drawString(84,H-64,'棒球比赛记录大师')
    c.setFont('Helvetica',12); c.drawRightString(W-36,H-50,'3.0')
    c.setFillColor(HexColor('#092a2a')); c.setFont('Chinese',24); c.drawString(36,H-150,title)
    para(subtitle,36,H-169,W-72,10)
    c.setStrokeColor(HexColor('#ccd6ce')); c.line(36,48,W-36,48)
    c.setFont('Helvetica',8); c.setFillColor(HexColor('#63776e'))
    c.drawString(36,32,'baseballmaster.cc'); c.drawRightString(W-36,32,f'{number} / {TOTAL_PAGES}')

def card(y, number, title, detail):
    c.setFillColor(HexColor('#1d795b')); c.roundRect(36,y-28,28,28,8,fill=1,stroke=0)
    c.setFillColor(HexColor('#ffffff')); c.setFont('Helvetica-Bold',12); c.drawCentredString(50,y-19,str(number))
    end=para(title,78,y,475,14,'#092a2a')
    return para(detail,78,end-4,475,10.5)-18

page(1,'同一支球队，一起记，一起看。','3.0 简要更新说明 · 保留「比赛 / 球队 / 统计 / 我的」四栏与现有记分方式。')
items=[
('球队共享，无需注册','在「球队」创建或加入共享球队。管理员和记录员都可新增、修改球员姓名和背号，联网后同步；也可以继续只在本机使用。'),
('比赛同步，双方确认交接','不同记录员可负责不同比赛。同一场由一台设备记分，交出方同步后发起交接，接手方核对确认再继续。'),
('实时直播照旧，历史观看自选','有效直播链接仍持链接可看。历史回看另选永久开放、仅指定人员或不开放；没有授权的人不能看受限历史。关闭网页仍保留队内 App 查看。'),
('按年度查看，也能导入旧比赛','不用主动分赛季，按比赛实际日期归入年度。管理员可把本机已结束比赛导入球队，先核对重复记录与球员对应。'),
('补填时间、背号与换投确认','开赛时可选过去时间，终场后可更正比赛日期；阵容选人同时显示背号。换投先确认，登板局数限制和实际投球统计分别计算。'),
('换设备与完整档案','支持新旧设备配对、管理恢复卡、全队档案导出和校验。未同步的原记录保留供核对；完整档案可导入为本机只读资料。')]
y=H-216
for i,(title,detail) in enumerate(items,1): y=card(y,i,title,detail)
assert y>61,y
c.showPage()
page(2,'几个常用操作，从哪里开始？','按下面的入口操作即可。指定观看者需要核对同意，普通转发链接不授予权限。')
flows=[
('开始共享','球队 → 球队共享 → 新建共享球队 / 通过邀请加入','每队最多两名管理员，负责成员与角色管理；记录员也可新增、修改球员并记录负责场次；成员只读。未安装 App 时，邀请页提示 iOS 下载。'),
('交给另一位记录员','共享球队 → 球队比赛与历史 → 某场比赛 → 交接','先同步，再由接手设备核对确认。断线不会自动换人，接管和处理草稿会保留记录。'),
('设置历史回看','共享比赛 → 历史网页观看 → 选择开放方式','永久开放：持链接可看。仅指定人员：申请后核对授权，可逐人撤销。不开放：关闭历史网页，队内 App 仍可查看。'),
('换手机、找回管理权','球队共享 → 接入这台新设备 / 使用恢复卡找回管理权','原手机可用时优先配对，平时提前保存管理恢复卡。用恢复卡接回球队后，旧设备与分享须重新授权。'),
('导出并保存全队资料','共享球队 → 导出球队完整档案','在「我的 → 备份与恢复 → 球队完整档案」校验并导入。文件未加密，请妥善保管；不含设备授权、恢复码或观看权限。')]
y=H-216
for i,(title,route,detail) in enumerate(flows,1): y=card(y,i,title,route+'<br/>'+detail)
assert y>100,y
para('网站与协作服务已部署；App 尚未提交审核。上线材料包由你在 Xcode 和 App Store Connect 完成提交，商店可下载版本以实际发布为准。',36,88,W-72,9,'#63776e')
c.showPage()

page(3,'升级后，怎样成为管理员？','已有本机球队和比赛时，按下面操作。创建共享球队后，你会自动成为第一位管理员。')
upgrade_steps = [
('打开共享入口','打开「球队 → 球队共享 → 新建共享球队」。'),
('选择原来的球队','在「选择球队」中，选择你原来使用的本机球队。'),
('填写队内称呼','填写你在队内的称呼，例如「陈教练」。'),
('核对并创建','核对球队名称和球员姓名、背号，点击「确认共享范围 → 创建并共享」。'),
('保存管理恢复卡','保存随后显示的管理恢复卡，以后管理设备丢失时可以用它恢复权限。'),
]
y=H-216
for i,(title,detail) in enumerate(upgrade_steps,1):
    y=card(y,i,title,detail)
y=para('已有比赛需要再导入一次',36,y,W-72,14,'#092a2a')-6
y=para('进入这支共享球队的「球队比赛与历史 → 导入历史比赛」，<br/>选择原本机球队和已结束的比赛，核对球员对应关系，预览后确认导入。<br/>系统会先备份；原本机比赛保留，导入后队内成员就能查看，历史网页默认不开放。',36,y,W-72,10.5)-14
y=para('之后你可以邀请其他人加入，并在「成员管理」中设置第二位管理员，每队最多两位。',36,y,W-72,10.5)-14
y=para('如果队友已经创建了这支共享球队，就通过他的邀请加入，<br/>再由他将你设为管理员，避免各自创建成两支独立球队。',36,y,W-72,10.5)
assert y>64,y
c.save()
print(out)
