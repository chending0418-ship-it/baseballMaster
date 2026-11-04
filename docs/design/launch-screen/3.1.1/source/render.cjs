const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { pathToFileURL } = require('node:url');
const modules = process.env.CODEX_ARTIFACT_NODE_MODULES || path.join(os.homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const { chromium } = require(path.join(modules, 'playwright'));
const sharp = require(path.join(modules, 'sharp'));
const root = path.resolve(__dirname, '../../../../..');
const output = path.join(root, 'output/samples/3.1.1/launch-screen/2026-10-22/v3');
const width = 402, height = 874;
const asset = fs.readFileSync(path.join(__dirname, 'assets/bm-approved.png'));
const logo = 'data:image/png;base64,' + asset.toString('base64');
const appIcon = fs.readFileSync(path.join(root, 'BaseballMaster/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png'));
const appIconURL = 'data:image/png;base64,' + appIcon.toString('base64');

// Preserve the approved BM silhouette. This App concept uses the white reverse
// on the scorekeeper product's #092A2A theme, as requested by the user.
const stitches = (cx, cy, rx, ry, start, end, count, color, opacity) => {
  let result = '';
  const rad = d => d * Math.PI / 180;
  const point = a => [cx + rx * Math.cos(rad(a)), cy + ry * Math.sin(rad(a))];
  const startPoint = point(start), endPoint = point(end);
  result += `<path d="M${startPoint[0]},${startPoint[1]} A${rx},${ry} 0 ${end-start>180?1:0} 1 ${endPoint[0]},${endPoint[1]}" fill="none" stroke="${color}" stroke-opacity="${opacity*.7}" stroke-width=".8"/>`;
  for (let i = 0; i < count; i++) {
    const a = start + (end-start)*i/(count-1);
    const [x,y] = point(a);
    const [x2,y2] = point(a+.1);
    const length = Math.hypot(x2-x,y2-y);
    const tx=(x2-x)/length, ty=(y2-y)/length, nx=-ty, ny=tx;
    result += `<path d="M${x-nx*5-tx*3},${y-ny*5-ty*3} L${x+tx*2},${y+ty*2} L${x+nx*5-tx*3},${y+ny*5-ty*3}" fill="none" stroke="${color}" stroke-opacity="${opacity}" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>`;
  }
  return result;
};

const art = theme => {
  const dark=theme==='night';
  const ink=dark?'#C8DCCE':'#092A2A';
  return `<svg class="art" viewBox="0 0 402 874" aria-hidden="true"><defs>
    <radialGradient id="glow"><stop stop-color="${dark?'#2F684B':'#DDE5D8'}" stop-opacity="${dark?'.27':'.6'}"/><stop offset="1" stop-color="${dark?'#092A2A':'#F5F5EF'}" stop-opacity="0"/></radialGradient>
    <linearGradient id="fades" x1="0" y1="0" x2="0" y2="1"><stop stop-color="white" stop-opacity=".12"/><stop offset=".5" stop-color="white" stop-opacity=".65"/><stop offset="1" stop-color="white" stop-opacity=".1"/></linearGradient>
    <mask id="fieldFade"><rect width="402" height="874" fill="url(#fades)"/></mask>
    </defs>
    <ellipse cx="${dark?360:60}" cy="${dark?610:480}" rx="390" ry="490" fill="url(#glow)"/>
    <g transform="rotate(-22 200 130)">${stitches(403,-34,258,311,48,191,31,ink,dark?.12:.10)}</g>
    <g transform="rotate(-22 80 760)">${stitches(-125,857,295,286,219,360,34,ink,dark?.095:.085)}</g>
    <g mask="url(#fieldFade)" fill="none" stroke="${ink}" stroke-width=".65" opacity="${dark?.13:.105}">
      <path d="M201 940L-123 616Q201 292 525 616Z"/>
      <path d="M201 940L-16 723Q201 506 418 723Z"/>
      <path d="M201 860L100 759L201 658L302 759Z"/>
      <circle cx="201" cy="759" r="18"/>
      <path d="M197 758h8M201 754v8"/>
      <path d="M201 940V858M201 658V636" stroke-dasharray="2 7"/>
      <rect x="196" y="653" width="10" height="10" transform="rotate(45 201 658)"/>
      <rect x="95" y="754" width="10" height="10" transform="rotate(45 100 759)"/>
      <rect x="297" y="754" width="10" height="10" transform="rotate(45 302 759)"/>
    </g>
    <g stroke="${ink}" stroke-width=".65" opacity="${dark?.11:.08}"><path d="M35 170h14M42 163v14M355 573h14M362 566v14"/></g>
    </svg>`;
};

const styles = `
*{box-sizing:border-box}html,body{margin:0;-webkit-font-smoothing:antialiased}body{font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif}.screen{width:402px;height:874px;position:relative;isolation:isolate;overflow:hidden;background:var(--bg);color:var(--ink)}.night{--bg:#092A2A;--ink:#FFFFFF;--secondary:#C6D5CC;--tertiary:#9FB4B3;--line:rgba(198,213,204,.25)}.day{--bg:#F5F5EF;--ink:#0B1933;--secondary:#596868;--tertiary:#697C71;--line:rgba(11,25,51,.20)}.art{position:absolute;inset:0;width:100%;height:100%;z-index:-1}.identity{position:absolute;top:299px;left:0;width:100%;display:flex;align-items:center;flex-direction:column}.mark{width:220px;height:125.4px;background:var(--ink);mask-image:url('${logo}');-webkit-mask-image:url('${logo}');mask-size:contain;-webkit-mask-size:contain;mask-position:center;-webkit-mask-position:center;mask-repeat:no-repeat;-webkit-mask-repeat:no-repeat}.wordmark{font-family:Arial,sans-serif;font-size:25.4px;letter-spacing:-.045em;line-height:1.05;white-space:nowrap;margin-top:35px;font-weight:800}.wordmark span{font-weight:400}.product{font-size:13px;font-weight:450;letter-spacing:.26em;text-indent:.26em;color:var(--secondary);margin:17px 0 0}.signoff{position:absolute;left:0;right:0;bottom:85px;display:flex;align-items:center;flex-direction:column}.app-icon{display:block;width:44px;height:44px;object-fit:contain;border-radius:10px;margin-bottom:14px}.tagline{font-size:11px;letter-spacing:.22em;text-indent:.22em;font-weight:450;color:var(--secondary)}
`;

const screen = theme => `<main class="screen ${theme}">${art(theme)}<div class="identity"><div class="mark" role="img" aria-label="BM"></div><div class="wordmark">BASEBALL<span>MASTER</span></div><p class="product">棒球比赛记录大师</p></div><div class="signoff"><img class="app-icon" src="${appIconURL}" width="44" height="44" alt="棒球比赛记录大师 App 图标"><div class="tagline">每一球 都有迹可循</div></div></main>`;
const document = (content, extra='') => `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>BaseballMaster 3.1.1 开机图概念稿</title><style>${styles}${extra}</style>${content}</html>`;

(async()=>{
  fs.mkdirSync(output,{recursive:true});
  const browser=await chromium.launch({headless:true});
  try {
    const checks=[];
    const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:3});
    for(const [theme,name] of [['night','01-产品深绿']]){
      const html=path.join(output,`${name}.html`);
      fs.writeFileSync(html,document(screen(theme)));
      await page.goto(pathToFileURL(html).href);
      await page.evaluate(async()=>{await document.fonts.ready;const i=new Image();i.src=getComputedStyle(document.querySelector('.mark')).maskImage.slice(5,-2);await i.decode();await Promise.all([...document.images].map(image=>image.decode()));});
      const check=await page.evaluate(()=>({appIconLoaded:document.querySelector('.app-icon').naturalWidth===1024,background:getComputedStyle(document.querySelector('.screen')).backgroundColor,wordmark:document.querySelector('.wordmark').textContent,typography:[...document.querySelectorAll('.wordmark,.wordmark span')].map(e=>{const s=getComputedStyle(e);return {weight:s.fontWeight,font:s.fontFamily,size:parseFloat(s.fontSize),spacing:parseFloat(s.letterSpacing)}}),bounds:[...document.querySelectorAll('.identity,.mark,.wordmark,.product,.signoff,.app-icon,.tagline')].map(e=>{const r=e.getBoundingClientRect();return {element:e.className,x:r.x,y:r.y,right:r.right,bottom:r.bottom,overflow:e.scrollWidth>e.clientWidth+1}})}));
      if(!check.appIconLoaded||check.background!=='rgb(9, 42, 42)'||check.wordmark!=='BASEBALLMASTER'||check.typography[0].weight!=='800'||check.typography[1].weight!=='400'||check.typography.some(t=>Math.abs(t.spacing/t.size+.045)>.0001)||check.bounds.some(b=>b.x<0||b.y<0||b.right>width||b.bottom>height||b.overflow))throw Error('Layout check failed: '+JSON.stringify(check));
      const png=path.join(output,`${name}.png`);await page.screenshot({path:png});
      const metadata=await sharp(png).metadata();
      if(metadata.width!==width*3||metadata.height!==height*3)throw Error('Unexpected image dimensions');
      await sharp(png).resize(480).png().toFile(path.join(output,'产品深绿-preview.png'));
      checks.push({theme,...check,width:metadata.width,height:metadata.height,sha256:crypto.createHash('sha256').update(fs.readFileSync(png)).digest('hex')});
    }
    const boardCSS=`body{background:#EEF0E9;color:#092A2A}.board{width:920px;height:1200px;padding:45px 62px;position:relative}.header{display:flex;justify-content:space-between;align-items:flex-start}.eyebrow{font-family:Arial,sans-serif;font-size:10px;letter-spacing:.13em;font-weight:700}.header h1{font-size:25px;font-weight:500;letter-spacing:.02em;margin:12px 0 0}.badge{font-size:11px;line-height:1.8;text-align:right;color:#687A70}.designs{display:flex;justify-content:center;margin-top:29px}.option{width:430px}.caption{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:13px}.caption strong{font-size:14px;font-weight:500;letter-spacing:.03em}.caption span{font-family:Arial,sans-serif;font-size:10px;letter-spacing:.05em;color:#617568}.device{width:430px;height:902px;padding:14px;background:#172A26;border-radius:53px;box-shadow:0 24px 42px #173F2A22,0 2px 3px #fff inset;position:relative}.device .screen{border-radius:40px}.island{position:absolute;top:26px;left:50%;transform:translateX(-50%);width:103px;height:29px;border-radius:30px;background:#071411;z-index:2}.gesture{position:absolute;bottom:24px;left:50%;transform:translateX(-50%);width:123px;height:4px;border-radius:4px;background:#ffffff80;z-index:2}.description{font-size:11px;color:#68756E;margin-top:23px;text-align:center;letter-spacing:.04em}.board-footer{position:absolute;bottom:27px;left:62px;right:62px;padding-top:15px;border-top:1px solid #092A2A1A;display:flex;justify-content:space-between;font-size:10px;color:#6D7875}`;
    const board=`<article class="board"><header class="header"><div><div class="eyebrow">BASEBALLMASTER / APP LAUNCH SCREEN</div><h1>棒球比赛记录大师</h1></div><div class="badge">3.1.1 · 开机图设计预览<br>2026.10.22 / V3</div></header><div class="designs"><section class="option"><div class="caption"><strong>产品深绿</strong><span>APP THEME / #092A2A</span></div><div class="device">${screen('night')}<div class="island"></div><div class="gesture"></div></div><p class="description">比赛记录大师主题色 · BM 反白 · App 图标</p></section></div><footer class="board-footer"><span>沿用 BM 原始图形与既有字体规范</span><span>本地设计预览</span></footer></article>`;
    const boardPath=path.join(output,'产品深绿-设备预览.html');fs.writeFileSync(boardPath,document(board,boardCSS));
    await page.setViewportSize({width:920,height:1200});
    await page.goto(pathToFileURL(boardPath).href);await page.evaluate(()=>document.fonts.ready);
    await page.screenshot({path:path.join(output,'产品深绿-设备预览.png')});
    await sharp(path.join(output,'产品深绿-设备预览.png')).resize(920,1200).png().toFile(path.join(output,'产品深绿-设备预览-preview.png'));
    fs.writeFileSync(path.join(output,'checks.json'),JSON.stringify({status:'approved deep green with user-requested App icon footer for 3.1.1',appIconSHA256:crypto.createHash('sha256').update(appIcon).digest('hex'),approvedLogoSHA256:crypto.createHash('sha256').update(asset).digest('hex'),checks},null,2)+'\n');
    console.log(output);
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
