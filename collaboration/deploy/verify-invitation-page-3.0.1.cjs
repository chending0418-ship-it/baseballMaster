const {webkit}=require('/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const {randomBytes,createHash}=require('node:crypto');const fs=require('node:fs');const assert=require('node:assert/strict');
const out='output/validation/3.0.1/release-2026-10-19/invitation-public';fs.mkdirSync(out,{recursive:true});
(async()=>{const browser=await webkit.launch();const results=[];try{for(const [width,colorScheme] of [[393,'light'],[402,'dark'],[440,'light']]){
 const context=await browser.newContext({viewport:{width,height:956},colorScheme}),page=await context.newPage(),token=randomBytes(32).toString('base64url'),requests=[],errors=[];
 page.on('request',r=>requests.push(r.url()));page.on('pageerror',e=>errors.push(e.message));
 const response=await page.goto('https://baseballmaster.cc/join/#'+token,{waitUntil:'networkidle'});assert.equal(response.status(),200);
 assert.equal(await page.locator('#open-app').getAttribute('href'),'baseballmaster://join#'+token);
 assert.equal(await page.getByText('旧版单次邀请和新版球队固定邀请都可以使用',{exact:false}).count(),1);
 assert.ok(await page.locator('a[href*="apps.apple.com"]').isVisible());assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 assert.ok(!requests.some(url=>url.split('#')[0].includes(token)));assert.deepEqual(errors,[]);
 await page.screenshot({path:out+`/join-${width}-${colorScheme}.png`,fullPage:true});
 await page.goto('https://baseballmaster.cc/join/#invalid',{waitUntil:'networkidle'});assert.equal(await page.locator('#open-app').isVisible(),false);
 results.push({width,colorScheme,legacyAndFixedInvitationExplained:true,app30DeepLinkRetained:true,downloadLinkVisible:true,secretNotRequested:true,invalidFragmentBlocked:true,overflow:false});await context.close();
}fs.writeFileSync(out+'/results.json',JSON.stringify({passed:true,checks:results},null,2));console.log('Public invitation page: 3 viewports passed; old and new flow coexist.');}finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
