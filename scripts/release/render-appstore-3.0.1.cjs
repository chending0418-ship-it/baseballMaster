const fs = require('fs');
const path = require('path');
const {createHash} = require('crypto');
const {createRequire} = require('module');
const req = createRequire((process.env.CODEX_ARTIFACT_NODE_MODULES || process.env.HOME + '/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules') + '/package.json');
const {chromium} = req('playwright');
const sharp = req('sharp');
const out = path.resolve(__dirname, '../../output/releases/3.0.1/app-store');
const data = [
 ["01","现场记分","每一球","依旧清楚记录","熟悉的球场、比分与逐球操作继续保留","现场记分","普通棒球 · 教练投手 · 成人慢垒"],
 ["02","固定球队邀请","一条邀请链接","邀请多位成员","同队链接保持不变，管理员可手动更换","球队邀请","无需注册 · 新旧邀请都可加入"],
 ["03","记录交接","换一位记录员","接着这一场","双方核对确认，再从球队最新记录继续","记分交接","一场一台设备记分 · 断线不自动换人"],
 ["04","共享未来比赛","下一场安排","队员提前知道","比赛设置中选择共享，成员就能查看安排","未来比赛","清楚区分本机球队和共享球队"],
 ["05","赛后共享","本场比赛结束","分享给球队成员","在比赛结果中选择球队，确认后即可共享","赛后共享","本机比赛保留 · 两边修改和删除互不影响"],
 ["06","年度统计","一年的比赛","自动放在一起","按比赛日期归入年度，无需手动划分赛季","年度统计","球队与个人表现 · PDF 与 TXT 报告"]
].map(([id,name,line1,line2,detail,tag,foot])=>({id,name,line1,line2,detail,tag,foot}));
function html(item,w,h) {
 const s=w/1320;
 return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>
 *{box-sizing:border-box}html,body{margin:0;width:${w}px;height:${h}px;overflow:hidden;background:#092a2a}body{font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif;-webkit-font-smoothing:antialiased;color:white}
 .canvas{position:relative;width:1320px;height:${h/s}px;transform:scale(${s});transform-origin:top left;background:radial-gradient(ellipse at 90% 68%,#1c535040 0,transparent 57%),#092a2a;overflow:hidden}
 .field{position:absolute;left:550px;top:1850px;width:1300px;height:1300px;border:2px solid #8fbaad19;border-radius:50%}.field:before{content:'';position:absolute;width:880px;height:880px;left:207px;top:207px;transform:rotate(45deg);border:2px solid #8fbaad16;border-radius:30px}
 .brand{position:absolute;left:96px;top:82px;display:flex;align-items:center;gap:20.57px}.brand img{width:72px;height:72px;border-radius:17.14px}.wordmark{font-family:Arial,sans-serif;font-size:29.14px;letter-spacing:-.045em;font-weight:800;line-height:1}.wordmark span{font-weight:400}.wordmark small{display:block;margin-top:8.57px;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif;font-size:18.86px;font-weight:450;letter-spacing:.2em;color:#c6d5cc}
 .number{position:absolute;right:100px;top:93px;color:#a4c1b5;font-size:27px;letter-spacing:5px;font-weight:500}
 .tag{position:absolute;left:99px;top:238px;font-size:32px;color:#b6d8b0;font-weight:600;letter-spacing:3px;display:flex;align-items:center;gap:17px}.tag:before{content:'';display:block;width:9px;height:28px;border-radius:6px;background:#bdde92}
 h1{position:absolute;left:93px;top:300px;margin:0;font-size:106px;line-height:1.23;font-weight:650;letter-spacing:-4px}h1 span{display:block;color:#c5e69b}
 .detail{position:absolute;left:99px;top:615px;margin:0;font-size:36px;line-height:1.45;letter-spacing:0;color:#d0e0d7}
 .phone{position:absolute;left:202px;top:770px;width:916px;padding:13px;background:#183733;border:2px solid #658276;border-radius:88px;box-shadow:0 26px 80px #00151480}.phone img{display:block;width:886px;height:auto;border-radius:74px}
 .footer{position:absolute;bottom:73px;width:100%;text-align:center;color:#a6c4b6;font-size:28px;letter-spacing:1px}
 </style><div class="canvas"><div class="field"></div><div class="brand"><img src="../source/app-icon.png"><div class="wordmark">BASEBALL<span>MASTER</span><small>棒球比赛记录大师</small></div></div><div class="number">${item.id} / ${String(data.length).padStart(2,'0')}</div><div class="tag">${item.tag}</div><h1>${item.line1}<span>${item.line2}</span></h1><p class="detail">${item.detail}</p><div class="phone"><img src="../raw/${item.id}.png" alt="真实 App 界面：${item.name}"></div><div class="footer">${item.foot}</div></div></html>`;
}
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
 fs.mkdirSync(out,{recursive:true});for(const d of ['iphone-6.3','iphone-6.9','iphone-6.5','source'])fs.mkdirSync(path.join(out,d),{recursive:true});
 fs.copyFileSync(path.resolve(__dirname,'../../BaseballMaster/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png'),path.join(out,'source/app-icon.png'));
 const manifest=[];
 for (const [dir,w,h] of [['iphone-6.3',1206,2622],['iphone-6.9',1320,2868],['iphone-6.5',1242,2688]]) {
  const page=await browser.newPage({viewport:{width:w,height:h},deviceScaleFactor:1});
  for(const item of data){
   const stem=`${item.id}-${item.name}`;
   const htmlPath=path.join(out,dir,stem+'.html');
   fs.writeFileSync(htmlPath,html(item,w,h));
   await page.goto('file://'+htmlPath);await page.evaluate(()=>document.fonts.ready);
   const broken=await page.locator('img').evaluateAll(imgs=>imgs.filter(i=>!i.complete||!i.naturalWidth).length);
   if(broken)throw new Error('Missing image for '+stem);
   const bounds=await page.locator('.phone').boundingBox();
   if(bounds.y+bounds.height>h-100*(w/1320))throw new Error('Device overlaps footer '+stem);
   const dest=path.join(out,dir,stem+'.png');await page.screenshot({path:dest});
   const meta=await sharp(dest).metadata();if(meta.width!==w||meta.height!==h||meta.hasAlpha)throw new Error('Invalid dimensions/alpha '+dest);
   manifest.push({file:dir+'/'+stem+'.png',width:w,height:h,alpha:meta.hasAlpha,source:'raw/'+item.id+'.png',sha256:createHash('sha256').update(fs.readFileSync(dest)).digest('hex')});
  }
  await page.close();
 }
 const gallery=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>BaseballMaster 3.0.1 · App Store 宣传图</title><style>*{box-sizing:border-box}body{margin:0;padding:42px;background:#e8eee8;color:#092a2a;font-family:-apple-system,'PingFang SC',sans-serif}h1{font-size:30px;margin:0 0 10px}p{font-size:16px;margin:0 0 32px;color:#4c675f}.grid{display:grid;grid-template-columns:repeat(6,1fr);gap:18px}figure{margin:0}img{width:100%;display:block;border-radius:12px;box-shadow:0 8px 25px #092a2a1c}figcaption{font-size:15px;font-weight:600;margin:14px 0}a{color:inherit;text-decoration:none}.caption{margin-top:22px}</style><h1>BaseballMaster · App Store 宣传图</h1><p>3.0.1 · 实际 App 界面 · 共享球队与本机记录分别保存</p><div class="grid">${data.map(d=>`<figure><a href="iphone-6.3/${d.id}-${d.name}.png"><img src="iphone-6.3/${d.id}-${d.name}.png"></a><figcaption>${d.id} · ${d.name}</figcaption></figure>`).join('')}</div><p class="caption">6.1／6.3 英寸栏目：1206 × 2622　 ·　6.9 英寸：1320 × 2868　 ·　6.5 英寸：1242 × 2688　 ·　真实 App 与配套观赛网页，合成比赛数据</p></html>`;
 fs.writeFileSync(path.join(out,'index.html'),gallery);
 const page=await browser.newPage({viewport:{width:2700,height:850},deviceScaleFactor:1});
 await page.goto('file://'+path.join(out,'index.html'));await page.evaluate(()=>document.fonts.ready);await page.screenshot({path:path.join(out,'overview.png'),fullPage:true});
 const supplementGallery=gallery.replace('repeat(6,1fr)','repeat(2,1fr)').replace(/<div class="grid">[\s\S]*?<\/div><p class="caption">/, '<div class="grid">'+data.slice(-2).map(d=>`<figure><img src="iphone-6.3/${d.id}-${d.name}.png"><figcaption>${d.id} · ${d.name}</figcaption></figure>`).join('')+'</div><p class="caption">');
 fs.writeFileSync(path.join(out,'core-features.html'),supplementGallery);
 await page.setViewportSize({width:1200,height:850});
 await page.goto('file://'+path.join(out,'core-features.html'));await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()))});
 await page.screenshot({path:path.join(out,'core-features-overview.png'),fullPage:true});
 fs.writeFileSync(path.join(out,'manifest.json'),JSON.stringify({created:'2026-10-19',appVersion:'3.0.1 (1)',items:data,files:manifest},null,2)+'\n');
 await browser.close();console.log('Rendered and verified '+manifest.length+' PNGs');
})();
