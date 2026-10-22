// Static screen rendering and layout verification; no product or data writes.
const fs=require('node:fs');
const path=require('node:path');
const modules='/Users/JasonChan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const {chromium}=require(path.join(modules,'playwright'));
const root=path.resolve(__dirname,'../../..');
const version=process.argv[2]||'';
if(!['','v2','v3'].includes(version))throw new Error('Unknown review version');
const out=path.join(root,'output/samples/scoring-flow-review',version);
const check=path.join(root,'output/validation/3.0/scoring-flow-review',version);
(async()=>{
  const items=JSON.parse(fs.readFileSync(path.join(out,'screens.json')));
  const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
  const page=await browser.newPage({viewport:{width:402,height:874},deviceScaleFactor:3});
  const results=[];
  for(const item of items){
    if(item.source){results.push({id:item.id,source:'existing-app-original',issues:[]});continue;}
    await page.setViewportSize({width:item.width,height:item.height});
    await page.goto('file://'+path.join(out,'screens',item.id+'.html'));
    await page.evaluate(()=>document.fonts.ready);
    const result=await page.evaluate(()=>{
      const content=document.querySelector('.content')||document.querySelector('.action-area')||document.querySelector('.sheet');
      const footer=document.querySelector('.footer');
      const bottom=Math.max(...[...content.children].map(el=>el.getBoundingClientRect().bottom));
      const limit=footer?footer.getBoundingClientRect().top-10:(content.classList.contains('sheet')?innerHeight-22:innerHeight);
      const issues=[];
      if(bottom>limit+1)issues.push('content overlaps footer');
      if(document.documentElement.scrollWidth>innerWidth)issues.push('horizontal overflow');
      for(const el of document.querySelectorAll('.choice,.dest,.tool,.pitch,.catalog-option,.listrow')){
        if(el.scrollWidth>el.clientWidth+2||el.scrollHeight>el.clientHeight+2)issues.push('control text overflow');
        const list=el.closest('.list');
        if(list&&el.getBoundingClientRect().bottom>list.getBoundingClientRect().bottom+2)issues.push('control clipped by parent');
      }
      return {bottom,limit,issues};
    });
    results.push({id:item.id,...result});
    await page.screenshot({path:path.join(out,'screens',item.id+'.png')});
  }
  await browser.close();
  fs.writeFileSync(path.join(check,'screen-layout.json'),JSON.stringify(results,null,2));
  const issues=results.filter(item=>item.issues.length);
  console.log(JSON.stringify({screens:results.length,issues}));
  if(issues.length)throw new Error('Static screen layout failed');
})();
