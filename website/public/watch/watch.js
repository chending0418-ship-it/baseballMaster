'use strict';
const BASE = '/api/v3';
const $ = id => document.getElementById(id);
const parts = location.hash.slice(1).split('/');
const isLiveLink = parts[0] === 'live';
const code = isLiveLink ? parts[1] : parts[0], invitation = isLiveLink ? null : parts[1];
const validCode = /^[A-Za-z0-9_-]{32}$/.test(code || '');
const validInvite = /^[A-Za-z0-9_-]{43}$/.test(invitation || '');
const matchTemplate = $('match').innerHTML;
let rebuild = false;
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
function clearPrivate(message) {
  snapshot = pending = metadata = null; revision = undefined;
  $('match').hidden = true; $('match').replaceChildren(); rebuild = true;
  document.title = '比赛观看 · BaseballMaster';
  for (const meta of document.querySelectorAll('meta[name="description"],meta[property^="og:"]')) if (meta.getAttribute('property') !== 'og:type') meta.content = '比赛观看';
  text('message', message);
}
function expire(message = '本场暂时无法观看，请联系邀请人。') { stopped = true; clearTimeout(timer); clearPrivate(message); }
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
  if (isLiveLink && now >= metadata.expiresAt) { expire('直播链接已到期，原链接不再展示内容。留存档案需通过球队授权的历史入口查看。'); return; }
  const stale = now - metadata.lastSeen > 45000;
  const state = networkFailed ? '网络暂不可用 · 正在重试' : stale ? '记分设备暂未同步 · 等待恢复' : snapshot?.isFinal ? '终场已同步' : '实时更新';
  text('connection', state); $('connection').hidden = !networkFailed && !stale;
  $('live-dot').classList.toggle('stale', networkFailed || stale); $('live-dot').setAttribute('aria-label', state);
  text('synced', `最近同步 ${time(metadata.lastSeen)}`);
  text('lineup-synced', `最近同步 ${time(metadata.lastSeen)}${networkFailed || stale ? ' · ' + state : ''}`);
  text('expiry', isLiveLink ? (snapshot?.isFinal ? `本直播链接在 ${new Date(metadata.expiresAt).toLocaleString('zh-CN')} 到期；到期后原链接不再展示内容。历史档案需另行授权查看。` : '任何人持链接可观看；连续一小时未同步时原链接关闭。') : '历史比赛档案持续保留，观看权限由球队管理。');
  updateClock();
}
function renderBox(data) {
  const root = $('history-box'); root.hidden = !data.snapshot.isFinal; root.replaceChildren();
  if (!data.snapshot.isFinal) return;
  root.append(node('h2','本场技术统计'));
  root.append(node('p', '比赛时间：' + new Date(data.playedAt).toLocaleString('zh-CN', data.timeZone ? {timeZone:data.timeZone} : {})));
  if (data.statisticsIncomplete) root.append(node('p','过程或责任待确认，统计可能不完整。'));
  const labels = {plateAppearances:'打席',atBats:'打数',hits:'安打',doubles:'二垒打',triples:'三垒打',homeRuns:'本垒打',runs:'得分／失分',runsBattedIn:'打点',walks:'四坏',hitByPitch:'触身',strikeouts:'三振',sacrifices:'牺牲打',stolenBases:'盗垒',caughtStealing:'盗垒失败',outsRecorded:'投球局数',battersFaced:'面对打者',earnedRuns:'自责分',wildPitches:'暴投',pitches:'投球数',strikes:'好球',putouts:'刺杀',assists:'助杀',errors:'失误',doublePlays:'双杀',passedBalls:'捕逸'};
  for (const [key,title] of [['batting','打击'],['pitching','投手'],['fielding','守备']]) {
    root.append(node('h3',title));
    if (key === 'pitching' && data.snapshot.mode === 'coachPitch') { root.append(node('p','教练投手模式不统计投手成绩')); continue; }
    for (const line of data.box[key] || []) {
      const card = node('details'); card.append(node('summary',player(line.player)));
      const list=node('dl');
      for (const [stat,value] of Object.entries(line.values)) {
        list.append(node('dt',labels[stat] || stat),node('dd',stat === 'outsRecorded' ? `${Math.floor(value/3)}.${value%3}` : value));
      }
      card.append(list);root.append(card);
    }
  }
}
async function poll() {
  if (!validCode || stopped || document.hidden || inFlight) return;
  inFlight = true; clearTimeout(timer);
  const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), 12000);
  try {
    const response = await fetch(`${BASE}/${isLiveLink ? 'live' : 'watch'}/${code}`, { cache:'no-store', credentials:'same-origin', headers:{'X-BM-Protocol':'1'}, signal:controller.signal });
    if (!response.ok) {
      clearPrivate(isLiveLink ? '直播已关闭或到期，原链接不再提供比赛内容。留存档案需通过球队授权的历史入口查看。' : response.status === 403 ? '尚未获得观看授权，请等待邀请人核对；已有授权也可能被撤销。' : '本场尚未开放，或观看已关闭。');
      return;
    }
    const data=await response.json();
    if (rebuild) { $('match').innerHTML=matchTemplate; bindControls(); rebuild=false; }
    networkFailed=false; metadata={...data,revision:data.serverRevision,lastSeen:data.updatedAt,expiresAt:isLiveLink ? data.expiresAt : Infinity}; clockOffset=0;
    revision=data.serverRevision;render(data.snapshot);renderBox(data);connection();$('gate').hidden=true;
  } catch { clearPrivate('连接暂不可用，正在重新核对观看权限…'); }
  finally { clearTimeout(timeout); inFlight=false; if (!stopped && !document.hidden) timer=setTimeout(poll,10000); }
}
function bindControls() {
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
}
bindControls();
$('gate').hidden = !validCode || !validInvite;
$('viewer-form').addEventListener('submit', async e => {
  e.preventDefault(); if (!validCode || !validInvite) return;
  const key='bm-view-request-'+code;
  let session=sessionStorage.getItem(key);
  if(!session) { const bytes=crypto.getRandomValues(new Uint8Array(32));session=btoa(String.fromCharCode(...bytes)).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');sessionStorage.setItem(key,session); }
  $('apply-viewer').disabled=true;
  try {
    const response=await fetch(`${BASE}/watch/${code}/apply`,{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json','X-BM-Protocol':'1'},body:JSON.stringify({token:invitation,session,nickname:$('nickname').value.trim()})});
    if(!response.ok) { text('message','邀请已失效或暂不可用，请联系邀请人重新发送。');return; }
    const data=await response.json();sessionStorage.removeItem(key);history.replaceState(null,'',location.pathname+'#'+code);$('gate').hidden=true;
    text('message',data.status==='approved' ? '授权成功，正在读取比赛…' : '申请已发送，等待邀请人核对。'); await poll();
  } catch { text('message','请求结果尚未确认，请重试；不会重复创建申请。'); }
  finally { $('apply-viewer').disabled=false; }
});
document.addEventListener('visibilitychange',()=>{clearTimeout(timer);if(document.hidden) clearPrivate('正在核对观看权限…');else poll();});
window.addEventListener('hashchange',()=>{clearPrivate('正在核对新链接…');location.reload();});
window.addEventListener('pagehide',()=>clearPrivate('正在核对观看权限…'));
window.addEventListener('pageshow',e=>{if(e.persisted) poll();});
window.addEventListener('scroll',()=>{if(snapshot) currentButton();},{passive:true});
setInterval(connection,1000);
if(validCode) { text('message','正在核对观看权限…');poll(); } else expire('请使用球队分享的完整观看链接。');
