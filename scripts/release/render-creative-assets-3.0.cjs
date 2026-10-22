'use strict';
// App Store creative placements; original App UI and approved brand assets only.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../..');
const modules = process.env.CODEX_ARTIFACT_NODE_MODULES || path.join(process.env.HOME, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const {chromium} = require(path.join(modules,'playwright')), sharp = require(path.join(modules,'sharp'));
const out = path.join(root,'output/marketing/3.0/app-store-creative'), qa = path.join(root,'output/validation/3.0/creative-assets');
const sourceFiles = {icon:'BaseballMaster/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png', scoring:'output/releases/3.0/app-store/raw/01.png',team:'output/releases/3.0/app-store/raw/02.png'};
// Bounds inset from the green ART SAFE AREA in Apple's downloaded PSD composites.
const variants = [
 {id:'header-5244x2950',placement:'header',w:5244,h:2950,safe:[1922,661,1400,960],title:'标题 · 通用比例'},
 {id:'header-3840x1646',placement:'header',w:3840,h:1646,safe:[1098,494,1644,658],title:'标题 · 宽幅'},
 {id:'search-5244x2950',placement:'search',w:5244,h:2950,safe:[1922,661,1400,960],title:'搜索结果 · 通用比例'},
 {id:'search-3840x2560',placement:'search',w:3840,h:2560,safe:[837,766,2166,1028],title:'搜索结果 · 标准比例'},
 {id:'search-1920x1280',placement:'search',w:1920,h:1280,safe:[418.5,383,1083,514],title:'搜索结果 · 标准比例小文件'}
];
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
function art(v) {
 const [sx,sy,sw,sh]=v.safe, universal=v.w===5244, isHeader=v.placement==='header';
 const u=sw/1400, inset=30*u, phoneH=Math.min(sh-100*u,840*u), phoneW=phoneH*1206/2622;
 const phoneX=sw-inset-phoneW,phoneY=(sh-phoneH)/2;
 const brandY=isHeader?(universal?90:66)*u:(universal?75:70)*u;
 const brandIcon=94*u, brandWord=38*u, brandSub=25*u;
 const titleY=brandY+brandIcon+65*u;
 const titleSize=(isHeader?(universal?99:94):135)*u;
 const titleLine=isHeader?1.24:1.12;
 const descY=titleY+titleSize*titleLine*2+34*u;
 const copyW=phoneX-70*u;
 const headline=isHeader?'同一支球队，<br><span>一起记，一起看。</span>':'棒球记分<br><span>球队共享</span>';
 const sub=isHeader?'每一场比赛，都是球队的记忆。':'逐球记录 · 年度统计';
 const icon='../images/icon.png',shot=`../images/${isHeader?'team':'scoring'}.png`;
 // The universal layout has a higher focal area because Store metadata may cover the bottom.
 const fieldX=v.w*.64,fieldY=v.h*1.19,fieldR=v.w*.70;
 return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>BaseballMaster 3.0 ${v.title}</title><style>
 *{box-sizing:border-box}html,body{margin:0;width:${v.w}px;height:${v.h}px;overflow:hidden}body{background:#092a2a;color:#fff;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif;-webkit-font-smoothing:antialiased}
 .canvas{position:relative;width:100%;height:100%;isolation:isolate;background:radial-gradient(ellipse at 72% 25%,#255b4760,transparent 57%),linear-gradient(115deg,#061f24 0%,#092a2a 37%,#164639 100%)}
 .field{position:absolute;inset:0;width:100%;height:100%;opacity:.6}.wash{position:absolute;inset:0;background:radial-gradient(ellipse at 49% 43%,#092a2a66 8%,transparent 54%),linear-gradient(90deg,#041d2355,transparent 52%)}
 .scene{position:absolute;left:${sx}px;top:${sy}px;width:${sw}px;height:${sh}px}
 .brand{position:absolute;left:${inset}px;top:${brandY}px;display:flex;align-items:center;gap:${brandIcon*12/42}px}
 .brand img{width:${brandIcon}px;height:${brandIcon}px;border-radius:${brandIcon*10/42}px}.word{font-family:Arial,sans-serif;font-size:${brandWord}px;font-weight:800;line-height:1;letter-spacing:-.045em}.word span{font-weight:400}.word small{display:block;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif;font-size:${brandSub}px;font-weight:450;letter-spacing:.2em;margin-top:${brandSub*5/11}px;color:#c6d5cc}
 h1{position:absolute;left:${inset-4*u}px;top:${titleY}px;margin:0;width:${copyW}px;font-size:${titleSize}px;font-weight:650;line-height:${titleLine};letter-spacing:-.045em;white-space:nowrap}h1 span{color:#cde6b6}
 .description{position:absolute;left:${inset}px;top:${descY}px;font-size:${(isHeader?30:35)*u}px;font-weight:400;color:#cadbd0;margin:0;line-height:1.5;letter-spacing:.05em;white-space:nowrap}
 .screen{position:absolute;left:${phoneX}px;top:${phoneY}px;width:${phoneW}px;height:${phoneH}px;border-radius:${phoneW*.074}px;box-shadow:0 ${32*u}px ${70*u}px #001412aa,0 0 0 ${2*u}px #aecfbb66,0 0 0 ${8*u}px #122b2b;overflow:hidden;background:#101923}
 .screen img{display:block;width:100%;height:100%;object-fit:contain}
 .rule{position:absolute;left:${inset}px;top:${descY+82*u}px;width:${90*u}px;height:${3*u}px;border-radius:99px;background:#89b7a399}
 </style><main class="canvas">
 <svg class="field" viewBox="0 0 ${v.w} ${v.h}" xmlns="http://www.w3.org/2000/svg" aria-hidden="true"><defs><linearGradient id="grass" x1="0" y1="1" x2="1" y2="0"><stop stop-color="#447b55" stop-opacity=".48"/><stop offset="1" stop-color="#29664f" stop-opacity=".1"/></linearGradient><linearGradient id="clay" x1="0" y1="1" x2="1" y2="0"><stop stop-color="#bda777" stop-opacity=".16"/><stop offset="1" stop-color="#bda777" stop-opacity=".04"/></linearGradient></defs>
 <g transform="translate(${fieldX} ${fieldY}) rotate(15)"><path d="M0 0 L${-fieldR*.707} ${-fieldR*.707} A${fieldR} ${fieldR} 0 0 1 ${fieldR*.707} ${-fieldR*.707}Z" fill="url(#grass)" stroke="#9cc1a633" stroke-width="${v.w*.001}"/>
 ${[.68,.84,.99].map(r=>`<path d="M${-fieldR*r*.707} ${-fieldR*r*.707} A${fieldR*r} ${fieldR*r} 0 0 1 ${fieldR*r*.707} ${-fieldR*r*.707}" fill="none" stroke="#a9cfb31c" stroke-width="${v.w*.0005}"/>`).join('')}
 <path d="M0 0 L${-fieldR*.707} ${-fieldR*.707} M0 0 L${fieldR*.707} ${-fieldR*.707}" stroke="#e4efd044" stroke-width="${v.w*.0013}" fill="none"/>
 <path d="M0 0 L${-fieldR*.23} ${-fieldR*.23} L0 ${-fieldR*.46} L${fieldR*.23} ${-fieldR*.23}Z" fill="url(#clay)" stroke="#d3d8ac32" stroke-width="${v.w*.0013}"/>
 <path d="M0 ${-fieldR*.028} L${-fieldR*.205} ${-fieldR*.233} L0 ${-fieldR*.438} L${fieldR*.205} ${-fieldR*.233}Z" fill="#15543d55"/>
 ${[[-.23,-.23],[0,-.46],[.23,-.23]].map(([x,y])=>`<rect x="${fieldR*x-10*u}" y="${fieldR*y-10*u}" width="${20*u}" height="${20*u}" fill="#e9ead180" transform="rotate(45 ${fieldR*x} ${fieldR*y})"/>`).join('')}
 <circle cx="0" cy="${-fieldR*.23}" r="${fieldR*.027}" fill="#d3cc8c18" stroke="#e7dfab30" stroke-width="${v.w*.0008}"/></g></svg>
 <div class="wash"></div><section class="scene"><div class="brand critical"><img src="${icon}" alt="BaseballMaster 图标"><div class="word">BASEBALL<span>MASTER</span><small>棒球比赛记录大师</small></div></div><h1 class="critical">${headline}</h1><p class="description critical">${sub}</p><div class="rule"></div><div class="screen critical"><img src="${shot}" alt="${isHeader?'球队共享':'现场记分'}实际界面"></div></section></main></html>`;
}
async function main(){
 for(const d of ['upload/header','upload/search','source/html','source/images','preview'])fs.mkdirSync(path.join(out,d),{recursive:true});
 const sources={};for(const [key,relative]of Object.entries(sourceFiles)){const bytes=fs.readFileSync(path.join(root,relative));sources[relative]=sha(bytes);fs.writeFileSync(path.join(out,'source/images',key+'.png'),bytes);}
 const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});const files=[];
 try{
 for(const v of variants){
  const source=path.join(out,'source/html',v.id+'.html');fs.writeFileSync(source,art(v));
  const page=await browser.newPage({viewport:{width:v.w,height:v.h},deviceScaleFactor:1});await page.goto('file://'+source);await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});
  const layout=await page.evaluate(()=>({images:[...document.images].every(i=>i.complete&&i.naturalWidth>0),bounds:[...document.querySelectorAll('.critical')].map(e=>{const r=e.getBoundingClientRect();return {type:e.className,x:r.x,y:r.y,w:r.width,h:r.height,right:r.right,bottom:r.bottom}})}));
  assert.ok(layout.images);const [x,y,w,h]=v.safe;for(const b of layout.bounds)assert.ok(b.x>=x&&b.y>=y&&b.right<=x+w&&b.bottom<=y+h,`${v.id}: outside safe area ${JSON.stringify(b)}`);
  const text=layout.bounds.find(b=>b.type.startsWith('critical')||b.type.startsWith('description'));
  const png=await page.screenshot({type:'png'}),file=`upload/${v.placement}/${v.id}.png`,dest=path.join(out,file);
  await sharp(png).removeAlpha().withIccProfile('srgb').png({compressionLevel:9}).toFile(dest);
  const meta=await sharp(dest).metadata();assert.equal(meta.width,v.w);assert.equal(meta.height,v.h);assert.equal(meta.hasAlpha,false);assert.equal(meta.space,'srgb');assert.equal(meta.depth,'uchar');
  await sharp(dest).resize({width:1440}).png().toFile(path.join(out,'preview',v.id+'.png'));
  await sharp(dest).extract({left:Math.ceil(x),top:Math.ceil(y),width:Math.floor(w),height:Math.floor(h)}).resize({width:1200}).png().toFile(path.join(out,'preview',v.id+'-safe.png'));
  const overlay=Buffer.from(`<svg width="${v.w}" height="${v.h}"><path d="M0 0H${v.w}V${v.h}H0Z M${x} ${y}V${y+h}H${x+w}V${y}Z" fill="black" fill-opacity=".5" fill-rule="evenodd"/><rect x="${x}" y="${y}" width="${w}" height="${h}" fill="none" stroke="#e5d087" stroke-width="8"/></svg>`);
  const safeOverlay=await sharp(dest).composite([{input:overlay}]).png().toBuffer();
  await sharp(safeOverlay).resize({width:1440}).png().toFile(path.join(qa,v.id+'-safe-overlay.png'));
  files.push({...v,file,bytes:fs.statSync(dest).size,sha256:sha(fs.readFileSync(dest)),alpha:meta.hasAlpha,colorSpace:meta.space,icc:!!meta.icc,layout});await page.close();console.log('Rendered '+file);
 }
 const gallery=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>3.0 App Store 创意素材</title><style>*{box-sizing:border-box}body{margin:0;padding:42px;background:#e9eee6;color:#092a2a;font-family:-apple-system,'PingFang SC',sans-serif}h1{font-size:30px;margin:0 0 12px}p{color:#456359;font-size:16px;line-height:1.8}section{display:grid;grid-template-columns:1fr 1fr;gap:24px;margin-top:24px}article{background:#fff;border:1px solid #d2ddd2;border-radius:15px;overflow:hidden}article>img{display:block;width:100%}.detail{padding:20px}h2{font-size:19px;margin:0 0 8px}.crop{width:100%;border-radius:8px}a{color:#1c7455}.note{font-size:13px;margin:8px 0 0}@media(max-width:850px){section{grid-template-columns:1fr}}</style><h1>BaseballMaster 3.0 · App Store 创意素材</h1><p>标题图 2 张 · 搜索结果图 3 张 · 简体中文 · 无透明通道 sRGB PNG<br>先看完整画布，再看模板安全区内的主体。此页仅供预览，上传使用各图的原尺寸 PNG。</p><section>${variants.map(v=>`<article><img src="preview/${v.id}.png" alt="${v.title}"><div class="detail"><h2>${v.title} · ${v.w} × ${v.h}</h2><a href="upload/${v.placement}/${v.id}.png">打开原尺寸 PNG</a><p class="note">安全区主体预览（模拟裁切，非商店实际页面）</p><img class="crop" src="preview/${v.id}-safe.png" alt="安全区主体"></div></article>`).join('')}</section></html>`;
 fs.writeFileSync(path.join(out,'index.html'),gallery);
 const p=await browser.newPage({viewport:{width:1600,height:1000},deviceScaleFactor:1});await p.goto('file://'+path.join(out,'index.html'));await p.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});await p.screenshot({path:path.join(out,'overview.png'),fullPage:true});
 fs.writeFileSync(path.join(out,'manifest.json'),JSON.stringify({created:'2026-10-13',appVersion:'3.0',locale:'zh-Hans',purpose:'App Store product page header and search results creative assets',specURL:'https://developer.apple.com/help/app-store-connect/reference/app-information/creative-assets-specifications',safeAreaSource:'Apple Photoshop templates downloaded 2026-10-13; green ART SAFE AREA bounds inset one pixel; 1920 search uses half scale',sourceHashes:sources,files},null,2));
 }finally{await browser.close();}
}
main().catch(e=>{console.error(e);process.exitCode=1});
