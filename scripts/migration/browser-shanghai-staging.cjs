// A real HTTPS forwarding adapter maps the unchanged domain to an SSH tunnel.
// Every request validates the temporary CA and hostname; public DNS is never used.
const {chromium}=require('/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const {readFileSync,writeFileSync,mkdirSync}=require('node:fs');
const path=require('node:path');
const https=require('node:https');
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const access=JSON.parse(readFileSync(process.env.BM_STAGING_ACCESS));
const output=process.env.BM_STAGING_BROWSER_OUTPUT;
mkdirSync(output,{recursive:true});
assert.equal(access.session.syntheticOnly,true);
const origin='https://baseballmaster.cc',checks=[],issues=[];
let networkDown=false;
async function request(method,url,body,headers={}){
 const u=new URL(url);assert.equal(u.origin,origin,'Only the unchanged domain is permitted');
 return new Promise((resolve,reject)=>{
  const bytes=body===undefined?undefined:Buffer.isBuffer(body)?body:Buffer.from(JSON.stringify(body));
  const clean={...headers,host:'baseballmaster.cc','accept-encoding':'identity'};
  delete clean['content-length'];delete clean.connection;
  if(bytes)clean['content-length']=String(bytes.length);
  const req=https.request({hostname:'baseballmaster.cc',port:Number(process.env.BM_STAGING_TUNNEL_PORT||18443),
   servername:'baseballmaster.cc',path:u.pathname+u.search,method,ca:access.caPem,
   lookup:(_h,o,cb)=>o.all?cb(null,[{address:'127.0.0.1',family:4}]):cb(null,'127.0.0.1',4),headers:clean},res=>{
    const chunks=[];res.on('data',b=>chunks.push(b));res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,body:Buffer.concat(chunks)}));
   });
  req.on('error',reject);req.setTimeout(15000,()=>req.destroy(new Error('SSH tunnel request timed out')));req.end(bytes);
 });
}
async function main(){
 const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
 const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:1});
 await context.route('**/*',async route=>{
  const incoming=route.request();
  if(networkDown && incoming.resourceType()==='fetch')return route.abort('internetdisconnected');
  try{
   const r=await request(incoming.method(),incoming.url(),incoming.postDataBuffer()??undefined,incoming.headers());
   const headers={};
   for(const [k,v] of Object.entries(r.headers))if(!['transfer-encoding','connection','content-length'].includes(k))headers[k]=Array.isArray(v)?v.join('\n'):String(v);
   await route.fulfill({status:r.status,headers,body:r.body});
  }catch(error){issues.push('Forwarding failed: '+error.name);await route.abort('failed');}
 });
 const page=await context.newPage();page.on('pageerror',e=>issues.push(e.message));
 const overflow=()=>page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
 try{
  for(const width of [320,390,1440])for(const pathname of ['/','/scorekeeper.html','/support.html','/privacy.html','/coach-insights.html','/coach-insights-support.html']){
   await page.setViewportSize({width,height:900});const r=await page.goto(origin+pathname,{waitUntil:'networkidle'});assert.equal(r.status(),200);
   await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].filter(i=>i.getAttribute('src')).map(i=>{i.loading='eager';return i.decode();}));});
   assert.equal(await overflow(),false,`Layout overflow at ${pathname} ${width}`);
   if(pathname==='/'){
    for(const index of [1,2,0]){await page.locator(`[data-slide="${index}"]`).click();assert.equal(await page.locator('.hero-carousel').getAttribute('data-active'),String(index));}
   }
   if(pathname==='/scorekeeper.html'){await page.locator('.release-shot').first().click();assert.ok(await page.locator('#screenshot-dialog').isVisible());await page.keyboard.press('Escape');}
   checks.push({kind:'website',pathname,width});
   if(pathname==='/' && width===1440)await page.screenshot({path:path.join(output,'website-desktop.png')});
  }
  await page.setViewportSize({width:390,height:844});
  await page.goto(origin+'/livestreaming/novideo/'+access.session.liveCode);
  await page.locator('#match').waitFor({state:'visible'});
  const current=JSON.parse((await request('GET',origin+'/livestreaming/novideo/api/sessions/'+access.session.liveCode)).body);
  const updated=current.snapshot;updated.revision++;
  updated.entries[0].details[0].text='浏览器逐球更新（合成）';
  const changed=await request('PUT',origin+'/livestreaming/novideo/api/sessions/'+access.session.liveCode,updated,
   {'Content-Type':'application/json',Authorization:'Bearer '+access.session.liveKey});assert.equal(changed.status,200);
  await page.locator('#entry-pa-1 summary').click();
  await page.evaluate(()=>scrollTo(0,0));
  await page.waitForFunction(()=>document.querySelector('#entry-pa-1')?.textContent.includes('浏览器逐球更新（合成）'),{timeout:20000});
  networkDown=true;
  await page.waitForFunction(()=>document.querySelector('#connection')?.textContent.includes('网络暂不可用'),{timeout:20000});
  networkDown=false;
  await page.waitForFunction(()=>!document.querySelector('#connection')?.textContent.includes('网络暂不可用'),{timeout:20000});
  assert.equal(await overflow(),false);await page.screenshot({path:path.join(output,'live-mobile.png'),fullPage:true});
  checks.push({kind:'live',pitchUpdate:true,controlledDisconnect:true,reconnected:true});
  await page.goto(origin+'/watch/#'+access.session.historyCode);await page.locator('#match').waitFor({state:'visible'});
  assert.equal(await overflow(),false);checks.push({kind:'shared-history',originalPath:true,displayed:true});
  const lifanRoot='/Users/JasonChan/Documents/上海立帆青少棒/registration-site';
  const {sections}=await import(pathToFileURL(path.join(lifanRoot,'public/schema.mjs')));
  const {fixture}=await import(pathToFileURL(path.join(lifanRoot,'test/fixture.mjs')));
  for(const width of [320,390,1440]){
   await page.setViewportSize({width,height:900});await page.goto(origin+'/lifan/tryout/');await page.locator('#playerName').waitFor();assert.equal(await overflow(),false);
   checks.push({kind:'lifan-layout',width});
  }
  await page.setViewportSize({width:390,height:844});
  await page.getByRole('button',{name:'下一步'}).click();assert.ok(await page.locator('#form-errors').isVisible());
  const data=fixture();data.playerName='上海浏览器合成选手';
  for(let i=0;i<sections.length;i++){
   for(const f of sections[i].fields){
    const value=data[f.name];if(value===undefined || value==='')continue;
    const input=page.locator(`[name="${f.name}"]`);
    if(f.type==='radio')await input.filter({visible:true}).locator('..').filter({hasText:value}).locator('input').check();
    else if(f.type==='checkbox')await input.check();else if(f.type==='select')await input.selectOption(value);else await input.fill(value);
   }
   assert.equal(await overflow(),false);
   if(i===3){assert.ok(await page.locator('#injuryDetails').isVisible());assert.equal(await page.locator('#restrictionDetails').isVisible(),false);}
   await page.locator('#next').click();
  }
  await page.locator('#success').waitFor();assert.match(await page.locator('#receipt-id').textContent(),/^LF-/);
  await page.screenshot({path:path.join(output,'lifan-success-mobile.png'),fullPage:true});
  await page.goto(origin+'/lifan/tryout/admin/');await page.locator('#password').fill(access.qa.lifanPassword);
  await page.locator('#login-form button').click();await page.locator('#dashboard').waitFor();
  const row=page.locator('tr').filter({hasText:'上海浏览器合成选手'});await row.getByRole('button',{name:'查看详情'}).first().click();
  await page.locator('#detail-dialog').waitFor();assert.ok((await page.locator('#detail-content').textContent()).includes('不同意'));
  assert.ok((await page.locator('#detail-content').textContent()).includes('测试伤病说明'));assert.equal(await overflow(),false);
  await page.screenshot({path:path.join(output,'lifan-admin-mobile.png'),fullPage:true});
  await page.locator('#close-dialog').click();await page.locator('#logout').click();await page.locator('#login-panel').waitFor();
  checks.push({kind:'lifan-complete-flow',sixSections:true,independentConsent:true,administratorDetails:true,logout:true});
  assert.deepEqual(issues,[]);
  writeFileSync(path.join(output,'browser-verification.json'),JSON.stringify({passed:true,syntheticOnly:true,checkedAt:new Date().toISOString(),
   unchangedOrigin:origin,transport:'SSH tunnel with hostname and private test CA validation',checks,issues},null,2)+'\n');
  console.log(JSON.stringify({passed:true,checks:checks.length,syntheticOnly:true}));
 }finally{await browser.close();}
}
main().catch(e=>{console.error('Browser verification failed:',e.message);process.exitCode=1;});
