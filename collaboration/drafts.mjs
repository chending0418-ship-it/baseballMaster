import { randomUUID } from 'node:crypto';

const uuid = v => typeof v === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(v);
const note = v => typeof v === 'string' && v.trim().length > 0 && v.length <= 500 && !/[\u0000-\u001f\u007f]/.test(v);

// Drafts are evidence for a human correction, never an alternative write lease.
export class DraftService {
  constructor(store, check) {
    this.store = store; this.db = store.db; this.check = check;
    this.db.exec(`CREATE TABLE IF NOT EXISTS conflict_drafts(
      id TEXT PRIMARY KEY, game_id TEXT NOT NULL REFERENCES shared_games(id),
      actor_device TEXT NOT NULL, client_id TEXT NOT NULL, nickname TEXT NOT NULL, device_name TEXT NOT NULL,
      payload TEXT NOT NULL, projection TEXT NOT NULL, digest TEXT NOT NULL, reason TEXT NOT NULL,
      received_revision INTEGER NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'pending',
      resolution_note TEXT, resolved_revision INTEGER, resolved_at INTEGER, resolved_by TEXT,
      UNIQUE(game_id,actor_device,client_id));
      CREATE INDEX IF NOT EXISTS drafts_game ON conflict_drafts(game_id,created_at);`);
  }
  submit(team, game, device, body) {
    this.check(uuid(body.conflictID) && note(body.reason));
    const valid = this.store.games.validate(body);
    return this.store.operation(device, body, `submitDraft:${team}:${game}`, () => this.store.membership(team,device,'createGame'), () => {
      const row = this.store.games.row(team,game,device), member = this.store.membership(team,device);
      this.check(!row.deleted,409,'deleted','比赛已删除，草稿保留在本机。');
      this.check(body.payload.id.toLowerCase() === JSON.parse(row.payload).id.toLowerCase());
      const prior = this.db.prepare('SELECT id,digest,reason FROM conflict_drafts WHERE game_id=? AND actor_device=? AND client_id=?').get(game,device,body.conflictID.toLowerCase());
      if (prior) {
        this.check(prior.digest === valid.digest && prior.reason === body.reason.trim(),409,'draft_conflict','此草稿已经提交过不同内容，请重新核对。');
        return { draftID: prior.id };
      }
      this.check(this.db.prepare("SELECT COUNT(*) AS n FROM conflict_drafts WHERE game_id=? AND status='pending'").get(game).n < 20,
        409,'draft_capacity','本场待核对草稿已达上限，请先由管理员处理。');
      const id = randomUUID(), name = this.db.prepare('SELECT label FROM devices WHERE id=?').get(device).label;
      this.store.checkGameCapacity(team,valid,undefined,true);
      this.db.prepare(`INSERT INTO conflict_drafts(id,game_id,actor_device,client_id,nickname,device_name,payload,projection,digest,reason,received_revision,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`).run(id,game,device,body.conflictID.toLowerCase(),member.nickname,name,valid.payload,valid.projection,valid.digest,body.reason.trim(),row.revision,this.store.now());
      this.store.audit(team,device,'draftSubmitted',id);
      return { draftID: id };
    });
  }
  list(team, game, device) {
    this.store.membership(team,device,'manageMembers');
    const row = this.store.games.row(team,game,device);
    this.check(!row.deleted,404,'not_found','比赛不存在或已删除。');
    return { drafts: this.db.prepare(`SELECT id,nickname,device_name AS deviceName,reason,received_revision AS receivedRevision,
      created_at AS createdAt,status,resolution_note AS resolutionNote,resolved_revision AS resolvedRevision,resolved_at AS resolvedAt
      FROM conflict_drafts WHERE game_id=? ORDER BY (status='pending') DESC,created_at DESC,id LIMIT 100`).all(game) };
  }
  read(team, game, draft, device) {
    const list = this.list(team,game,device), summary = list.drafts.find(d => d.id === draft);
    this.check(summary,404,'not_found','草稿不存在或无权查看。');
    const row = this.db.prepare('SELECT payload FROM conflict_drafts WHERE game_id=? AND id=?').get(game,draft);
    return { ...summary, payload: JSON.parse(row.payload) };
  }
  resolve(team, game, draft, device, body) {
    this.check(['supplemented','discarded'].includes(body.decision) && note(body.note));
    return this.store.operation(device,body,`resolveDraft:${team}:${game}:${draft}`, () => this.store.membership(team,device,'manageMembers'), () => {
      const row = this.store.games.row(team,game,device);
      this.check(!row.deleted,409,'deleted','比赛已删除。');
      this.check(!this.db.prepare('SELECT id FROM handoffs WHERE game_id=?').get(game),409,'handoff_pending','正在交接，请完成交接后核对。');
      this.check(body.baseRevision === row.revision && body.writerEpoch === row.epoch,409,'revision_conflict','正式比赛已有变化，请重新核对。');
      const source = this.db.prepare('SELECT * FROM conflict_drafts WHERE game_id=? AND id=?').get(game,draft);
      this.check(source,404,'not_found','草稿不存在或无权查看。');
      this.check(source.status === 'pending',409,'draft_resolved','草稿已经处理，请刷新。');
      if (body.decision === 'supplemented') {
        this.store.games.writer(row,device);
        this.check(row.revision > source.received_revision && this.db.prepare("SELECT id FROM audit WHERE team_id=? AND object_id=? AND action='gameUpdated' AND id>? LIMIT 1")
          .get(team,game,this.db.prepare("SELECT id FROM audit WHERE object_id=? AND action='draftSubmitted'").get(draft)?.id ?? Number.MAX_SAFE_INTEGER),
          409,'correction_required','请先取得记分权，参照草稿更正比赛并同步，再完成核对。');
      }
      this.db.prepare('UPDATE shared_games SET revision=revision+1,updated_at=? WHERE id=?').run(this.store.now(),game);
      this.db.prepare('UPDATE conflict_drafts SET status=?,resolution_note=?,resolved_revision=?,resolved_at=?,resolved_by=? WHERE id=?')
        .run(body.decision,body.note.trim(),row.revision+1,this.store.now(),device,draft);
      const current = this.store.games.row(team,game,device);
      this.store.games.changed(current,device,body.decision === 'supplemented' ? 'draftSupplemented' : 'draftDiscarded');
      this.store.audit(team,device,'draftResolved',draft);
      return this.store.games.metadata(current,device);
    });
  }
}
