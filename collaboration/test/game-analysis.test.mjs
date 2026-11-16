import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { createCollaborationServer } from '../server.mjs';

const player = (id, side) => ({
  id, side, games: 1, batting: [4, 3, 2, 5, 1, 2, 1, 0, 0, 0, 0], fielding: [2, 1, 0, 0]
});
const game = {
  mode: 'standard', ownHome: true, away: [0, 2, 0], home: [1, 0, 3], totals: [2, 3, 0, 4, 6, 0],
  incomplete: false, events: [{ inning: 3, top: false, kind: 'HR', batter: 'P1',
    before: [3, 0, 2, 1, 0, 1], beforeScore: [2, 1], afterScore: [2, 4], pending: false }]
};
const input = { scope: 'single', games: [game], players: [player('P1', 1), player('P2', 0)] };

async function serverTest(t, options) {
  const app = createCollaborationServer(options);
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    app.server.closeAllConnections();
    await new Promise(resolve => app.server.close(resolve));
    app.store.close();
  });
  const url = 'http://127.0.0.1:' + app.server.address().port + '/api/v3/';
  const request = async (method, path, body, token) => {
    const headers = { 'X-BM-Protocol': '1', 'Content-Type': 'application/json' };
    if (token) headers.Authorization = 'Bearer ' + token;
    const response = await fetch(url + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
    return { status: response.status, body: await response.json() };
  };
  async function enroll() {
    const token = randomBytes(32).toString('base64url');
    assert.equal((await request('POST', 'devices', { label: 'QA' }, token)).status, 200);
    return token;
  }
  return { request, enroll };
}

test('game analysis is device-scoped, asynchronous and sends only allowed baseball data', async t => {
  let up, sent = 0;
  const result = new Promise(resolve => { up = resolve; });
  const { request, enroll } = await serverTest(t, { gameAnalysis: {
    apiKey: 'fixture-key', fetcher: async (url, request) => {
      sent++;
      assert.equal(url.href, 'https://api.vveai.com/v1/chat/completions');
      const body = JSON.parse(request.body);
      assert.equal(request.headers.Authorization, 'Bearer fixture-key');
      assert.deepEqual(JSON.parse(body.messages[1].content), input);
      await result;
      return Response.json({ choices: [{ message: { content: JSON.stringify({
        overview: '主队第 3 局反超。', situations: ['P1击出本垒打。'],
        players: [{ id: 'P1', evaluation: '两支安打，贡献 5 个总垒数。' }, { id: 'P2', evaluation: '本场有 2 支安打。' }],
        mvp: { id: 'P1', reason: '第 3 局的长打改变了比分。' }, extra: 'ignored'
      }) } }] });
    }
  } });
  const first = await enroll(), other = await enroll();
  const data = { requestID: randomUUID(), input };
  assert.equal((await request('POST', 'game-analysis', data)).status, 401);
  const invalid = structuredClone(data);
  invalid.input.players[0].name = 'fixture name';
  assert.equal((await request('POST', 'game-analysis', invalid, first)).status, 400);
  const started = await request('POST', 'game-analysis', data, first);
  assert.deepEqual(started, { status: 202, body: { state: 'pending' } });
  const reorderedInput = { players: input.players, games: input.games, scope: input.scope };
  assert.equal((await request('POST', 'game-analysis', { ...data, input: reorderedInput }, first)).status, 202);
  assert.equal((await request('POST', 'game-analysis', { ...data, requestID: randomUUID() }, first)).status, 429);
  assert.equal((await request('GET', 'game-analysis/' + data.requestID, null, other)).status, 404);
  assert.equal(sent, 1);
  up();
  const response = await request('GET', 'game-analysis/' + data.requestID, null, first);
  assert.equal(response.body.state, 'ready');
  assert.deepEqual(response.body.report.players.map(p => p.id), ['P1', 'P2']);
  assert.equal(response.body.report.mvp.id, 'P1');
  assert.equal(response.body.report.extra, undefined);
});

test('selected games are retained, incomplete provider output is rejected, and the daily bound applies', async t => {
  const selection = { scope: 'filtered', games: [game, { ...game, mode: 'slowPitch' }], players: [player('P1', 2)] };
  const { request, enroll } = await serverTest(t, { gameAnalysis: {
    apiKey: 'fixture-key', dailyLimit: 1, fetcher: async (_, request) => {
      assert.equal(JSON.parse(JSON.parse(request.body).messages[1].content).games.length, 2);
      return Response.json({ choices: [{ message: { content: JSON.stringify({
        overview: '概要', situations: [], players: [{ id: 'P9', evaluation: '未知球员' }], mvp: null
      }) } }] });
    }
  } });
  const token = await enroll();
  const id = randomUUID();
  assert.equal((await request('POST', 'game-analysis', { requestID: id, input: selection }, token)).status, 202);
  assert.deepEqual((await request('GET', 'game-analysis/' + id, null, token)).body, { state: 'failed' });
  const blocked = await request('POST', 'game-analysis', { requestID: randomUUID(), input }, token);
  assert.equal(blocked.status, 429);
  assert.equal(blocked.body.error.code, 'analysis_limit');
});

test('game analysis is off when the feature key is absent', async t => {
  const { request, enroll } = await serverTest(t);
  const response = await request('POST', 'game-analysis', { requestID: randomUUID(), input }, await enroll());
  assert.equal(response.status, 503);
  assert.equal(response.body.error.code, 'analysis_unavailable');
});
