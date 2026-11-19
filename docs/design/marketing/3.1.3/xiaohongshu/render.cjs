const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const modules = process.env.CODEX_ARTIFACT_NODE_MODULES || path.join(os.homedir(), ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules");
const sharp = require(path.join(modules, "sharp"));
const { chromium } = require(path.join(modules, "playwright"));
const out = path.resolve(__dirname, "../../../../../output/marketing/3.1.3/小红书");
const assets = path.resolve(__dirname, "../source");
function image(file) {
  return "data:image/" + (file.endsWith(".svg") ? "svg+xml" : "png") + ";base64," + fs.readFileSync(file).toString("base64");
}
async function crop(file, left, top, width, height) {
  const data = await sharp(file).extract({left, top, width, height}).png().toBuffer();
  return "data:image/png;base64," + data.toString("base64");
}
async function render() {
  const originals = path.join(out, "高清原图");
  const field = await crop(path.resolve(__dirname,"../../3.1.2/appstore/source/iphone-01.png"),40,545,1124,761);
  const schedule = await crop(path.join(assets,"schedule.png"),32,1204,1142,511);
  const runners = await crop(path.join(__dirname,"android-runners.png"),55,704,966,665);
  const report = await crop(path.join(assets,"analysis.png"),112,680,986,530);
  const score = image(path.join(assets,"obs-score.png"));
  const logo = image(path.join(assets,"scorekeeper-horizontal-reverse.svg"));
  const mark = image(path.join(assets,"baseballmaster-horizontal-reverse.svg"));
  const slides = [
    {file:"01-封面", theme:"cover dark", label:"iOS × Android", title:"一场棒球赛<br>记分员都在记什么？", copy:"从赛前约场，到赛后复盘。", art:`<img class="field" src="${field}">`, caption:"示例比赛 · 现场记分"},
    {file:"02-球队赛程", theme:"light", label:"Feature 1　球队赛程", title:"周末这场球<br>几点，哪个场？", copy:"队里发布安排后，打开「比赛 → 即将进行」，<br>就能查看对手、时间和场地。", art:`<div class="screen"><img src="${schedule}"></div><aside>如果改期或取消，队员刷新即可查看。</aside>`, caption:"示例赛程 · 成员视角"},
    {file:"03-在线比赛分析", theme:"sage", label:"Feature 2　在线比赛分析", title:"海浪这一分<br>是怎么拿到的？", copy:"赛后回看关键局面和球员表现。选单场、全年<br>或指定场次，都能生成文字和 PDF 报告。", art:`<div class="report"><img src="${report}"></div>`, caption:"示例比赛 · A4 报告节选"},
    {file:"04-四垒核对", theme:"light", label:"Feature 3　四垒核对与赛中纠错", title:"一垒安打之后<br>二垒跑者去哪了？", copy:"这球打者上一垒，原二垒跑者停在三垒。<br>在四垒图上逐人确认，记错的换人也能更正。", art:`<div class="runner"><img src="${runners}"></div>`, caption:"示例比赛 · Android 记分界面"},
    {file:"05-打击与捕手统计", theme:"light", label:"Feature 4　打击与捕手统计", title:"两支安打<br>能记几个垒数？", copy:"一支一垒安打，加一支二垒安打。<br>这次打击总垒数（TB）记 3。", art:'<div class="math"><div><strong>1</strong><small>一垒安打</small></div><em>+</em><div><strong>2</strong><small>二垒安打</small></div><em>=</em><div><strong>3</strong><small>打击总垒数</small></div></div><aside>看捕手守备时，还可以查阻杀（CCS）和捕逸（PB）。</aside>', caption:"示例计算 · 打击与守备统计"},
    {file:"06-OBS转播", theme:"dark", label:"Feature 5　球队固定 OBS 转播源", title:"转播画面里<br>现在是谁投球？", copy:"比分条把投手、球数和局面叠在画面上。<br>当前打者、客队阵容、主队阵容也各有固定来源。", art:`<div class="broadcast"><img src="${score}"></div><aside>图中是三局上，海浪 2 比 1 领先，一人出局。</aside>`, caption:"示例比赛 · OBS 浏览器源"}
  ];
  const css = fs.readFileSync(path.join(__dirname,"style.css"),"utf8");
  const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",headless:true});
  fs.mkdirSync(originals,{recursive:true});
  try {
    const page=await browser.newPage({viewport:{width:720,height:960},deviceScaleFactor:2});
    for (const [i,s] of slides.entries()) {
      await page.setContent(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>${css}</style><main class="${s.theme}"><header><img src="${logo}"></header><section><p class="label">${s.label}</p><h1>${s.title}</h1><p class="copy">${s.copy}</p></section><figure>${s.art}</figure><footer><img src="${mark}"><span>${s.caption}</span><b>${i+1} / 6</b></footer></main></html>`);
      await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(x=>x.decode()));});
      const valid=await page.evaluate(()=>{const rect = s => document.querySelector(s).getBoundingClientRect();return rect("section").bottom<=rect("figure").top && rect("figure").bottom<=rect("footer").top && document.documentElement.scrollWidth===720 && document.documentElement.scrollHeight===960;});
      if(!valid)throw Error("Content overflows "+s.file);
      const pixels=await page.screenshot();
      await sharp(pixels).removeAlpha().png({compressionLevel:9}).toFile(path.join(originals,s.file+".png"));
    }
    const thumbnails=await Promise.all(slides.map(async(s,i)=>({input:await sharp(path.join(originals,s.file+".png")).resize(252,336).png().toBuffer(),left:8+(i%3)*260,top:8+Math.floor(i/3)*344})));
    await sharp({create:{width:788,height:696,channels:3,background:"#e9e7db"}}).composite(thumbnails).jpeg({quality:92}).toFile(path.join(out,"六图预览.jpg"));
    fs.copyFileSync(path.join(__dirname,"笔记.txt"),path.join(out,"笔记.txt"));
  } finally {await browser.close();}
}
render().catch(e=>{console.error(e);process.exitCode=1;});
