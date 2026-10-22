const fs=require('node:fs'),path=require('node:path');
const {chromium}=require('/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async()=>{
 const directory=__dirname;
 const screens=[['01-shortcuts','打席里查看表现／常用局面'],['02-record-contact','选择球型，点选大致落点'],['03-confirm-play','核对结果和跑者，再保存'],['04-batter-chart','本场／年度击球图']];
 const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>body{margin:0;background:#111820;color:#E8F1F7;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif}main{width:900px;padding:28px;box-sizing:border-box}h1{font-size:25px;font-weight:600;margin:0 0 9px}p{margin:0 0 25px;color:#AAB8C5;font-size:14px}.grid{display:grid;grid-template-columns:402px 402px;gap:26px 28px}h2{font-size:16px;font-weight:500;margin:0 0 11px}img{display:block;width:402px;height:874px;border-radius:26px}.tag{color:#F2A65A}</style><main><h1>击球记录与打者击球图</h1><p><span class="tag">交互提案 · 尚未实现</span>　按最新反馈更新：七项球型；图上不标位置名称。示例数据。</p><div class="grid">${screens.map(([file,label],i)=>`<section><h2>${i+1}　${label}</h2><img alt="${label}" src="data:image/png;base64,${fs.readFileSync(path.join(directory,file+'.png')).toString('base64')}"></section>`).join('')}</div></main></html>`;
 fs.writeFileSync(path.join(directory,'overview.html'),html);
 const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
 const page=await browser.newPage({viewport:{width:900,height:2100},deviceScaleFactor:2});
 await page.goto('file://'+path.join(directory,'overview.html'));await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});
 await page.locator('main').screenshot({path:path.join(directory,'overview.png')});await browser.close();
})();
