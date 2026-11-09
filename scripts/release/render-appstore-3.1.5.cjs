const fs = require('node:fs/promises');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { homedir } = require('node:os');

const root = path.resolve(__dirname, '../..');
const out = path.join(root, 'output/releases/3.1.5/app-store');
const modules = process.env.CODEX_ARTIFACT_NODE_MODULES || path.join(homedir(),
  '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const { chromium } = require(path.join(modules, 'playwright'));
const sharp = require(path.join(modules, 'sharp'));
const brand = process.env.BASEBALLMASTER_BRAND_ROOT;
if (!brand) throw new Error('Set BASEBALLMASTER_BRAND_ROOT to brand/specifications/1.3.');
const groups = {
  'iphone-6.3': [1206, 2622],
  'ipad-13': [2064, 2752],
  'duo-outer': [1398, 2034],
  'duo-inner': [2853, 2007],
};
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const dataURL = (type, bytes) => 'data:' + type + ';base64,' + bytes.toString('base64');
const esc = text => text.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');

function pageHTML(item, dimensions, screenshot, logo) {
  const [width, height] = dimensions;
  const landscape = width > height;
  const unit = width / 1080;
  const top = landscape ? 113 : item.group === 'iphone-6.3' ? 520 : 445;
  const bottom = landscape ? 87 : item.group === 'iphone-6.3' ? 120 : 93;
  const left = landscape ? 365 : item.group === 'iphone-6.3' ? 78 : 72;
  const right = landscape ? 45 : left;
  const imageWidth = Math.min(1080 - left - right, (height / unit - top - bottom) * width / height);
  return `<!doctype html><html lang="zh-Hans"><meta charset="utf-8"><style>
    *{box-sizing:border-box}
    html,body{width:${width}px;height:${height}px;overflow:hidden;margin:0}
    .card{width:1080px;height:${height / unit}px;transform:scale(${unit});transform-origin:top left;
      font-family:"PingFang SC",-apple-system,sans-serif;position:relative;overflow:hidden;
      background:${item.dark ? 'radial-gradient(ellipse at 58% 76%,#17443b,#082d28 73%)' : 'radial-gradient(ellipse at 70% 72%,#e4ebdf,#f4f5ef 78%)'};
      color:${item.dark ? '#fff' : '#092a2a'}}
    .brand{position:absolute;top:44px;left:54px;width:${landscape ? 268 : 330}px;display:block}
    .copy{position:absolute;left:${landscape ? 54 : 50}px;top:${landscape ? 209 : 145}px;
      width:${landscape ? 300 : 980}px;text-align:${landscape ? 'left' : 'center'}}
    .label{margin:0 0 15px;font-size:${landscape ? 20 : 27}px;letter-spacing:.08em;
      color:${item.dark ? '#bad2c6' : '#476a54'}}
    h1{margin:0;font-size:${landscape ? 60 : item.group === 'iphone-6.3' ? 91 : 75}px;
      font-weight:600;letter-spacing:-.04em;line-height:1.12}
    h1 span{display:block;color:${item.dark ? '#d7edb7' : '#537947'}}
    .detail{margin:20px auto 0;font-size:${landscape ? 23 : 31}px;line-height:1.46;
      color:${item.dark ? '#e0eae2' : '#143b2d'}}
    .visual{position:absolute;inset:${top}px ${right}px ${bottom}px ${left}px;
      display:flex;align-items:center;justify-content:center}
    .visual img{width:${imageWidth}px;height:auto;display:block;
      border-radius:${landscape ? 18 : 32}px;box-shadow:0 16px 43px #001b1852;outline:2px solid #42675b55}
    footer{position:absolute;bottom:32px;left:54px;font-size:${landscape ? 17 : 21}px;
      color:${item.dark ? '#bfd3c6' : '#426553'}}
  </style><main class="card">
    <img class="brand" src="${logo}" alt="棒球比赛记录大师">
    <header class="copy"><p class="label">${esc(item.label)}</p>
      <h1>${esc(item.title[0])}<span>${esc(item.title[1])}</span></h1>
      <p class="detail">${esc(item.detail)}</p></header>
    <div class="visual"><img src="${screenshot}" alt="候选构建的模拟器截图"></div>
    <footer>实际 App 界面 · 合成比赛示例</footer>
  </main></html>`;
}

async function render() {
  const items = JSON.parse(await fs.readFile(path.join(root, 'ios/docs/releases/3.1.5/artwork.json')));
  const brandManifest = JSON.parse(await fs.readFile(path.join(brand, 'manifest.json')));
  if (brandManifest.version !== '1.3') throw new Error('Expected product brand version 1.3');
  const browser = await chromium.launch({executablePath: process.env.CHROME_BIN ||
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true});
  const files = [];
  try {
    for (const item of items) {
      const dimensions = groups[item.group];
      const [width, height] = dimensions;
      const source = await fs.readFile(path.join(out, 'source', item.source));
      const sourceSize = await sharp(source).metadata();
      if (sourceSize.width !== width || sourceSize.height !== height)
        throw new Error('Unexpected simulator dimensions for ' + item.source);
      const logoName = item.dark ? 'scorekeeper-horizontal-reverse.svg' : 'scorekeeper-horizontal.svg';
      const logo = await fs.readFile(path.join(brand, 'assets', logoName));
      if (sha(logo) !== brandManifest.assets[logoName]) throw new Error('Brand asset changed: ' + logoName);
      const page = await browser.newPage({viewport:{width,height}, deviceScaleFactor:1});
      await page.setContent(pageHTML(item, dimensions, dataURL('image/png',source), dataURL('image/svg+xml',logo)));
      await page.evaluate(async () => {
        await document.fonts.ready;
        await Promise.all([...document.images].map(img => img.decode()));
        const box = name => document.querySelector(name).getBoundingClientRect();
        for (const name of ['.brand','.copy','.visual img','footer']) {
          const b = box(name);
          if (b.left < 0 || b.right > innerWidth || b.top < 0 || b.bottom > innerHeight)
            throw new Error('Artwork extends beyond canvas: ' + name);
        }
        if (box('.brand').bottom > box('.copy').top ||
            (box('.copy').right > box('.visual img').left && box('.copy').bottom > box('.visual img').top))
          throw new Error('Artwork regions overlap');
        if (box('.visual img').bottom > box('footer').top) throw new Error('Screenshot overlaps footer');
      });
      const relative = path.join(item.group, item.file + '.png');
      const output = path.join(out, relative);
      await fs.mkdir(path.dirname(output), {recursive:true});
      const screenshot = await page.screenshot();
      await sharp(screenshot).removeAlpha().png().toFile(output);
      const final = await fs.readFile(output);
      const check = await sharp(final).metadata();
      if (check.width !== width || check.height !== height || check.hasAlpha)
        throw new Error('Invalid App Store PNG: ' + relative);
      files.push({file:relative,width,height,sha256:sha(final),source:item.source,
        sourceSHA256:sha(source),brand:'1.3',logo:logoName,logoSHA256:sha(logo)});
      await page.close();
    }
    await fs.writeFile(path.join(out,'manifest.json'), JSON.stringify({
      version:'3.1.5',build:1,sourceCommit:'8ce4380',files,
    },null,2)+'\n');
    const gallery = await browser.newPage({viewport:{width:1460,height:950}});
    await gallery.setContent(`<!doctype html><meta charset="utf-8"><style>
      body{margin:0;padding:42px;background:#e7ede7;color:#092a2a;font-family:"PingFang SC",sans-serif}
      h1{margin:0;font-size:39px}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:32px;align-items:start}
      figure{margin:0}img{height:480px;width:100%;object-fit:contain;object-position:bottom}figcaption{text-align:center;padding-top:12px}
    </style><h1>棒球比赛记录大师 · 3.1.5</h1><p>App Store 截图预览 · 品牌规范 1.3</p><div class="grid">${(await Promise.all(files.map(async file =>
      `<figure><img src="${dataURL('image/png',await fs.readFile(path.join(out,file.file)))}"><figcaption>${esc(file.file)}</figcaption></figure>`
    ))).join('')}</div>`);
    await gallery.evaluate(async () => Promise.all([...document.images].map(img => img.decode())));
    await gallery.screenshot({path:path.join(out,'overview.png'),fullPage:true});
  } finally {
    await browser.close();
  }
}
render().catch(error => { console.error(error); process.exitCode = 1; });
