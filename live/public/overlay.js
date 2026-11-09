'use strict';

const $ = id => document.getElementById(id);
const put = (id, value) => { $(id).textContent = value; };
const teamName = team => team.shortName || team.name;
const number = player => player?.number ? '#' + player.number : '';
const positions = new Map(Object.entries({
  投手: 'P', 捕手: 'C', 一垒手: '1B', 二垒手: '2B', 三垒手: '3B',
  游击手: 'SS', 左外野手: 'LF', 中外野手: 'CF', 右外野手: 'RF'
}));
const results = new Map(Object.entries({
  '1B': '一垒安打', '2B': '二垒安打', '3B': '三垒安打', HR: '本垒打',
  BB: '保送', HBP: '触身球', K: '三振', 'K-BUNT': '触击界外三振'
}));

function showScore(snapshot, box) {
  for (const side of ['away', 'home']) {
    put(side + '-name', teamName(snapshot[side]));
    put(side + '-runs', snapshot[side].runs);
  }
  const playing = !snapshot.isFinal && snapshot.hasStarted !== false;
  put('inning', snapshot.isFinal ? '终场' : playing ? snapshot.inning + (snapshot.isTop ? '局上' : '局下') : '赛前');
  put('outs', playing ? snapshot.outs + ' 出局' : '');
  $('count-row').hidden = !playing;
  const occupied = snapshot.bases.map(runner => runner.base);
  for (const diamond of $('bases').children) {
    diamond.classList.toggle('occupied', occupied.includes(Number(diamond.dataset.base)));
  }
  $('bases').setAttribute('aria-label', occupied.length ? occupied.join('、') + ' 垒有人' : '垒上无人');
  put('count', snapshot.balls + '–' + snapshot.strikes);
  $('count').setAttribute('aria-label', snapshot.balls + ' 坏球，' + snapshot.strikes + ' 好球');

  const pitcher = snapshot.isFinal ? null : snapshot.pitcher;
  const coachPitch = snapshot.mode === 'coachPitch';
  put('pitcher-label', coachPitch ? 'P 位守备' : '投手');
  put('pitcher-name', pitcher?.name || '—');
  put('pitcher-number', number(pitcher));
  const line = pitcher && !coachPitch ? box?.pitching?.find(row => row.player.id.toLowerCase() === pitcher.id.toLowerCase())?.values : null;
  const total = snapshot.pitchCount ?? line?.pitches;
  put('pitches', !pitcher ? '' : coachPitch
    ? '本打席 ' + snapshot.appearancePitchCount + (snapshot.pitchLimit ? '/' + snapshot.pitchLimit : '') + ' 球'
    : total == null ? '球数未记录' : '本场 ' + total + ' 球');
  put('pitch-strikes', line?.strikes == null ? '' : line.strikes + ' 好球');
  put('pitch-strikeouts', line?.strikeouts == null ? '' : line.strikeouts + ' 三振');
  return true;
}

function appearanceResult(entry) {
  if (results.has(entry.label)) return results.get(entry.label);
  const prefix = entry.player.name + '：';
  return (entry.summary.startsWith(prefix) ? entry.summary.slice(prefix.length) : entry.summary) || entry.label;
}

function showBatter(snapshot) {
  const batter = snapshot.batter;
  if (!batter || snapshot.isFinal || snapshot.hasStarted === false) return false;
  put('batting-team', teamName(snapshot[snapshot.isTop ? 'away' : 'home']));
  put('batter-order', snapshot.batterOrder == null ? '' : '第 ' + snapshot.batterOrder + ' 棒');
  put('batter-name', batter.name);
  put('batter-number', number(batter));
  const stats = snapshot.batterStats;
  put('batter-stats', stats
    ? `本场 ${stats.hits} 安打 / ${stats.atBats} 打数${stats.isComplete ? '' : ' · 待确认'}`
    : '本场打击统计未记录');
  const appearances = snapshot.entries.filter(entry => entry.kind === 'appearance'
    && entry.status === 'completed' && entry.appearanceID !== snapshot.currentAppearanceID
    && entry.player?.id === batter.id);
  put('appearances', appearances.length
    ? appearances.slice(-3).map(appearanceResult).join(' · ')
    : '暂无已完成打席记录');
  return true;
}

function showLineup(snapshot, side, page) {
  const team = snapshot[side];
  const lineup = [...(team.lineup ?? [])].sort((a, b) => (a.order ?? Infinity) - (b.order ?? Infinity));
  const pageSize = 11;
  const pageCount = Math.max(1, Math.ceil(lineup.length / pageSize));
  const pageIndex = Math.min(page, pageCount) - 1;
  put('lineup-team', team.name);
  const label = snapshot.hasStarted === false ? '先发阵容' : '当前阵容';
  put('lineup-title', pageCount === 1 ? label : `${label} ${pageIndex + 1}/${pageCount}`);
  const rows = lineup.slice(pageIndex * pageSize, (pageIndex + 1) * pageSize).map(({ order, player, position }) => {
    const row = document.createElement('li');
    for (const value of [order ?? '—', player.name, number(player), positions.get(position) ?? position]) {
      const cell = document.createElement('span');
      cell.className = 'truncate';
      cell.textContent = value;
      row.append(cell);
    }
    return row;
  });
  $('lineup-rows').replaceChildren(...rows);
  put('lineup-empty', team.lineup ? '当前阵容为空' : '直播未提供阵容');
  $('lineup-empty').hidden = rows.length > 0;
  return true;
}

function start() {
  const params = new URLSearchParams(location.search);
  const component = params.get('view');
  if (!['score', 'batter', 'lineup'].includes(component)) return;
  const [, kind, code] = /^#(temporary|shared)\/([A-Za-z0-9_-]+)$/.exec(location.hash) ?? [];
  const shared = kind === 'shared';
  if (!code || !(shared ? /^[A-Za-z0-9_-]{32}$/ : /^[A-Fa-f0-9]{24}$/).test(code)) return;
  const endpoint = shared ? '/api/v3/live/' + code : '/livestreaming/novideo/api/sessions/' + code;
  const side = params.get('team') === 'home' ? 'home' : 'away';
  const page = Math.max(1, Number.parseInt(params.get('page'), 10) || 1);
  const source = $(component);
  const render = component === 'score' ? showScore : component === 'batter' ? showBatter
    : snapshot => showLineup(snapshot, side, page);
  let revision, visible = false, hideTimer;
  const refresh = async () => {
    try {
      const response = await fetch(endpoint + (!shared && revision !== undefined ? '?since=' + revision : ''), {
        cache: 'no-store', credentials: 'omit',
        headers: shared ? { 'X-BM-Protocol': '1' } : {},
        signal: AbortSignal.timeout(6000)
      });
      if ([401, 403, 404, 410].includes(response.status)) {
        clearTimeout(hideTimer);
        source.hidden = true;
        return;
      }
      if (!response.ok) throw new Error('读取直播失败');
      const data = await response.json();
      const next = shared ? data.serverRevision : data.revision;
      if (data.snapshot && next !== revision) {
        visible = render(data.snapshot, data.box);
        source.dataset.revision = next;
        revision = next;
      }
      clearTimeout(hideTimer);
      const serverDate = Date.parse(response.headers.get('Date'));
      const serverTime = shared ? (Number.isFinite(serverDate) ? serverDate : Date.now()) : data.serverTime;
      const remaining = data.expiresAt - serverTime;
      if (!Number.isFinite(remaining) || remaining <= 0) {
        source.hidden = true;
        return;
      }
      source.hidden = !visible;
      // A source that has lost connectivity should not leave an old score on air indefinitely.
      hideTimer = setTimeout(() => { source.hidden = true; }, Math.min(remaining, 20_000));
    } catch {
      // Leave the previous revision on screen until its visibility timer expires.
    }
    setTimeout(refresh, 5000);
  };
  window.addEventListener('hashchange', () => location.reload(), { once: true });
  refresh();
}

start();
