const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto');
const {pathToFileURL}=require('node:url');
const deps=process.env.CODEX_ARTIFACT_NODE_MODULES||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const {chromium}=require(path.join(deps,'playwright')),sharp=require(path.join(deps,'sharp'));
const base=path.resolve(__dirname,'..'),repo=path.resolve(__dirname,'../../../../..');
const kit=path.join(repo,'output/marketing/brand/2026-10-22-dual-product/brand-kit');
const reviewAssets=path.join(repo,'output/marketing/brand/2026-10-22-dual-product/website-review/assets');
const navy='#0B1933',white='#FFFFFF';
const round=n=>Math.round(n*1000)/1000;
const sha=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
(async()=>{
  fs.mkdirSync(kit,{recursive:true});
  const png=fs.readFileSync(path.join(base,'transparent-source.png'));
  const uri='data:image/png;base64,'+png.toString('base64');
  const {data,info}=await sharp(png).ensureAlpha().raw().toBuffer({resolveWithObject:true});
  let left=info.width,top=info.height,right=0,bottom=0;
  for(let y=0;y<info.height;y++)for(let x=0;x<info.width;x++)if(data[(y*info.width+x)*4+3]>80){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);}
  left-=2;top-=2;right+=2;bottom+=2;
  const bw=right-left+1,bh=bottom-top+1,ratio=bw/bh;
  const browser=await chromium.launch({headless:true});
  try{
    const page=await browser.newPage({viewport:{width:1600,height:1200},deviceScaleFactor:1});
    const metrics=await page.evaluate(()=>{
      const c=document.createElement('canvas').getContext('2d');
      const measure=(s,size,weight,tracking)=>{c.font=`${weight} ${size}px Arial`;return c.measureText(s).width+tracking*(s.length-1);};
      return {base:measure('BASEBALL',54,800,-2.43),master:measure('MASTER',54,400,-2.43),smallBase:measure('BASEBALL',35,800,-1.575),smallMaster:measure('MASTER',35,400,-1.575)};
    });
    const totalWord=metrics.base+metrics.master-2.43;
    const word=(x,y,size=54,color=navy,tracking=-2.43)=>`<text x="${x}" y="${y}" fill="${color}" font-family="Arial,sans-serif" font-size="${size}" letter-spacing="${tracking}"><tspan font-weight="800">BASEBALL</tspan><tspan font-weight="400">MASTER</tspan></text>`;
    const symbol=(x,y,w,color=navy)=>{
      const h=w/ratio;
      return `<svg x="${x}" y="${y}" width="${w}" height="${round(h)}" viewBox="0 0 ${bw} ${bh}"><defs><mask id="bm-alpha" x="0" y="0" width="${bw}" height="${bh}" maskUnits="userSpaceOnUse" style="mask-type:alpha"><image x="${-left}" y="${-top}" width="${info.width}" height="${info.height}" href="${uri}"/></mask></defs><path d="M0 0H${bw}V${bh}H0Z" fill="${color}" mask="url(#bm-alpha)"/></svg>`;
    };
    const svg=(w,h,body)=>`<svg xmlns="http://www.w3.org/2000/svg" width="${round(w)}" height="${round(h)}" viewBox="0 0 ${round(w)} ${round(h)}" role="img" aria-label="BaseballMaster"><title>BaseballMaster</title>${body}</svg>`;
    const markW=148,markH=markW/ratio;
    const horizontalW=24+markW+28+totalWord+24,horizontalH=132;
    const horizontal=color=>svg(horizontalW,horizontalH,symbol(24,(horizontalH-markH)/2,markW,color)+word(24+markW+28,85,54,color));
    const verticalW=totalWord+64,verticalH=292,verticalMarkW=242;
    const stacked=svg(verticalW,verticalH,symbol((verticalW-verticalMarkW)/2,24,verticalMarkW)+word(32,248));
    const compactW=24+124+22+Math.max(metrics.smallBase,metrics.smallMaster)+24,compactH=120;
    const compact=svg(compactW,compactH,symbol(24,(compactH-124/ratio)/2,124)+`<g fill="${navy}" font-family="Arial,sans-serif" font-size="35" letter-spacing="-1.575"><text x="170" y="49" font-weight="800">BASEBALL</text><text x="170" y="88" font-weight="400">MASTER</text></g>`);
    const variants={horizontal:horizontal(navy),stacked,compact,mark:svg(bw,bh,symbol(0,0,bw)),'horizontal-reverse':horizontal(white)};
    const checks=[];
    for(const [name,source] of Object.entries(variants)){
      const file=path.join(base,'baseballmaster-'+name+'.svg');fs.writeFileSync(file,source);fs.copyFileSync(file,path.join(kit,path.basename(file)));
      await page.goto(pathToFileURL(file).href);await page.evaluate(()=>document.fonts.ready);
      const boxes=await page.evaluate(()=>[...document.querySelectorAll('text')].map(e=>{const r=e.getBBox();return {text:e.textContent,x:r.x,right:r.x+r.width,bottom:r.y+r.height};}));
      const typography=await page.evaluate(()=>[...document.querySelectorAll('text tspan, text:not(:has(tspan))')].map(e=>{const c=getComputedStyle(e);return {text:e.textContent,weight:c.fontWeight,font:c.fontFamily,size:parseFloat(c.fontSize),spacing:parseFloat(c.letterSpacing)};}));
      if(typography.some(t=>!t.font.startsWith('Arial')||t.weight!==(t.text==='BASEBALL'?'800':'400')||Math.abs(t.spacing/t.size+.045)>.00001))throw Error('Brand typography mismatch '+name+' '+JSON.stringify(typography));
      const dimensions=await page.evaluate(()=>{const s=document.documentElement;return {width:s.viewBox.baseVal.width,height:s.viewBox.baseVal.height};});
      if(boxes.some(b=>b.x<0||b.right>dimensions.width||b.bottom>dimensions.height))throw Error('Wordmark overflow '+name+' '+JSON.stringify(boxes));
      const targetWidth=name==='mark'?1200:name==='stacked'?1400:2200,scale=targetWidth/dimensions.width,targetHeight=Math.ceil(dimensions.height*scale);
      await page.setViewportSize({width:targetWidth,height:targetHeight});
      await page.evaluate(({w,h})=>{document.documentElement.setAttribute('width',w);document.documentElement.setAttribute('height',h);}, {w:targetWidth,h:targetHeight});
      await page.screenshot({path:path.join(kit,'baseballmaster-'+name+'.png'),omitBackground:true,clip:{x:0,y:0,width:targetWidth,height:targetHeight}});
      checks.push({name,...dimensions,boxes,typography});
    }
    for(const name of ['horizontal','compact','mark'])fs.copyFileSync(path.join(base,'baseballmaster-'+name+'.svg'),path.join(reviewAssets,'baseballmaster-'+name+'.svg'));
    const board=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>BaseballMaster 藏青品牌标识组合</title><style>*{box-sizing:border-box}body{margin:0;background:#f4f6f9;color:${navy};font-family:Arial,-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif}.board{width:1600px;height:1210px;padding:60px}.top{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:38px}.top h1{font-size:31px;font-weight:500;letter-spacing:-1px;margin:0}.top p{font-size:13px;letter-spacing:.12em;margin:12px 0 0;opacity:.6}.top span{font:12px monospace;letter-spacing:.1em}.tile{background:#fff;border:1px solid #dfe5ed;border-radius:18px;position:relative;display:flex;align-items:center;justify-content:center}.tile>span{position:absolute;top:25px;left:28px;font-size:12px;letter-spacing:.08em;opacity:.6}.main{height:300px}.main img{width:1160px}.row{display:grid;grid-template-columns:1fr 1fr;gap:26px;margin-top:26px}.vertical{height:386px}.vertical img{width:540px}.reverse{background:${navy};color:#fff;border-color:${navy}}.reverse img{width:645px}.bottom{display:flex;gap:55px;align-items:center;justify-content:space-between;padding-top:43px}.small{width:480px}.sizes{display:flex;align-items:center;gap:38px}.sizes>div{text-align:center;font-size:10px}.sizes img{margin:0 auto 12px}.meta{font-size:12px;line-height:1.9;opacity:.65}.note{display:flex;justify-content:space-between;border-top:1px solid #d5dde7;margin-top:35px;padding-top:22px;font-size:11px;opacity:.6}</style><main class="board"><header class="top"><div><h1>BaseballMaster 品牌标识组合</h1><p>ONE BRAND. TWO WAYS TO PLAY.</p></div><span>DEEP NAVY / ${navy}<br>2026.10.22</span></header><div class="tile main"><span>01 / 横版主组合</span><img src="../baseballmaster-horizontal.svg" alt="横版主组合"></div><div class="row"><div class="tile vertical"><span>02 / 竖版组合</span><img src="../baseballmaster-stacked.svg" alt="竖版组合"></div><div class="tile vertical reverse"><span>03 / 藏青底反白组合</span><img src="../baseballmaster-horizontal-reverse.svg" alt="反白组合"></div></div><div class="bottom"><div class="small"><img src="../baseballmaster-compact.svg" width="320" alt="紧凑组合"></div><div class="sizes"><div><img src="../baseballmaster-mark.svg" width="38" alt="小尺寸">图形</div><div><img src="../baseballmaster-mark.svg" width="56" alt="中尺寸">中尺寸</div><div><img src="../baseballmaster-mark.svg" width="80" alt="大尺寸">大尺寸</div></div><div class="meta">BM 图形 + BASEBALLMASTER<br>BASEBALL 800 / MASTER 400 · −0.045em<br>图形与英文统一深藏青</div></div><div class="note"><span>母品牌图形由用户选定 · 藏青色按用户要求加深</span><span>独立于产品深绿与酒红 · 英文组合为本轮设计稿</span></div></main></html>`;
    const boardFile=path.join(__dirname,'brand-board.html');fs.writeFileSync(boardFile,board);
    await page.setViewportSize({width:1600,height:1210});await page.goto(pathToFileURL(boardFile).href);await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});
    await page.screenshot({path:path.join(base,'brand-system.png')});fs.copyFileSync(path.join(base,'brand-system.png'),path.join(kit,'brand-system.png'));
    fs.copyFileSync(path.join(base,'brand-system.png'),path.join(repo,'output/marketing/brand/2026-10-22-dual-product/website-review/previews/brand-system.png'));
    const files=['supplied-original.png','transparent-source.png','brand-system.png',...Object.keys(variants).map(n=>'baseballmaster-'+n+'.svg'),'source/render.cjs','source/brand-board.html'];
    const hashes={};for(const f of files)hashes[path.relative(repo,path.join(base,f))]=sha(path.join(base,f));
    fs.writeFileSync(path.join(repo,'docs/brand/baseballmaster-parent-baseline.json'),JSON.stringify({date:'2026-10-22',status:{symbol:'user-selected',color:'user-requested deeper navy',englishLockups:'current design proposal'},symbol:{description:'BM ligature with baseball seam in negative space',original:'docs/brand/references/baseballmaster-2026-10-22/supplied-original.png',transparentPreparation:'built-in ImageGen background removal and navy preparation; original retained',alphaBounds:{left,top,right,bottom,width:bw,height:bh},svgConstruction:'embedded raster alpha silhouette; exact flat SVG navy fill. SVG English text remains editable; not a fully traced vector mark.'},color:{navy,reverse:white},wordmark:{text:'BASEBALLMASTER',fontFamily:'Arial, sans-serif',BASEBALLWeight:800,MASTERWeight:400,primaryFontSize:54,primaryLetterSpacing:-2.43,letterSpacingEm:-0.045,sourceBaseline:'docs/brand/brand-baseline.json'},variants:Object.keys(variants),scope:'local design and brand archive; no production deployment',verification:checks,assets:hashes},null,2)+'\n');
    fs.writeFileSync(path.join(kit,'README.txt'),'BaseballMaster 藏青品牌标识组合\n统一主色：'+navy+'\n横版主组合、竖版、紧凑版、独立图形及藏青底反白版。PNG 均为透明底；SVG 的英文可编辑，BM 图形以透明位图轮廓嵌入，不是完全描摹的纯矢量。\n图形原稿与生成来源保存在项目 docs/brand/references/baseballmaster-2026-10-22/。母品牌图形与藏青色由用户指定；英文组合为本轮设计交付。\n');
    console.log('Deep navy brand kit, SVG/transparent PNG variants, board and homepage assets rendered.');
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
