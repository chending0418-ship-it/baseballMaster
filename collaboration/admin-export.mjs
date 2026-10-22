import { DatabaseSync } from 'node:sqlite';
import { deflateRawSync } from 'node:zlib';
import { createHash } from 'node:crypto';

const crcTable=Array.from({length:256},(_,n)=>{for(let i=0;i<8;i++)n=n&1?0xedb88320^(n>>>1):n>>>1;return n>>>0;});
const crc32=data=>{let n=0xffffffff;for(const byte of data)n=crcTable[(n^byte)&255]^(n>>>8);return (n^0xffffffff)>>>0;};
const json=data=>Buffer.from(JSON.stringify(data,null,2)+'\n');
const parse=(text,fallback={})=>{try{return JSON.parse(text);}catch{return fallback;}};
const write=(res,buffer)=>new Promise((resolve,reject)=>res.write(buffer,error=>error?reject(error):resolve()));

// A separate read-only transaction keeps the archive consistent while app writes continue.
// ZIP entries stream individually; no complete team archive is buffered in process memory.
export async function exportTeam(database,teamID,res,now=Date.now()) {
  const db=new DatabaseSync(database,{readOnly:true});
  db.exec('PRAGMA query_only=ON; PRAGMA busy_timeout=5000; BEGIN');
  try {
    const teams=db.prepare(`SELECT t.id,t.name,t.created_at AS createdAt,t.revision,p.profile,d.deleted_at AS dissolvedAt
      FROM teams t LEFT JOIN team_profiles p ON p.team_id=t.id LEFT JOIN team_deletions d ON d.team_id=t.id AND d.restored_at IS NULL
      ${teamID?'WHERE t.id=?':''} ORDER BY t.id`).all(...(teamID?[teamID]:[]));
    if(teamID&&!teams.length){res.writeHead(404,{'Content-Type':'application/json'});res.end(JSON.stringify({error:'球队不存在。'}));return;}
    const count=db.prepare(`SELECT COUNT(*) AS n,COALESCE(SUM(length(payload)+length(projection)),0) AS bytes FROM shared_games WHERE purged=0 ${teamID?'AND team_id=?':''}`).get(...(teamID?[teamID]:[]));
    if(count.n+teams.length*2>60000||count.bytes>2*1024**3){res.writeHead(413,{'Content-Type':'application/json'});res.end(JSON.stringify({error:'资料量较大，请分别下载球队或单场比赛。'}));return;}
    const files=[],directory=[];let offset=0;
    res.writeHead(200,{'Content-Type':'application/zip','Content-Disposition':`attachment; filename="BaseballMaster-${teamID?'Team-'+teamID.slice(0,8):'All-Cloud'}-${new Date(now).toISOString().slice(0,10)}.zip"`});
    async function entry(name,data) {
      if(Date.now()-start>120000)throw new Error('Export timed out');
      const bytes=deflateRawSync(data),filename=Buffer.from(name),crc=crc32(data);
      const local=Buffer.alloc(30);local.writeUInt32LE(0x04034b50);local.writeUInt16LE(20,4);local.writeUInt16LE(0x800,6);local.writeUInt16LE(8,8);
      local.writeUInt16LE(33,12);local.writeUInt32LE(crc,14);local.writeUInt32LE(bytes.length,18);local.writeUInt32LE(data.length,22);local.writeUInt16LE(filename.length,26);
      const central=Buffer.alloc(46);central.writeUInt32LE(0x02014b50);central.writeUInt16LE(20,4);central.writeUInt16LE(20,6);central.writeUInt16LE(0x800,8);central.writeUInt16LE(8,10);
      central.writeUInt16LE(33,14);central.writeUInt32LE(crc,16);central.writeUInt32LE(bytes.length,20);central.writeUInt32LE(data.length,24);central.writeUInt16LE(filename.length,28);central.writeUInt32LE(offset,42);
      directory.push(Buffer.concat([central,filename]));offset+=local.length+filename.length+bytes.length;
      if(offset>0xffffffff)throw new Error('Archive exceeds ZIP32 size');
      await write(res,local);await write(res,filename);await write(res,bytes);
      files.push({path:name,bytes:data.length,sha256:createHash('sha256').update(data).digest('hex')});
    }
    const start=Date.now();
    await entry('README.txt',Buffer.from('BaseballMaster 云端资料导出\n\n包含球队资料、球员姓名和背号、成员称呼与角色、云端比赛完整正文和展示统计。\n已删除但尚在保留期的比赛带 deleted 标记；已清理比赛只保留目录记录。\n不含登录密码、设备凭证、管理恢复码、邀请秘密和观看授权。\n临时直播请在后台直播内容页面单独下载。\n这是供查看和自行保存的 ZIP，不是 App 的 .bmbackup 或球队档案导入文件。\n文件未加密，请妥善保管。\n'));
    const catalog=[];
    for(const row of teams){
      const {profile,...team}=row,prefix=teamID?'':'teams/'+team.id+'/';
      const members=db.prepare('SELECT id,nickname,role,active FROM members WHERE team_id=? ORDER BY id').all(team.id);
      const rows=db.prepare('SELECT id,revision,deleted,purged,updated_at FROM shared_games WHERE team_id=? ORDER BY id').all(team.id);
      await entry(prefix+'team.json',json({...team,...parse(profile)}));
      await entry(prefix+'members.json',json(members));
      for(const item of rows){
        if(item.purged)continue;
        const game=db.prepare('SELECT payload,projection FROM shared_games WHERE id=? AND team_id=?').get(item.id,team.id);
        await entry(prefix+'matches/'+item.id+'.json',json({id:item.id,revision:item.revision,deleted:Boolean(item.deleted),updatedAt:item.updated_at,
          payload:parse(game.payload),projection:parse(game.projection)}));
      }
      catalog.push({teamID:team.id,games:rows});
    }
    await entry('manifest.json',json({format:'BaseballMaster Admin Export',version:1,exportedAt:now,scope:teamID?'team':'all-cloud',
      ...(teamID?{teamID,games:catalog[0].games}:{teams:catalog}),files:[...files]}));
    const centralOffset=offset;for(const data of directory){await write(res,data);offset+=data.length;}
    const end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50);end.writeUInt16LE(directory.length,8);end.writeUInt16LE(directory.length,10);end.writeUInt32LE(offset-centralOffset,12);end.writeUInt32LE(centralOffset,16);
    await write(res,end);res.end();
  }finally{db.exec('ROLLBACK');db.close();}
}
