import { isDeepStrictEqual } from 'node:util';

// A schedule owns its assignments. Only the designated scoring device commits play.
const identifier = value => typeof value === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const fits = value => object(value) && Buffer.byteLength(JSON.stringify(value)) <= 384 * 1024;

const integer = (n,min,max) => Number.isSafeInteger(n) && n >= min && n <= max;
const short = value => typeof value === 'string' && value.length <= 240;
const fields = (value,keys) => object(value) && Object.keys(value).every(k => keys.includes(k));
const ids = list => Array.isArray(list) && list.length <= 200 && list.every(identifier) &&
  new Set(list.map(x => x.toLowerCase())).size === list.length;
function validRules(r) {
  if (!object(r) || !integer(r.scheduledInnings,1,999) || !integer(r.fieldersCount,1,10)) return false;
  const enumerations = { mode:['standard','slowPitch','coachPitch'], competitionFormat:['innings','timed'],
    twoStrikeFoulPolicy:['outImmediately','oneExtraFoul'],recordingMethod:['traditional','situation'] };
  const flags = ['usesDesignatedHitter','allowsTwoWayPlayer'];
  const numbers = ['scheduledInnings','fieldersCount','timeLimitMinutes','timeWarningMinutes','pitchLimit','pitchWarningRemaining',
    'pitcherInningsLimit','halfInningRunLimit','coachPitchLimit','initialBalls','initialStrikes','inningsLimit','rulesVersion'];
  return Object.entries(r).every(([key,v]) => v == null || (flags.includes(key) ? typeof v === 'boolean' :
    numbers.includes(key) ? integer(v,0,1000000) : Object.hasOwn(enumerations,key) && enumerations[key].includes(v)));
}
function player(p) {
  return fields(p,['id','chineseName','englishName','numbers','jerseyNumbers','primaryPosition']) && identifier(p.id) &&
    short(p.chineseName) && short(p.englishName) && integer(p.primaryPosition,1,10) &&
    Array.isArray(p.numbers) && p.numbers.length <= 100 && p.numbers.every(n => integer(n,0,99)) &&
    (p.jerseyNumbers == null || Array.isArray(p.jerseyNumbers) && p.jerseyNumbers.length <= 100 &&
      p.jerseyNumbers.every(n => typeof n === 'string' && /^(00|0|[1-9][0-9]?)$/.test(n)));
}
function runners(value) {
  // Swift represents dictionaries keyed by the native Base enum as alternating key/value pairs.
  return Array.isArray(value) && value.length <= 6 && value.length % 2 === 0 &&
    value.every((v,i) => i % 2 ? player(v) : integer(v,1,3)) &&
    new Set(value.filter((_,i) => i % 2 === 0)).size === value.length / 2;
}
function validLineup(v) {
  if (!fields(v,['isHome','players','battingIDs','fieldingIDs','dhID','pitcherID','anchorID','runners','automaticIDs','changes']) ||
      typeof v.isHome !== 'boolean' || !Array.isArray(v.players) || !v.players.every(player) ||
      !ids(v.players.map(p => p.id)) || !ids(v.battingIDs) || !ids(v.fieldingIDs) || !runners(v.runners) ||
      ![v.dhID,v.pitcherID,v.anchorID].every(x => x == null || identifier(x)) ||
      v.automaticIDs != null && !ids(v.automaticIDs) || !Array.isArray(v.changes) ||
      v.changes.length > 200 || !v.changes.every(x => typeof x === 'string' && x.length <= 1024)) return false;
  const known = new Set(v.players.map(p => p.id.toLowerCase()));
  return v.battingIDs.length > 0 && v.fieldingIDs.length > 0 && [...v.battingIDs,...v.fieldingIDs].every(id => known.has(id.toLowerCase()));
}

export class ScheduleScoring {
  constructor(store, check) {
    this.store = store;
    this.db = store.db;
    this.check = check;
    this.db.exec(`CREATE TABLE IF NOT EXISTS schedule_scoring(
      team TEXT NOT NULL, source TEXT NOT NULL, epoch INTEGER NOT NULL DEFAULT 0,
      scorer TEXT, lineup TEXT, writer TEXT, editor TEXT, frame TEXT,
      full_version INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0, closed INTEGER NOT NULL DEFAULT 0,
      proposal TEXT, receipt TEXT, transfer TEXT, checkpoint TEXT, reserved TEXT, last_frame TEXT, opening_ready INTEGER DEFAULT 0, opening_confirmed INTEGER DEFAULT 0, PRIMARY KEY(team,source),
      FOREIGN KEY(team,source) REFERENCES team_schedules(team_id,source_id) ON DELETE CASCADE)`);
    if (!this.db.prepare('PRAGMA table_info(schedule_scoring)').all().some(column => column.name === 'full_version')) {
      this.db.exec('ALTER TABLE schedule_scoring ADD COLUMN full_version INTEGER NOT NULL DEFAULT 0');
    }
  }

  row(team, source, knownVersion) {
    // Polling never loads the complete handoff document or an unchanged field snapshot.
    const columns = knownVersion === undefined ? 'c.*' : `c.team,c.source,c.epoch,c.version,c.full_version,c.closed,c.editor,c.writer,c.scorer,c.lineup,
      c.opening_ready,c.opening_confirmed,c.transfer,c.checkpoint IS NOT NULL AS checkpoint,c.last_frame,c.reserved,c.receipt,c.proposal,
      CASE WHEN c.version = ? AND c.epoch = ? THEN NULL ELSE c.frame END AS frame`;
    const row = this.db.prepare(`SELECT s.owner_id,s.cancelled,s.away,s.home,${columns}
      FROM team_schedules s LEFT JOIN schedule_scoring c ON c.team=s.team_id AND c.source=s.source_id
      WHERE s.team_id=? AND s.source_id=?`).get(...(knownVersion === undefined ? [team,source] : [...knownVersion,team,source]));
    this.check(row,404,'schedule_missing','球队赛程不存在。');
    return row;
  }

  role(member,row) {
    if (row.cancelled || member.role === 'member') return 'viewer';
    if (row.scorer === member.id || (!row.scorer && row.owner_id === member.id && member.role === 'admin')) return 'scorer';
    return row.lineup === member.id ? 'lineup' : 'viewer';
  }

  summary(row, member, device) {
    const role = this.role(member,row);
    const label = id => id ? this.db.prepare('SELECT nickname FROM members WHERE id=?').get(id)?.nickname : null;
    const target = JSON.parse(row.transfer);
    return { canPrepare:member.id === row.owner_id && member.role !== 'member' && !row.writer && !row.editor && !row.cancelled && !row.closed, openingReady:!!row.opening_ready, openingConfirmed:!!row.opening_confirmed, transfer:target, hasCheckpoint:!!row.checkpoint, epoch: row.epoch ?? 0, scorer:row.scorer, lineup:row.lineup, scorerName:row.scorerName ?? label(row.scorer),
      lineupName:row.lineupName ?? label(row.lineup), role, active: !!row.writer && !row.closed, closed: !!row.closed,
      holdsDevice: role === 'scorer' ? row.writer === device : role === 'lineup' && row.editor === device };
  }

  clearRevokedProposal(row) {
    if (!row.proposal || row.reserved) return;
    const valid = this.db.prepare(`SELECT 1 FROM members m JOIN member_devices d ON d.member_id=m.id AND d.team_id=m.team_id
      JOIN devices v ON v.id=d.device_id WHERE m.id=? AND m.team_id=? AND m.active=1 AND m.role IN ('admin','scorer')
      AND d.device_id=? AND d.active=1 AND v.revoked=0`).get(row.lineup,row.team,row.editor);
    if (!valid) {
      row.receipt = JSON.stringify({id:JSON.parse(row.proposal).id,applied:false});
      row.proposal = null;
      this.db.prepare('UPDATE schedule_scoring SET proposal=NULL,receipt=? WHERE team=? AND source=?').run(row.receipt,row.team,row.source);
    }
  }

  read(team,source,device,query = new URLSearchParams()) {
    const param = name => /^\d{1,12}$/.test(query.get(name) ?? '') ? Number(query.get(name)) : -1;
    const known = param('version'), epoch = param('epoch');
    const member = this.store.membership(team,device), row = this.row(team,source,[known || -1,epoch]);
    this.clearRevokedProposal(row);
    const summary = this.summary(row,member,device);
    const privateView = summary.role !== 'viewer' && !row.closed;
    const sameEpoch = epoch === summary.epoch;
    const hasBase = privateView && sameEpoch && Number.isSafeInteger(known) && known > 0 && known <= row.version;
    const reuseFrame = hasBase && known === row.version;
    const reusePlayers = hasBase && !reuseFrame && row.full_version > 0 && known >= row.full_version;
    const frame = privateView && !reuseFrame ? JSON.parse(row.frame) : null;
    if (reusePlayers && frame) {
      frame.state.homeTeam.players = [];
      frame.state.awayTeam.players = [];
    }
    return { ...summary, version:row.version ?? 0, compactSupported:true, reuseFrame, reusePlayers, frame,
      lastFrameID:summary.role === 'scorer' ? row.last_frame : null, reserved:privateView ? row.reserved : null, proposal:privateView ? JSON.parse(row.proposal) : null, receipt:privateView ? JSON.parse(row.receipt) : null };
  }

  checkpoint(team,source,device) {
    const member = this.store.membership(team,device), row = this.row(team,source);
    this.check(this.role(member,row) === 'scorer' && row.writer === device && row.checkpoint,403,'forbidden','请由接手设备核对比赛记录。');
    return { game:JSON.parse(row.checkpoint) };
  }

  requireGameWriter(team,source,device) {
    if (!this.db.prepare('SELECT 1 FROM team_schedules WHERE team_id=? AND source_id=?').get(team,source)) return;
    const row = this.row(team,source), member = this.store.membership(team,device);
    this.check(this.role(member,row) === 'scorer' && row.writer === device && !row.transfer,
      403,'schedule_writer','本场比赛只允许指定的局面设备提交。');
  }

  write(team,source,device,action,body) {
    let member, row;
    const assign = action === 'assign', seed = action === 'prepare';
    return this.store.operation(device,body,`schedule-scoring:${team}:${source}:${action}`,() => {
      member = this.store.membership(team,device,assign ? 'manageMembers' : 'readSchedule');
      row = this.row(team,source);
      this.clearRevokedProposal(row);
      this.check(!row.cancelled,409,'cancelled','赛程已取消。');
      const role = this.role(member,row);
      if (seed) this.check(member.id === row.owner_id && member.role !== 'member',403,'forbidden','只有赛程发布人可提供赛前阵容。');
      else if (!assign) {
        this.check(!row.transfer || action === 'release',409,'handoff_pending','管理员已安排交接，请先交接已保存的记录。');
        const needed = ['propose','joinLineup','confirmLineup'].includes(action) ? 'lineup' : 'scorer';
        this.check(role === needed,403,'forbidden','当前成员没有本场岗位权限。');
        const holder = needed === 'scorer' ? row.writer : row.editor;
        this.check(holder === device || (!holder && ['start','joinLineup','resumeLegacy'].includes(action)),
          409,'device_changed','本场岗位已由其他设备持有。');
        this.check(body.epoch === (row.epoch ?? 0),409,'assignment_changed','指派已变化，请重新进入比赛。');
      }
    },() => {
      this.check(!row.closed,409,'closed','比赛已经结束。');
      this.db.prepare('INSERT OR IGNORE INTO schedule_scoring(team,source) VALUES(?,?)').run(team,source);
      if (assign) {
        this.check(body.epoch === (row.epoch ?? 0),409,'assignment_changed','其他管理员已修改指派，请刷新。');
        const ids = [body.scorer,body.lineup].map(id => {
          if (id == null) return null;
          this.check(identifier(id));
          const memberID = id.toLowerCase();
          this.check(this.db.prepare("SELECT id FROM members WHERE id=? AND team_id=? AND active=1 AND role IN ('admin','scorer')").get(memberID,team),
            400,'ineligible','请选择本队有效管理员或记录员。');
          return memberID;
        });
        const [scorer,lineup] = ids;
        this.check(!lineup || scorer && lineup !== scorer);
        const sameWriter = (row.scorer ?? row.owner_id) === (scorer ?? row.owner_id);
        if (row.writer && !sameWriter) {
          this.check(!row.proposal,409,'pending_lineup','请先处理阵容员已提交的调整。');
          const target = scorer ?? row.owner_id;
          this.check(scorer || this.db.prepare("SELECT 1 FROM members WHERE id=? AND team_id=? AND active=1 AND role='admin'").get(target,team),
            409,'ineligible','请明确指定接手的局面记录员。');
          this.db.prepare('UPDATE schedule_scoring SET epoch=epoch+1,transfer=? WHERE team=? AND source=?')
            .run(JSON.stringify({id:body.operationID,scorer,lineup,targetName:this.db.prepare('SELECT nickname FROM members WHERE id=?').get(target).nickname}),team,source);
          this.store.audit(team,device,'scheduleHandoffRequested',source);
          return {ok:true};
        }
        this.check(!row.proposal || row.lineup === lineup,409,'pending_lineup','请先处理阵容员已提交的调整。');
        this.db.prepare(`UPDATE schedule_scoring SET scorer=?,lineup=?,epoch=epoch+1,
          opening_ready=CASE WHEN lineup IS ? THEN opening_ready ELSE 0 END,
          editor=CASE WHEN lineup IS ? THEN editor ELSE NULL END,transfer=NULL WHERE team=? AND source=?`)
          .run(scorer,lineup,lineup,lineup,team,source);
        this.store.audit(team,device,'scheduleScorersAssigned',source);
      } else if (action === 'confirmLineup' || action === 'confirmOpening') {
        this.check(row.frame && !row.proposal && row.version === body.version && !row.opening_confirmed,
          409,'opening_changed','阵容正在变化，请核对最新先发。');
        if (action === 'confirmLineup') {
          this.db.prepare('UPDATE schedule_scoring SET opening_ready=1 WHERE team=? AND source=?').run(team,source);
        } else {
          this.check(!row.lineup || row.opening_ready,409,'opening_required','请阵容员先确认双方先发。');
          this.db.prepare('UPDATE schedule_scoring SET opening_confirmed=1 WHERE team=? AND source=?').run(team,source);
        }
      } else if (action === 'resumeLegacy') {
        this.check(!row.writer && !row.frame && !row.closed && !row.transfer && row.owner_id === member.id,
          409,'already_started','本场已有记录位置，请核对原设备。');
        this.validateFrame(body.frame,row);
        this.db.prepare('UPDATE schedule_scoring SET frame=?,version=1,full_version=1,opening_confirmed=1,writer=? WHERE team=? AND source=?')
          .run(JSON.stringify(body.frame),device,team,source);
        this.store.audit(team,device,'scheduleScoringResumed',source);
      } else if (action === 'release') {
        const target = JSON.parse(row.transfer);
        this.check(target && target.id === body.transferID && body.version === row.version && !row.proposal,
          409,'handoff_changed','交接已变化，请重新核对。');
        const eligible = this.db.prepare("SELECT role FROM members WHERE id=? AND team_id=? AND active=1 AND role IN ('admin','scorer')");
        const next = eligible.get(target.scorer ?? row.owner_id,team);
        this.check(next && (target.scorer || next.role === 'admin') && (!target.lineup || eligible.get(target.lineup,team)),
          409,'member_changed','接手成员资格已变化，请管理员重新安排。');
        this.check(body.document?.payload?.id?.toLowerCase() === source && !body.document.payload.state?.isFinal);
        const validated = this.store.games.validate(body.document);
        this.validateFrame(body.frame,row);
        this.check(body.frame.state.homeTeam.id === body.document.payload.state.homeTeam.id &&
          body.frame.state.awayTeam.id === body.document.payload.state.awayTeam.id);
        this.db.prepare(`UPDATE schedule_scoring SET epoch=epoch+1,version=version+1,full_version=version+1,writer=NULL,editor=NULL,
          scorer=?,lineup=?,transfer=NULL,frame=?,checkpoint=?,receipt=NULL WHERE team=? AND source=?`)
          .run(target.scorer,target.lineup,JSON.stringify(body.frame),validated.payload,team,source);
        this.store.audit(team,device,'scheduleHandoffReleased',source);
      } else if (action === 'reserve') {
        this.check(identifier(body.appliedID) && JSON.parse(row.proposal)?.id === body.appliedID,409,'proposal_changed','请刷新阵容操作。');
        this.db.prepare('UPDATE schedule_scoring SET reserved=? WHERE team=? AND source=?').run(body.appliedID,team,source);
      } else if (action === 'received') {
        this.db.prepare('UPDATE schedule_scoring SET checkpoint=NULL WHERE team=? AND source=?').run(team,source);
      } else if (seed || action === 'frame') {
        this.check(body.version === (row.version ?? 0),409,'frame_changed','请先核对最新局面。');
        const compact = body.reusePlayers === true;
        if (compact) {
          this.check(!seed && row.frame && fits(body.frame) && ['homeTeam','awayTeam'].every(side =>
            Array.isArray(body.frame.state?.[side]?.players) && body.frame.state[side].players.length === 0));
          const previous = JSON.parse(row.frame);
          // Reconstruct locally: the idempotency record must retain the original request.
          const state = {...body.frame.state};
          for (const side of ['homeTeam','awayTeam']) state[side] = {...state[side],players:previous.state[side].players};
          body = {...body,frame:{...body.frame,state}};
        }
        if (seed) this.check(!row.checkpoint && !row.writer && !row.editor && !row.proposal,409,'already_started','记录已开始。');
        this.validateFrame(body.frame,row);
        if (seed) this.check(body.frame.pregame === true && !body.frame.state.isFinal);
        this.check(!body.finished || !row.proposal || body.appliedID === JSON.parse(row.proposal).id,409,'pending_lineup','终场前请核对待处理的阵容操作。');
        if (!seed && !body.frame.pregame && row.lineup) this.check(row.opening_confirmed,409,'opening_required','请先确认双方先发。');
        const receipt = body.appliedID ?? null;
        if (!seed) this.validateAssignedLineups(row,body.frame,receipt,body.rejected === true);
        if (receipt) this.check((body.rejected === true || row.reserved === receipt) && JSON.parse(row.proposal)?.id === receipt,409,'proposal_changed','阵容操作已变化。');
        this.db.prepare(`UPDATE schedule_scoring SET frame=?,last_frame=?,full_version=CASE WHEN ? THEN full_version ELSE version+1 END,version=version+1,closed=?,
          opening_confirmed=CASE WHEN lineup IS NULL AND writer IS NOT NULL THEN 1 ELSE opening_confirmed END,
          reserved=CASE WHEN ? THEN NULL ELSE reserved END,proposal=CASE WHEN ? THEN NULL ELSE proposal END,receipt=CASE WHEN ? THEN ? ELSE receipt END WHERE team=? AND source=?`)
          .run(JSON.stringify(body.frame),body.operationID,Number(compact),Number(!seed && body.finished === true),Number(!!receipt),Number(!!receipt),Number(!!receipt),
            JSON.stringify({ id:receipt, applied:body.rejected !== true }),team,source);
      } else if (action === 'start' || action === 'joinLineup') {
        this.check(row.frame,409,'no_lineup','请发布人先确认双方名单与比赛规则。');
        const column = action === 'start' ? 'writer' : 'editor';
        this.db.prepare(`UPDATE schedule_scoring SET ${column}=? WHERE team=? AND source=?`).run(device,team,source);
      } else if (action === 'propose') {
        const p = body.proposal;
        this.check(fits(p) && identifier(p.id) && Number.isSafeInteger(p.version) && p.version > 0 && p.version <= row.version &&
          fields(p,['id','version','baseline','lastPlay','lineup']) && (p.lastPlay == null || identifier(p.lastPlay)) &&
          validLineup(p.baseline) && validLineup(p.lineup) && p.baseline.isHome === p.lineup.isHome && p.lineup.changes.length > 0);
        this.check(!row.proposal,409,'pending_lineup','请先处理已提交的阵容调整。');
        this.db.prepare('UPDATE schedule_scoring SET proposal=?,receipt=NULL,opening_ready=CASE WHEN opening_confirmed=1 THEN opening_ready ELSE 0 END WHERE team=? AND source=?')
          .run(JSON.stringify(p),team,source);
      } else this.check(false,404);
      return { ok:true };
    });
  }

  validateAssignedLineups(row,frame,receipt,rejected) {
    if (!row.lineup || !row.frame) return;
    const before = JSON.parse(row.frame).state;
    const proposed = receipt && !rejected ? JSON.parse(row.proposal)?.lineup : null;
    for (const side of ['home','away']) {
      const priorTeam = before[side+'Team'], nextTeam = frame.state[side+'Team'];
      const next = proposed?.isHome === (side === 'home') ? proposed : null;
      const expected = next ? { ...priorTeam, players:next.players } : priorTeam;
      const pitchKey = side === 'home' ? 'activeHomePitcherID' : 'activeAwayPitcherID';
      const orPrior = (k,v) => next ? v ?? null : before[k] ?? null;
      const keys = [[side+'BattingOrderIDs',next?.battingIDs],[side+'FieldingPlayerIDs',next?.fieldingIDs],
        [side+'DesignatedHitterID',next?.dhID],[pitchKey,next?.pitcherID]];
      this.check(isDeepStrictEqual(expected,nextTeam) &&
        keys.every(([k,v]) => isDeepStrictEqual(orPrior(k,v),frame.state[k] ?? null)),
        409,'lineup_changed','双人记录时，阵容变更须由阵容岗位提交并核对。');
    }
  }

  validateFrame(frame,row) {
    this.check(fits(frame) && typeof frame.isHome === 'boolean' && object(frame.rules) && object(frame.state));
    const s = frame.state;
    this.check(fields(frame,['isHome','rules','pregame','lastPlay','state']) && typeof frame.pregame === 'boolean' &&
      (frame.lastPlay == null || identifier(frame.lastPlay)) && validRules(frame.rules) && (s.rulesSnapshot == null || isDeepStrictEqual(s.rulesSnapshot,frame.rules)) &&
      ((s.homeTeam?.name === row.home && s.awayTeam?.name === row.away) ||
       (s.homeTeam?.name === row.away && s.awayTeam?.name === row.home)) &&
      typeof s.isTop === 'boolean' && typeof s.isFinal === 'boolean' && integer(s.inning,1,999) &&
      integer(s.scheduledInnings,1,999) && integer(s.balls,0,20) && integer(s.strikes,0,3) && integer(s.outs,0,3) &&
      runners(s.baseRunners) && (s.automaticRunnerIDs == null || ids(s.automaticRunnerIDs)));
    // A normal field update may never carry archives, full event history or individual statistics.
    const shared = ['homeTeam','awayTeam','scheduledInnings','rulesSnapshot','extraFoulUsed','pitchingOutsAccountingVersion',
      'inning','isTop','balls','strikes','outs','baseRunners','automaticRunnerIDs','batting','pitching','fielding','playLog','isFinal','clockDisplayMode'];
    const sideFields = ['RunsByInning','Hits','Errors','BatterIndex','BattingOrderIDs','FieldingPlayerIDs','DesignatedHitterID'];
    this.check(fields(s,[...shared,...sideFields.flatMap(k => ['home'+k,'away'+k]),'activeHomePitcherID','activeAwayPitcherID']) &&
      ['batting','pitching','fielding','playLog'].every(k => s[k] && Object.keys(s[k]).length === 0));
    const all = new Set();
    for (const side of ['away','home']) {
      const team = s[side+'Team'], order = s[side+'BattingOrderIDs'];
      this.check(identifier(team.id) && Array.isArray(team.players) && team.players.length >= 1 && team.players.length <= 200);
      const ids = new Set();
      for (const p of team.players) {
        this.check(player(p) && !all.has(p.id.toLowerCase()));
        all.add(p.id.toLowerCase()); ids.add(p.id.toLowerCase());
      }
      const runs = s[side+'RunsByInning'], fielders = s[side+'FieldingPlayerIDs'] ?? order;
      this.check(fields(team,['id','name','shortName','city','players','isDemo']) &&
        (team.isDemo == null || typeof team.isDemo === 'boolean') && short(team.shortName) && short(team.city) && Array.isArray(runs) && runs.length > 0 && runs.length <= 999 &&
        runs.every(n => integer(n,0,100000)) && integer(s[side+'Hits'],0,100000) && integer(s[side+'Errors'],0,100000) &&
        Array.isArray(fielders) && fielders.length <= 200 && fielders.every(id => identifier(id) && ids.has(id.toLowerCase())));
      this.check(Array.isArray(order) && order.length > 0 && order.every(id => identifier(id) && ids.has(id.toLowerCase())) &&
        new Set(order.map(id => id.toLowerCase())).size === order.length && Number.isSafeInteger(s[side+'BatterIndex']) && integer(s[side+'BatterIndex'],0,1000000));
    }
  }
}
