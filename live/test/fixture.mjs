export function snapshot(revision = 1) {
  const batter = { id: 'batter-1', name: '陈昊', number: '00' };
  return { schema: 1, gameID: 'fixture-game', revision, mode: 'standard', isFinal: false, endedAt: null,
    inning: 2, isTop: true, balls: 1, strikes: 2, outs: 1,
    away: { name: '青岛海浪', runs: 2, hits: 3, errors: 0, innings: [1, 1, 0, 0, 0, 0] },
    home: { name: '济南猎鹰', runs: 1, hits: 2, errors: 1, innings: [1, 0, 0, 0, 0, 0] },
    batter, pitcher: { id: 'pitcher-1', name: '赵一鸣', number: '17' }, pitchCount: 28,
    appearancePitchCount: 3, pitchLimit: null, bases: [{ base: 2, player: { id: 'runner-1', name: '周子墨', number: '7' } }], currentAppearanceID: 'pa-8', notice: '',
    entries: [
      { id: 'pa-1', appearanceID: 'pa-1', inning: 1, isTop: true, kind: 'appearance', label: '1B', summary: '陈昊：中外野一垒安打，上一垒。', player: batter, status: 'completed', details: [{ id: 'event-1', text: '陈昊：一垒安打（1B）' }] },
      { id: 'pa-2', appearanceID: 'pa-2', inning: 1, isTop: false, kind: 'appearance', label: 'K', summary: '林宇轩：挥棒三振出局，三出局。', player: { id: 'batter-2', name: '林宇轩', number: '18' }, status: 'completed', details: [] },
      { id: 'pa-7', appearanceID: 'pa-7', inning: 2, isTop: true, kind: 'appearance', label: '2B', summary: '周子墨：右外野二垒安打，王星野回本垒得分。', player: { id: 'runner-1', name: '周子墨', number: '7' }, status: 'completed', details: [{ id: 'event-7', text: '周子墨：右外野二垒安打（2B），1 分打点' }] },
      { id: 'pa-8', appearanceID: 'pa-8', inning: 2, isTop: true, kind: 'appearance', label: 'F', summary: '陈昊：界外球（1 坏 2 好）', player: batter, status: 'current', details: [{ id: 'event-8', text: '陈昊：坏球（1 坏 0 好）' }, { id: 'event-9', text: '陈昊：看好球（1 坏 1 好）' }, { id: 'event-10', text: '陈昊：界外球（1 坏 2 好）' }] }
    ] };
}
