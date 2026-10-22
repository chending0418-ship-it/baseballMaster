// Capture the deployed companion viewer using synthetic data; delete it afterwards.
import {createRequire} from 'node:module';
import {randomBytes,randomUUID} from 'node:crypto';
import {mkdirSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
import {uiSnapshot} from '../../live/test/fixture.mjs';
const require=createRequire(import.meta.url),{webkit}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const base=process.env.LIVE_BASE;assert.ok(base,'Set LIVE_BASE explicitly');
const out=resolve('output/releases/2.2/app-store/raw');mkdirSync(out,{recursive:true});
const token=randomBytes(32).toString('hex');let code;let browser;
try{
 const state=uiSnapshot(); const create=await fetch(base+'/api/sessions',{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({requestID:randomUUID(),createdAt:Date.now(),snapshot:state})});assert.equal(create.status,201);code=(await create.json()).code;
 browser=await webkit.launch();const page=await browser.newPage({viewport:{width:440,height:956},deviceScaleFactor:3,isMobile:true,hasTouch:true,colorScheme:'light'});
 await page.goto(base+'/'+code);await page.waitForSelector('#entry-pa-8');await page.evaluate(()=>document.fonts.ready);
 await page.screenshot({path:out+'/02.png'});await page.locator('#open-lineups').click();await page.locator('#home-tab').click();await page.screenshot({path:out+'/03.png'});
 writeFileSync(out+'/viewer-source.json',JSON.stringify({date:new Date().toISOString(),origin:new URL(base).origin,synthetic:true,viewport:[440,956],scale:3,files:['02.png','03.png'],source:'Deployed 2.2 HTML/CSS/JS; temporary session deleted after capture'},null,2)+'\n');
 console.log('Captured production viewer and current lineup');
}finally{if(browser)await browser.close();if(code){const deleted=await fetch(base+'/api/sessions/'+code,{method:'DELETE',headers:{Authorization:`Bearer ${token}`}});assert.equal(deleted.status,200);}}
