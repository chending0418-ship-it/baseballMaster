// Static design review only. No application, server, or production data writes.
const fs = require('node:fs');
const path = require('node:path');
const modules = process.env.CODEX_ARTIFACT_NODE_MODULES || path.join(process.env.HOME, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const { chromium } = require(path.join(modules, 'playwright'));
const out = path.resolve(__dirname, '../../../output/samples/3.0-ui-review');
(async () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(out, 'screens.json')));
  const browser = await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless:true});
  const page = await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:3});
  const checks = [];
  for (const item of manifest) {
    if (item.source) {
      fs.copyFileSync(item.source,path.join(out,'screens',item.id+'.png'));
      checks.push({id:item.id,source:'unchanged-existing-app-screenshot',overflow:false,horizontalOverflow:false,missingImages:0});
      continue;
    }
    await page.goto('file://' + path.join(out, 'screens', item.id + '.html'));
    await page.evaluate(() => document.fonts.ready);
    const check = await page.evaluate(() => {
      const content = document.querySelector('.content');
      const box = content.getBoundingClientRect();
      const bottom = Math.max(...[...content.children].map(el => el.getBoundingClientRect().bottom));
      return {contentBottom:bottom,allowedBottom:box.bottom,overflow:bottom>box.bottom+1,horizontalOverflow:document.documentElement.scrollWidth>innerWidth,missingImages:[...document.images].filter(i=>!i.complete||!i.naturalWidth).length};
    });
    checks.push({id:item.id,...check});
    await page.screenshot({path:path.join(out,'screens',item.id+'.png')});
  }
  await page.setViewportSize({width:1620,height:800});
  await page.goto('file://' + path.join(out,'overview.html'));
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({path:path.join(out,'overview.png'),fullPage:true});
  fs.writeFileSync(path.join(out,'layout-checks.json'),JSON.stringify(checks,null,2));
  await browser.close();
  const issues=checks.filter(c=>c.overflow||c.horizontalOverflow||c.missingImages);
  console.log(JSON.stringify({screens:checks.length,issues}));
  if(issues.length) throw new Error('Review layout has overflow or missing images');
})();
