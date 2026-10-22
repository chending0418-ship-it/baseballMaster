const fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url');
const repo=path.resolve(__dirname,'../../../..'),dest=path.join(__dirname,'assets');
const deps=process.env.CODEX_ARTIFACT_NODE_MODULES||'/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const {chromium}=require(path.join(deps,'playwright'));
(async()=>{fs.mkdirSync(dest,{recursive:true});for(const n of ['horizontal','horizontal-reverse','stacked','compact','mark'])fs.copyFileSync(path.join(repo,'output/marketing/brand/2026-10-22-dual-product/brand-kit/baseballmaster-'+n+'.png'),path.join(dest,'parent-'+n+'.png'));
const root=path.join(repo,'output/marketing/brand/2026-10-22-dual-product/website-review');
const browser=await chromium.launch({headless:true});try{const page=await browser.newPage({viewport:{width:1440,height:1000},deviceScaleFactor:1});await page.goto(pathToFileURL(path.join(root,'index.html')).href);await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});
const types=await page.evaluate(()=>[...document.querySelectorAll('.product .word')].map(e=>{const s=getComputedStyle(e);return {name:e.textContent,weight:s.fontWeight,second:getComputedStyle(e.querySelector('span')).fontWeight,ratio:parseFloat(s.letterSpacing)/parseFloat(s.fontSize)};}));if(types.some(t=>t.weight!=='800'||t.second!=='400'||Math.abs(t.ratio+.045)>.00001))throw Error(JSON.stringify(types));
await page.screenshot({path:path.join(dest,'homepage-first-screen.png'),clip:{x:0,y:0,width:1440,height:950}});
await page.locator('.score-image').screenshot({path:path.join(dest,'record-field.png')});await page.locator('.coach-image').screenshot({path:path.join(dest,'coach-pitcher.png')});
await page.setViewportSize({width:390,height:1080});await page.evaluate(()=>window.scrollTo(0,0));await page.screenshot({path:path.join(dest,'homepage-mobile.png')});
for(const which of ['record','coach'])for(const shade of ['navy','white']){
const lp=await browser.newPage({viewport:{width:1440,height:200},deviceScaleFactor:4});await lp.goto(pathToFileURL(path.join(root,'index.html')).href);
await lp.evaluate(({which,shade})=>{const source=document.querySelector(which==='record'?'.product.scoring .lockup':'.product.coach .lockup').outerHTML;document.body.innerHTML=source;document.body.style.cssText='margin:0;background:transparent';document.documentElement.style.background='transparent';const e=document.querySelector('.lockup');e.style.display='inline-flex';e.style.color=shade==='white'?'#FFFFFF':'#0B1933';},{which,shade});
await lp.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});await lp.locator('.lockup').screenshot({path:path.join(dest,'product-'+which+'-'+shade+'.png'),omitBackground:true});await lp.close();}
console.log('Version 1.0 application snapshots captured; shared typography verified.');}finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
