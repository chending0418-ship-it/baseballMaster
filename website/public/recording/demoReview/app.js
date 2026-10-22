import {scenarios,outcomes,fielders,batter,batterFor,lineups,ballTypes,inferredType,destName,clone,initial,unavailable,draftPlay,draftRunner,draftSpecial,contactError,validate,applyDraft,pitch,undo,redo,beforeState} from './engine.js?v=4';
const $=s=>document.querySelector(s),esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const params=new URLSearchParams(location.search);if(params.has('capture'))document.body.classList.add('capture');
let state=initial(params.get('scenario')),draft=null,base=null,view='',selected=null,editIndex=null,ballType='',contactReturn='',fieldMode='landing',error='',toastTimer,previousFocus;
const causeNames={hit:'随本次击球',steal:'盗垒',wild:'暴投',passed:'捕逸',throw:'趁传进垒',error:'失误进垒',tag:'进垒被触杀',caught:'盗垒失败',pickoff:'牵制出局',hold:'留垒',award:'裁判判给',appeal:'申诉出局',other:'待确认'};
const btn=(text,action,value='',cls='',extra='')=>`<button class="${cls}" data-action="${action}" data-value="${esc(value)}" ${extra}>${text}</button>`;

const context=()=>`${state.inning} 局${state.top?'上':'下'} · ${state.outs} 出局 · ${state.bases.length?state.bases.map(r=>destName(r.start)).join('、')+'有人':'垒上无人'}`;
const routeText=r=>r.length?r.map(n=>fielders[n]).join(' → '):'未补守备';
const defenseText=d=>d.defenseStatus==='noFielding'?'越过围墙 · 无人处理':`${d.route.length?routeText(d.route)+(d.route.length===1&&d.defenseStatus!=='pending'?' · 未传球':''):''}${d.defenseStatus==='pending'?(d.route.length?' · 后续待补':'守备待补'):''}${d.errorFielder?' · 失误：'+fielders[d.errorFielder]:''}`;
const fielderShort=['','投手','捕手','一垒','二垒','三垒','游击','左外','中外','右外'];
const fielderPositions={1:[.5,.70],2:[.5,.91],3:[.84,.65],4:[.65,.49],5:[.16,.65],6:[.35,.49],7:[.22,.23],8:[.5,.12],9:[.78,.23]};
function notify(text){clearTimeout(toastTimer);$('#toast').textContent=text;$('#toast').classList.add('visible');toastTimer=setTimeout(()=>$('#toast').classList.remove('visible'),2600);}
function field(){
 const pos={1:[50,68],2:[50,92],3:[76,62],4:[64,51],5:[24,62],6:[36,51],7:[29,31],8:[50,22],9:[71,31]};
 const bp={1:[69,73],2:[50,52],3:[31,73]};
 return `<div class="field"><svg viewBox="0 0 400 360" preserveAspectRatio="none" aria-hidden="true"><path d="M-45 85 Q200 -98 445 85 L445 178 L200 330 L-45 178Z" fill="#d8ba88"/><path d="M-40 94 Q200 -86 440 94 L440 170 L200 314 L-40 170Z" fill="#2c765b"/><path d="M200 169L289 252L200 333L111 252Z" fill="#d4b783"/><path d="M200 190L267 252L200 313L133 252Z" fill="#397c61"/><path d="M-12 99L200 325L412 99M117 252L200 177L283 252" fill="none" stroke="#fff7dc" stroke-width="3"/><path d="M194 320h12v10l-6 6-6-6Z" fill="white"/><path d="M195 174h10v10h-10ZM114 247h10v10h-10ZM277 247h10v10h-10Z" fill="white"/></svg><span class="field-badge">${state.top?'飞鹰':'海浪'}守备</span><span class="field-badge right">垒上 ${state.bases.length} 人</span>${Object.entries(pos).map(([n,p])=>`<span class="fielder" style="left:${p[0]}%;top:${p[1]}%">${fielderShort[n]}<br>#${n}</span>`).join('')}${state.bases.map(r=>btn('#'+r.number,'runner',r.id,'base-runner',`style="left:${bp[r.start][0]}%;top:${bp[r.start][1]}%" aria-label="${destName(r.start)}跑者 ${r.name}，记录跑垒"`)).join('')}</div>`;
}
function dots(n,count,cls=''){return `<span class="dots">${Array.from({length:n},(_,i)=>`<i class="dot ${cls} ${i<count?'on':''}"></i>`).join('')}</span>`;}
function renderMain(){
 const player=batterFor(state),order=state.battingIndex[state.top?0:1]+1;
 const last=state.history.at(-1);
 $('#app').innerHTML=`<div class="topbar"><h2>现场记分</h2>${btn('◷ 比赛记录','history')}</div><div class="scoreboard"><div><div class="inning">${state.inning} 局${state.top?'上':'下'}</div><div class="score">海浪 <strong>${state.score}</strong> : <strong>${state.home}</strong> 飞鹰</div></div><div class="countboard"><div>练习中 · 已投 ${state.pitches} 球</div>B${dots(3,state.balls)} S${dots(2,state.strikes,'strike')} O${dots(2,state.outs,'out')}</div></div><div class="field-card">${field()}<div class="matchup"><div><small>投手 · 已投 ${state.pitchCounts[state.top?1:0]} 球</small><strong>#1 赵一鸣</strong></div><div class="batter"><small>第 ${order} 棒 · 打者</small><strong>#${player.number} ${player.name}</strong></div></div></div><div class="pitch-grid">${['ball','called','swing','foul'].map((k,i)=>btn(['坏球','看振','挥空','界外'][i],'pitch',k,'pitch-button',state.outs===3?'disabled':'')).join('')}</div>${btn(state.outs===3?'本半局结束 · 继续练习':'⚾ 击球进入场内',state.outs===3?'nextHalf':'hit','','hit-button')}<div class="secondary-grid">${btn('跑者 / 出局','runnerMenu','')}${btn('特殊 / 判罚','special','')}</div><div class="utility-grid">${btn('↶ 撤销','undo','','',!last?'disabled':'')}${btn('↷ 恢复','redo','','',!state.redo.length?'disabled':'')}${btn('修正','history')}${btn('阵容','lineup')}</div><div class="last-play"><span class="latest">${last?`<b>${esc(last.label)}${last.draft?.ballType?' · '+last.draft.ballType:''}</b>　${esc(last.note)}`:'点“击球进入场内”，开始记录这球'}</span>${last?.draft?btn('补充这球','amend'):last?.pitchOnly&&state.bases.length?btn('补跑者','runnerMenu'):''}</div><div class="end-row"><span>V4 · 交互练习</span>${btn('设置练习局面','setup')}</div>`;
}
function shell(title,body,footer='',back='',extra=''){
 if(!$('#overlay').innerHTML)previousFocus=document.activeElement;
 const sameView=$('.sheet')?.dataset.view===view,scroll=sameView?$('.sheet-body').scrollTop:0;
 const focused=sameView?document.activeElement:null,focusAction=focused?.dataset.action,focusValue=focused?.dataset.value;
 $('#overlay').innerHTML=`<div class="scrim" data-action="close"></div><section class="sheet ${['contact','extraRoute'].includes(view)?'field-sheet':''} ${sameView?'updating':''}" data-view="${view}" role="dialog" aria-modal="true" aria-labelledby="sheetTitle"><div class="sheet-handle"></div><div class="sheet-header">${back?btn('‹ 返回','view',back):''}<h3 id="sheetTitle" tabindex="-1">${title}</h3>${extra}${btn('×','close','','close','aria-label="取消并关闭"')}</div><div class="sheet-body">${body}</div>${footer?`<div class="sheet-footer">${footer}</div>`:''}</section>`;
 $('#app').inert=true;$('.sheet-body').scrollTop=scroll;
 const focusTarget=focusAction?[...$('.sheet').querySelectorAll('button:not(:disabled)')].find(b=>b.dataset.action===focusAction&&b.dataset.value===focusValue):null;
 (focusTarget||$('#sheetTitle')).focus({preventScroll:true});
}
function close(){view='';draft=null;base=null;editIndex=null;error='';$('#overlay').innerHTML='';$('#app').inert=false;previousFocus?.focus({preventScroll:true});}
function choose(id){
 const s=base||state;const reason=unavailable(id,s);if(reason){notify(reason);return;}
 draft=draftPlay(id,s);draft.ballType=ballType||inferredType[id]||'';selected=draft.rows.length>3?null:(draft.rows[1]?.id||'b');error='';
 contactReturn='';fieldMode='landing';view='contact';show();
}
function advanceDraft(){if(!draft.requiresReview&&draft.kind==='batted'&&editIndex===null){save();}else{view='review';show();}}
function finishContact(){fieldMode='defense';error='';show();}
function finishField(){const issue=contactError(draft);if(issue){error=issue;show();return;}error='';if(contactReturn){view=contactReturn;contactReturn='';show();}else advanceDraft();}
function locationLabel(x,y){if(y<.46)return x<.37?'左外野':x>.63?'右外野':'中外野';if(y>.85)return '本垒前';if(x<.3)return '三垒侧';if(x>.7)return '一垒侧';if(y<.66)return x<.5?'游击区域':'二垒区域';return '投手前后';}
function contactMap(extra=false){
 const point=draft.location,route=extra?(draft.rows.find(r=>r.id===selected).extraRoute||[]):draft.route,mode=extra?'defense':fieldMode;
 const segments=route.slice(1).map((n,i)=>{const a=fielderPositions[route[i]],b=fielderPositions[n];if(!a||!b)return '';const dx=(b[0]-a[0])*360,dy=(b[1]-a[1])*260,len=Math.hypot(dx,dy);if(len<1)return '';const inset=Math.min(25,len/3);return `<line x1="${a[0]*360+dx/len*inset}" y1="${a[1]*260+dy/len*inset}" x2="${b[0]*360-dx/len*inset}" y2="${b[1]*260-dy/len*inset}" marker-end="url(#throwArrow)"/>`;}).join('');
 return `<div class="play-field ${mode==='landing'?'landing-mode':'defense-mode'}"><button type="button" class="contact-field" ${mode==='landing'?'':'disabled tabindex="-1"'} aria-label="在球场上点选击球落点；键盘操作可展开区域选项"><svg viewBox="0 0 360 260" preserveAspectRatio="none" aria-hidden="true"><path d="M-5 72Q180 -80 365 72L180 251Z" fill="#d5b780"/><path d="M3 77Q180 -68 357 77L180 242Z" fill="#2b7558"/><path d="M180 130L247 192L180 251L113 192Z" fill="#cdb27f"/><path d="M180 148L228 192L180 236L132 192Z" fill="#397c60"/><path d="M0 65L180 246L360 65M116 192L180 134L244 192" fill="none" stroke="#fff5d9" stroke-width="2"/><g fill="white" font-size="12" text-anchor="middle"><text x="79" y="62">左外野</text><text x="180" y="36">中外野</text><text x="281" y="62">右外野</text><text x="110" y="154">游击</text><text x="250" y="154">二垒</text><text x="180" y="205">投手</text></g></svg></button><svg class="throw-lines" viewBox="0 0 360 260" preserveAspectRatio="none" aria-hidden="true"><defs><marker id="throwArrow" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto"><path d="M0 0L6 3L0 6Z" fill="#ffe39a"/></marker></defs><g stroke="#ffe39a" stroke-width="2.5" fill="none">${segments}</g></svg>${mode!=='landing'?Object.entries(fielderPositions).map(([n,p])=>{const orders=route.flatMap((v,i)=>v===Number(n)?[i+1]:[]),marked=!extra&&draft.errorFielder===Number(n),name=fielderShort[n];return btn(`<span>${name}</span><small>${n}</small>${marked?'<i>E</i>':''}`,mode==='error'?'errorFielder':'appendRoute',n,`field-player ${orders.length?'touched':''} ${marked?'has-error':''}`,`style="left:${p[0]*100}%;top:${p[1]*100}%" aria-label="${mode==='error'?'标记失误责任人：':route.length?'传给':'首先处理：'}${fielders[n]}"`);}).join(''):''}${point?`<span class="landing-dot" style="left:${point.x*100}%;top:${point.y*100}%" aria-hidden="true"></span>`:''}<span class="field-instruction">${mode==='landing'?'点选落点':mode==='error'?'点选失误责任人':route.length?'继续点传球对象':'点第一位处理球的人'}</span></div>`;
}
function routeStrip(route){const presets=view==='contact'?(draft.outcome==='groundOut'?[[6,3],[4,3],[5,3]]:draft.outcome==='doublePlay'?[[6,4,3],[4,6,3],[5,4,3]]:[]):[];return `<div class="field-route"><div class="route-scroll" aria-live="polite">${route.length?route.map((n,i)=>`<span><b>${i+1}</b>${fielderShort[n]}</span>`).join('<em>→</em>'):presets.length?presets.map(r=>btn(r.join(' → '),'quickRoute',r.join(','),'quick-route')).join(''):'<span class="route-empty">按实际传接顺序点选守备员</span>'}</div>${btn('↶','popRoute','','route-undo',`aria-label="撤回最后一位守备员" ${route.length?'':'disabled'}`)}</div>`;}
function begin(){if(state.outs===3){notify('本半局已结束，请先继续练习。');return false;}base=clone(state);draft=null;editIndex=null;ballType='';error='';return true;}
function rowsHTML(){return draft.rows.map(r=>`<div class="runner-card">${btn(`<span><small>${r.start?'原'+destName(r.start)+'跑者':'打者'}</small>#${r.number} ${r.name}</span><strong>${r.start>0&&r.dest===r.start?'留在'+destName(r.dest):destName(r.dest)} ${selected===r.id?'⌃':'⌄'}</strong>`,'selectRunner',r.id,'runner-head')}${selected===r.id?`<div class="destinations">${[1,2,3,4,0].filter(n=>n===0||n>=Math.max(1,r.start)).map(n=>btn(r.start>0&&n===r.start?'留垒':destName(n),'dest',n,n===r.dest?'active':'')).join('')}</div>${(r.start===0||r.dest!==r.start)?btn(`${causeNames[r.cause]||r.cause||'选择原因'}${r.extraRoute?.length?' · '+routeText(r.extraRoute):''}　›`,'cause',r.id,'cause'):''}`:''}</div>`).join('');}
function preview(){const newOut=draft.rows.filter(r=>r.dest===0).length;const runs=['after','force'].includes(draft.timing)?0:draft.rows.filter(r=>r.dest===4).length;const on=draft.rows.filter(r=>r.dest>0&&r.dest<4);return `${base.top?base.score+runs:base.score} : ${base.top?base.home:base.home+runs} · ${base.outs+newOut} 出局 · ${on.length?on.map(r=>destName(r.dest)).join('、')+'有人':'垒上无人'}`;}
function show(){
 const ctx=`<div class="context">${context()} · 打者 #${batterFor(base||state).number} ${batterFor(base||state).name}</div>`;
 if(view==='entry')shell('记录击球',ctx+`<div class="required-note">球种、落点、守备 · 在同一个球场记录</div><div class="choice-grid">${['single','double','flyOut','groundOut','error','doublePlay'].map(id=>{const o=outcomes.find(x=>x.id===id);return btn(o.name,'outcome',id);}).join('')}</div><div class="link-block">${btn('按击球过程记录 <span>球型 → 处理 ›</span>','view','process','sheet-row')}${btn('结果待确认 <span>仍需球种与落点 ›</span>','outcome','other','sheet-row')}</div><p class="fine">点落点，再点守备员；有跑者时统一核对。</p>`,'','',btn('全部击球','view','catalog'));
 if(view==='catalog')catalog('');
 if(view==='contact'){
  const fixed=inferredType[draft.outcome],types=ballTypes.filter(t=>t!=='界外飞球'||fixed==='界外飞球'||['other','pendingOut'].includes(draft.outcome));
  const issue=contactError(draft),ready=!issue;
  shell(draft.label,`<div class="type-chips" aria-label="击球球种">${types.map(t=>btn(t.replace('滚球','滚').replace('飞球','飞'),'requiredType',t,t===draft.ballType?'active':'',`${fixed&&fixed!==t?'disabled':''} aria-pressed="${t===draft.ballType}"`)).join('')}</div><div class="field-tabs" aria-label="球场记录内容">${btn((draft.location?'✓ ':'')+'落点','fieldMode','landing',fieldMode==='landing'?'active':'',`aria-pressed="${fieldMode==='landing'}"`)}${btn((draft.route.length||draft.defenseStatus?'✓ ':'')+'守备 / 传球','fieldMode','defense',fieldMode==='defense'?'active':'',`aria-pressed="${fieldMode==='defense'}"`)}${draft.outcome==='error'?btn((draft.errorFielder?'✓ ':'')+'失误责任','fieldMode','error',fieldMode==='error'?'active':'',`aria-pressed="${fieldMode==='error'}"`):''}</div>${contactMap()}${routeStrip(draft.route)}<div class="field-status">${draft.location?'落点：'+draft.location.label:'落点待记录'}<span>${draft.errorFielder?'失误：'+fielders[draft.errorFielder]:draft.defenseStatus==='pending'?'守备待补':draft.defenseStatus==='noFielding'?'越过围墙 · 无人处理':draft.route.length===1?'由'+fielders[draft.route[0]]+'处理 · 未传球':draft.route.length>1?'已记录 '+(draft.route.length-1)+' 次传球':'守备待记录'}</span></div>${error?`<div class="error" role="alert">${esc(error)}</div>`:''}<details class="landing-alternative"><summary>区域选择 / 其他处理</summary><div class="f-grid">${[['左外野','0.24,0.27'],['中外野','0.5,0.14'],['右外野','0.76,0.27'],['三垒侧','0.18,0.64'],['游击区域','0.42,0.55'],['二垒区域','0.6,0.55'],['一垒侧','0.82,0.64'],['投手前后','0.5,0.75'],['本垒前','0.5,0.9']].map(([t,v])=>btn(t,'locationPreset',v)).join('')}</div>${draft.outcome==='homeRun'?btn('越过围墙，无人处理','defenseStatus','noFielding','sheet-row'):''}${btn('没看清守备，明确标记待补','defenseStatus','pending','sheet-row')}${btn('清空守备路线，重新记录','clearRoute','','sheet-row',draft.route.length||draft.defenseStatus?'':'disabled')}</details>`,`${!ready?`<div class="field-hint">${!draft.ballType?'请选择球种':!draft.location?'请在球场上标记落点':!draft.defenseStatus?'请点选第一位处理球的守备员':issue}</div>`:''}${btn(contactReturn?'完成修改':draft.requiresReview?'下一步 · 核对跑者':'完成本球','finishField','','primary',ready?'':'disabled')}`,contactReturn||'entry');
 }
 if(view==='process')shell('是什么球？',ctx+`<div class="choice-grid">${ballTypes.map(t=>btn(t,'ballType',t)).join('')}</div><p class="fine">随后选择处理结果，已选球型自动带入。</p>`,'','entry');
 if(view==='processResult')shell(ballType+' · 结果',`<div class="choice-grid">${(ballType==='地滚球'?['single','groundOut','error','fieldersChoice','doublePlay','other']:ballType==='触击'?['sacrificeBunt','single','groundOut','error','fieldersChoice','other']:ballType==='平飞球'?['single','lineOut','double','error','other']:ballType==='界外飞球'?['foulFlyOut','other']:['single','flyOut','double','sacrificeFly','error','other']).map(id=>btn(outcomes.find(o=>o.id===id).name,'outcome',id)).join('')}</div>`,'','process');
 if(view==='extraRoute'){
  const row=draft.rows.find(x=>x.id===selected),route=row.extraRoute||[];
  shell('补充跑垒处理',`<div class="context">#${row.number} ${row.name} · ${causeNames[row.cause]} · 依次点实际处理的人</div>${draft.route.length?btn('沿用本球路线：'+routeText(draft.route),'reuseRoute','','reuse-route'):''}${contactMap(true)}${routeStrip(route)}<div class="fine">本次跑垒的处理单独保留；不会改变原本的安打结果。</div>`,btn('完成路线','finishRoute','','primary',route.length?'':'disabled'),'review');
 }
 if(view==='review'){
  shell(draft.kind==='runner'?'核对跑者变化':'核对本球',`<span class="badge">${editIndex===null?'尚未提交':'修改刚才这球 · 保存后替换'}</span><div class="summary"><strong>${draft.label}${draft.ballType?' · '+draft.ballType:''}</strong>${draft.kind==='batted'?defenseText(draft):'只核对与现场不同的去向'}</div>${draft.kind==='batted'?btn('落点：'+(draft.location?.label||'待记录')+' · 修改球场记录','editContact','','detail-link'):''}${error?`<div class="error" role="alert">${esc(error)}</div>`:''}${rowsHTML()}<p class="fine">${draft.kind==='runner'?'沿用当前打者和球数；不新增投球。':'进垒是建议值，按现场调整；点击姓名展开去向。'}</p>`, `<div class="context">记录后：${preview()}</div>${btn(editIndex===null?'确认本球':'保存本球修改','save','','primary')}`,draft.kind==='batted'?'entry':draft.kind==='runner'?'runnerList':'special');
 }
 if(view==='runnerList')shell('选择跑者',`<div class="context">${context()} · 跑者事件不增加投球数</div>${state.bases.length?state.bases.map(r=>btn(`<strong>原${destName(r.start)} · #${r.number} ${r.name}</strong><span>记录变化 ›</span>`,'runner',r.id,'sheet-row')).join(''):'<p class="fine">当前垒上无人，先选择一个有跑者的练习局面。</p>'}`);
 if(view==='cause'){
  const row=draft.rows.find(x=>x.id===selected),out=row.dest===0;
  const choices=out?['tag','caught','pickoff','appeal','other']:(draft.kind==='runner'?['steal','wild','passed','throw','error','award','other']:['hit','throw','error','award','other']);
  shell(`${row.name} · ${destName(row.dest)}`,`<div class="context">${out?'这名跑者为什么出局？':'这次进垒的原因是什么？'}</div><div class="choice-grid">${choices.map(c=>btn(causeNames[c]+(['wild','passed'].includes(c)&&!base.history.at(-1)?.pitchOnly?'<small>先记本次投球后可选</small>':''),'setCause',c,'',['wild','passed'].includes(c)&&!base.history.at(-1)?.pitchOnly?'disabled':'')).join('')}</div>`,'','review');
 }
 if(view==='timing')shell('第三出局，核对得分',`<div class="error">本球同时出现第三出局和跑者回本垒，需要按实际出局性质与先后核对。</div>${btn('第三出局为封杀 / 打者到一垒前出局 <span>本球不得分</span>','timing','force','sheet-row')}${btn('第三出局为其他触杀，得分在先 <span>本球计分</span>','timing','before','sheet-row')}${btn('第三出局在先，回本垒在后 <span>本球不得分</span>','timing','after','sheet-row')}<p class="fine">涉及申诉、离垒或判罚，请依裁判裁定；无法确定时保留草稿。</p>`,'','review');
 if(view==='special')shell('特殊 / 判罚',`<div class="choice-grid">${btn('触身球','specialPick','hbp')}${btn('故意保送','specialPick','ibb')}${btn('三振未接住<small>第三好球在这里一并记录</small>','specialPick','dropped')}${btn('判罚与其他情况<small>按分类查找</small>','view','penalties')}</div><p class="fine">涉及跑者时，同屏核对受影响的人；无须逐个重新录入。</p>`);
 if(view==='penalties')shell('判罚与其他情况',`<div class="context">完整 App 继续保留这些入口；此演示展示检索与位置核对，不模拟全部判罚统计。</div>${['投手犯规 / 违规投球','捕手妨碍 / 防守阻挡','打者干扰 / 打击次序','跑者干扰 / 申诉判罚','TB 突破僵局 / 规则调整'].map(t=>btn(t+' <span>查看 ›</span>','penaltyInfo',t,'sheet-row')).join('')}`,'','special');
 if(view==='scenarios')shell('换一个局面试试',`<div class="context">切换会开始一份新的练习记录。</div>${scenarios.map(s=>btn(`<strong>${s.title}</strong><span>${s.id===state.scenario?'当前':'›'}</span>`,'scenario',s.id,'sheet-row')).join('')}`);
 if(view==='history')shell('本次练习记录',state.history.length?state.history.map((e,i)=>`<div class="history-item"><strong>${i+1}. ${e.label}${e.draft?.ballType?' · '+e.draft.ballType:''}</strong><p>${esc(e.note)}</p>${e.draft?`<p>${e.draft.location?'落点：'+e.draft.location.label+'<br>':''}${e.draft.kind==='batted'?'守备：'+defenseText(e.draft):''}${e.draft.rows.filter(r=>r.extraRoute?.length).map(r=>`<br>#${r.number} 进垒处理：${routeText(r.extraRoute)}`).join('')}</p><p>本球投球 +${e.draft.addPitch} · 得分 +${e.runs}</p>`:''}${i===state.history.length-1&&e.draft?btn('补充 / 修改这球','amend'):''}</div>`).reverse().join(''):'<p class="fine">还没有记录。选择一个练习局面开始。</p>');
 if(view==='setup'){
  const num=(key,label,val,min,max)=>`<label class="setup-field">${label}<input id="setup-${key}" type="number" inputmode="numeric" value="${val}" min="${min}" max="${max}" required></label>`;
  shell('自由设置局面',`<div class="context">从任意局面开始练习。应用后会清空当前练习记录。</div><div class="setup-grid">${num('inning','局数',state.inning,1,99)}<label class="setup-field">攻守<select id="setup-half"><option value="top" ${state.top?'selected':''}>上半局 · 海浪进攻</option><option value="bottom" ${state.top?'':'selected'}>下半局 · 飞鹰进攻</option></select></label>${num('away','海浪得分',state.score,0,99)}${num('home','飞鹰得分',state.home,0,99)}${num('outs','出局数',Math.min(2,state.outs),0,2)}${num('order','当前棒次',state.battingIndex[state.top?0:1]+1,1,9)}${num('balls','坏球',state.balls,0,3)}${num('strikes','好球',state.strikes,0,2)}</div><div class="category">垒上跑者</div><div class="setup-bases">${[1,2,3].map(n=>`<label><input type="checkbox" id="setup-base${n}" ${state.bases.some(r=>r.start===n)?'checked':''}>${destName(n)}有人</label>`).join('')}</div><div id="setup-error" class="fine" role="alert"></div>`,btn('从此局面开始','applySetup','','primary'));
 }
 if(view==='lineup')shell('当前打线',`<div class="context">合成阵容，按打序自动轮换。换人沿用正式 App 的阵容入口。</div>${lineups[state.top?0:1].map((r,i)=>`<div class="sheet-row">${i+1} 棒 · #${r.number} ${r.name}<span>${i===state.battingIndex[state.top?0:1]?'当前打者':''}</span></div>`).join('')}`);
}
function catalog(term){const items=outcomes.filter(x=>x.name.includes(term));const body=`<input class="search" aria-label="搜索击球结果" placeholder="搜索：牺牲、野手选择、待确认…" value="${esc(term)}"><div id="catalogResults">${[...new Set(items.map(x=>x.group))].map(g=>`<div class="category">${g}</div><div class="catalog-grid">${items.filter(x=>x.group===g).map(o=>{const reason=unavailable(o.id,base||state);return btn(o.name+(reason?'<small>'+reason+'</small>':''),'outcome',o.id,'',reason?'disabled':'');}).join('')}</div>`).join('')||'<p class="fine">没有匹配结果，请试试“失误”或“接杀”。</p>'}</div>`;shell('全部击球',body,'','entry');}
function save(){
 const err=validate(draft,base);if(err==='third-out'){view='timing';show();return;}if(err){error=err;view='review';show();return;}
 const label=draft.label;
 if(editIndex!==null){const history=state.history.slice(0,editIndex);state={...clone(base),history,redo:[]};}
 applyDraft(state,draft);close();renderMain();notify(label+' · 已记录，可撤销');
}
function amend(){const event=state.history.at(-1);if(!event?.draft)return;editIndex=state.history.length-1;base={...clone(event.before),history:clone(state.history.slice(0,-1)),redo:[]};draft=clone(event.draft);selected=draft.rows[0].id;error='';view='review';show();}
function action(a,v){
 if(a==='locationPreset'){const [x,y]=v.split(',').map(Number);draft.location={x,y,label:locationLabel(x,y),precision:'region'};finishContact();return;}
 if(a==='applySetup'){
  const read=k=>Number($('#setup-'+k).value),rules={inning:[1,99],away:[0,99],home:[0,99],outs:[0,2],order:[1,9],balls:[0,3],strikes:[0,2]};
  if(Object.entries(rules).some(([k,[min,max]])=>!$('#setup-'+k).value||!Number.isInteger(read(k))||read(k)<min||read(k)>max)){$('#setup-error').textContent='请填写范围内的整数；出局 0–2、坏球 0–3、好球 0–2。';return;}
  const next=initial();next.inning=read('inning');next.top=$('#setup-half').value==='top';next.score=read('away');next.home=read('home');next.outs=read('outs');next.balls=read('balls');next.strikes=read('strikes');next.battingIndex[next.top?0:1]=read('order')-1;
  const lineup=lineups[next.top?0:1],at=next.battingIndex[next.top?0:1];
  next.bases=[1,2,3].filter(n=>$('#setup-base'+n).checked).map((n,i)=>({...lineup[(at-i-1+9)%9],id:'r'+n,start:n}));state=next;close();renderMain();history.replaceState(null,'',location.pathname);notify('局面已就绪，可以自由记分');return;
 }
 if(a==='close'){close();return;}
 if(a==='view'){view=v;error='';show();return;}
 if(a==='hit'){if(begin()){view='entry';show();}return;}
 if(a==='outcome'){choose(v);return;}
 if(a==='requiredType'){draft.ballType=v;ballType=v;error='';show();return;}
 if(a==='editContact'){contactReturn=view;fieldMode='defense';view='contact';error='';show();return;}
 if(a==='fieldMode'){fieldMode=v;show();return;}
 if(a==='finishField'){finishField();return;}
 if(a==='defenseStatus'){draft.defenseStatus=v;if(v==='noFielding')draft.route=[];fieldMode='defense';error='';show();return;}
 if(a==='quickRoute'){draft.route=v.split(',').map(Number);draft.defenseStatus='recorded';fieldMode='defense';error='';show();return;}
 if(a==='clearRoute'){draft.route=[];delete draft.defenseStatus;fieldMode='defense';error='';show();return;}
 if(a==='errorFielder'){draft.errorFielder=Number(v);fieldMode='defense';error='';show();return;}
 if(a==='reuseRoute'){draft.rows.find(r=>r.id===selected).extraRoute=clone(draft.route);view='review';show();return;}
 if(a==='ballType'){ballType=v;view='processResult';show();return;}
 if(a==='appendRoute'){if(view==='extraRoute'){const row=draft.rows.find(r=>r.id===selected);row.extraRoute??=[];if(row.extraRoute.at(-1)===Number(v))return;row.extraRoute.push(Number(v));}else{if(draft.route.at(-1)===Number(v))return;draft.route.push(Number(v));draft.defenseStatus='recorded';error='';}show();return;}
 if(a==='popRoute'){if(view==='extraRoute')draft.rows.find(r=>r.id===selected).extraRoute?.pop();else{draft.route.pop();if(!draft.route.length)delete draft.defenseStatus;}show();return;}
 if(a==='finishRoute'){view='review';show();return;}
 if(a==='selectRunner'){selected=selected===v?null:v;show();return;}
 if(a==='dest'){const row=draft.rows.find(r=>r.id===selected);row.dest=Number(v);draft.timing=null;error='';if(row.start>0&&row.dest===row.start){row.cause='hold';delete row.extraRoute;show();return;}if(draft.kind==='runner'||row.dest===0){row.cause='';view='cause';}else if(!row.cause||row.cause==='hold'){row.cause='hit';}show();return;}
 if(a==='cause'){selected=v;view='cause';show();return;}
 if(a==='setCause'){const row=draft.rows.find(r=>r.id===selected);row.cause=v;if(draft.kind==='runner')draft.label=causeNames[v];if(['tag','caught','pickoff','error'].includes(v)){row.extraRoute??=[];view='extraRoute';}else view='review';show();return;}
 if(a==='save'){save();return;}
 if(a==='timing'){draft.timing=v;view='review';error='';show();return;}
 if(a==='runner'||a==='runnerMenu'){if(!begin())return;if(a==='runner'&&state.bases.some(x=>x.id===v)){draft=draftRunner(base,v);selected=v;view='review';}else view='runnerList';show();return;}
 if(a==='special'){if(begin()){view='special';show();}return;}
 if(a==='specialPick'){if(v==='dropped'&&(base.strikes!==2||(base.outs<2&&base.bases.some(r=>r.start===1)))){notify('两好球，且一垒无人或已两出局时，可试本路径。');return;}draft=draftSpecial(base,v);selected=draft.rows.length>3?null:(draft.rows[1]?.id||'b');view='review';show();return;}
 if(a==='pitch'){try{pitch(state,v);renderMain();notify(state.history.at(-1).label+' · 已记录');}catch(e){notify(e.message);}return;}
 if(a==='undo'||a==='redo'){close();if(a==='undo')undo(state);else redo(state);renderMain();notify(a==='undo'?'已撤销整次记录':'已恢复记录');return;}
 if(a==='amend'){amend();return;}
 if(a==='history'||a==='lineup'||a==='scenarios'||a==='setup'){view=a;show();return;}
 if(a==='scenario'){state=initial(v);close();renderMain();history.replaceState(null,'',location.pathname+'?scenario='+encodeURIComponent(v)+(params.has('capture')?'&capture=1':''));return;}
 if(a==='penaltyInfo'){shell(esc(v),'<div class="summary">正式方案：选具体判罚 → 核对裁判判给的打者与跑者位置 → 预览得分和出局 → 一次提交。</div><p class="fine">本 demo 已实现触身球、故意保送、三振未接住；此分类展示低频入口，不代替完整规则引擎。</p>','','penalties');return;}
 if(a==='nextHalf'){const old=beforeState(state);state.bases=[];state.outs=0;state.balls=0;state.strikes=0;state.top=!state.top;if(state.top)state.inning++;state.history.push({before:old,label:'开始下一半局',note:'清空垒况及球数',pitchOnly:false});state.redo=[];renderMain();}
}
document.addEventListener('click',e=>{const target=e.target.closest('[data-action]');if(target&&!target.disabled)action(target.dataset.action,target.dataset.value);});
document.addEventListener('click',e=>{const map=e.target.closest('.contact-field');if(!map||map.disabled||!draft||view!=='contact')return;if(e.detail===0&&e.clientX===0&&e.clientY===0){const alt=$('.landing-alternative');alt.open=true;alt.querySelector('button').focus();return;}const r=map.getBoundingClientRect(),x=Math.max(0,Math.min(1,(e.clientX-r.left)/r.width)),y=Math.max(0,Math.min(1,(e.clientY-r.top)/r.height));draft.location={x:Math.round(x*1000)/1000,y:Math.round(y*1000)/1000,label:locationLabel(x,y),precision:'point'};finishContact();});
$('#scenarioPicker').addEventListener('click',()=>action('setup'));
$('#resetScenario').addEventListener('click',()=>{state=initial();close();renderMain();history.replaceState(null,'',location.pathname);notify('已从 1 局上重新开始');});
document.addEventListener('input',e=>{if(e.target.matches('.search')){const term=e.target.value,start=e.target.selectionStart;catalog(term);$('.search').focus();$('.search').setSelectionRange(start,start);}});
document.addEventListener('keydown',e=>{if(!$('.sheet'))return;if(e.key==='Escape'){close();return;}if(e.key==='Tab'){const items=[...$('.sheet').querySelectorAll('button:not(:disabled),input')];if(!items.length)return;const first=items[0],last=items.at(-1);if(e.shiftKey&&(document.activeElement===first||document.activeElement.id==='sheetTitle')){e.preventDefault();last.focus();}else if(!e.shiftKey&&(document.activeElement===last||document.activeElement.id==='sheetTitle')){e.preventDefault();first.focus();}}});
window.recordingReview={getState:()=>clone(state),getDraft:()=>clone(draft)};
renderMain();
