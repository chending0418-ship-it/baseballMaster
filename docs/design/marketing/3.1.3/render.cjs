// Run with node from any directory. The source screenshots use synthetic teams.
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const modules = process.env.CODEX_ARTIFACT_NODE_MODULES || path.join(os.homedir(), ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules");
const sharp = require(path.join(modules, "sharp"));
const { chromium } = require(path.join(modules, "playwright"));
const source = path.join(__dirname, "source");
const out = path.resolve(__dirname, "../../../../output/releases/3.1.3");
const image = file => "data:image/" + (file.endsWith(".svg") ? "svg+xml" : "png") + ";base64," + fs.readFileSync(path.resolve(source, file)).toString("base64");
const product = image("scorekeeper-horizontal-reverse.svg");
const signature = image("baseballmaster-horizontal-reverse.svg");
const bodyStyle = '*{box-sizing:border-box}html,body{margin:0;height:100%;width:100%;overflow:hidden}body{background:#092a2a;color:#faf9ed;font-family:"PingFang SC",-apple-system,sans-serif;-webkit-font-smoothing:antialiased}h1,h2,p{margin:0}img{display:block}';
function storeScreen(file, title, detail) {
  return `<style>${bodyStyle}
  main{height:100%;padding:4.5vh 6vw 3vh;display:flex;flex-direction:column;align-items:center;background:radial-gradient(ellipse at 50% 85%,#244f46,transparent 72%)}
  header{align-self:center}header img{height:3.6vh}h1{margin-top:4vh;font-size:min(8.4vw,5.5vh);font-weight:650;letter-spacing:-.05em}
  p{margin:1.1vh 0 2.8vh;font-size:min(3.6vw,2.2vh);color:#cfddd1}figure{flex:1;min-height:0;width:100%;margin:0;text-align:center}
  figure img{display:inline-block;max-height:100%;max-width:100%;border-radius:2vh;box-shadow:0 1vh 3vh #00181480}footer{align-self:flex-start;padding-top:2vh}footer img{height:1.65vh}
  </style><main><header><img src="${product}"></header><h1>${title}</h1><p>${detail}</p><figure><img src="${image(file)}"></figure><footer><img src="${signature}"></footer></main>`;
}
async function wechat() {
  // Preserve the actual section heading, schedule card, time and venue at a readable size.
  const crop = await sharp(path.join(source, "schedule.png")).extract({left:32,top:1204,width:1142,height:511}).png().toBuffer();
  return `<style>${bodyStyle}
  main{height:1800px;position:relative;overflow:hidden;background:#092a2a}
  header{height:168px;display:flex;align-items:center;justify-content:center}header img{height:76px}
  .hero{padding:50px 73px 0;position:relative;z-index:1}.topline{color:#cde3a9;font-size:30px;letter-spacing:.08em}
  h1{font-size:111px;line-height:1.13;font-weight:650;letter-spacing:-.065em;margin:22px 0 26px}h1 span{color:#d8eba9}
  .desc{font-size:37px;line-height:1.55;color:#e2e9dd}
  .sample{position:absolute;top:690px;left:71px;right:71px}.sample img{width:100%;border-radius:28px;box-shadow:0 16px 38px #00161455}
  .sample p{font-size:23px;color:#b6c7bb;margin:17px 3px}
  .lower{position:absolute;top:1222px;left:0;width:100%;height:578px;padding:48px 73px 0;background:#f3f1e6;color:#173e38}
  .lower h2{font-size:57px;font-weight:650;line-height:1.25;letter-spacing:-.035em}.lower p{font-size:35px;line-height:1.52;margin:12px 0 22px}
  .lower .compat{border-top:2px solid #aab8a7;padding-top:23px;font-size:31px;line-height:1.55;color:#34574d}
  footer{position:absolute;bottom:0;background:#092a2a;height:112px;width:100%;padding:0 72px;display:flex;align-items:center;justify-content:space-between;color:#c3d1bd;font-size:25px}footer img{height:35px}
  .field{width:680px;height:680px;border:2px solid #6c988342;border-radius:17% 75% 17% 17%;position:absolute;right:-415px;top:335px;transform:rotate(-45deg)}
  </style><main><header><img src="${product}"></header><div class="field"></div><section class="hero"><div class="topline">球队赛程</div><h1>下一场在哪打<br><span>打开首页就知道</span></h1><p class="desc">记分员安排好比赛，点「发布到球队赛程」。<br>队员打开首页，就能查看对手、时间和场地。</p></section><div class="sample"><img src="data:image/png;base64,${crop.toString("base64")}"><p>队员的「比赛」首页 · 示例赛程</p></div><section class="lower"><h2>场边记分，切换更流畅</h2><p>翻看长比赛记录、切换球队和统计更顺手。<br>赛后还能生成比赛分析，分享文字或 PDF。</p><p class="compat">已加入球队的队员，将 iOS 版更新到 3.1.3，<br>在「比赛 → 即将进行」查看最新安排。</p></section><footer><img src="${signature}"><span>棒球 · 慢垒</span></footer></main>`;
}
async function render() {
  const browser = await chromium.launch({executablePath:process.env.CHROME_BIN || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",headless:true});
  async function draw(width,height,filename,html) {
    const page = await browser.newPage({viewport:{width,height},deviceScaleFactor:1});
    try {
      await page.setContent('<!doctype html><html lang="zh-CN"><meta charset="utf-8">'+html+'</html>');
      await page.evaluate(async () => { await document.fonts.ready; await Promise.all([...document.images].map(img => img.decode())); });
      const destination = path.join(out,filename);
      fs.mkdirSync(path.dirname(destination),{recursive:true});
      const pixels = await page.screenshot();
      await sharp(pixels).removeAlpha().png().toFile(destination);
    } finally { await page.close(); }
  }
  try {
    await draw(1080,1800,"微信宣传图.png",await wechat());
    const screenshots = [
      ["iphone-6.9/01-schedule","schedule.png","下一场比赛，首页见","在「即将进行」查看球队的对手、开赛时间与场地"],
      ["iphone-6.9/02-runners","runner-iphone.png","每个跑者去了哪一垒","确认击球结果前，在四垒图上核对每个人的去向"],
      ["iphone-6.9/03-analysis","analysis.png","赛后回看关键局面","按单场、全年或选定场次生成比赛分析"],
      ["ipad-13/01-runners","runner-ipad.png","球场与跑者，同屏核对","在确认打者和跑者去向时，仍能看见场上局面"],
      ["ipad-13/02-scoring","../../3.1.2/appstore/source/ipad-01.png","在 iPad 上逐球记分","查看比分、垒况和场上球员，记录每个打席"]
    ];
    for (const [name,file,title,detail] of screenshots) {
      const tablet = name.startsWith("ipad");
      await draw(tablet?2064:1320,tablet?2752:2868,"screenshots/"+name+".png",storeScreen(file,title,detail));
    }
  } finally { await browser.close(); }
}
render().catch(error => { console.error(error); process.exitCode=1; });
