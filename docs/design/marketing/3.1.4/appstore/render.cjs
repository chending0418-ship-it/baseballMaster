// node render.cjs <3.1.3 screenshots directory> [CHROME_BIN via environment]
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const modules = process.env.CODEX_ARTIFACT_NODE_MODULES || path.join(os.homedir(), ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules");
const sharp = require(path.join(modules, "sharp"));
const {chromium} = require(path.join(modules, "playwright"));
const previous = process.argv[2];
if (!previous) throw new Error("Pass the screenshots folder from the 3.1.3 delivery.");
const out = path.resolve(__dirname, "../../../../../output/releases/3.1.4/AppStore送审资料");
const source = name => path.join(__dirname, "source", name);
const uri = (buffer, mime) => "data:image/" + mime + ";base64," + buffer.toString("base64");
const art = name => uri(fs.readFileSync(source(name)), name.endsWith(".svg") ? "svg+xml" : "png");
async function crop(name, top, height) {
  const buffer = await sharp(source(name)).extract({left:0, top, width:1206, height}).png().toBuffer();
  return uri(buffer, "png");
}
const logo = art("scorekeeper-horizontal-reverse.svg");
const signature = art("baseballmaster-horizontal-reverse.svg");
const devices = [
  ["iphone-6.1",1179,2556], ["iphone-6.1-face-id",1170,2532],
  ["iphone-6.9",1320,2868], ["ipad-13",2064,2752]
];
const phoneArchive = [
  ["01-现场记分","01-现场记分"], ["05-球队统计","02-球队统计"], ["06-球队赛程","08-球队赛程"],
  ["07-球队名单","06-球队名单"], ["08-记录方式","03-记录方式"], ["09-四垒确认","09-四垒确认"], ["10-赛后分析","10-赛后分析"]
];
const tabletArchive = [["01-现场记分","01-现场记分"],["03-球队名单","02-球队名单"],["04-四垒确认","03-四垒确认"]];
function storePage(category, title, detail, inside) {
  return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>
  *{box-sizing:border-box}html,body{margin:0;height:100%;width:100%;overflow:hidden}
  body{background:#092a2a;color:#faf9ed;font-family:"PingFang SC",-apple-system,sans-serif;-webkit-font-smoothing:antialiased}
  h1,p,figure{margin:0}img{display:block}main{height:100%;position:relative;background:radial-gradient(ellipse at 60% 78%,#20483f,transparent 70%)}
  header{position:absolute;top:3.7%;width:100%;display:flex;justify-content:center}header img{height:2.8vh}
  section{position:absolute;top:10.5%;left:8%;right:7%}.kind,figcaption{font-size:min(3.2vw,1.9vh);color:#c4dbac}
  h1{font-size:min(8.4vw,5.1vh);line-height:1.25;letter-spacing:-.055em;margin:1.2vh 0}
  .detail{font-size:min(3.15vw,1.8vh);color:#d7e3d8}
  article{position:absolute;top:27.5%;bottom:6.7%;left:10%;right:10%;display:flex;flex-direction:column;justify-content:center;gap:2.4vh}
  article>img{width:100%;height:100%;object-fit:contain}figure{width:95%;align-self:center}figure img{width:100%;border-radius:1.1vh}figcaption{margin-bottom:.7vh}
  footer{position:absolute;bottom:2.8%;left:8%}footer img{height:1.3vh}
  </style><main><header><img src="${logo}"></header><section><p class="kind">${category}</p><h1>${title}</h1><p class="detail">${detail}</p></section><article>${inside}</article><footer><img src="${signature}"></footer></main></html>`;
}
const block = (caption,url) => `<figure><figcaption>${caption}</figcaption><img src="${url}"></figure>`;
async function load(page,html) {
  await page.setContent(html);
  await page.evaluate(async()=>{await document.fonts.ready; await Promise.all([...document.images].map(img=>img.decode()));});
}
async function render(){
  const assign = await crop("assign.png",180,750);
  const lineup = await crop("lineup.png",352,1070);
  const add = await crop("add-player.png",180,1340);
  const bench = await crop("bench.png",883,635);
  const pages = [
    ["02-双人记录",storePage("共享球队赛程","两人分工记一场球","管理员指派两人，一人记局面，一人管阵容。",block("管理员安排岗位",assign)+block("阵容员核对双方先发",lineup))],
    ["03-赛中加人",storePage("赛中补员","迟到的队员也能上场","从名册补选或临时登记，加入本场替补后再换人。",block("选择或登记队员",add)+block("本场替补席",bench))],
    ["04-守备传球",storePage("Pro 记分","三垒接球，传向一垒","依次点选守备员，记下这次 5 → 3 的传球。",`<img src="${art("route.png")}">`)]
  ];
  const tablet = [["02-守备传球",storePage("iPad · Pro 记分","三垒接球，传向一垒","依次点选守备员，核对这次出局的传球路线。",`<img src="${art("ipad-route.png")}">`)]];
  const browser=await chromium.launch({executablePath:process.env.CHROME_BIN || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",headless:true});
  try {
    for(const [group,width,height] of devices) {
      const folder=path.join(out,"screenshots",group);
      fs.mkdirSync(folder,{recursive:true});
      const isPad=group==="ipad-13";
      for(const [dest,original] of isPad?tabletArchive:phoneArchive)
        fs.copyFileSync(path.join(previous,group,original+".jpg"),path.join(folder,dest+".jpg"));
      const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:1});
      for(const [name,html] of isPad?tablet:pages){
        await load(page,html);
        const fits=await page.evaluate(()=>{const container=document.querySelector("article").getBoundingClientRect();
          return [...document.querySelectorAll("figure")].every(el=>el.getBoundingClientRect().top>=container.top-1 && el.getBoundingClientRect().bottom<=container.bottom+1);});
        if(!fits) throw Error("Screenshot excerpts overflow "+group+"/"+name);
        await sharp(await page.screenshot()).removeAlpha().jpeg({quality:90,mozjpeg:true,chromaSubsampling:"4:4:4"}).toFile(path.join(folder,name+".jpg"));
      }
      await page.close();
    }
    const sheet=await browser.newPage();
    let html=fs.readFileSync(path.join(__dirname,"review-guide.html"),"utf8");
    const excerpts={assign,opening:await crop("opening.png",900,825),add};
    html=html.replace(/\{\{(assign|opening|add)\}\}/g,(_,key)=>excerpts[key]);
    await load(sheet,html);
    await sheet.pdf({path:path.join(out,"App-Review-双机操作图.pdf"),preferCSSPageSize:true,printBackground:true});
  } finally {await browser.close();}
}
render().catch(err=>{console.error(err);process.exitCode=1;});
