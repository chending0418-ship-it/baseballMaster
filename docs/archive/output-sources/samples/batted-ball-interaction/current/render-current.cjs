const fs=require('node:fs'),path=require('node:path');
const {chromium}=require('/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const repository=path.resolve(__dirname,'../../../..');
const out=__dirname;
const original=path.join(repository,'output/samples/scoring-flow-review/v2/screens');
(async()=>{
 const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
 const page=await browser.newPage({viewport:{width:736,height:1200},deviceScaleFactor:3,colorScheme:'dark'});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('file://'+path.join(repository,'output/validation/3.0/batted-ball-design/preview.html'));
 const frame=page.frames().find(f=>f.parentFrame());
 await frame.waitForSelector('.bm-main img');
 await frame.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});
 const checks=[];
 async function snapshot(locator,name){
  const check=await locator.evaluate(el=>{const b=el.getBoundingClientRect();return {width:b.width,height:b.height,horizontalOverflow:el.scrollWidth>el.clientWidth};});
  checks.push({name,...check});await locator.screenshot({path:path.join(out,name+'.png')});
 }
 await frame.evaluate(()=>document.getElementById('bm-contact-proposal').bmDemo.show('home',{menu:false,player:'home',result:null}));
 const phone=frame.locator('.bm-phone').first();
 // Use the existing full-width hit button, with no external shortcut menu.
 await phone.locator('.bm-main-hit .quick').evaluate(el=>el.remove());
 await snapshot(phone,'01-scorekeeping');
 await phone.locator('.bm-main-batter').click();
 if(!(await phone.textContent()).includes('打击表现'))throw new Error('Batter tap failed');
 await frame.evaluate(()=>document.getElementById('bm-contact-proposal').bmDemo.show('observe',{type:'平飞',point:{x:103,y:148},result:null}));
 await phone.locator('[data-action="continue"]').evaluate(el=>el.textContent='继续');
 await phone.locator('.bm-location').evaluate(el=>el.textContent='已记录落点');
 await snapshot(phone,'02-contact-location');
 await frame.evaluate(()=>document.getElementById('bm-contact-proposal').bmDemo.show('history',{scope:'year',player:'home',event:'c'}));
 await snapshot(phone,'05-annual-chart');
 await frame.evaluate(()=>document.getElementById('bm-contact-proposal').bmDemo.show('history',{scope:'game',player:'home',event:'a'}));
 await snapshot(phone,'06-current-game-chart');
 await frame.evaluate(()=>document.getElementById('bm-contact-proposal').bmDemo.show('history',{scope:'year',player:'opponent',event:'e'}));
 await snapshot(phone,'07-opponent-chart');
 const preservedLayout=`<style>:root{--bg:#111820;--paper:#1B2632;--ink:#E8F1F7;--muted:#AAB8C5;--green:#48B889;--soft:#173D30;--line:#31404D;--orange:#F2A65A;--orangebg:#49321C}html,body{background:var(--bg)}.status .island{background:#05080b}.tabs{background:#1B2632}.tab.active{background:#173D30}.choice,.card,.list,.runner{border-color:#31404D}.listrow{border-color:#31404D}.card.green{background:#173D30;border-color:#31404D}.dest{background:#25323E;color:#AAB8C5}.dest.active{background:#217A57;color:white}.card.orange{background:#49321C;border-color:#815a31}.proposal{color:#AAB8C5}.homebar{background:#AAB8C5}.outcome .link{color:#48B889}</style>`;
 for(const [source,name] of [['01-common','03-existing-play-flow'],['04-runners','04-existing-runners']]){
  let html=fs.readFileSync(path.join(original,source+'.html'),'utf8');
  html=html.replace('海浪 2 : 1 飞鹰','海浪 1 : 0 飞鹰').replaceAll('3 局上','2 局上').replaceAll('#5 许嘉树','#23 王星野').replaceAll('#18 林子轩','#18 林宇轩');
  html=html.replace('V2 · 界面示意，尚未实现','平飞 · 已记录落点');
  // The original result/runner arrangement is retained; observations are a carried draft.
  if(source==='01-common')html=html.replace('球型 → 处理 ›','处理 ›');
  if(source==='04-runners'){
   html=html.replaceAll('三垒','二垒');
   // Avoid duplicate "二垒" controls caused by updating the selected sample destination.
   html=html.replace('<div class="dest ">二垒</div><div class="dest active ">二垒</div>','<div class="dest active ">二垒</div><div class="dest ">三垒</div>');
   html=html.replace('一、三垒有人','一、二垒有人').replace('补充球型 / 处理细节','球型与落点').replace('选填 ›','已记录 ›');
  }
  html=html.replace('</style>', '</style>'+preservedLayout);
  fs.writeFileSync(path.join(out,name+'.html'),html);
  await page.setViewportSize({width:402,height:874});await page.goto('file://'+path.join(out,name+'.html'));await page.evaluate(()=>document.fonts.ready);
  const layout=await page.evaluate(()=>{const c=document.querySelector('.content'),f=document.querySelector('.footer');const max=Math.max(...[...c.children].map(el=>el.getBoundingClientRect().bottom));return {width:innerWidth,height:innerHeight,horizontalOverflow:document.documentElement.scrollWidth>innerWidth,contentBottom:max,footerTop:f.getBoundingClientRect().top,overlap:max>f.getBoundingClientRect().top-4};});
  checks.push({name,...layout});await page.screenshot({path:path.join(out,name+'.png')});
 }
 fs.writeFileSync(path.join(out,'validation.json'),JSON.stringify({checks,errors,demoOnly:true,originalFlowSource:'scoring-flow-review/v2',productionWrites:false},null,2));
 const screens=[['01-scorekeeping','记分页：点击打者打开表现'],['02-contact-location','新增：球型与落点，选完继续'],['03-existing-play-flow','接回 v2 原有局面记录'],['05-annual-chart','打者表现：本场／年度击球图']];
 const overview=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>body{margin:0;background:#111820;color:#E8F1F7;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif}main{width:900px;padding:28px;box-sizing:border-box}h1{font-size:25px;font-weight:600;margin:0 0 9px}p{margin:0 0 25px;color:#AAB8C5;font-size:14px}.grid{display:grid;grid-template-columns:402px 402px;gap:26px 28px}h2{font-size:16px;font-weight:500;margin:0 0 11px}img{display:block;width:402px;height:874px;border-radius:26px}.tag{color:#F2A65A}</style><main><h1>当前设计 · 击球记录与打者表现</h1><p><span class="tag">设计截图 · 尚未开发</span>　球型与落点是新增步骤，之后接回原有局面流程。示例数据。</p><div class="grid">${screens.map(([file,label],i)=>`<section><h2>${i+1}　${label}</h2><img alt="${label}" src="data:image/png;base64,${fs.readFileSync(path.join(out,file+'.png')).toString('base64')}"></section>`).join('')}</div></main></html>`;
 fs.writeFileSync(path.join(out,'overview.html'),overview);await page.setViewportSize({width:900,height:2100});await page.goto('file://'+path.join(out,'overview.html'));
 await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});
 await page.locator('main').screenshot({path:path.join(out,'overview.png')});
 await browser.close();console.log(JSON.stringify({checks,errors}));
 if(errors.length||checks.some(c=>c.height>874.5||c.horizontalOverflow||c.overlap))process.exitCode=1;
})();
