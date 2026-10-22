import test from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import {CollaborationStore} from '../server.mjs';
import {fixture} from './match-fixture.mjs';
import {maintain} from '../maintenance.mjs';
const secret=()=>randomBytes(32).toString('base64url'),op=b=>({operationID:randomUUID(),...b});
test('portable archive exports complete formal records and tombstones without granting access',t=>{
 let now=Date.now();const s=new CollaborationStore({now:()=>now});t.after(()=>s.close());
 const credential=secret(),admin=s.register(credential,{label:'iPhone'}).deviceID,recovery=secret();
 const team=s.createTeam(admin,op({name:'海浪',nickname:'教练',recoveryToken:recovery})).teamID;
 const body=fixture(true),a=s.games.create(team,admin,op(body)),b=s.games.create(team,admin,op(fixture(true)));
 s.games.access(team,a.id,admin,op({mode:'public',baseAccessVersion:1}));
 s.games.deleteOrRestore(team,b.id,admin,op({deleted:true,baseRevision:1,writerEpoch:1}));
 const manifest=s.archives.manifest(team,admin);assert.equal(manifest.games.length,2);
 const item=s.archives.item(team,a.id,admin,manifest.snapshot);assert.deepEqual(item.payload,body.payload);assert.deepEqual(item.projection,JSON.parse(s.db.prepare('SELECT projection FROM shared_games WHERE id=?').get(a.id).projection));
 const all=JSON.stringify([manifest,item]);for(const privateValue of [credential,recovery,a.viewCode])assert.equal(all.includes(privateValue),false);
 assert.equal('writerDeviceID' in item,false);assert.equal('accessMode' in item,false);
 assert.equal(s.archives.item(team,b.id,admin,manifest.snapshot).deleted,true);
 assert.equal(s.archives.finish(team,admin,op({snapshot:manifest.snapshot})).verified,true);
 now+=31*86400000;maintain(s);const after=s.archives.manifest(team,admin),purged=s.archives.item(team,b.id,admin,after.snapshot);
 assert.equal(purged.purged,true);assert.equal(purged.payload,null);assert.deepEqual(purged.playerLinks,[]);
});
test('archive export is admin-only and rejects mixed revisions, removal, wrong team and old snapshots',t=>{
 const s=new CollaborationStore();t.after(()=>s.close());const admin=s.register(secret(),{label:'iPhone'}).deviceID,team=s.createTeam(admin,op({name:'海浪',nickname:'教练'})).teamID;
 const body=fixture(),game=s.games.create(team,admin,op(body)),start=s.archives.manifest(team,admin);
 for(const role of ['scorer','member']) {const device=s.register(secret(),{label:role}).deviceID,token=secret();s.invite(team,admin,op({role,token}));s.redeem(device,op({token,nickname:role}));assert.throws(()=>s.archives.manifest(team,device),e=>e.code==='forbidden');assert.throws(()=>s.archives.item(team,game.id,device,start.snapshot));}
 s.games.update(team,game.id,admin,op({...body,baseRevision:1,writerEpoch:1}));
 assert.throws(()=>s.archives.item(team,game.id,admin,start.snapshot),e=>e.code==='archive_changed');
 assert.throws(()=>s.archives.finish(team,admin,op({snapshot:start.snapshot})),e=>e.code==='archive_changed');
 const other=s.createTeam(admin,op({name:'飞鹰',nickname:'教练'})).teamID;
 assert.throws(()=>s.archives.item(other,game.id,admin,s.archives.manifest(other,admin).snapshot),e=>e.code==='not_found');
});
