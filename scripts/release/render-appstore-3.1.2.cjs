// Reproducible, local-only App Store artwork. No uploads or service requests.
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const modules=process.env.CODEX_ARTIFACT_NODE_MODULES||path.join(require('os').homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const {chromium}=require(path.join(modules,'playwright')),sharp=require(path.join(modules,'sharp'));
const root=path.resolve(__dirname,'../..'),source=path.join(root,'docs/design/marketing/3.1.2/appstore/source');
const out=path.join(root,'output/releases/3.1.2/app-store');
const items=JSON.parse(fs.readFileSync(path.join(source,'content.json')));
const sizes=[['iphone-6.9',1320,2868,'iphone'],['iphone-6.3',1206,2622,'iphone'],['iphone-6.5',1242,2688,'iphone'],['duo-outer-landscape',2034,1398,'duo-outer'],['duo-outer-portrait',1398,2034,'duo-outer'],['duo-inner-landscape',2853,2007,'duo-inner'],['duo-inner-portrait',2007,2853,'duo-inner'],['ipad-12.9-landscape',2732,2048,'ipad'],['ipad-12.9-portrait',2048,2732,'ipad'],['ipad-13-landscape',2752,2064,'ipad'],['ipad-13-portrait',2064,2752,'ipad']];
const dataURL=f=>'data:image/'+(f.endsWith('.webp')?'webp':'png')+';base64,'+fs.readFileSync(path.join(source,f)).toString('base64');
const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
function html(item,w,h,index,total){
 const landscape=w>h, phone=item.devices.includes('iphone');
 const cw=landscape?1440:1080,ch=h/w*cw;
 const top=phone?600:510,bottom=100;
 const visual=landscape?`left:540px;right:62px;top:100px;bottom:110px`:`left:70px;right:70px;top:${top}px;bottom:${bottom}px`;
 return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>
 *{box-sizing:border-box}html,body{margin:0;width:${w}px;height:${h}px;overflow:hidden}body{font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif;-webkit-font-smoothing:antialiased;background:#092a2a;color:#fff}.canvas{position:relative;width:${cw}px;height:${ch}px;transform:scale(${w/cw});transform-origin:top left;background:radial-gradient(ellipse at 70% 75%,#335f4f66,transparent 65%),#092a2a;overflow:hidden}
 .brand{position:absolute;top:48px;left:62px;display:flex;align-items:center;gap:14px}.brand img{width:53px;height:53px;border-radius:13px}.mark{font-family:Arial,sans-serif;font-size:24px;font-weight:800;letter-spacing:-.045em;line-height:1}.mark span{font-weight:400}.mark small{display:block;font-family:-apple-system,'PingFang SC',sans-serif;font-size:15px;font-weight:450;letter-spacing:.2em;margin-top:8px;color:#c6d5cc}
 .tag{position:absolute;top:${landscape?190:158}px;left:65px;font-size:23px;letter-spacing:3px;color:#b2c9ae}h1{position:absolute;left:60px;top:${landscape?237:208}px;font-size:${landscape?64:phone?87:77}px;line-height:1.2;letter-spacing:-3px;font-weight:600;margin:0}h1 span{display:block;color:#d7edb7}.detail{position:absolute;left:65px;top:${landscape?415:phone?454:410}px;right:${landscape?'calc(100% - 510px)':'65px'};font-size:${landscape?25:27}px;line-height:1.6;color:#bdd1c4;margin:0}.visual{position:absolute;${visual};display:flex;align-items:center;justify-content:center}.visual img{display:block;max-width:100%;max-height:100%;width:auto;height:auto;object-fit:contain;border:7px solid #23463e;border-radius:${phone?48:24}px;box-shadow:0 20px 55px #00191288}.foot{position:absolute;bottom:35px;left:65px;right:65px;font-size:18px;color:#a9c2b0;display:flex;justify-content:space-between;gap:20px}.count{position:absolute;right:64px;top:62px;font-size:20px;color:#9bbb9f;letter-spacing:2px}
 </style><div class="canvas"><div class="brand"><img src="${dataURL('app-icon.png')}"><div class="mark">BASEBALL<span>MASTER</span><small>棒球比赛记录大师</small></div></div><div class="count">${String(index+1).padStart(2,'0')} / ${String(total).padStart(2,'0')}</div><div class="tag">${item.name}</div><h1>${item.line1}<span>${item.line2}</span></h1><p class="detail">${item.detail}</p><div class="visual"><img src="${dataURL(item.file)}" alt="实际模拟器界面"></div><div class="foot"><span>实际 App 界面 · 合成演示资料</span><span>${item.devices[0]==='ipad'?'iPad':item.devices[0].startsWith('duo')?'iPhone Duo':'iPhone'}</span></div></div></html>`;
}
(async()=>{
 fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
 const manifest=[],checks=[];
 try{for(const [dir,w,h,device] of sizes){
  const selected=items.filter(i=>i.devices.includes(device)),folder=path.join(out,dir);fs.mkdirSync(folder,{recursive:true});
  const p=await browser.newPage({viewport:{width:w,height:h},deviceScaleFactor:1});
  for(const [n,item] of selected.entries()){
   if(hash(path.join(source,item.file))!==(item.assetSHA256||item.sourceSHA256))throw Error('Source changed '+item.id);
   const stem=String(n+1).padStart(2,'0')+'-'+item.name,doc=path.join(folder,stem+'.html');fs.writeFileSync(doc,html(item,w,h,n,selected.length));
   await p.goto('file://'+doc);await p.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});
   const c=await p.evaluate(()=>({images:[...document.images].every(i=>i.complete&&i.naturalWidth),boxes:[...document.querySelectorAll('.brand,.tag,h1,.detail,.foot,.count,.visual')].map(e=>{let r=e.getBoundingClientRect();return {selector:e.className||e.tagName,x:r.x,y:r.y,right:r.right,bottom:r.bottom,overflow:e.scrollWidth>e.clientWidth+2}})}));
   for(const box of c.boxes)if(box.x<0||box.y<0||box.right>w+1||box.bottom>h+1||box.overflow)throw Error(JSON.stringify({dir,item:item.id,box}));
   const headline=c.boxes.find(b=>b.selector==='H1'),detail=c.boxes.find(b=>b.selector==='detail'),visual=c.boxes.find(b=>b.selector==='visual');
   if(headline.bottom>detail.y||(!(w>h)&&detail.bottom>visual.y))throw Error('Text overlap '+item.id+' '+dir);
   const f=path.join(folder,stem+'.png');await p.screenshot({path:f});const m=await sharp(f).metadata();if(m.width!==w||m.height!==h||m.hasAlpha)throw Error('Invalid PNG '+f);
   manifest.push({file:dir+'/'+stem+'.png',width:w,height:h,device,source:item.file,sourceSHA256:hash(path.join(source,item.file)),originalSource:item.original,originalSourceSHA256:item.sourceSHA256,provenance:item.provenance,sha256:hash(f)});checks.push({file:dir+'/'+stem+'.png',...c});
  }await p.close();
 }
 const groups=sizes.filter(s=>['iphone-6.9','duo-inner-landscape','duo-outer-portrait','ipad-13-portrait'].includes(s[0]));
 const gallery=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>3.1.2 商店素材</title><style>body{margin:0;padding:40px;background:#e8eee8;color:#092a2a;font:18px -apple-system,'PingFang SC',sans-serif}h1{font-size:36px}h2{margin-top:40px}.row{display:flex;align-items:start;gap:20px}figure{margin:0;width:220px}img{width:100%;border-radius:10px}figcaption{font-size:15px;margin:10px 0}a{color:inherit}</style><h1>棒球比赛记录大师 · 3.1.2</h1><p>商店图片评审稿 · 11 组尺寸 · 实际模拟器界面 · 未上传</p>${groups.map(([dir])=>`<h2>${dir}</h2><div class="row">${manifest.filter(m=>m.file.startsWith(dir+'/')).map(m=>`<figure><a href="${m.file}"><img src="${m.file}"></a><figcaption>${m.file.split('/')[1]}</figcaption></figure>`).join('')}</div>`).join('')}`;
 fs.writeFileSync(path.join(out,'index.html'),gallery);const page=await browser.newPage({viewport:{width:1500,height:900}});await page.goto('file://'+path.join(out,'index.html'));await page.evaluate(async()=>Promise.all([...document.images].map(i=>i.decode())));await page.screenshot({path:path.join(out,'overview.png'),fullPage:true});await page.close();
 fs.writeFileSync(path.join(out,'manifest.json'),JSON.stringify({version:'3.1.2',build:1,status:'local-review-not-uploaded',generated:new Date().toISOString(),files:manifest},null,2)+'\n');
 fs.writeFileSync(path.join(out,'layout-checks.json'),JSON.stringify(checks,null,2)+'\n');console.log(JSON.stringify({images:manifest.length,sets:sizes.length,output:out}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
