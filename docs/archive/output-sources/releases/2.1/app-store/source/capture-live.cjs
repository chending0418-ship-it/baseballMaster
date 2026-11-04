const fs=require('fs');
const path=require('path');
const {pathToFileURL}=require('url');
const {randomBytes,randomUUID}=require('crypto');
const req=require('module').createRequire(process.env.CODEX_ARTIFACT_NODE_MODULES+'/package.json');
const {chromium}=req('playwright');
(async()=>{
 let repo=path.resolve(__dirname);
 while(!fs.existsSync(path.join(repo,'BaseballMaster.xcodeproj'))){
  const parent=path.dirname(repo);if(parent===repo)throw Error('Cannot find BaseballMaster project root');repo=parent;
 }
 const {snapshot}=await import(pathToFileURL(path.join(repo,'live/test/fixture.mjs')));
 const state=snapshot();const token=randomBytes(32).toString('hex');
 const base='http://127.0.0.1:18088/livestreaming/novideo';
 const res=await fetch(base+'/api/sessions',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify({requestID:randomUUID(),createdAt:Date.now(),snapshot:state})});
 if(!res.ok)throw Error('Session HTTP '+res.status);const {code}=await res.json();
 const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 try{
 const page=await browser.newPage({viewport:{width:440,height:956},deviceScaleFactor:3,isMobile:true,hasTouch:true,colorScheme:'light'});
 await page.goto(base+'/'+code);await page.locator('#batter').filter({hasText:'陈昊'}).waitFor();
 await page.evaluate(()=>document.fonts.ready);
 if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw Error('Horizontal overflow');
 await page.screenshot({path:path.resolve(__dirname,'../raw/02.png')});
 fs.writeFileSync(path.resolve(__dirname,'../raw/live-capture.json'),JSON.stringify({capturedAt:new Date().toISOString(),source:'live/public with real local HTTP session',fixture:'live/test/fixture.mjs',viewport:[440,956],scale:3,publiclyPublished:false},null,2));
 }finally{await browser.close();await fetch(base+'/api/sessions/'+code,{method:'DELETE',headers:{Authorization:'Bearer '+token}})}
 console.log('Captured actual local web viewer');
})();
