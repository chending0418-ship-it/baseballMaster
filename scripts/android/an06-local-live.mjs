// Explicit Android development test only: original service, memory DB, loopback.
import { createLiveServer } from '../../live/server.mjs';
import { createInterface } from 'node:readline';
let fixtureNow=Date.now();
const app=createLiveServer({dbPath:':memory:',now:()=>fixtureNow,createLimit:10000});
app.server.listen(0,'127.0.0.1',()=>process.stdout.write(JSON.stringify({port:app.server.address().port,now:fixtureNow})+'\n'));
const input=createInterface({input:process.stdin});
input.on('line',line=>{
  const command=JSON.parse(line);
  if(command.kind==='advance') {
    if(!Number.isSafeInteger(command.milliseconds)||command.milliseconds<0||command.milliseconds>86400000) throw new Error('Invalid fixture time');
    fixtureNow+=command.milliseconds;app.sweep();
  } else if(command.kind!=='count') throw new Error('Unknown fixture command');
  process.stdout.write(JSON.stringify({count:app.count(),now:fixtureNow})+'\n');
});
input.on('close',()=>{app.server.closeAllConnections();app.server.close();});
