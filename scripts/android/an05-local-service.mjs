// Android JVM integration fixture only. Existing service, in-memory DB, loopback;
// no production config, deploy script, credentials, shared service changes or UI.
import { createCollaborationServer } from '../../collaboration/server.mjs';
import { createInterface } from 'node:readline';
import { randomUUID } from 'node:crypto';
import { fixture } from '../../collaboration/test/match-fixture.mjs';

const app = createCollaborationServer({ database: ':memory:', requestsPerMinute: 10000 });
app.server.listen(0, '127.0.0.1', () => process.stdout.write(JSON.stringify({ port: app.server.address().port }) + '\n'));
const input = createInterface({ input: process.stdin });
input.on('line', line => {
  const command = JSON.parse(line);
  if (command.kind === 'fixture') {
    const document = fixture(Boolean(command.final)); document.formatVersion = 2;
    document.payload.opaqueFutureHistory = { retained: ['original-event', 'audit-checkpoint'] };
    process.stdout.write(JSON.stringify({ document }) + '\n'); return;
  }
  if (command.kind === 'recovery') {
    app.store.db.prepare("UPDATE metadata SET value=? WHERE key='recoveryEpoch'").run(randomUUID());
    app.store.db.prepare("INSERT OR REPLACE INTO metadata VALUES('needsDeviceRecoveryReview','1')").run();
  } else if (command.kind === 'access') {
    app.store.db.prepare('UPDATE teams SET access_epoch=access_epoch+1 WHERE id=?').run(command.teamID);
  } else if (command.kind === 'credential') {
    app.store.db.prepare('UPDATE devices SET revoked=1 WHERE id=?').run(command.teamID);
  } else if (command.kind === 'compact') {
    app.store.games.compactChanges(command.teamID);
  } else throw new Error('Unknown isolated fixture command');
  process.stdout.write('{"updated":true}\n');
});
input.on('close', () => { app.server.closeAllConnections(); app.server.close(() => { app.store.close(); }); });
