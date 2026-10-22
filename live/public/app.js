'use strict';
const BASE = '/livestreaming/novideo';
const $ = id => document.getElementById(id);
const code = location.pathname.split('/').filter(Boolean)[2]?.toUpperCase();
let snapshot, metadata, pending, revision, timer, stopped = false, visibleHalves = 4, clockOffset = 0, inFlight = false, networkFailed = false;
const text = (id, value) => { $(id).textContent = value; };
const node = (tag, value, className) => { const el = document.createElement(tag); if (value !== undefined) el.textContent = value; if (className) el.className = className; return el; };
const halfKey = e => `${e.inning}-${e.isTop ? 'top' : 'bottom'}`;
const halfName = e => `第 ${e.inning} 局${e.isTop ? '上' : '下'}半局`;
const player = p => p ? `${p.name} · #${p.number}` : '—';
const time = t => new Date(t).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
function expire(message = '本场直播已到期，链接已失效，不再提供比赛记录。') {
  stopped = true; clearTimeout(timer); snapshot = pending = metadata = null; revision = null;
  $('match').hidden = true; $('match').replaceChildren(); text('message', message);
}
function render(s) {
  const open = new Set([...document.querySelectorAll('details[open]')].map(e => e.id));
  const anchor = [...document.querySelectorAll('.entry')].find(e => e.getBoundingClientRect().top >= 0);
  const anchorTop = anchor?.getBoundingClientRect().top;
  const anchorID = anchor?.id;
  snapshot = s; $('match').hidden = false; text('message', '');
  text('phase', s.isFinal ? '比赛已结束' : halfName(s)); text('mode', s.mode === 'coachPitch' ? '教练投手' : '普通模式');
  text('away-name', s.away.name); text('home-name', s.home.name); text('score', `${s.away.runs} : ${s.home.runs}`);
  const head = node('tr'); ['', ...s.away.innings.map((_, i) => i + 1), 'R', 'H', 'E'].forEach(v => head.append(node('th', v)));
  $('innings-head').replaceChildren(head);
  $('innings-body').replaceChildren(...[s.away, s.home].map(t => { const row = node('tr'); [t.name, ...t.innings, t.runs, t.hits, t.errors].forEach(v => row.append(node('td', v))); return row; }));
  text('count', `B ${s.balls}　S ${s.strikes}　O ${s.outs}`);
  for (let b = 1; b <= 3; b++) { const runner = s.bases.find(r => r.base === b); $('base-' + b).classList.toggle('occupied', !!runner); $('base-' + b).setAttribute('aria-label', `${b} 垒${runner ? runner.player.name : '无人'}`); }
  text('runners', s.bases.length ? s.bases.map(b => `${b.base} 垒 ${b.player.name}`).join(' / ') : '垒上无人');
  text('batter-label', !s.isFinal && s.batter && Number.isInteger(s.batterOrder) && s.batterOrder > 0 ? `第 ${s.batterOrder} 棒 · 打者` : '打者');
  text('batter', s.isFinal ? '—' : player(s.batter)); text('pitcher-label', s.mode === 'coachPitch' ? 'P 位守备' : '投手');
  text('pitcher', player(s.pitcher)); text('pitch-count', s.mode === 'coachPitch' ? `本打席 ${s.appearancePitchCount} / ${s.pitchLimit} 球` : `本场已投 ${s.pitchCount ?? 0} 球`);
  text('notice', s.notice); $('notice').hidden = !s.notice;
  const groups = new Map();
  for (const entry of s.entries) { const k = halfKey(entry); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(entry); }
  const currentKey = halfKey(s); if (!groups.has(currentKey)) groups.set(currentKey, []);
  const halves = [...groups.entries()].sort((a, b) => { const order = k => Number(k.split('-')[0]) * 2 + (k.endsWith('bottom') ? 1 : 0); return order(b[0]) - order(a[0]); });
  $('half-select').replaceChildren(...halves.map(([k, entries]) => { const option = node('option', entries.length ? halfName(entries[0]) : halfName(s)); option.value = k; return option; }));
  $('timeline').replaceChildren(...halves.slice(0, visibleHalves).map(([k, entries]) => {
    const section = node('section', undefined, 'half'); section.id = 'half-' + k;
    section.append(node('h2', entries.length ? halfName(entries[0]) : halfName(s)));
    if (!entries.length) section.append(node('p', s.isFinal ? '本半局没有记录' : '等待下一次记分…', 'empty'));
    for (const e of [...entries].reverse()) {
      const card = node('details', undefined, 'entry ' + e.kind); card.id = 'entry-' + e.id; card.open = open.has(card.id);
      const summary = node('summary'); summary.append(node('span', e.player?.number || '·', 'avatar'));
      const body = node('div'); body.append(node('span', e.label || '比赛动态', 'label'));
      const state = { current: '进行中', interrupted: '未完成 · 中断', review: '待确认', completed: '' }[e.status];
      if (state) body.append(node('span', state, 'status'));
      body.append(node('span', e.summary, 'summary')); summary.append(body); card.append(summary);
      const list = node('ul'); for (const d of e.details) list.append(node('li', d.text));
      if (!e.details.length) list.append(node('li', '尚无逐球记录'));
      card.append(list); section.append(card);
    }
    return section;
  }));
  $('more').hidden = visibleHalves >= halves.length;
  if (anchorID && $(anchorID)) window.scrollBy(0, $(anchorID).getBoundingClientRect().top - anchorTop);
}
function showPending() { if (pending) { render(pending); pending = null; } $('updates').hidden = true; }
function connection() {
  if (!metadata) return;
  const now = Date.now() + clockOffset;
  if (now >= metadata.expiresAt) { expire(); return; }
  const stale = now - metadata.lastSeen > 45000;
  text('connection', networkFailed ? '网络暂不可用 · 正在重试' : snapshot?.isFinal ? '终场已同步' : stale ? '记分设备暂未同步 · 等待恢复' : '● 正在同步 · 每 10 秒刷新');
  text('synced', `最近同步 ${time(metadata.lastSeen)}`);
  text('expiry', snapshot?.isFinal ? `本场直播将于 ${new Date(metadata.expiresAt).toLocaleString('zh-CN')} 删除，不保留回放。` : '记分设备连续一小时未同步时，直播也会自动关闭。');
}
async function poll() {
  if (!code || stopped || document.hidden || inFlight) return;
  inFlight = true; clearTimeout(timer);
  const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), 12000);
  try {
    const response = await fetch(`${BASE}/api/sessions/${code}${revision === undefined ? '' : '?since=' + revision}`, { cache: 'no-store', signal: controller.signal });
    if ([404, 410].includes(response.status)) { expire('直播已关闭、过期或串码不正确。这里不保留历史回放。'); return; }
    if (!response.ok) throw new Error('unavailable');
    const data = await response.json(); networkFailed = false; metadata = data; clockOffset = data.serverTime - Date.now();
    if (data.snapshot) {
      revision = data.revision;
      if (snapshot && window.scrollY > 400) { pending = data.snapshot; $('updates').hidden = false; }
      else { render(data.snapshot); pending = null; $('updates').hidden = true; }
    }
    connection();
  } catch { networkFailed = true; if (!stopped) { if (snapshot) text('connection', '网络暂不可用 · 正在重试'); else text('message', '连接暂不可用，正在重试…'); } }
  finally { clearTimeout(timeout); inFlight = false; if (!stopped && !document.hidden) timer = setTimeout(poll, 10000); }
}
$('code-form').addEventListener('submit', e => { e.preventDefault(); const input = $('code').value.trim().toUpperCase(); if (/^[A-F0-9]{24}$/.test(input)) location.assign(`${BASE}/${input}`); else text('message', '请输入完整的 24 位场次串码。'); });
$('updates').addEventListener('click', showPending);
$('current').addEventListener('click', () => { showPending(); window.scrollTo({ top: 0, behavior: 'smooth' }); });
$('more').addEventListener('click', () => { visibleHalves += 4; render(snapshot); });
$('half-select').addEventListener('change', e => { const value = e.target.value; visibleHalves = 999; render(snapshot); $('half-select').value = value; $('half-' + value)?.scrollIntoView({ behavior: 'smooth' }); });
document.addEventListener('visibilitychange', () => { clearTimeout(timer); if (!document.hidden && !stopped) { connection(); if (!stopped) poll(); } });
setInterval(connection, 1000);
if (code) { $('gate').hidden = true; if (/^[A-F0-9]{24}$/.test(code)) { text('message', '正在连接比赛…'); poll(); } else expire('场次串码不正确，请使用 App 分享的完整链接。'); }
