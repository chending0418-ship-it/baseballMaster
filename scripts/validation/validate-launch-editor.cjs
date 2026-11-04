// Local synthetic data only. No production requests or deployment commands.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const {pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'../..');
const modules=process.env.CODEX_ARTIFACT_NODE_MODULES||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const {chromium}=require(path.join(modules,'playwright'));
const output=process.env.BM_LAUNCH_VALIDATION_OUTPUT?path.resolve(process.env.BM_LAUNCH_VALIDATION_OUTPUT):path.join(root,'output/validation/3.1.1/configurable-launch');
(async()=>{
 const {adminFixture}=await import(pathToFileURL(path.join(root,'collaboration/test/admin-fixture.mjs')).href);
 const fixture=await adminFixture();let browser;
 try{
  browser=await chromium.launch({headless:true,...(process.env.BM_LAUNCH_BROWSER_PATH?{executablePath:process.env.BM_LAUNCH_BROWSER_PATH}:{})});
  fs.mkdirSync(output,{recursive:true});
  const page=await browser.newPage({viewport:{width:1440,height:1100},deviceScaleFactor:1});
  const errors=[],blocked=[];page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/*',route=>{if(new URL(route.request().url()).origin===fixture.origin)return route.continue();blocked.push(route.request().url());return route.abort();});
  await page.goto(fixture.origin+'/admin/#launch');
  await page.locator('#password').fill(fixture.password);await page.locator('#login-form button').click();
  await page.getByRole('heading',{name:'开屏配置',exact:true}).waitFor();
  const uploadURL=fixture.origin+'/admin/api/launch-screen/image';
  for(const [status,text]of [[413,'请求内容超过服务器限制。背景图最多 3 MB；若图片未超限，请联系管理员检查上传配置。'],[502,'服务器响应异常（HTTP 502），请稍后重试。']]){
   await page.route(uploadURL,route=>route.fulfill({status,contentType:'text/html',body:`<html><h1>${status}</h1></html>`}));
   await page.locator('input[type=file]').setInputFiles(path.join(root,'collaboration/test/fixtures/launch-background.png'));
   await page.getByText(text,{exact:true}).waitFor();
   if(await page.getByRole('button',{name:'发布配置',exact:true}).isDisabled())throw Error('Upload failure left editor locked');
   if(fixture.store.launchScreen.get().revision!==0)throw Error('Failed upload changed published config');
   await page.unroute(uploadURL);
  }
  await page.locator('input[type=file]').setInputFiles(path.join(root,'collaboration/test/fixtures/launch-background.png'));
  await page.getByText('背景已上传，点击“发布配置”后生效。',{exact:true}).waitFor();
  await page.locator('textarea').fill('每一场热爱 都值得记录');await page.locator('input[type=number]').fill('1.2');
  await page.locator('input[type=datetime-local]').nth(0).fill('2026-10-24T00:00');
  await page.locator('input[type=datetime-local]').nth(1).fill('2026-10-31T23:59');
  await page.getByRole('button',{name:'发布配置',exact:true}).click();
  await page.getByText('配置已发布。App 下载完成后，在下次启动时生效。',{exact:true}).waitFor();
  const config=await page.evaluate(()=>fetch('/api/v3/app/launch-screen').then(r=>r.json()));
  if(config.message!=='每一场热爱 都值得记录'||config.displayDurationMs!==1200||config.startsAt!==Date.parse('2026-10-24T00:00+08:00'))throw Error('Published config mismatch');
  await page.screenshot({path:path.join(output,'后台开屏配置-桌面.png'),fullPage:true});
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:path.join(output,'后台开屏配置-手机.png'),fullPage:true});
  if(await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth))throw Error('Mobile horizontal overflow');
  await page.locator('input[type=checkbox]').uncheck();await page.getByRole('button',{name:'发布配置',exact:true}).click();
  await page.getByText('配置已发布。App 下载完成后，在下次启动时生效。',{exact:true}).waitFor();
  const disabled=await page.evaluate(()=>fetch('/api/v3/app/launch-screen').then(r=>r.json()));if(disabled.enabled)throw Error('Disable failed');
  await page.getByRole('button',{name:'恢复默认内容',exact:true}).click();await page.getByRole('button',{name:'发布配置',exact:true}).click();
  await page.getByText('配置已发布。App 下载完成后，在下次启动时生效。',{exact:true}).waitFor();
  const defaults=await page.evaluate(()=>fetch('/api/v3/app/launch-screen').then(r=>r.json()));if(defaults.backgroundImage!==null||!defaults.enabled||defaults.message!=='每一球 都有迹可循')throw Error('Reset failed');
  await page.route(uploadURL,route=>route.fulfill({status:401,contentType:'text/html',body:'<html>Unauthorized</html>'}));
  await page.locator('input[type=file]').setInputFiles(path.join(root,'collaboration/test/fixtures/launch-background.png'));
  await page.locator('#login').waitFor({state:'visible'});
  if(await page.locator('#login-message').innerText()!=='登录已到期，请重新输入密码。')throw Error('HTML 401 did not reset session');
  if(errors.length||blocked.length)throw Error(JSON.stringify({errors,blocked}));
  fs.writeFileSync(path.join(output,'browser-checks.json'),JSON.stringify({localOnly:true,productionRequests:0,passed:['owner login','HTML 413 upload feedback and retry','HTML 502 upload feedback and retry','PNG upload and preview','message and duration publication','Beijing schedule serialization','desktop and mobile layout','disable','restore bundled defaults','HTML 401 resets session'],config},null,2)+'\n');
  console.log('Local launch-editor browser checks passed.');
 }finally{await browser?.close();await fixture.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
