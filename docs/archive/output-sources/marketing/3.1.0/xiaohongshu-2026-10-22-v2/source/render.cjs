const fs=require('fs'),path=require('path'),crypto=require('crypto');
const modules='/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const {chromium}=require(path.join(modules,'playwright')),sharp=require(path.join(modules,'sharp'));
const out=path.resolve(__dirname,'..');
const brand='<div class="brand"><img src="assets/icon.png"><div>BASEBALL<span>MASTER</span><small>棒球比赛记录大师</small></div></div>';
const phone=(name,cls)=>`<div class="phone ${cls}"><img src="assets/${name}.png"></div>`;
const css=fs.readFileSync(path.join(__dirname,'style.css'),'utf8');
const cards=[
{id:'01-把过程记在球场上',tag:'看清落点，也记下守备与传球',title:'这一球的过程<br><em>放在球场上记</em>',sub:'球型、落点与实际传球，在同一处记录。<br>最后一起核对跑者去向。',body:`<div class="coverwords"><p>击球落点</p><p>守备传球</p><p>跑者核对</p><div class="line"></div><small>把场上的经过<br>留成赛后的细节</small></div>${phone('process','coverphone')}`,note:'实际界面 · 示例比赛资料'},
{id:'02-两种方式自己选',tag:'开局自己选，也能先练习一局',title:'熟悉的记分保留<br><em>新的方式也能试</em>',sub:'基础版保留原有记分流程。<br>Pro 版把球型、落点与传球放在同一个球场。',body:`${phone('modes','leftphone')}<div class="side stats"><b>基础版</b><p>保留熟悉流程</p><b>Pro 版</b><p>球型 · 落点 · 传球</p><b>先练习一局</b><p>两种方式<br>都能体验</p></div>`,note:'实际界面 · 示例比赛资料'}
];
(async()=>{const browser=await chromium.launch({headless:true});try{
 const page=await browser.newPage({viewport:{width:1080,height:1440},deviceScaleFactor:1});const checks=[];
 for(const [i,c] of cards.entries()){
  const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>${c.id}</title><style>${css}</style><main class="canvas">${brand}<p class="tag">${c.tag}</p><h1>${c.title}</h1><p class="sub">${c.sub}</p><div class="body">${c.body}</div><footer class="note"><span>${c.note}</span><span>${i+1} / ${cards.length}</span></footer></main></html>`;
  const file=path.join(__dirname,c.id+'.html');fs.writeFileSync(file,html);await page.goto('file://'+file);
  await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});
  const check=await page.evaluate(()=>({demoGap:document.querySelector('.phone').getBoundingClientRect().top-document.querySelector('.sub').getBoundingClientRect().bottom,text:document.body.innerText,images:[...document.images].every(x=>x.complete&&x.naturalWidth>0),bounds:[...document.querySelectorAll('h1,.sub,.phone,.side,.coverwords,.brand,.note')].map(x=>{const r=x.getBoundingClientRect();return {element:x.className||x.tagName,top:r.top,bottom:r.bottom,left:r.left,right:r.right,overflow:x.scrollWidth>x.clientWidth+2}})}));
  if(check.demoGap<72||!check.images)throw Error('Invalid demo placement');
  if(/https?:|www\.|baseballmaster\.cc|二维码|邀请码|下载|App Store|私信|微信|邮箱|3\.1|预览|预告|即将/.test(check.text))throw Error('Unwanted publication text');
  for(const b of check.bounds)if(b.left<0||b.right>1080||b.bottom>1440||b.overflow)throw Error('Overflow '+JSON.stringify(b));
  const target=path.join(out,'images',c.id+'.png');await page.screenshot({path:target});
  const m=await sharp(target).metadata();if(m.width!==1080||m.height!==1440||m.hasAlpha)throw Error('Invalid output');
  checks.push({id:c.id,...check,sha256:crypto.createHash('sha256').update(fs.readFileSync(target)).digest('hex')});
 }
 fs.writeFileSync(path.join(out,'layout-checks.json'),JSON.stringify(checks,null,2)+'\n');
 const thumbs=await Promise.all(cards.map(c=>sharp(path.join(out,'images',c.id+'.png')).resize(540,720).toBuffer()));
 await sharp({create:{width:1080,height:720,channels:3,background:'#092a2a'}}).composite(thumbs.map((input,i)=>({input,left:i*540,top:0}))).png().toFile(path.join(out,'overview.png'));
 console.log('Rendered two images using the exact 3.0.1 v2 stylesheet; layout and text checks passed.');
 }finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
