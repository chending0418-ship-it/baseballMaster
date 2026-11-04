// Synthetic loopback-only server for native cache/network integration checks.
import {adminFixture} from '../../collaboration/test/admin-fixture.mjs';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {defaultLaunchConfig} from '../../collaboration/launch-screen.mjs';
const app=await adminFixture();
const image=app.store.launchScreen.upload(readFileSync(new URL('../../collaboration/test/fixtures/launch-background.png',import.meta.url)),'image/png');
const {schemaVersion,updatedAt,...config}=defaultLaunchConfig();
app.store.launchScreen.save({...config,message:'本地联调 背景与文案已更新',displayDurationMs:2000,backgroundImage:image});
const output=new URL('../../output/validation/3.1.1/configurable-launch/',import.meta.url);mkdirSync(output,{recursive:true});
writeFileSync(new URL('local-server.json',output),JSON.stringify({origin:app.origin,configurationURL:app.origin+'/api/v3/app/launch-screen'},null,2)+'\n');
console.log('Synthetic launch server: '+app.origin);
let stopping=false;async function stop(){if(stopping)return;stopping=true;await app.close();process.exit(0);}process.on('SIGINT',stop);process.on('SIGTERM',stop);
