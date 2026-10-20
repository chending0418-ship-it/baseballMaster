import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID,randomBytes } from 'node:crypto';
import { CollaborationStore } from '../server.mjs';
import { fixture } from './match-fixture.mjs';
const secret=()=>randomBytes(32).toString('base64url'),op=b=>({operationID:randomUUID(),...b});
const rejected=(f,c)=>assert.throws(f,e=>e.code===c);
function setup(t) {
 let now=Date.now();const s=new CollaborationStore({now:()=>now});t.after(()=>s.close());
 const admin=s.register(secret(),{label:'原设备'}).deviceID,recovery=secret(),team=s.createTeam(admin,op({name:'海浪',nickname:'陈教练',recoveryToken:recovery})).teamID;
 const member=s.register(secret(),{label:'队员手机'}).deviceID,token=secret();s.invite(team,admin,op({role:'member',token}));s.redeem(member,op({token,nickname:'队员'}));
 const g=s.games.create(team,admin,op(fixture(true)));s.games.access(team,g.id,admin,op({mode:'public',baseAccessVersion:1}));
 const invitation=secret();s.invite(team,admin,op({role:'scorer',token:invitation}));
 const dissolve=op({name:'海浪',confirm:true,baseRevision:1}),restore=op({name:'海浪',confirm:true});
 return {s,admin,member,team,g,recovery,invitation,dissolve,restore,later:days=>now+=days*86400000};
}
test('dissolve immediately fences every member, writer, live and archive link; only former admin device can restore within 30 days',t=>{
 const a=setup(t),ongoing=a.s.games.create(a.team,a.admin,op(fixture())),live=a.s.games.live(a.team,ongoing.id,a.admin,op({enabled:true}));
 assert.ok(a.s.games.publicRead(a.g.viewCode));
 rejected(()=>a.s.lifecycle.dissolve(a.team,a.member,a.dissolve),'forbidden');
 const result=a.s.lifecycle.dissolve(a.team,a.admin,a.dissolve);
 assert.equal(result.dissolved,true);assert.equal(a.s.listTeams(a.admin).teams.length,0);assert.equal(a.s.listTeams(a.member).teams.length,0);
 assert.deepEqual(a.s.lifecycle.dissolve(a.team,a.admin,a.dissolve),result);
 rejected(()=>a.s.games.document(a.team,a.g.id,a.admin),'not_found');
 rejected(()=>a.s.games.publicRead(a.g.viewCode),'not_found');rejected(()=>a.s.games.publicRead(live.liveCode,null,true),'live_expired');
 rejected(()=>a.s.security.recoveryPreview({teamID:a.team,token:a.recovery}),'recovery_invalid');
 assert.equal(a.s.lifecycle.list(a.admin).teams.length,1);assert.equal(a.s.lifecycle.list(a.member).teams.length,0);
 rejected(()=>a.s.lifecycle.restore(a.team,a.member,a.restore),'not_found');
 a.later(29);const restored=a.s.lifecycle.restore(a.team,a.admin,a.restore);assert.equal(restored.restored,true);
 assert.deepEqual(a.s.lifecycle.restore(a.team,a.admin,a.restore),restored);
 assert.equal(a.s.lifecycle.list(a.admin).teams.length,0);assert.equal(a.s.listTeams(a.member).teams.length,0);
 const game=a.s.games.document(a.team,a.g.id,a.admin);assert.equal(game.deleted,false);assert.equal(game.accessMode,'closed');assert.equal(game.canWrite,true);assert.ok(game.payload);
 assert.equal(a.s.security.recoveryStatus(a.team,a.admin).hasCode,false);
 rejected(()=>a.s.games.publicRead(a.g.viewCode),'not_found');
 const stranger=a.s.register(secret(),{label:'新手机'}).deviceID;
 rejected(()=>a.s.redeem(stranger,op({token:a.invitation,nickname:'新队员'})),'invitation_invalid');
});
test('team restore leaves matches already in trash deleted and only reactivates the restoring admin device',t=>{
 const a=setup(t),prior=a.s.games.create(a.team,a.admin,op(fixture(true)));
 a.s.games.deleteOrRestore(a.team,prior.id,a.admin,op({deleted:true,baseRevision:1,writerEpoch:1}));
 a.s.changeMember(a.team,a.s.membership(a.team,a.member).id,a.admin,op({role:'admin',baseRevision:1}));
 a.s.lifecycle.dissolve(a.team,a.admin,a.dissolve);assert.equal(a.s.lifecycle.list(a.member).teams.length,1);
 a.s.lifecycle.restore(a.team,a.member,a.restore);
 assert.equal(a.s.listTeams(a.admin).teams.length,0);assert.equal(a.s.games.document(a.team,prior.id,a.member).deleted,true);
 rejected(()=>a.s.lifecycle.restore(a.team,a.admin,op({name:'海浪',confirm:true})),'not_found');
});
test('wrong confirmation, stale team, failed disk write, changed replay and expired recovery do not resurrect access',t=>{
 const a=setup(t);
 rejected(()=>a.s.lifecycle.dissolve(a.team,a.admin,{...a.dissolve,name:'错误'}),'confirmation_required');
 rejected(()=>a.s.lifecycle.dissolve(a.team,a.admin,{...a.dissolve,baseRevision:0}),'revision_conflict');
 a.s.db.exec("CREATE TRIGGER fail_dissolve BEFORE INSERT ON team_deletions BEGIN SELECT RAISE(ABORT,'disk full'); END;");
 assert.throws(()=>a.s.lifecycle.dissolve(a.team,a.admin,a.dissolve));assert.ok(a.s.games.publicRead(a.g.viewCode));assert.equal(a.s.listTeams(a.admin).teams.length,1);
 a.s.db.exec('DROP TRIGGER fail_dissolve');a.s.lifecycle.dissolve(a.team,a.admin,a.dissolve);
 rejected(()=>a.s.lifecycle.dissolve(a.team,a.admin,{...a.dissolve,name:'其他'}),'operation_conflict');
 a.later(30);assert.equal(a.s.lifecycle.list(a.admin).teams.length,0);rejected(()=>a.s.lifecycle.restore(a.team,a.admin,a.restore),'not_found');
});

test('daily maintenance purges expired contents while retaining deletion and operation fences; old imports cannot resurrect a match',async t=>{
 const {maintain}=await import('../maintenance.mjs'),a=setup(t);
 const body=op(fixture(true)),created=a.s.games.create(a.team,a.admin,body);
 a.s.games.deleteOrRestore(a.team,created.id,a.admin,op({deleted:true,baseRevision:1,writerEpoch:1}));
 a.later(30);assert.equal(maintain(a.s).purgedGames,1);
 const tombstone=a.s.db.prepare('SELECT * FROM shared_games WHERE id=?').get(created.id);
 assert.equal(tombstone.payload,'{}');assert.equal(tombstone.projection,'{}');assert.equal(tombstone.purged,1);
 rejected(()=>a.s.games.create(a.team,a.admin,body),'operation_expired');
 rejected(()=>a.s.games.create(a.team,a.admin,op({...body,operationID:randomUUID()})),'duplicate_source');
 rejected(()=>a.s.games.deleteOrRestore(a.team,created.id,a.admin,op({deleted:false,baseRevision:tombstone.revision,writerEpoch:tombstone.epoch})),'restore_expired');
 const data=fixture(true),source=data.payload.id;data.payload.id=randomUUID();data.projection.gameID=data.payload.id;
 data.payload.isHome=true;data.payload.ourTeamID=data.payload.state.homeTeam.id;
 const preview=a.s.imports.preview(a.team,a.admin,{...data,sourceID:body.payload.id,sourceTeamID:data.payload.ourTeamID,playerLinks:[]});
 assert.equal(preview.status,'deleted');assert.equal(preview.candidates[0].title,'已删除并清除的比赛');
 const newPreview=a.s.imports.preview(a.team,a.admin,{...data,sourceID:source,sourceTeamID:data.payload.ourTeamID,playerLinks:[]});assert.ok(['new','similar'].includes(newPreview.status));
 a.s.lifecycle.dissolve(a.team,a.admin,a.dissolve);a.later(30);const result=maintain(a.s);assert.equal(result.expiredTeams,1);
 assert.equal(a.s.db.prepare('SELECT name FROM teams WHERE id=?').get(a.team).name,'已清除球队');
 assert.ok(a.s.db.prepare('SELECT nickname FROM members WHERE team_id=?').all(a.team).every(m=>m.nickname==='已清除成员'));
 assert.equal(a.s.lifecycle.list(a.admin).teams.length,0);
});
