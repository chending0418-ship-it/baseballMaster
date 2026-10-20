import assert from 'node:assert/strict';
import {mkdirSync,writeFileSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {createRequire} from 'node:module';
import {adminFixture} from './admin-fixture.mjs';
const require=createRequire(import.meta.url);
const {chromium,webkit}=require(process.env.BM_PLAYWRIGHT||'/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const output=resolve('output/validation/3.0/admin');mkdirSync(output,{recursive:true});
const a=await adminFixture(),results=[];
try{
 for(const [engine,width]of [[chromium,1440],[webkit,402]]){
  const browser=await engine.launch(engine===chromium?{executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'}:{});
  try{
   const context=await browser.newContext({viewport:{width,height:1000},acceptDownloads:true}),page=await context.newPage(),errors=[];
   page.on('pageerror',e=>errors.push(e.message));
   await page.goto(a.origin+'/admin/');await page.locator('#password').waitFor();await page.screenshot({path:join(output,`login-${width}.png`)});
   await page.locator('#password').fill('wrong');await page.getByRole('button',{name:'登录后台'}).click();await page.getByText('密码不正确。',{exact:true}).waitFor();
   await page.locator('#password').fill(a.password);await page.getByRole('button',{name:'登录后台'}).click();await page.getByRole('heading',{name:'云端资料总览'}).waitFor();
   await page.screenshot({path:join(output,`overview-${width}.png`),fullPage:true});
   const allPending=page.waitForEvent('download');await page.getByRole('button',{name:'下载全部云端资料'}).click();const all=await allPending;assert.equal(await all.failure(),null);await all.saveAs(join(output,`synthetic-all-${width}.zip`));
   await page.locator('nav').getByRole('link',{name:'球队资料'}).click();await page.getByRole('button',{name:'查看球队'}).first().waitFor();
   await page.getByPlaceholder('球队名称').fill('上海');await page.getByRole('button',{name:'筛选',exact:true}).click();await page.getByText('共 1 项 · 1–1',{exact:true}).waitFor();
   await page.getByRole('button',{name:'查看球队'}).click();await page.getByRole('heading',{name:'球员资料'}).waitFor();assert.match(await page.locator('#content').innerText(),/00 \/ 12/);
   await page.screenshot({path:join(output,`team-${width}.png`),fullPage:true});
   const pending=page.waitForEvent('download');await page.getByRole('button',{name:'下载球队资料'}).click();const download=await pending;assert.equal(await download.failure(),null);await download.saveAs(join(output,`synthetic-team-${width}.zip`));
   await page.getByRole('link',{name:'查看这支球队的比赛 →'}).click();await page.getByRole('heading',{name:'上海海浪 · 比赛'}).waitFor();await page.getByRole('button',{name:'查看内容'}).first().click();await page.getByRole('heading',{name:'比赛内容'}).waitFor();await page.getByText('完整比赛数据（只读）',{exact:true}).click();await page.locator('pre').waitFor();
   const gamePending=page.waitForEvent('download');await page.getByRole('button',{name:'下载比赛内容'}).click();const gameDownload=await gamePending;assert.equal(await gameDownload.failure(),null);await gameDownload.saveAs(join(output,`synthetic-game-${width}.json`));
   await page.locator('nav').getByRole('link',{name:'实时直播'}).click();await page.getByRole('button',{name:'查看内容'}).first().waitFor();
   await page.goto(a.origin+'/admin/#live/temporary/'+a.temporary.code);await page.getByRole('heading',{name:'当前阵容'}).waitFor();assert.equal(await page.locator('table').count(),3);await page.getByText('3 局上 · 陈昊：界外球（1 坏 2 好）',{exact:true}).click();
   await page.screenshot({path:join(output,`live-${width}.png`),fullPage:true});
   const livePending=page.waitForEvent('download');await page.getByRole('button',{name:'下载直播内容'}).click();const liveDownload=await livePending;assert.equal(await liveDownload.failure(),null);await liveDownload.saveAs(join(output,`synthetic-live-${width}.json`));
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);
   await page.locator(width===1440?'#logout':'#logout-mobile').click();await page.locator('#login').waitFor({state:'visible'});assert.equal(await page.locator('#content').innerText(),'');
   assert.equal((await context.request.get(a.origin+'/admin/api/teams')).status(),401);assert.deepEqual(errors,[]);
   results.push({engine:engine.name(),width,login:true,search:true,team:true,cloudMatch:true,liveLineups:true,download:true,logout:true,overflow:false,pageErrors:errors});
  }finally{await browser.close();}
 }
 writeFileSync(join(output,'browser-results.json'),JSON.stringify(results,null,2)+'\n');console.log(JSON.stringify(results));
}finally{await a.close();}
