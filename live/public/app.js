'use strict';
const BASE = '/livestreaming/novideo';
const $ = id => document.getElementById(id);
const code = location.pathname.split('/').filter(Boolean)[2]?.toUpperCase();
let snapshot, metadata, pending, revision, timer, stopped = false, visibleHalves = 4, clockOffset = 0, inFlight = false, networkFailed = false;
let view = 'live', lineupTeam = 'away', liveScroll = 0;
const text = (id, value) => { $(id).textContent = value; };
const node = (tag, value, className) => { const el = document.createElement(tag); if (value !== undefined) el.textContent = value; if (className) el.className = className; return el; };
const halfKey = e => `${e.inning}-${e.isTop ? 'top' : 'bottom'}`;
const halfName = e => `${e.inning} 局${e.isTop ? '上' : '下'}`;
const player = p => p ? `#${p.number} ${p.name}` : '—';
const time = t => new Date(t).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
const baseNames = { 1: '一', 2: '二', 3: '三' };
function baseDisplay(list) {
  const box = node('div', undefined, 'entry-bases');
  if (!Array.isArray(list)) { box.append(node('span', '垒况未记录')); return box; }
  const occupied = [...list].sort();
  const description = occupied.length ? `${occupied.map(b => baseNames[b]).join('、')}垒有人` : '垒上无人';
  box.setAttribute('aria-label', description);
  const diamond = node('div', undefined, 'mini-bases'); diamond.setAttribute('aria-hidden', 'true');
  for (const [b, name] of [[2, 'second'], [3, 'third'], [1, 'first']]) diamond.append(node('i', undefined, `mini-${name}${occupied.includes(b) ? ' occupied' : ''}`));
  box.append(diamond, node('span', occupied.length === 3 ? '满垒' : description.replaceAll('、', '')));
  return box;
}
function expire(message = '本场直播已到期，链接已失效，不再提供比赛记录。') {
  stopped = true; clearTimeout(timer); snapshot = pending = metadata = null; revision = null;
  document.title = '文字直播 · BaseballMaster';
  document.querySelector('meta[property="og:title"]').content = document.title;
  for (const meta of document.querySelectorAll('meta[name="description"],meta[property="og:description"]')) meta.content = '比赛文字直播';
  $('match').hidden = true; $('match').replaceChildren(); text('message', message);
}
function updateClock() {
  if (!snapshot || stopped) return;
  const s = snapshot, c = s.clock;
  if (!c) { text('game-time', s.hasStarted === false ? '尚未开赛' : '开赛时间未记录'); return; }
  if (!s.isFinal && s.hasStarted === false) { text('game-time', '尚未开赛'); return; }
  const now = Date.now() + clockOffset;
  const stale = networkFailed || metadata && now - metadata.lastSeen > 45000;
  const until = s.isFinal ? s.endedAt : stale && metadata ? Math.min(now, metadata.lastSeen) : now;
  const elapsed = Math.floor(c.elapsedSeconds + (c.runningSince ? Math.max(0, until - c.runningSince) / 1000 : 0));
  const start = c.startedAt ? `${new Date(c.startedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })} 开赛` : '开赛时间未记录';
  const measured = c.startedAt || c.runningSince || elapsed > 0;
  const remaining = s.rules?.competitionFormat === 'timed' && Number.isFinite(s.rules.timeLimitMinutes) ? Math.max(0, s.rules.timeLimitMinutes * 60 - elapsed) : null;
  const duration = remaining !== null && !s.isFinal ? `剩余 ${Math.floor(remaining / 60)} 分 ${remaining % 60} 秒` : measured ? `${s.isFinal ? '比赛用时' : '已进行'} ${Math.floor(elapsed / 60)} 分 ${elapsed % 60} 秒` : '未计时';
  const pause = measured && !s.isFinal && !c.runningSince ? ' · 计时暂停' : '';
  text('game-time', `${start} · ${duration}${pause}`);
}
function played(s, side, index, isHome) {
  if (side.playedInnings) return side.playedInnings[index];
  if (s.hasStarted === false) return false;
  const half = index * 2 + (isHome ? 1 : 0), current = (s.inning - 1) * 2 + (s.isTop ? 0 : 1);
  if (half < current || side.innings[index] > 0) return true;
  if (half > current) return false;
  return !s.isFinal || s.entries.some(e => e.inning === index + 1 && e.isTop !== isHome && e.kind === 'appearance' && e.details.length);
}
function renderLineups(s) {
  const side = s[lineupTeam];
  for (const team of ['away', 'home']) { text(team + '-tab', `${team === 'away' ? '客队' : '主队'} · ${s[team].name}`); $(team + '-tab').setAttribute('aria-selected', String(team === lineupTeam)); }
  $('roster-panel').setAttribute('aria-labelledby', lineupTeam + '-tab');
  const roster = side.lineup;
  const p = roster?.find(row => row.player.id === side.pitcherID)?.player;
  text('lineup-pitcher', `${s.mode === 'coachPitch' ? '当前 P 位守备' : '当前投手'}：${player(p)}`);
  $('lineup-empty').hidden = !!roster?.length;
  text('lineup-empty', roster ? '当前阵容为空' : '此场直播未提供当前阵容');
  $('roster-body').replaceChildren(...(roster || []).map(row => {
    const current = !s.isFinal && s.hasStarted !== false && s.batter?.id === row.player.id;
    const tr = node('tr', undefined, current ? 'roster-current' : '');
    tr.append(node('td', row.order ?? '—'));
    const name = node('td'); name.append(node('span', '#' + row.player.number, 'roster-number'), node('span', row.player.name));
    if (current) name.append(node('span', '打击中', 'roster-now'));
    tr.append(name, node('td', row.position)); return tr;
  }));
}
function render(s) {
  const open = new Set([...document.querySelectorAll('details[open]')].map(e => e.id));
  const anchor = view === 'live' && window.scrollY > 0 ? [...document.querySelectorAll('.entry')].find(e => e.getBoundingClientRect().top >= 0 && e.getBoundingClientRect().top < innerHeight && e.getBoundingClientRect().height > 0) : null;
  const anchorTop = anchor?.getBoundingClientRect().top, anchorID = anchor?.id;
  const selectedHalf = $('half-select').value;
  snapshot = s; $('match').hidden = false; text('message', '');
  document.title = `${s.away.name} vs ${s.home.name}｜文字直播`;
  document.querySelector('meta[property="og:title"]').content = document.title;
  for (const meta of document.querySelectorAll('meta[name="description"],meta[property="og:description"]')) meta.content = `使用 BaseballMaster 观看 ${s.away.name} vs ${s.home.name} 的比赛文字直播。`;
  text('phase', s.isFinal ? '终场' : s.hasStarted === false ? '未开始' : halfName(s));
  text('game-status', s.isFinal ? '比赛已结束' : s.hasStarted === false ? '等待开赛' : '进行中');
  text('mode', s.mode === 'slowPitch' ? '成人慢垒' : s.mode === 'coachPitch' ? '教练投手' : '普通比赛');
  const rules = s.rules ? [...(s.rules.competitionFormat === 'timed' ? [`时间赛 ${s.rules.timeLimitMinutes ?? '待定'} 分钟`, ...(s.rules.inningsLimit ? [`局数上限 ${s.rules.inningsLimit}`] : [])] : [`${s.rules.scheduledInnings} 局`]), ...(s.rules.initialBalls !== undefined ? [`初始 ${s.rules.initialStrikes} 好 ${s.rules.initialBalls} 坏`] : []), ...(s.rules.twoStrikeFoulPolicy ? [s.rules.twoStrikeFoulPolicy === 'outImmediately' ? '两好后界外出局' : '再允许一次界外'] : []), ...(s.rules.fieldersCount ? [`${s.rules.fieldersCount} 人守备`] : []), ...(s.strikes >= 2 && s.rules.twoStrikeFoulPolicy === 'oneExtraFoul' && typeof s.rules.extraFoulUsed === 'boolean' ? [s.rules.extraFoulUsed ? '额外界外机会已用' : '仍允许一次界外'] : []), ...(s.rules.halfInningRunLimit ? [`半局 ${s.rules.halfInningRunLimit} 分换边`] : []), ...(s.rules.timeLimitMinutes ? [`限时 ${s.rules.timeLimitMinutes} 分钟`] : [])] : [];
  text('rules', rules.length ? '· ' + rules.join(' · ') : '');
  text('away-name', s.away.name); text('home-name', s.home.name); text('away-score', s.away.runs); text('home-score', s.home.runs);
  const innings = s.rules?.competitionFormat === 'timed' ? Math.max(1, s.inning, s.away.innings.reduce((last, n, i) => n > 0 ? i + 1 : last, 0), s.home.innings.reduce((last, n, i) => n > 0 ? i + 1 : last, 0)) : Math.max(s.away.innings.length, s.home.innings.length, s.inning);
  const head = node('tr'); ['', ...Array.from({length: innings}, (_, i) => i + 1), 'R', 'H', 'E'].forEach((v, i) => { const th = node('th', v, i === innings + 1 ? 'total' : ''); th.scope = 'col'; head.append(th); });
  $('innings-head').replaceChildren(head);
  const teamCol = node('col', undefined, 'team-col'), otherCols = node('col'); otherCols.span = innings + 3; $('innings-cols').replaceChildren(teamCol, otherCols);
  document.querySelector('.innings').classList.toggle('wide', innings > 9);
  $('innings-body').replaceChildren(...['away', 'home'].map(team => {
    const side = s[team], row = node('tr'), th = node('th', side.shortName || side.name); th.scope = 'row'; row.append(th);
    for (let i = 0; i < innings; i++) {
      const known = i < side.innings.length && played(s, side, i, team === 'home');
      const active = known && !s.isFinal && s.hasStarted !== false && i + 1 === s.inning && (team === 'away') === s.isTop;
      row.append(node('td', known ? side.innings[i] : '-', !known ? 'future' : active ? 'active' : ''));
    }
    [side.runs, side.hits, side.errors].forEach((v, i) => row.append(node('td', v, i === 0 ? 'total' : ''))); return row;
  }));
  text('batter-label', !s.isFinal && s.batter && s.batterOrder ? `当前打者 · 第 ${s.batterOrder} 棒` : '当前打者');
  text('batter', s.isFinal ? '—' : player(s.batter));
  text('batter-stats', s.batterStats ? `本场 ${s.batterStats.atBats} 打数 · ${s.batterStats.hits} 安打${s.batterStats.isComplete ? '' : ' · 待确认'}` : '本场表现未记录');
  text('pitcher-label', s.mode === 'coachPitch' ? 'P 位守备' : '当前投手'); text('pitcher', player(s.pitcher));
  text('pitch-count', s.mode === 'coachPitch' ? `本打席 ${s.appearancePitchCount} / ${s.pitchLimit ?? '—'} 球` : s.pitchCount === null ? '球数未记录' : `已投 ${s.pitchCount} 球`);
  $('matchup').hidden = s.isFinal || s.hasStarted === false;
  const next = $('next-batters'); next.replaceChildren(); next.hidden = s.isFinal || s.hasStarted === false || Array.isArray(s.nextBatters) && !s.nextBatters.length;
  if (s.nextBatters?.length) {
    next.append(node('span', '待打'));
    s.nextBatters.forEach((p, i) => { if (i) { const arrow = node('span', '→'); arrow.setAttribute('aria-hidden', 'true'); next.append(arrow); } const label = node('span', undefined, 'next-player'); label.append(node('span', p.order + '棒'), node('span', '#' + p.player.number), node('span', p.player.name)); next.append(label); });
    next.setAttribute('aria-label', '后两位待打：' + s.nextBatters.map(p => `第 ${p.order} 棒，${player(p.player)}`).join('；'));
  } else { next.append(node('span', '待打未记录')); next.setAttribute('aria-label', '后两位待打未记录'); }
  text('notice', s.notice); $('notice').hidden = !s.notice;
  const groups = new Map();
  for (const entry of s.entries) { if (s.hasStarted === false && entry.status === 'current') continue; const k = halfKey(entry); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(entry); }
  const currentKey = halfKey(s); if (!groups.has(currentKey)) groups.set(currentKey, []);
  const halves = [...groups.entries()].sort((a, b) => { const order = k => Number(k.split('-')[0]) * 2 + (k.endsWith('bottom') ? 1 : 0); return order(b[0]) - order(a[0]); });
  $('half-select').replaceChildren(...halves.map(([k, entries]) => { const option = node('option', entries.length ? halfName(entries[0]) : halfName(s)); option.value = k; return option; }));
  if ([...$('half-select').options].some(o => o.value === selectedHalf)) $('half-select').value = selectedHalf;
  $('timeline').replaceChildren(...halves.slice(0, visibleHalves).map(([k, entries]) => {
    const section = node('section', undefined, 'half'); section.id = 'half-' + k;
    section.append(node('h2', s.hasStarted === false ? '赛前准备' : entries.length ? halfName(entries[0]) : halfName(s)));
    if (!entries.length) section.append(node('p', s.isFinal ? '本半局没有记录' : s.hasStarted === false ? '比赛尚未开始' : '等待下一次记分…', 'empty'));
    for (const e of [...entries].reverse()) {
      const current = !s.isFinal && s.hasStarted !== false && e.appearanceID !== null && e.appearanceID === s.currentAppearanceID;
      const situation = e.situation || (current ? { balls:s.balls, strikes:s.strikes, outs:s.outs, bases:s.bases.map(b => b.base), halfEnded:false } : null);
      const card = node('details', undefined, 'entry ' + e.kind); card.id = 'entry-' + e.id; card.open = open.has(card.id);
      const summary = node('summary'); summary.append(node('span', e.player?.number || '·', 'avatar'));
      const body = node('div', undefined, 'entry-body'), heading = node('div', undefined, 'entry-heading');
      heading.append(node('strong', e.player?.name || '比赛动态'));
      heading.append(node('span', current ? '打席进行中' : e.label || '比赛动态', 'label'));
      const status = { interrupted:'未完成 · 中断', review:'待确认' }[e.status]; if (status) heading.append(node('span', status, 'status ' + e.status));
      body.append(heading);
      const clean = e.player && e.summary.startsWith(e.player.name + '：') ? e.summary.slice(e.player.name.length + 1) : e.summary;
      body.append(node('span', current ? `${s.balls} 坏 ${s.strikes} 好 · ${s.outs} 出局` : clean, 'summary'));
      const order = e.order ?? (current ? s.batterOrder : null);
      const caption = [order ? `第 ${order} 棒` : '', situation?.halfEnded ? '半局结束' : !current && situation ? `${situation.outs} 出局` : ''].filter(Boolean).join(' · ');
      if (caption) body.append(node('span', caption, 'caption'));
      summary.append(body, baseDisplay(situation?.bases)); card.append(summary);
      const list = node('ul'); for (const d of e.details) { const li = node('li'); li.append(node('span', d.text), baseDisplay(d.situation?.bases)); list.append(li); }
      if (!e.details.length) list.append(node('li', '尚无逐球记录'));
      card.append(list); section.append(card);
    }
    return section;
  }));
  $('more').hidden = visibleHalves >= halves.length;
  renderLineups(s); updateClock();
  if (anchorID && $(anchorID) && view === 'live') window.scrollBy(0, $(anchorID).getBoundingClientRect().top - anchorTop);
}
function showPending() { if (pending) { render(pending); pending = null; } $('updates').hidden = true; }
function currentButton() {
  const button = $('current');
  if (button) button.hidden = stopped || view !== 'live' || window.scrollY <= 400;
}
function connection() {
  if (!metadata) return;
  const now = Date.now() + clockOffset;
  if (now >= metadata.expiresAt) { expire(); return; }
  const stale = now - metadata.lastSeen > 45000;
  const state = networkFailed ? '网络暂不可用 · 正在重试' : stale ? '记分设备暂未同步 · 等待恢复' : snapshot?.isFinal ? '终场已同步' : '实时更新';
  text('connection', state); $('connection').hidden = !networkFailed && !stale;
  $('live-dot').classList.toggle('stale', networkFailed || stale); $('live-dot').setAttribute('aria-label', state);
  text('synced', `最近同步 ${time(metadata.lastSeen)}`);
  text('lineup-synced', `最近同步 ${time(metadata.lastSeen)}${networkFailed || stale ? ' · ' + state : ''}`);
  text('expiry', snapshot?.isFinal ? `本场直播将于 ${new Date(metadata.expiresAt).toLocaleString('zh-CN')} 删除，不保留回放。` : '记分设备连续一小时未同步时，直播也会自动关闭。');
  updateClock();
}
async function poll() {
  if (!code || stopped || document.hidden || inFlight) return;
  inFlight = true; clearTimeout(timer);
  const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), 12000);
  try {
    const response = await fetch(`${BASE}/api/sessions/${code}${revision === undefined ? '' : '?since=' + revision}`, { cache:'no-store', signal:controller.signal });
    if ([404,410].includes(response.status)) { expire('直播已关闭、过期或串码不正确。这里不保留历史回放。'); return; }
    if (!response.ok) throw new Error('unavailable');
    const data = await response.json(); networkFailed = false; metadata = data; clockOffset = data.serverTime - Date.now();
    if (data.snapshot) { revision = data.revision; if (snapshot && view === 'live' && window.scrollY > 400) { pending = data.snapshot; $('updates').hidden = false; } else { render(data.snapshot); pending = null; $('updates').hidden = true; } }
    connection();
  } catch { networkFailed = true; if (!stopped) { if (snapshot) connection(); else text('message', '连接暂不可用，正在重试…'); } }
  finally { clearTimeout(timeout); inFlight = false; if (!stopped && !document.hidden) timer = setTimeout(poll, 10000); }
}
$('code-form').addEventListener('submit', e => { e.preventDefault(); const input = $('code').value.trim().toUpperCase(); if (/^[A-F0-9]{24}$/.test(input)) location.assign(`${BASE}/${input}`); else text('message','请输入完整的 24 位场次串码。'); });
$('updates').addEventListener('click', showPending);
$('current').addEventListener('click', () => { showPending(); window.scrollTo({top:0,behavior:'smooth'}); });
$('more').addEventListener('click', () => { visibleHalves += 4; render(snapshot); });
$('half-select').addEventListener('change', e => { const value = e.target.value; visibleHalves = 999; render(snapshot); $('half-select').value = value; $('half-' + value)?.scrollIntoView({behavior:'smooth'}); });
$('open-lineups').addEventListener('click', () => { showPending(); liveScroll = window.scrollY; view = 'lineups'; $('live-view').hidden = true; $('lineup-view').hidden = false; renderLineups(snapshot); window.scrollTo(0,0); $('close-lineups').focus({preventScroll:true}); });
$('close-lineups').addEventListener('click', () => { view = 'live'; $('lineup-view').hidden = true; $('live-view').hidden = false; window.scrollTo(0,liveScroll); $('open-lineups').focus({preventScroll:true}); });
for (const tab of document.querySelectorAll('[data-team]')) {
  tab.addEventListener('click', () => { lineupTeam = tab.dataset.team; renderLineups(snapshot); });
  tab.addEventListener('keydown', e => { if (['ArrowLeft','ArrowRight'].includes(e.key)) { e.preventDefault(); lineupTeam = lineupTeam === 'away' ? 'home' : 'away'; renderLineups(snapshot); $(lineupTeam + '-tab').focus(); } });
}
document.addEventListener('visibilitychange', () => { clearTimeout(timer); if (!document.hidden && !stopped) { connection(); if (!stopped) poll(); } });
window.addEventListener('scroll', currentButton, { passive:true });
currentButton();
setInterval(connection,1000);
if (code) { $('gate').hidden = true; if (/^[A-F0-9]{24}$/.test(code)) { text('message','正在连接比赛…'); poll(); } else expire('场次串码不正确，请使用 App 分享的完整链接。'); }
