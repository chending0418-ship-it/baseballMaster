// Native layouts with unmodified official device bezels and native screenshots.
// Main posters show the full device family together, as explicitly requested by the user.
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const root=path.resolve(__dirname,'../../../../..'),assets=path.join(__dirname,'assets');
const modules=process.env.CODEX_ARTIFACT_NODE_MODULES||path.join(require('os').homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const {chromium}=require(path.join(modules,'playwright')),sharp=require(path.join(modules,'sharp'));
const androidOnly=process.argv.includes('--android-only');
const appleFile=path.join(assets,'hardware/apple-provenance.json');
const models={
 'android-phone':{id:'android-phone',file:'android-phone/back.webp',foreground:'android-phone/mask.webp',width:1200,height:2541,screen:{x:59,y:58,width:1080,height:2400}},
 'android-open':{id:'android-open',file:'android-open/back.webp',foreground:'android-open/mask.webp',width:2204,height:2274,screen:{x:62,y:58,width:2076,height:2152}},
 'android-closed':{id:'android-closed',file:'android-closed/back.webp',foreground:'android-closed/mask.webp',width:1236,height:2554,screen:{x:84,y:66,width:1080,height:2364}}
};
if(!androidOnly){if(!fs.existsSync(appleFile))throw Error('Official Apple bezels must be extracted after license acceptance first');const p=JSON.parse(fs.readFileSync(appleFile));for(const m of p.devices)models[m.id]=m;}
const base64=f=>{const ext=path.extname(f).slice(1);return `data:image/${ext==='jpg'?'jpeg':ext};base64,`+fs.readFileSync(f).toString('base64');};
const image=f=>base64(path.join(assets,'screens',f));
const icon=base64(path.join(root,'BaseballMaster/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png'));
const brand=`<div class="brand"><img src="${icon}"><div>BASEBALL<span>MASTER</span><small>棒球比赛记录大师</small></div></div>`;
function device(id,screen,box,label){
 const m=models[id];if(!m)throw Error('Missing genuine device model '+id);
 const scale=Math.min(box.w/m.width,box.h/m.height),w=m.width*scale,h=m.height*scale;
 const x=box.x+(box.w-w)/2,y=box.y+(box.h-h)/2,s=m.screen;
 const screenMask=s.mask?base64(path.join(assets,'hardware',s.mask)):null;
 const frame=base64(path.join(assets,'hardware',m.file));
 const fore=m.foreground?base64(path.join(assets,'hardware',m.foreground)):null;
 return `<figure class="device" data-model="${id}" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px" aria-label="${label}"><img class="shell" src="${frame}"><div class="display" style="left:${s.x*scale}px;top:${s.y*scale}px;width:${s.width*scale}px;height:${s.height*scale}px;${screenMask?`mask-image:url(${screenMask});mask-size:100% 100%;mask-repeat:no-repeat;`:''}${s.radius?`border-radius:${s.radius*scale}px`:''}"><img class="app" src="${image(screen)}">${fore?`<img class="foreground" src="${fore}">`:''}</div>${m.frameOnTop?`<img class="top-shell" src="${frame}">`:''}</figure>`;
}
function allDevices(wechat=false){
 const dy=wechat?5:0;
 const caption=(text,cx,y,w)=>`<div class="device-caption" style="left:${cx-w/2}px;top:${y+dy}px;width:${w}px">${text}</div>`;
 return device('ipad-landscape','ipad-landscape.png',{x:250,y:535+dy,w:580,h:445},'iPad Pro 原生工作台')+
 device('iphone-portrait','ios-field.png',{x:42.5,y:680+dy,w:145,h:300},'iPhone 18 Pro Max 现场记分')+
 device('duo-closed','duo-closed.png',{x:892.5,y:778+dy,w:145,h:202},'iPhone Duo 闭合外屏')+
 caption('iPhone',115,994,210)+caption('iPad',540,994,580)+caption('Duo 外屏',965,994,210)+
 device('duo-open','duo-open.png',{x:70,y:1125+dy,w:280,h:205},'iPhone Duo 展开内屏')+
 device('android-phone','android-phone-live.png',{x:475,y:1055+dy,w:130,h:275},'Android Pixel 7 手机现场记分')+
 device('android-open','android-open-live.png',{x:730,y:1040+dy,w:280,h:290},'Android Pixel 10 Pro Fold 展开工作台')+
 caption('Duo 展开',210,1350,300)+caption('Android 手机',540,1350,250)+caption('Android 折叠屏',870,1350,300);
}

const css=`*{box-sizing:border-box}html,body{margin:0;width:1080px;overflow:hidden}body{font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif;-webkit-font-smoothing:antialiased}.canvas{position:relative;width:1080px;height:1440px;overflow:hidden;color:#eff5e9;background:radial-gradient(ellipse at 50% 85%,#2c5e4338,transparent 65%),#082d28}.light{color:#143b2d;background:radial-gradient(ellipse at 80% 85%,#d9e6c355,transparent 60%),#f4f5ef}.brand{position:absolute;left:50%;top:49px;transform:translateX(-50%);display:flex;gap:12px;align-items:center;white-space:nowrap}.brand img{width:52px;height:52px;border-radius:12px}.brand>div{font:800 23px Arial;letter-spacing:-.045em;color:white}.brand span{font-weight:400}.brand small{font:450 14px -apple-system,'PingFang SC',sans-serif;letter-spacing:.2em;color:#c6d5cc;display:block;margin-top:7px}.light .brand{padding:14px 21px;background:#092a2a;border-radius:22px;top:35px}.eyebrow{position:absolute;top:160px;left:55px;right:55px;text-align:center;font-size:24px;letter-spacing:2px;color:#b3cdaa}.light .eyebrow{color:#71856b}h1{position:absolute;top:211px;left:38px;right:38px;margin:0;text-align:center;font-size:86px;line-height:1.18;font-weight:600;letter-spacing:-3.5px}h1 em{font-style:normal;color:#d7edb7}.light h1 em{color:#537947}.intro{position:absolute;top:440px;left:60px;right:60px;margin:0;text-align:center;font-size:26px;line-height:1.65;color:#b5ccbb}.light .intro{color:#6b806b}.device{position:absolute;margin:0;overflow:visible}.device img{display:block}.shell,.top-shell{position:absolute;inset:0;width:100%;height:100%;object-fit:contain}.shell{z-index:1}.display{position:absolute;z-index:2;overflow:hidden;background:#f5f2e9}.app{width:100%;height:100%;object-fit:contain}.foreground{position:absolute;inset:0;width:100%;height:100%;z-index:3;pointer-events:none}.top-shell{z-index:4;pointer-events:none}.note{position:absolute;left:70px;right:70px;bottom:112px;display:flex;justify-content:center;gap:35px;align-items:center;color:#cedfc5;font-size:25px;line-height:1.5;text-align:center}.light .note{color:#55714f}.note strong{font-weight:550}.subnote{position:absolute;left:65px;right:65px;bottom:70px;text-align:center;color:#8fae94;font-size:19px}.light .subnote{color:#82937c}.footer{position:absolute;left:60px;right:60px;bottom:30px;display:flex;justify-content:space-between;font-size:16px;color:#91ab95;letter-spacing:.5px}.light .footer{color:#81927b}.footer>span:first-child{position:absolute;left:50%;transform:translateX(-50%);white-space:nowrap}.footer>span:last-child{margin-left:auto}.device-caption{position:absolute;text-align:center;color:#c9dbbe;font-size:19px;letter-spacing:.5px}.duo-label{position:absolute;text-align:center;left:400px;right:160px;top:1185px;color:#c5d9ba;font-size:25px;line-height:1.6}.duo-label b{display:block;font-size:32px;font-weight:550;color:#dfedce;margin-bottom:10px}.wx{height:1800px}.wx h1{top:205px}.wx .intro{top:437px}.features{position:absolute;top:1210px;left:80px;right:80px;display:grid;grid-template-columns:1fr 1fr;gap:28px 40px;border-top:1px solid #8cab8d55;padding-top:34px}.features h2{font-size:28px;font-weight:550;margin:0 0 9px;color:#e2edd5}.features p{font-size:21px;color:#acc3af;line-height:1.5;margin:0}.wx .roster{position:absolute;left:80px;right:80px;top:1490px;text-align:center;color:#b6ccba;font-size:24px}.cta{position:absolute;top:1570px;left:50px;right:50px;text-align:center;font-size:25px;line-height:1.8;color:#eef3e6}.cta small{font:22px Arial;color:#9fba9e;display:block;margin-top:14px}.wx .footer{bottom:29px}.wx .features.all-family{top:1410px;gap:24px 35px;padding-top:26px}.all-family h2{font-size:25px}.all-family p{font-size:19px}.wx .cta.all-family-cta{top:1660px;font-size:24px}.all-family-cta small{margin-top:8px;font-size:21px}`;
const cards=[
 {id:'01-一场比赛多种屏幕',theme:'dark',eyebrow:'给认真记录比赛的你',title:'一场比赛<br><em>多种屏幕一样顺手</em>',intro:'iPhone · iPad · iPhone Duo · Android',body:()=>allDevices(false),note:'',footer:'实际 App 界面 · 示例数据 · Android 测试中'},
 {id:'02-iPad宽屏工作台',theme:'light',eyebrow:'iPad · 宽屏工作台',title:'球场和记录<br><em>在大屏一起展开</em>',intro:'比分 垒况与记录区域　同场查看',body:()=>device('ipad-landscape','ipad-landscape.png',{x:55,y:565,w:970,h:690},'真实 iPad Pro 横屏界面'),note:'双列工作台　　页内 Pro',footer:'实际 App 界面 · 示例数据'},
 {id:'03-Duo内外屏记录',theme:'dark',eyebrow:'iPhone Duo · 内外屏切换',title:'展开或合上<br><em>这一球接着记</em>',intro:'球型 落点与守备草稿　随屏幕变化保留',body:()=>device('duo-open','duo-pro.png',{x:62,y:540,w:956,h:540},'Duo 内屏页内Pro')+device('duo-closed','duo-closed.png',{x:150,y:1110,w:200,h:250},'Duo 外屏Pro草稿')+'<div class="duo-label"><b>同一份草稿</b>从外屏到内屏<br>继续把这一球记完整</div>',note:'',footer:'实际 App 界面 · 示例数据'},
 {id:'04-Android折叠工作台',theme:'light',eyebrow:'Android · 手机与折叠屏',title:'展开看全场<br><em>合上随手记</em>',intro:'折叠展开工作台　分局 球场与现场信息',body:()=>device('android-open','android-open-live.png',{x:75,y:540,w:930,h:810},'Pixel 10 Pro Fold 官方外壳与Android实际工作台'),note:'',footer:'Android 测试中 · 实际界面 · 示例数据'},
 {id:'05-整份名单批量输入',theme:'light',eyebrow:'本机球队 · 对手名单',title:'整份名单<br><em>一次输入更省心</em>',intro:'先预览核对　再整批确认',body:()=>device('ipad-portrait','ios-bulk.png',{x:230,y:535,w:620,h:790},'iPad 原生批量名单输入'),note:'',footer:'iPad 实际界面 · 示例数据'},
 {id:'06-回看球员击球落点',theme:'dark',eyebrow:'球员统计 · 回看已记录的比赛',title:'把每次击球<br><em>放回球场看</em>',intro:'按所选记录回看　有位置看位置 缺失不猜测',body:()=>device('android-open','android-open-statistics.png',{x:75,y:540,w:930,h:810},'Android 球员击球落点实际界面'),note:'',footer:'Android 测试中 · 实际界面 · 示例数据'}
];
function html(c,i,wechat=false){return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>${c.id}</title><style>${css}</style><main class="canvas ${c.theme} ${wechat?'wx':''}">${brand}<div class="eyebrow">${c.eyebrow}</div><h1>${c.title}</h1><p class="intro">${c.intro}</p>${c.body()}${c.note?`<div class="note">${c.note}</div>`:''}<footer class="footer"><span>${c.footer}</span><span>${wechat?'':String(i+1).padStart(2,'0')+' / 06'}</span></footer></main></html>`;}
const wx={id:'棒球比赛记录大师-微信一页图',theme:'dark',eyebrow:'棒球比赛记录大师',title:'认真记录<br><em>每一块屏幕都顺手</em>',intro:'iPhone · iPad · iPhone Duo · Android',body:()=>allDevices(true)+'<div class="features all-family"><section><h2>iPad 与折叠工作台</h2><p>展开查看球场 分局与现场信息</p></section><section><h2>屏幕变化 草稿保留</h2><p>内外屏切换 这一球接着记</p></section><section><h2>名单整批准备</h2><p>本机球队与对手 先核对再确认</p></section><section><h2>回看球员落点</h2><p>从已记录的比赛里看击球去向</p></section></div><div class="cta all-family-cta">App Store 搜索「棒球比赛记录大师」<small>baseballmaster.cc</small></div>',footer:'实际 App 界面 · 示例数据 · Android 测试中'};

(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
 try{
 for(const [channel,list,wechat] of [['xiaohongshu',androidOnly?cards.filter(c=>['04','06'].includes(c.id.slice(0,2))):cards,false],...(!androidOnly?[['wechat',[wx],true]]:[])]){
  const out=path.join(root,'output/marketing/3.1.2',channel,'2026-11-04');fs.mkdirSync(path.join(out,'source'),{recursive:true});fs.mkdirSync(path.join(out,'images'),{recursive:true});const manifest=[];
  for(const [i,c] of list.entries()){
   const h=wechat?1800:1440,doc=path.join(out,'source',c.id+'.html');fs.writeFileSync(doc,html(c,cards.indexOf(c)<0?i:cards.indexOf(c),wechat));const p=await browser.newPage({viewport:{width:1080,height:h},deviceScaleFactor:1});await p.goto('file://'+doc);await p.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});
   const check=await p.evaluate(()=>({text:document.body.innerText,headline:document.querySelector('h1').innerText,images:[...document.images].every(i=>i.complete&&i.naturalWidth),boxes:[...document.querySelectorAll('.brand,.eyebrow,h1,.intro,.footer,.device,.note,.duo-label,.features,.roster,.cta')].map(e=>{const r=e.getBoundingClientRect();return {class:e.className,x:r.x,y:r.y,right:r.right,bottom:r.bottom,overflow:e.scrollWidth>e.clientWidth+2}}),models:[...document.querySelectorAll('[data-model]')].map(e=>e.dataset.model)}));
   if(!check.images||/\b\d+\.\d+\.\d+\b/.test(check.text)||/[，。！？：；、,.!?;:]/.test(check.headline))throw Error('Image/version/headline check '+c.id);
   if(!wechat&&/https?:|baseballmaster\.cc|App Store|下载|微信号|二维码/.test(check.text))throw Error('XHS prohibited text '+c.id);
   for(const b of check.boxes)if(b.x<0||b.right>1081||b.y<0||b.bottom>h+1||b.overflow)throw Error('Bounds '+c.id+' '+JSON.stringify(b));
   if((wechat||c.id.startsWith('01'))&&check.models.length!==6)throw Error('Full device family requires six genuine hardware views');
   const centered=check.boxes.filter(b=>['brand','eyebrow','H1','intro'].includes(b.class));for(const b of centered)if(Math.abs((b.x+b.right)/2-540)>1)throw Error('Center alignment '+c.id+' '+b.class);
   if(wechat||c.id.startsWith('01')){const ds=check.boxes.filter(b=>b.class==='device');const middle=(Math.min(...ds.map(b=>b.x))+Math.max(...ds.map(b=>b.right)))/2;if(Math.abs(middle-540)>1)throw Error('Device family must be centered '+middle);}
   const png=path.join(out,'images',c.id+'.png');await p.screenshot({path:png});const m=await sharp(png).metadata();if(m.width!==1080||m.height!==h||m.hasAlpha)throw Error('PNG validation '+c.id);await sharp(png).jpeg({quality:95,mozjpeg:true}).toFile(path.join(out,'images',c.id+'.jpg'));
   manifest.push({file:'images/'+c.id+'.png',width:1080,height:h,sha256:crypto.createHash('sha256').update(fs.readFileSync(png)).digest('hex'),...check});await p.close();
  }
  fs.writeFileSync(path.join(out,androidOnly?'android-checks.json':'manifest.json'),JSON.stringify({status:'review-only-not-published',hardware:'unmodified-official-bezels',files:manifest},null,2)+'\n');
  if(!androidOnly){const thumbs=[];for(let i=0;i<manifest.length;i++)thumbs.push({input:await sharp(path.join(out,manifest[i].file)).resize(324,wechat?540:432).toBuffer(),left:i*324,top:0});await sharp({create:{width:324*manifest.length,height:wechat?540:432,channels:3,background:'#e8eee8'}}).composite(thumbs).png().toFile(path.join(out,'overview.png'));fs.copyFileSync(path.join(__dirname,'copy.md'),path.join(out,'文案与使用说明.md'));}
  console.log(channel+': '+manifest.length+' native device artwork rendered');
 }
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
