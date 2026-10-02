export function snapshot(revision = 1) {
  const batter = { id: 'batter-1', name: '陈昊', number: '00' };
  return { schema: 1, gameID: 'fixture-game', revision, mode: 'standard', isFinal: false, endedAt: null,
    inning: 2, isTop: true, balls: 1, strikes: 2, outs: 1,
    away: { name: '青岛海浪', runs: 2, hits: 3, errors: 0, innings: [1, 1, 0, 0, 0, 0] },
    home: { name: '济南猎鹰', runs: 1, hits: 2, errors: 1, innings: [1, 0, 0, 0, 0, 0] },
    batter, batterOrder: 4, pitcher: { id: 'pitcher-1', name: '赵一鸣', number: '17' }, pitchCount: 28,
    appearancePitchCount: 3, pitchLimit: null, bases: [{ base: 2, player: { id: 'runner-1', name: '周子墨', number: '7' } }], currentAppearanceID: 'pa-8', notice: '',
    entries: [
      { id: 'pa-1', appearanceID: 'pa-1', inning: 1, isTop: true, kind: 'appearance', label: '1B', summary: '陈昊：中外野一垒安打，上一垒。', player: batter, status: 'completed', details: [{ id: 'event-1', text: '陈昊：一垒安打（1B）' }] },
      { id: 'pa-2', appearanceID: 'pa-2', inning: 1, isTop: false, kind: 'appearance', label: 'K', summary: '林宇轩：挥棒三振出局，三出局。', player: { id: 'batter-2', name: '林宇轩', number: '18' }, status: 'completed', details: [] },
      { id: 'pa-7', appearanceID: 'pa-7', inning: 2, isTop: true, kind: 'appearance', label: '2B', summary: '周子墨：右外野二垒安打，王星野回本垒得分。', player: { id: 'runner-1', name: '周子墨', number: '7' }, status: 'completed', details: [{ id: 'event-7', text: '周子墨：右外野二垒安打（2B），1 分打点' }] },
      { id: 'pa-8', appearanceID: 'pa-8', inning: 2, isTop: true, kind: 'appearance', label: 'F', summary: '陈昊：界外球（1 坏 2 好）', player: batter, status: 'current', details: [{ id: 'event-8', text: '陈昊：坏球（1 坏 0 好）' }, { id: 'event-9', text: '陈昊：看好球（1 坏 1 好）' }, { id: 'event-10', text: '陈昊：界外球（1 坏 2 好）' }] }
    ] };
}

// Public, synthetic 2.2 projection. The original fixture remains the released 2.1 shape.
export function uiSnapshot(revision = 1) {
  const s = snapshot(revision);
  s.inning = 3; s.balls = 2; s.strikes = 1;
  s.home.name = '北京飞鹰'; s.home.innings = [1, 0, 0, 0, 0, 0, 0, 0, 0];
  s.away.innings = [0, 2, 0, 0, 0, 0, 0, 0, 0];
  s.away.hits = 4; s.home.hits = 3; s.batter.number = '12';
  s.pitcher = { id: 'pitcher-1', name: '王星野', number: '23' };
  s.hasStarted = true;
  s.rules = { scheduledInnings: 9, halfInningRunLimit: 6, timeLimitMinutes: null };
  s.clock = { startedAt: Date.now() - 1_578_000, runningSince: Date.now(), elapsedSeconds: 1578 };
  s.batterStats = { atBats: 2, hits: 1, isComplete: true };
  s.nextBatters = [{ player: { id: 'runner-1', name: '周子墨', number: '7' }, order: 5 },
    { player: { id: 'next-2', name: '林宇轩', number: '18' }, order: 6 }];
  s.bases = [{ base: 1, player: { id: 'runner-a', name: '赵一鸣', number: '17' } },
    { base: 2, player: { id: 'runner-1', name: '周子墨', number: '7' } }];
  for (const [side, shortName, played] of [[s.away, '海浪', 3], [s.home, '飞鹰', 2]]) {
    side.shortName = shortName; side.playedInnings = side.innings.map((_, i) => i < played);
    side.lineup = Array.from({ length: 9 }, (_, i) => ({ order: i + 1,
      player: { id: `${shortName}-${i}`, name: ['李泽宇', '张子涵', '赵一鸣', '陈昊', '周子墨', '林宇轩', '吴晓峰', '刘家豪', '黄浩然'][i], number: String(i + 1) },
      position: ['投手', '捕手', '一垒手', '二垒手', '三垒手', '游击手', '左外野手', '中外野手', '右外野手'][i] }));
  }
  s.away.lineup[3].player = { ...s.batter };
  s.away.lineup[4].player = { ...s.nextBatters[0].player };
  s.away.lineup[5].player = { ...s.nextBatters[1].player };
  s.home.lineup[0].player = { ...s.pitcher }; s.home.pitcherID = s.pitcher.id;
  s.away.pitcherID = s.away.lineup[0].player.id;
  const situation = (bases, halfEnded = false) => ({ balls: 0, strikes: 0, outs: halfEnded ? 0 : 1, bases, halfEnded });
  s.entries[0].situation = situation([1]); s.entries[0].order = 4;
  s.entries[1].situation = situation([], true); s.entries[1].order = 3;
  s.entries[2].inning = 3; s.entries[2].situation = situation([2]); s.entries[2].order = 3;
  const current = s.entries[3]; current.inning = 3; current.order = 4;
  current.situation = { ...situation([1, 2]), balls: 2, strikes: 1 };
  current.details[0].situation = { ...situation([2]), balls: 1 };
  current.details[1].situation = { ...situation([1, 2]), balls: 1, strikes: 1 };
  current.details[2].situation = { ...current.situation };
  for (const entry of s.entries.slice(0, 3)) for (const d of entry.details) d.situation = structuredClone(entry.situation);
  return s;
}
