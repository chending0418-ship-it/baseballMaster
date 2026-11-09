// Loopback-only interactive demo. The test team and matches use in-memory services.
import { randomBytes, randomUUID } from 'node:crypto';
import { createCollaborationServer } from '../../collaboration/server.mjs';
import { BASE, createLiveServer } from '../server.mjs';
import { uiSnapshot } from './fixture.mjs';

const team = createCollaborationServer();
const live = createLiveServer();
const serveTeam = team.server.listeners('request')[0];
const serveLive = live.server.listeners('request')[0];
const port = Number(process.env.BM_BROADCAST_DEMO_PORT || 18788);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('无效的本地演示端口');
const origin = 'http://127.0.0.1:' + port;
const key = randomBytes(32).toString('base64url');
const games = [uiSnapshot(), uiSnapshot()];
for (const [index, game] of games.entries()) {
  game.gameID = randomUUID();
  game.home.name = index ? '济南猎鹰' : '北京飞鹰';
  game.home.shortName = index ? '猎鹰' : '飞鹰';
}
let path, sourceCode, message = '球队已创建，四条链接已可以使用。当前没有比赛。';
let busy = false;

async function api(method, path, body) {
  const response = await fetch(origin + '/api/v3' + path, {
    method, headers: { 'X-BM-Protocol': '1', 'Content-Type': 'application/json', Authorization: 'Bearer ' + key },
    body: body ? JSON.stringify(body) : undefined
  });
  if (!response.ok) throw new Error('HTTP ' + response.status);
  return response.json();
}

function home(res) {
  const sources = [
    ['比分＋投手', 'score', '', '640 × 116'],
    ['当前打者', 'batter', '', '600 × 108'],
    ['客队阵容', 'lineup', '&team=away', '440 × 540'],
    ['主队阵容', 'lineup', '&team=home', '440 × 540']
  ];
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>球队转播 · 本地试用</title><style>
body{margin:0;background:#f4f5ef;color:#092a2a;font:16px/1.55 Arial,sans-serif}
main{max-width:680px;margin:auto;padding:32px 20px} h1{margin:0} h2{margin:26px 0 8px}
ul{list-style:none;padding:0} li{display:flex;gap:12px;padding:9px 0;border-bottom:1px solid #aebfb4}
li a{color:#154b3b} form{display:flex;gap:8px;flex-wrap:wrap} button{font:inherit;padding:8px 10px;min-height:44px;color:#f4f5ef;background:#092a2a;border:0;border-radius:5px}
</style><main><h1>青岛海浪 · 球队转播</h1><p>本地演示 · 合成比赛</p>
<h2>固定转播源</h2><ul>${sources.map(([name, view, side, size]) =>
  `<li><a target="_blank" rel="noopener" href="${BASE}/overlay?view=${view}${side}#team/${sourceCode}">${name}</a><span>${size}</span></li>`).join('')}</ul>
<h2>模拟记分</h2><p role="status">${message}</p>
<form method="post" action="/demo"><button name="action" value="first">第一场开赛</button>
<button name="action" value="second">第二场开赛</button>
<button name="action" value="pitch">记一球</button>
<button name="action" value="switch">手动切换第二场</button>
<button name="action" value="final">终场</button></form>
</main></html>`);
}

async function action(req, res) {
  if (req.headers.origin !== origin || busy) { res.writeHead(403).end(); return; }
  busy = true;
  try {
    let raw = '';
    for await (const chunk of req) {
      raw += chunk;
      if (raw.length > 120) { res.writeHead(413).end(); return; }
    }
    const command = new URLSearchParams(raw).get('action');
    const active = await api('GET', path);
    const selected = games.find(game => game.gameID === active.activeGameID);
    const game = command === 'first' ? games[0] : ['second', 'switch'].includes(command) ? games[1] : selected;
    if (!game || !['first', 'second', 'pitch', 'switch', 'final'].includes(command)) {
      message = '请先开始一场比赛。';
    } else if (game.isFinal) {
      message = '这场比赛已结束，可以开始另一场。';
    } else {
      if (command === 'pitch') { game.pitchCount++; game.balls = (game.balls + 1) % 4; }
      if (command === 'final') { game.isFinal = true; game.endedAt = Date.now(); game.batterOrder = null; }
      game.revision++;
      const data = { snapshot: game, pitching: { strikes: 17, strikeouts: 2 } };
      if (command === 'switch') data.expectedGameID = active.activeGameID;
      const result = await api('PUT', path, data);
      message = result.selected
        ? command === 'final' ? '比赛已结束，转播保留终场比分一分钟。' : '当前转播 ' + result.activeMatch
        : '固定源仍在转播第一场。要切过去，请点「手动切换第二场」。';
    }
    res.writeHead(303, { Location: '/' }).end();
  } catch {
    res.writeHead(500).end('本地演示操作失败');
  } finally { busy = false; }
}

team.server.removeListener('request', serveTeam);
team.server.on('request', (req, res) => {
  if (req.url === '/' && req.method === 'GET' && sourceCode) home(res);
  else if (req.url === '/demo' && req.method === 'POST' && sourceCode) action(req, res);
  else if (req.url.startsWith(BASE + '/')) serveLive(req, res);
  else serveTeam(req, res);
});
await new Promise(resolve => team.server.listen(port, '127.0.0.1', resolve));
await api('POST', '/devices', { label: '本地转播演示' });
const { teamID } = await api('POST', '/teams', { operationID: randomUUID(), name: '青岛海浪', nickname: '记录员' });
path = '/teams/' + teamID + '/broadcast';
({ code: sourceCode } = await api('GET', path));
console.log('球队固定转播源本地演示 ' + origin + '/');
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => team.server.close(() => {
    team.store.close();
    live.server.emit('close');
  }));
}
