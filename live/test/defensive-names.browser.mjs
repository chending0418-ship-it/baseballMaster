// Local-only projection rendering check; never connects to production services.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createLiveServer, BASE as LIVE } from '../server.mjs';
import { createCollaborationServer, BASE as CLOUD } from '../../collaboration/server.mjs';
import { fixture } from '../../collaboration/test/match-fixture.mjs';
import { uiSnapshot } from './fixture.mjs';
const require = createRequire(import.meta.url);
const { webkit } = require(process.env.PLAYWRIGHT_MODULE || '/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const output = new URL('../../output/validation/3.1.0/browser/', import.meta.url);
mkdirSync(output, { recursive: true });
const live = createLiveServer(), cloud = createCollaborationServer({ allowLocalViewer: true, requestsPerMinute: 10000 });
for (const app of [live, cloud]) await new Promise(r => app.server.listen(0, '127.0.0.1', r));
const origin = app => `http://127.0.0.1:${app.server.address().port}`;
const key = randomBytes(32).toString('base64url'), liveKey = randomBytes(32).toString('hex');
async function request(app, method, path, body) {
 const response = await fetch(origin(app) + path, {method, headers: {'Content-Type':'application/json','X-BM-Protocol':'1',Authorization:`Bearer ${app === live ? liveKey : key}`},body: body ? JSON.stringify(body) : undefined});
 const data = await response.json(); assert.ok(response.ok, JSON.stringify(data)); return data;
}
const command = (method, path, body) => request(cloud, method, CLOUD + path, body ? {operationID:randomUUID(),...body} : undefined);
const route = '防守路线 6-4-3，游击手张三-二垒手李四-一垒手王五';
const state = uiSnapshot();
state.entries[0].label = '6-4-3';
state.entries[0].summary = '陈昊：双杀，' + route + '；击球观察：地滚 · 内野中间';
state.entries[0].details = [{id:'route-names',text:state.entries[0].summary}];
const browser = await webkit.launch(), errors = [], results = [];
try {
 const session = await request(live,'POST',LIVE + '/api/sessions',{requestID:randomUUID(),createdAt:Date.now(),snapshot:state});
 await command('POST','/devices',{label:'局面记录网页测试'});
 const team = await command('POST','/teams',{name:'路线姓名合成队',nickname:'测试记录员'});
 const match = fixture(); match.formatVersion = 2; match.payload.rules.recordingMethod = 'situation';
 match.projection = {...structuredClone(state), gameID:match.payload.id};
 for (const key of ['inning','isTop','balls','strikes','outs']) match.payload.state[key] = state[key];
 for (const side of ['home','away']) {
  match.payload.state[side+'Team'].name=state[side].name; match.payload.state[side+'RunsByInning']=[...state[side].innings];
  match.payload.state[side+'Hits']=state[side].hits; match.payload.state[side+'Errors']=state[side].errors;
 }
 const game=await command('POST',`/teams/${team.teamID}/games`,match),path=`/teams/${team.teamID}/games/${game.id}`;
 const opened=await command('POST',path+'/live',{enabled:true});
 match.payload.status=1; match.payload.state.isFinal=true;
 Object.assign(match.projection,{isFinal:true,endedAt:Date.now(),batterOrder:null,currentAppearanceID:null});
 await command('PUT',path,{...match,baseRevision:1,writerEpoch:1});
 await command('POST',path+'/access',{mode:'public',baseAccessVersion:1});
 for (const [name,url] of [
  ['legacy-live',`${origin(live)}${LIVE}/${session.code}`],
  ['shared-live',`${origin(cloud)}/watch/#live/${opened.liveCode}`],
  ['shared-history',`${origin(cloud)}/watch/#${game.viewCode}`]
 ]) for (const [width,height] of [[393,852],[402,874]]) for (const colorScheme of ['light','dark']) {
  const context=await browser.newContext({viewport:{width,height},colorScheme}),page=await context.newPage();
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto(url); await page.locator('#match').waitFor({state:'visible'});
  await page.locator('#entry-pa-1 summary').click();
  assert.ok((await page.locator('#entry-pa-1').innerText()).includes(route));
  assert.ok((await page.locator('#entry-pa-1').innerText()).includes('地滚 · 内野中间'));
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  const test=`${name}-${width}-${colorScheme}`;
  await page.locator('#entry-pa-1').screenshot({path:new URL(test+'.png',output).pathname});
  results.push(test); await context.close();
 }
 assert.deepEqual(errors,[]);
 writeFileSync(new URL('results.json',output),JSON.stringify({results,errors,route,productionAccess:false},null,2));
 console.log(`PASS: ${results.length} WebKit route-name, contact-text and overflow checks.`);
} finally {
 await browser.close();
 for(const app of [live,cloud]){app.server.closeAllConnections();await new Promise(r=>app.server.close(r));}
 cloud.store.close();
}
