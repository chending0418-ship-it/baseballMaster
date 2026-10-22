const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto');
const {pathToFileURL}=require('node:url');
const deps=process.env.CODEX_ARTIFACT_NODE_MODULES||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const {chromium}=require(path.join(deps,'playwright'));
const base=path.resolve(__dirname,'..'),repo=path.resolve(__dirname,'../../../../..');
const sha=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
(async()=>{
  const browser=await chromium.launch({headless:true});
  try{
    const page=await browser.newPage({viewport:{width:680,height:200},deviceScaleFactor:2});
    await page.goto(pathToFileURL(path.join(__dirname,'lockup.html')).href);
    await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});
    const checked=await page.evaluate(()=>{
      const img=document.querySelector('.lockup img'),word=document.querySelector('.word'),sub=word.querySelector('small');
      const bounds=document.querySelector('.lockup').getBoundingClientRect();
      return {loaded:img.complete&&img.naturalWidth===1024,word:word.firstChild.textContent+word.querySelector('span').textContent,subtitle:sub.textContent,iconWidth:getComputedStyle(img).width,wordFontSize:getComputedStyle(word).fontSize,firstWeight:getComputedStyle(word).fontWeight,secondWeight:getComputedStyle(word.querySelector('span')).fontWeight,subtitleWeight:getComputedStyle(sub).fontWeight,inside:bounds.x>=0&&bounds.right<=innerWidth&&bounds.y>=0&&bounds.bottom<=innerHeight};
    });
    if(!checked.loaded||!checked.inside||checked.word!=='BaseballMaster.cc'||checked.subtitle!=='教练口袋'||checked.iconWidth!=='44px'||checked.wordFontSize!=='19px'||checked.firstWeight!=='800'||checked.secondWeight!=='400')throw Error(JSON.stringify(checked));
    await page.locator('.canvas').screenshot({path:path.join(base,'approved-lockup.png')});
    const hashes={};for(const file of ['app-icon.png','approved-lockup.png','source/lockup.html','source/render.cjs']){const absolute=path.join(base,file);hashes[path.relative(repo,absolute)]=sha(absolute);}
    const baseline={status:'user-approved product lockup',approvedOn:'2026-10-22',approvalQuote:'教练口袋的产品 LOGO 不错，作为产品规范做保留 和 棒球比赛记录大师 类似。',product:{englishName:'Coach Insights',chineseName:'教练口袋'},parentBrand:{name:'BaseballMaster',logo:'User-selected BM ligature and baseball seam; see docs/brand/PARENT-BRAND.md',productColorsAreParentBrandColors:false},reference:'docs/brand/references/coach-pocket-2026-10-22/approved-lockup.png',source:'docs/brand/references/coach-pocket-2026-10-22/source/lockup.html',icon:{sourceProject:'CoachInsights',sourceRelativePath:'CoachInsights/Assets.xcassets/AppIcon.appiconset/AppIcon.png',description:'burgundy eye with baseball pupil; existing approved icon',width:1024,height:1024},layout:{direction:'icon left, two-line text right',alignItems:'center',gap:12,iconSize:44,iconBorderRadius:11},wordmark:{text:'BaseballMaster.cc',fontFamily:'Arial, sans-serif',fontSize:19,lineHeight:1.1,letterSpacing:-0.855,letterSpacingEm:-0.045,segments:[{text:'Baseball',fontWeight:800},{text:'Master.cc',fontWeight:400}],color:'#242426'},subtitle:{text:'教练口袋',fontFamily:'-apple-system, BlinkMacSystemFont, PingFang SC, sans-serif',fontSize:11,fontWeight:450,lineHeight:1.3,letterSpacing:'0.16em',marginTop:7},webNavyVariant:{color:'#0B1933',appliedOn:'2026-10-22',scope:'current local homepage; icon and layout unchanged; initial approved black-text reference retained'},colors:{approvedSurface:'#FFFFFF',text:'#242426',productBurgundy:'#722F37',iconIvory:'#FFF8F0'},responsivePolicy:'Scale composition proportionally; preserve icon, spelling, weight contrast and Chinese name.',assets:hashes,verification:checked,scope:'Local brand archive only; no production website or app changes, no upload or deployment.'};
    fs.writeFileSync(path.join(repo,'docs/brand/coach-pocket-baseline.json'),JSON.stringify(baseline,null,2)+'\n');
    console.log('Approved Coach Pocket lockup archived with source, original icon, parameters and hashes.');
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
