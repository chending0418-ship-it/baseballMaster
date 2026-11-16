import { createCollaborationServer } from '../server.mjs';
const app = createCollaborationServer({
  gameAnalysis: {
    apiKey: 'ui-test-only',
    dailyLimit: 100,
    fetcher: async (_url, request) => {
      const input = JSON.parse(JSON.parse(request.body).messages[1].content);
      const mvp = input.players.find(player => player.batting[2] > 0);
      const report = {
        overview: '本次分析覆盖 ' + input.games.length + ' 场比赛。',
        situations: ['关键局面会结合这些比赛的比分、出局与垒况。'],
        players: input.players.map(p => ({ id: p.id, evaluation: p.id + '的表现以已记录数据为准。' })),
        mvp: mvp ? { id: mvp.id, reason: mvp.id + '用安打创造了进攻机会。' } : null
      };
      console.log('scope=%s games=%d players=%d', input.scope, input.games.length, input.players.length);
      return Response.json({ choices: [{ message: { content: JSON.stringify(report) } }] });
    }
  }
});
app.server.listen(18843, '127.0.0.1');
