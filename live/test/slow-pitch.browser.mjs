// Isolated WebKit regression; no user browser session or production data.
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {randomBytes, randomUUID} from 'node:crypto';
import {mkdirSync, writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {createLiveServer, BASE} from '../server.mjs';
import {uiSnapshot} from './fixture.mjs';
const require=createRequire(import.meta.url), {webkit}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const app=process.env.LIVE_BROWSER_ORIGIN ? null : createLiveServer({createLimit:10000});
if(app) await new Promise(r=>app.server.listen(0,'127.0.0.1',r));
const origin=process.env.LIVE_BROWSER_ORIGIN || `http://127.0.0.1:${app.server.address().port}`;
const output=resolve(process.env.LIVE_QA_OUTPUT || 'output/validation/2.3/live/slow-browser');mkdirSync(output,{recursive:true});
const browser=await webkit.launch(), results=[];
try {
 for(const [width,colorScheme] of [[320,'light'],[375,'light'],[375,'dark'],[1440,'light']]) {
  const context=await browser.newContext({viewport:{width,height:1000},colorScheme});
  const page=await context.newPage(), errors=[];page.on('pageerror',e=>errors.push(e.message));
  const state=uiSnapshot(), token=randomBytes(32).toString('hex');
  let code;
  try {
  state.mode='slowPitch';state.batterOrder=12;state.balls=1;state.strikes=2;state.pitchCount=2;state.appearancePitchCount=2;
  state.rules={scheduledInnings:6,timeLimitMinutes:90,competitionFormat:'timed',initialBalls:1,initialStrikes:1,twoStrikeFoulPolicy:'oneExtraFoul',fieldersCount:10,rulesVersion:2,extraFoulUsed:true};
  const positions=['投手','捕手','一垒手','二垒手','三垒手','游击手','左外野手','中外野手','右外野手','自由人'];
  for(const [side, count, prefix] of [[state.away,12,'客队'],[state.home,11,'主队']]) {
   side.lineup=Array.from({length:count},(_,i)=>({player:{id:`${prefix}-${i}`,name:`${prefix}球员${i+1}`,number:String(i+1)},order:i+1,position:positions[i]||'打击'}));
   side.pitcherID=side.lineup[0].player.id;side.innings=side.innings.slice(0,state.inning);side.playedInnings=side.playedInnings.slice(0,state.inning);
  }
  state.batter=state.away.lineup[11].player;state.pitcher=state.home.lineup[0].player;
  state.nextBatters=state.away.lineup.slice(0,2).map(p=>({player:p.player,order:p.order}));
  const response=await fetch(origin+BASE+'/api/sessions',{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({requestID:randomUUID(),createdAt:Date.now(),snapshot:state})});
  assert.equal(response.status,201);code=(await response.json()).code;
  await page.goto(origin+BASE+'/'+code);await page.waitForFunction(()=>document.querySelector('#mode')?.textContent==='成人慢垒');
  assert.ok((await page.textContent('#rules')).includes('时间赛 90 分钟'));
  assert.ok((await page.textContent('#rules')).includes('额外界外机会已用'));
  assert.ok((await page.textContent('#game-time')).includes('剩余'));
  assert.ok((await page.textContent('#batter-label')).includes('第 12 棒'));
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.screenshot({path:`${output}/${width}-${colorScheme}-live.png`,fullPage:true});
  await page.locator('#open-lineups').click();await page.waitForFunction(()=>document.querySelector('#lineup-view')?.hidden===false);
  const body=await page.textContent('body');assert.ok(body.includes('自由人'));assert.ok(body.includes('客队球员12'));await page.locator('#home-tab').click();assert.ok((await page.textContent('body')).includes('主队球员11'));
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.screenshot({path:`${output}/${width}-${colorScheme}-lineups.png`,fullPage:true});
  assert.deepEqual(errors,[]);results.push({width,colorScheme,passed:true});
  } finally {
   if(code) {
    const deleted=await fetch(origin+BASE+'/api/sessions/'+code,{method:'DELETE',headers:{Authorization:`Bearer ${token}`}});
    assert.equal(deleted.status,200);
   }
   await context.close();
  }
 }
 writeFileSync(output+'/results.json',JSON.stringify(results,null,2)+'\n');console.log('PASS '+results.length+' slow-pitch browser groups');
}finally{await browser.close();if(app)await new Promise(r=>app.server.close(r));}
