// Run with node from any directory. The source screenshots use synthetic teams.
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const modules = process.env.CODEX_ARTIFACT_NODE_MODULES || path.join(os.homedir(), ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules");
const sharp = require(path.join(modules, "sharp"));
const { chromium } = require(path.join(modules, "playwright"));
const source = path.join(__dirname, "source");
const out = path.resolve(__dirname, "../../../../output/releases/3.1.3");
const image = file => "data:image/" + (file.endsWith(".svg") ? "svg+xml" : path.extname(file).slice(1)) + ";base64," + fs.readFileSync(path.resolve(source, file)).toString("base64");
const product = image("scorekeeper-horizontal-reverse.svg");
const signature = image("baseballmaster-horizontal-reverse.svg");
const bodyStyle = '*{box-sizing:border-box}html,body{margin:0;height:100%;width:100%;overflow:hidden}body{background:#092a2a;color:#faf9ed;font-family:"PingFang SC",-apple-system,sans-serif;-webkit-font-smoothing:antialiased}h1,h2,p{margin:0}img{display:block}';
const previous = "../../3.1.2/appstore/source/";
const phoneScreens = [
  ["01-现场记分", previous+"iphone-01.png", "现场记分", "每一球，都记在场上", "比分、垒况与场上球员，同屏查看。"],
  ["02-球队统计", "statistics.png", "球队与球员统计", "看一场，也看一整年", "按球队、年度或场次查看打击、投手与守备统计。"],
  ["03-记录方式", previous+"iphone-02.png", "记录方式", "基础版与 Pro 版", "按熟悉的方式开始记录，每场比赛都能选择。"],
  ["04-击球落点", previous+"iphone-03.png", "Pro 记分", "从落点到传球", "点选击球落点，再依次记录守备路线。"],
  ["05-击球结果", previous+"iphone-04.png", "击球结果", "这次击球，怎么记", "从安打、出局等结果中选择场上发生的那一种。"],
  ["06-球队名单", previous+"iphone-05.png", "球队名单", "把队员一次录好", "批量输入名单，预览姓名与背号后确认。"],
  ["07-球员落点", previous+"iphone-06.png", "球员统计", "回看每次击球去向", "按选定的比赛查看球员击球落点。"],
  ["08-球队赛程", "schedule.png", "球队赛程", "下一场比赛，首页见", "队员在「比赛」查看对手、开赛时间和场地。"],
  ["09-四垒确认", "runner-iphone.png", "跑者确认", "每个跑者去了哪一垒", "确认击球结果前，在四垒图上核对每个人的去向。"],
  ["10-赛后分析", "analysis.png", "赛后分析", "从比分读回关键局面", "查看 MVP、比赛综述和球员评价，分享文字或 PDF。"]
];
const tabletScreens = [
  ["01-现场记分", previous+"ipad-01.png", "iPad 现场记分", "球场和比分，同屏查看", "看清垒况和场上球员，记录每个打席。"],
  ["02-球队名单", previous+"ipad-02.png", "球队名单", "赛前把名单备好", "批量输入球员，再逐项核对姓名与背号。"],
  ["03-四垒确认", "runner-ipad.png", "跑者确认", "球场与跑者，同屏核对", "确认跑者去向时，仍能看到场上局面。"]
];
function storeScreen(file, label, title, detail) {
  return `<style>${bodyStyle}
  main{height:100%;position:relative;background:radial-gradient(ellipse at 60% 78%,#20483f,transparent 70%)}
  header{position:absolute;top:3.7%;width:100%;display:flex;justify-content:center}header img{height:2.8vh}
  section{position:absolute;top:10.5%;left:8%;right:7%}.kind{font-size:min(3.2vw,1.9vh);color:#c4dbac}
  h1{font-size:min(8.4vw,5.1vh);line-height:1.25;letter-spacing:-.055em;margin:1.2vh 0}.detail{font-size:min(3.15vw,1.8vh);color:#d7e3d8}
  figure{position:absolute;top:27.5%;bottom:6.7%;left:10%;right:10%;margin:0;display:flex;align-items:center;justify-content:center}
  figure img{width:100%;height:100%;object-fit:contain}footer{position:absolute;bottom:2.8%;left:8%}footer img{height:1.3vh}
  </style><main><header><img src="${product}"></header><section><p class="kind">${label}</p><h1>${title}</h1><p class="detail">${detail}</p></section><figure><img src="${image(file)}"></figure><footer><img src="${signature}"></footer></main>`;
}
async function wechat() {
  async function excerpt(file, area) {
    const pixels = await sharp(path.join(source,file)).extract(area).png().toBuffer();
    return "data:image/png;base64," + pixels.toString("base64");
  }
  const assets = {
    product, signature, ballpark: image("ballpark.webp"), scoreboard: image("obs-score.png"),
    schedule: await excerpt("schedule.png", {left:47, top:1365, width:1113, height:338}),
    runners: await excerpt("runner-iphone.png", {left:52, top:817, width:1101, height:910}),
    report: await excerpt("analysis.png", {left:12, top:515, width:1182, height:1668})
  };
  const template = fs.readFileSync(path.join(__dirname, "wechat.html"), "utf8");
  return template.replace(/\{\{(\w+)\}\}/g, (_,name) => {
    if (!assets[name]) throw new Error("Unknown poster asset: " + name);
    return assets[name];
  });
}
async function render() {
  const browser = await chromium.launch({executablePath:process.env.CHROME_BIN || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",headless:true});
  async function draw(width,height,filename,html) {
    const page = await browser.newPage({viewport:{width,height},deviceScaleFactor:1});
    try {
      await page.setContent(html.startsWith('<!doctype html>') ? html : '<!doctype html><html lang="zh-CN"><meta charset="utf-8">'+html+'</html>');
      await page.evaluate(async () => { await document.fonts.ready; await Promise.all([...document.images].map(img => img.decode())); });
      const destination = path.join(out,filename);
      fs.mkdirSync(path.dirname(destination),{recursive:true});
      const pixels = await page.screenshot();
      const rgb = sharp(pixels).removeAlpha();
      if (filename.endsWith(".jpg")) {
        await rgb.jpeg({quality:88,mozjpeg:true,chromaSubsampling:"4:4:4"}).toFile(destination);
      } else {
        await rgb.png().toFile(destination);
      }
    } finally { await page.close(); }
  }
  try {
    await draw(1080,1800,"微信宣传图.png",await wechat());
    if (process.argv.includes("--wechat-only")) return;
    const devices = [
      ["iphone-6.1",1179,2556,phoneScreens],
      ["iphone-6.1-face-id",1170,2532,phoneScreens],
      ["iphone-6.9",1320,2868,phoneScreens],
      ["ipad-13",2064,2752,tabletScreens]
    ];
    for (const [group,width,height,screens] of devices) {
      for (const [name,file,label,title,detail] of screens) {
        await draw(width,height,`screenshots/${group}/${name}.jpg`,storeScreen(file,label,title,detail));
      }
    }
  } finally { await browser.close(); }
}
render().catch(error => { console.error(error); process.exitCode=1; });
