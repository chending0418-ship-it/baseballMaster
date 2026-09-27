import { createServer } from 'node:http';
import { DatabaseSync } from 'node:sqlite';
import { randomBytes, createHash, timingSafeEqual } from 'node:crypto';
import { readFileSync, mkdirSync, chmodSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const BASE = '/baseballmaster/live';
export const HOUR = 3_600_000;
const CODE = /^[A-F0-9]{24}$/;
const assets = new Map([
  ['', ['index.html', 'text/html; charset=utf-8']],
  ['app.js', ['app.js', 'text/javascript; charset=utf-8']],
  ['style.css', ['style.css', 'text/css; charset=utf-8']]
]);
const hash = value => createHash('sha256').update(value).digest('hex');
class HTTPError extends Error { constructor(status, message) { super(message); this.status = status; } }
const reject = (condition, message = '资料格式不正确') => { if (!condition) throw new HTTPError(400, message); };
const str = (s, max = 200) => typeof s === 'string' && s.length <= max;
const int = (n, min = 0, max = 100000) => Number.isSafeInteger(n) && n >= min && n <= max;
const person = p => p === null || (p && str(p.id, 50) && str(p.name) && str(p.number, 20));
const keys = (obj, allowed) => obj && Object.keys(obj).every(k => allowed.includes(k));
// Strict, versioned public projection: never accept an entire local game/roster as a snapshot.
export function validateSnapshot(s) {
  reject(keys(s, ['schema', 'gameID', 'revision', 'mode', 'isFinal', 'endedAt', 'inning', 'isTop', 'balls', 'strikes', 'outs', 'home', 'away', 'batter', 'pitcher', 'pitchCount', 'appearancePitchCount', 'pitchLimit', 'bases', 'currentAppearanceID', 'notice', 'entries']));
  reject(s.schema === 1 && str(s.gameID, 50) && int(s.revision) && ['standard', 'coachPitch'].includes(s.mode));
  reject(typeof s.isFinal === 'boolean' && typeof s.isTop === 'boolean' && int(s.inning, 1, 999));
  reject(int(s.balls, 0, 20) && int(s.strikes, 0, 3) && int(s.outs, 0, 3));
  reject(s.endedAt === null || (Number.isFinite(s.endedAt) && s.endedAt > 0));
  reject(!s.isFinal || s.endedAt !== null);
  for (const t of [s.home, s.away]) {
    reject(keys(t, ['name', 'runs', 'hits', 'errors', 'innings']) && str(t.name));
    reject(int(t.runs) && int(t.hits) && int(t.errors) && Array.isArray(t.innings) && t.innings.length <= 999 && t.innings.every(n => int(n)));
    reject(t.runs === t.innings.reduce((a, b) => a + b, 0));
  }
  for (const p of [s.batter, s.pitcher]) reject(person(p) && (p === null || keys(p, ['id', 'name', 'number'])));
  reject(s.pitchCount === null || int(s.pitchCount));
  reject(int(s.appearancePitchCount) && (s.pitchLimit === null || int(s.pitchLimit, 1, 20)));
  reject(s.currentAppearanceID === null || str(s.currentAppearanceID, 50));
  reject(str(s.notice, 500));
  reject(Array.isArray(s.bases) && s.bases.length <= 3 && new Set(s.bases.map(b => b.base)).size === s.bases.length);
  for (const b of s.bases) reject(keys(b, ['base', 'player']) && int(b.base, 1, 3) && b.player && person(b.player) && keys(b.player, ['id', 'name', 'number']));
  reject(Array.isArray(s.entries) && s.entries.length <= 10000);
  const ids = new Set();
  for (const e of s.entries) {
    reject(keys(e, ['id', 'appearanceID', 'inning', 'isTop', 'kind', 'label', 'summary', 'player', 'status', 'details']));
    reject(str(e.id, 80) && e.id.length > 0 && !ids.has(e.id)); ids.add(e.id);
    reject(e.appearanceID === null || str(e.appearanceID, 50));
    reject(int(e.inning, 1, 999) && typeof e.isTop === 'boolean' && ['appearance', 'event'].includes(e.kind));
    reject(str(e.label, 100) && str(e.summary, 1000) && person(e.player) && (e.player === null || keys(e.player, ['id', 'name', 'number'])));
    reject(['current', 'completed', 'interrupted', 'review'].includes(e.status));
    reject(Array.isArray(e.details) && e.details.length <= 2000 && e.details.every(d => keys(d, ['id', 'text']) && str(d.id, 50) && str(d.text, 1000)));
  }
  return s;
}

export function createLiveServer({ dbPath = ':memory:', now = Date.now, trustProxy = false, maxSessions = 500, createLimit = 30 } = {}) {
  if (dbPath !== ':memory:') mkdirSync(dirname(dbPath), { recursive: true, mode: 0o700 });
  const db = new DatabaseSync(dbPath);
  if (dbPath !== ':memory:') chmodSync(dbPath, 0o600);
  db.exec(`PRAGMA journal_mode=DELETE; PRAGMA secure_delete=ON; PRAGMA busy_timeout=5000;
    CREATE TABLE IF NOT EXISTS live (code TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL,
    token_hash TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL, payload_hash TEXT NOT NULL,
    last_seen INTEGER NOT NULL, expires_at INTEGER NOT NULL, final INTEGER NOT NULL);
    CREATE INDEX IF NOT EXISTS live_expiry ON live(expires_at);`);
  const get = db.prepare('SELECT * FROM live WHERE code=?');
  const sweep = () => db.prepare('DELETE FROM live WHERE expires_at<=?').run(now());
  sweep(); // Remove expired content before accepting any request, including after a restart.
  const cleanup = setInterval(() => { sweep(); for (const [key, value] of limits) if (value.until <= now()) limits.delete(key); }, 1000); cleanup.unref();
  const limits = new Map();
  function limit(key, max, window = 60000) {
    const time = now();
    if (limits.size > 10000) for (const [k, v] of limits) if (v.until <= time) limits.delete(k);
    let bucket = limits.get(key);
    if (!bucket || bucket.until <= time) { bucket = { count: 0, until: time + window }; limits.set(key, bucket); }
    if (++bucket.count > max || limits.size > 20000) throw new HTTPError(429, '请求过于频繁，请稍后重试');
  }
  function token(req) {
    const t = req.headers.authorization?.match(/^Bearer ([a-f0-9]{64})$/)?.[1];
    if (!t) throw new HTTPError(401, '缺少发布凭证');
    return hash(t);
  }
  function authenticate(req, row) {
    if (!row) throw new HTTPError(410, '直播已关闭或过期');
    if (!timingSafeEqual(Buffer.from(token(req), 'hex'), Buffer.from(row.token_hash, 'hex'))) throw new HTTPError(403, '无发布权限');
  }
  async function body(req) {
    const chunks = []; let length = 0;
    if (!req.headers['content-type']?.startsWith('application/json')) throw new HTTPError(415, '需要 JSON');
    for await (const chunk of req) { length += chunk.length; if (length > 2 * 1024 * 1024) throw new HTTPError(413, '比赛资料过大'); chunks.push(chunk); }
    try { return JSON.parse(Buffer.concat(chunks).toString()); } catch { throw new HTTPError(400, 'JSON 无效'); }
  }
  function envelope(row, unchanged = false) {
    return { code: row.code, revision: row.revision, serverTime: now(), lastSeen: row.last_seen, expiresAt: row.expires_at,
      ...(unchanged ? { unchanged: true } : { snapshot: JSON.parse(row.payload) }) };
  }
  function deadline(s, row) {
    if (!s.isFinal) return now() + HOUR;
    // Editing a completed game must not keep pushing its deletion time back.
    return Math.min(row?.final ? row.expires_at : Infinity, Math.min(s.endedAt, now()) + HOUR);
  }
  const server = createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('X-Robots-Tag', 'noindex, nofollow, noarchive');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    const json = (status, data) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(data)); };
    try {
      sweep();
      const url = new URL(req.url, 'http://localhost');
      const path = url.pathname;
      if (path === `${BASE}/health` && req.method === 'GET') return json(200, { ok: true });
      const ip = trustProxy ? (req.headers['x-real-ip'] || req.socket.remoteAddress) : req.socket.remoteAddress;
      limit(`all:${ip}`, 6000);
      if (path === `${BASE}/api/sessions` && req.method === 'DELETE') {
        token(req);
        const b = await body(req); reject(b && typeof b === 'object');
        reject(/^[a-f0-9-]{36}$/i.test(b.requestID));
        const row = db.prepare('SELECT * FROM live WHERE request_id=?').get(b.requestID);
        if (row) { authenticate(req, row); db.prepare('DELETE FROM live WHERE code=?').run(row.code); }
        return json(200, { deleted: true });
      }
      if (path === `${BASE}/api/sessions` && req.method === 'POST') {
        limit(`create:${ip}`, createLimit, HOUR);
        const tokenHash = token(req);
        const b = await body(req); reject(b && typeof b === 'object'); const s = validateSnapshot(b.snapshot);
        reject(/^[a-f0-9-]{36}$/i.test(b.requestID) && Number.isFinite(b.createdAt));
        const existing = db.prepare('SELECT * FROM live WHERE request_id=?').get(b.requestID);
        if (existing) { authenticate(req, existing); return json(200, envelope(existing)); }
        reject(Math.abs(now() - b.createdAt) <= 15 * 60000, '开播请求已过期，请重新开启');
        reject(!s.isFinal, '只能为进行中的比赛开启直播');
        if (db.prepare('SELECT COUNT(*) AS n FROM live').get().n >= maxSessions) throw new HTTPError(503, '直播服务繁忙');
        const code = randomBytes(12).toString('hex').toUpperCase();
        const payload = JSON.stringify(s);
        db.prepare('INSERT INTO live VALUES (?,?,?,?,?,?,?,?,?)').run(code, b.requestID, tokenHash, s.revision, payload, hash(payload), now(), deadline(s), 0);
        return json(201, envelope(get.get(code)));
      }
      const match = path.match(new RegExp(`^${BASE}/api/sessions/([A-Fa-f0-9]{24})$`));
      if (match) {
        const code = match[1].toUpperCase(); let row = get.get(code);
        if (req.method === 'GET') {
          if (!row) throw new HTTPError(404, '直播已关闭、过期或不存在');
          return json(200, envelope(row, url.searchParams.get('since') === String(row.revision)));
        }
        authenticate(req, row);
        if (req.method === 'DELETE') { db.prepare('DELETE FROM live WHERE code=?').run(code); return json(200, { deleted: true }); }
        if (req.method === 'POST') {
          limit(`write:${code}`, 180);
          db.prepare('UPDATE live SET last_seen=?,expires_at=? WHERE code=?').run(now(), row.final ? row.expires_at : now() + HOUR, code);
          return json(200, envelope(get.get(code), true));
        }
        if (req.method === 'PUT') {
          limit(`write:${code}`, 180);
          const raw = await body(req);
          // Body reception yields: another publish/delete may have committed in the meantime.
          // Re-read and authenticate inside this synchronous compare-and-write section.
          sweep(); row = get.get(code); authenticate(req, row);
          const s = validateSnapshot(raw); const payload = JSON.stringify(s);
          if (s.gameID !== JSON.parse(row.payload).gameID) throw new HTTPError(409, '比赛身份不符');
          if (s.revision < row.revision || (s.revision === row.revision && hash(payload) !== row.payload_hash)) throw new HTTPError(409, '修订版本冲突');
          const expiry = deadline(s, row);
          if (expiry <= now()) { db.prepare('DELETE FROM live WHERE code=?').run(code); throw new HTTPError(410, '直播已过期'); }
          db.prepare('UPDATE live SET revision=?,payload=?,payload_hash=?,last_seen=?,expires_at=?,final=? WHERE code=?')
            .run(s.revision, payload, hash(payload), now(), expiry, Number(s.isFinal), code);
          return json(200, envelope(get.get(code), true));
        }
        throw new HTTPError(405, '方法不支持');
      }
      if (req.method === 'GET' && (path === BASE || path.startsWith(`${BASE}/`))) {
        const part = path.slice(BASE.length).replace(/^\//, '');
        const asset = assets.get(part) || (CODE.test(part.toUpperCase()) ? assets.get('') : null);
        if (asset) {
          res.writeHead(CODE.test(part.toUpperCase()) && !get.get(part.toUpperCase()) ? 410 : 200, { 'Content-Type': asset[1] });
          res.end(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'public', asset[0]))); return;
        }
      }
      throw new HTTPError(404, '页面不存在');
    } catch (e) {
      // Never log URL codes, bearer tokens, participant names, or snapshot bodies.
      if (!e.status) console.error('Live request failed:', e.code || e.name);
      if (!res.headersSent) json(e.status || 500, { error: e.status ? e.message : '服务暂时不可用' });
      else res.end();
    }
  });
  server.requestTimeout = 15000; server.headersTimeout = 10000;
  server.on('close', () => { clearInterval(cleanup); db.close(); });
  return { server, sweep, count: () => db.prepare('SELECT COUNT(*) AS n FROM live').get().n };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.umask(0o077);
  const { server } = createLiveServer({ dbPath: process.env.LIVE_DB || './data/live.sqlite', trustProxy: process.env.TRUST_PROXY === '1' });
  server.listen(Number(process.env.PORT || 8088), process.env.HOST || '127.0.0.1', () => console.log('BaseballMaster live service ready'));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
}
