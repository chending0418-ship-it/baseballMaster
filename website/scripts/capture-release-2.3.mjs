// Capture the companion viewer from an actual synthetic 2.3 App projection.
import {createRequire} from 'node:module';
import {randomBytes,randomUUID,createHash} from 'node:crypto';
import {mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const require=createRequire(import.meta.url),{webkit}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const base=process.env.LIVE_BASE;assert.ok(base,'Set LIVE_BASE explicitly');
const out=resolve('output/releases/2.3/app-store/raw');mkdirSync(out,{recursive:true});
const source=readFileSync(out+'/slow-live-source.json');const state=JSON.parse(source);assert.equal(state.mode,'slowPitch');
const token=randomBytes(32).toString('hex');let code,browser;
try {
 const response=await fetch(base+'/api/sessions',{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({requestID:randomUUID(),createdAt:Date.now(),snapshot:state})});
 assert.equal(response.status,201);code=(await response.json()).code;
 browser=await webkit.launch();
 const page=await browser.newPage({viewport:{width:440,height:956},deviceScaleFactor:3,isMobile:true,hasTouch:true,colorScheme:'light'});
 await page.goto(base+'/'+code);await page.waitForFunction(()=>document.querySelector('#mode')?.textContent==='成人慢垒');await page.evaluate(()=>document.fonts.ready);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 await page.screenshot({path:out+'/05.png'});
 await page.locator('#open-lineups').click();await page.locator('#home-tab').click();
 assert.ok((await page.textContent('body')).includes('自由人'));
 await page.screenshot({path:out+'/05b-lineups.png'});
 writeFileSync(out+'/viewer-source.json',JSON.stringify({captured:new Date().toISOString(),origin:new URL(base).origin,synthetic:true,viewport:[440,956],deviceScaleFactor:3,appProjectionSHA256:createHash('sha256').update(source).digest('hex'),source:'Actual 2.3 App projection captured by passed simulator HTTPS integration test; public 2.3 viewer; temporary clone deleted after capture',files:['05.png','05b-lineups.png']},null,2)+'\n');
 console.log('Captured deployed 2.3 slow-pitch viewer from actual App state');
} finally {
 if(browser)await browser.close();
 if(code){const deleted=await fetch(base+'/api/sessions/'+code,{method:'DELETE',headers:{Authorization:`Bearer ${token}`}});assert.equal(deleted.status,200);}
}
