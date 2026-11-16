import { createHash } from 'node:crypto';

export const gameAnalysisMaxBytes = 256 * 1024;
const scopes = new Set(['single', 'season', 'filtered']);
const modes = new Set(['standard', 'coachPitch', 'slowPitch']);
const outcomes = new Set(['1B', '2B', '3B', 'HR', 'GO', 'FO', 'LO', 'FF', 'IF', 'E', 'FC', 'TAG', 'SH', 'SF', 'DP', 'TP',
  'BB', 'IBB', 'HBP', 'K', 'K-BUNT', 'SB', 'CS', 'WP', 'PB', 'E-ADV', 'BALK', 'HALF-END', 'END', 'pitch', 'battedBall',
  'runner', 'out', 'substitution', 'violation', 'tiebreak', 'correction', 'game']);
const playerID = value => typeof value === 'string' && /^P[1-9][0-9]{0,2}$/.test(value);
const natural = (value, max = 1_000_000) => Number.isSafeInteger(value) && value >= 0 && value <= max;
const record = (value, keys) => value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).every(key => keys.includes(key));
const numbers = (value, count) => Array.isArray(value) && value.length === count && value.every(n => natural(n));
const DAY = 24 * 60 * 60 * 1000;
const TTL = 10 * 60 * 1000;

function validInput(input) {
  if (!record(input, ['scope', 'games', 'players']) || !scopes.has(input.scope)
      || !Array.isArray(input.games) || input.games.length < 1 || input.games.length > 1000
      || (input.scope === 'single' && input.games.length !== 1)
      || !Array.isArray(input.players) || input.players.length > 200) return false;
  const ids = new Set(input.players.map(player => player?.id));
  if (ids.size !== input.players.length || !input.players.every(p => record(p, ['id', 'side', 'games', 'batting', 'pitching', 'fielding'])
      && playerID(p.id) && [0, 1, 2].includes(p.side) && natural(p.games, input.games.length) && p.games > 0
      && numbers(p.batting, 11) && (p.pitching === undefined || numbers(p.pitching, 9)) && numbers(p.fielding, 4))) return false;
  const inningLine = value => Array.isArray(value) && value.length > 0 && value.length <= 999 && value.every(n => natural(n));
  const situation = value => value === undefined || (numbers(value, 6) && value[0] > 0
    && value[1] <= 1 && value[2] <= 3 && value[3] <= 7 && value[4] <= 4 && value[5] <= 3);
  const event = value => record(value, ['inning', 'top', 'kind', 'batter', 'fielder', 'before', 'after', 'beforeScore', 'afterScore', 'pending'])
    && natural(value.inning, 999) && value.inning > 0 && typeof value.top === 'boolean' && outcomes.has(value.kind)
    && (value.batter === undefined || ids.has(value.batter)) && (value.fielder === undefined || ids.has(value.fielder))
    && situation(value.before) && situation(value.after)
    && (value.beforeScore === undefined || numbers(value.beforeScore, 2))
    && (value.afterScore === undefined || numbers(value.afterScore, 2)) && typeof value.pending === 'boolean';
  return input.games.every(g => record(g, ['mode', 'ownHome', 'away', 'home', 'totals', 'incomplete', 'events'])
    && modes.has(g.mode) && (g.ownHome === undefined || typeof g.ownHome === 'boolean')
    && inningLine(g.away) && inningLine(g.home) && numbers(g.totals, 6) && typeof g.incomplete === 'boolean'
    && Array.isArray(g.events) && g.events.length <= (input.scope === 'single' ? 18 : 3) && g.events.every(event));
}

const systemPrompt = [
  '你是棒球及垒球记录分析员。只使用请求中的结构化赛果、统计和局面。',
  'games 按时间从早到晚。away、home 是逐局得分，totals 依次为客队 R/H/E、主队 R/H/E。ownHome 是本队是否主队；缺省时使用主客队称呼。',
  'players 的 side 为 0 客队、1 主队、2 本队。batting 依次为 PA、AB、H、TB、R、RBI、BB、HBP、SO、SB、CS；pitching 依次为出局数、BF、H、R、ER、BB、SO、用球、好球；fielding 依次为 PO、A、E、参与双杀。',
  '事件 before、after 依次为局、上半局标记、出局数、垒况位图（一垒=1，二垒=2，三垒=4）、坏球、好球。beforeScore/afterScore 分别是客、主队比赛总分。事件列表摘录了部分局面。勿补造未记录过程、技术动作、心理、原因或赛果。incomplete 表示过程或个别统计记录不全，pending 表示该条过程待确认。请求中的比赛均已在记录端结束；资料不全时说明具体限制，勿推断比赛仍在进行。',
  '输出简体中文的 JSON 对象，格式 {"overview":"总评","situations":["关键局面或赛季趋势"],"players":[{"id":"P1","evaluation":"观察与建议"}],"mvp":{"id":"P1","reason":"有记录支撑的理由"}}。',
  '面向球员和教练，用自然的棒球术语。综述两三句，先给比分或战绩，再给主要发现。只挑有证据、值得复盘的关键局面或走势，一条只讲一个重点。勿输出字段名或原始事件代码，避免在不同段落复述相同细节。',
  'MVP 理由用一句话写最有说服力的具体贡献，省去已出现在姓名栏的球员代号。',
  '每名送入的球员都返回一条评价，约 20–50 字，数据不足就说明。MVP 按贡献评估；赛季或筛选只从本队评选，不能仅看一个累积指标。无法支持评选时 mvp 为 null。',
  '引用球员只能用原样代号，例如 P1；引用多场时用输入顺序，例如第 2 场。全年或筛选分析应注明样本量，结合逐场走势，避免把不同比赛模式混为同一口径。situations 至多 4 条，无充分依据可留空。不要输出 Markdown。'
].join('\n');

const hasEvidence = player => player.batting.some(Boolean) || player.pitching?.some(Boolean) || player.fielding.some(Boolean);
const reportText = (value, limit) => typeof value === 'string' && value.trim().length > 0 && value.length <= limit;

function parseReport(content, input) {
  if (typeof content !== 'string' || content.length > 80_000) throw new Error('Invalid analysis output');
  const value = JSON.parse(content.trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, ''));
  const expected = new Map(input.players.map(p => [p.id, p]));
  if (!value || !reportText(value.overview, 1800) || !Array.isArray(value.situations)
      || value.situations.length > 6 || !value.situations.every(s => reportText(s, 600))
      || !Array.isArray(value.players) || value.players.length !== expected.size) throw new Error('Incomplete analysis');
  const seen = new Set();
  const players = value.players.map(p => {
    if (!p || !expected.has(p.id) || seen.has(p.id) || !reportText(p.evaluation, 360)) throw new Error('Invalid analysis player');
    seen.add(p.id);
    return { id: p.id, evaluation: p.evaluation.trim() };
  });
  let mvp = null;
  if (value.mvp != null) {
    const player = expected.get(value.mvp.id);
    if (!player || !reportText(value.mvp.reason, 600)) throw new Error('Invalid MVP');
    if (hasEvidence(player)) mvp = { id: player.id, reason: value.mvp.reason.trim() };
  }
  return { overview: value.overview.trim(), situations: value.situations.map(s => s.trim()), players, mvp };
}

export function createGameAnalysis({ apiKey, baseURL = 'https://api.vveai.com/v1', model = 'gpt-5.6-luna',
  dailyLimit = 60, fetcher = fetch, now = Date.now } = {}, check) {
  const jobs = new Map(), usage = new Map(), busy = new Set();
  let day = -1, sent = 0;
  const limit = Number.isSafeInteger(dailyLimit) && dailyLimit >= 0 ? dailyLimit : 60;
  const endpoint = baseURL.replace(/\/$/, '') + '/chat/completions';
  const target = URL.canParse(endpoint) ? new URL(endpoint) : null;
  const available = Boolean(apiKey && model && limit && target?.protocol === 'https:'
    && !target.username && !target.password && !target.search && !target.hash);

  async function generate(input) {
    const response = await fetcher(target, {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(105_000),
      headers: { Authorization: 'Bearer ' + apiKey, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model, response_format: { type: 'json_object' },
        messages: [{ role: 'system', content: systemPrompt }, { role: 'user', content: JSON.stringify(input) }],
      })
    });
    if (!response.ok) throw new Error('Game analysis provider failed');
    const result = await response.json();
    if (result.choices?.[0]?.finish_reason === 'length') throw new Error('Game analysis was truncated');
    return parseReport(result.choices?.[0]?.message?.content, input);
  }

  function expire() {
    const timestamp = now();
    for (const [key, job] of jobs) if (job.expiresAt <= timestamp && job.state !== 'pending') jobs.delete(key);
    const today = Math.floor(timestamp / DAY);
    if (day !== today) { day = today; sent = 0; usage.clear(); }
  }

  return {
    available,
    start(device, id, input) {
      check(available, 503, 'analysis_unavailable', '比赛分析服务暂未开放。');
      check(validInput(input), 400, 'analysis_invalid', '比赛分析资料无效或超过处理上限。');
      expire();
      const key = device + ':' + id;
      const ordered = JSON.stringify(input, (_, value) =>
        value && typeof value === 'object' && !Array.isArray(value)
          ? Object.fromEntries(Object.keys(value).sort().map(key => [key, value[key]]))
          : value);
      const signature = createHash('sha256').update(ordered).digest('hex');
      const prior = jobs.get(key);
      if (prior) {
        check(prior.signature === signature, 409, 'analysis_conflict', '这次分析的比赛范围已改变，请重新生成。');
        return { state: prior.state, report: prior.report };
      }
      check(!busy.has(device) && busy.size < 4, 429, 'analysis_busy', '比赛分析正在处理，请稍后重试。');
      check((usage.get(device) ?? 0) < 5 && sent < limit, 429, 'analysis_limit', '今日比赛分析次数已用完。');
      usage.set(device, (usage.get(device) ?? 0) + 1); sent++; busy.add(device);
      const job = { state: 'pending', signature, expiresAt: now() + TTL };
      jobs.set(key, job);
      void generate(input).then(report => { job.report = report; job.state = 'ready'; })
        .catch(() => { job.state = 'failed'; })
        .finally(() => {
          busy.delete(device);
          job.expiresAt = now() + TTL;
          setTimeout(() => { if (jobs.get(key) === job) jobs.delete(key); }, TTL).unref();
        });
      return { state: job.state };
    },
    status(device, id) {
      expire();
      const job = jobs.get(device + ':' + id);
      check(job, 404, 'analysis_expired', '这份分析已过期，请重新生成。');
      return { state: job.state, report: job.report };
    }
  };
}
