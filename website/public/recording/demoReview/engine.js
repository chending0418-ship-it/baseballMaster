// Independent, browser-memory review model. No network or production game APIs.
export const batter={id:'b',number:5,name:'许嘉树',start:0};
export const lineups=[[
 {number:18,name:'林子轩'},{number:7,name:'周子墨'},{number:23,name:'陈昊'},{number:9,name:'陆承宇'},
 {number:5,name:'许嘉树'},{number:12,name:'吴星辰'},{number:16,name:'谢明远'},{number:21,name:'刘嘉宁'},{number:3,name:'沈予安'}
],[{number:2,name:'张云帆'},{number:6,name:'李牧'},{number:8,name:'王一舟'},{number:10,name:'陈远'},{number:11,name:'江川'},{number:15,name:'梁景'},{number:17,name:'程野'},{number:20,name:'唐越'},{number:26,name:'顾青'}]];
export const batterFor=s=>({...lineups[s.top?0:1][s.battingIndex[s.top?0:1]],id:'b',start:0});
export const ballTypes=['地滚球','高飞球','平飞球','触击','界外飞球'];
export const inferredType={groundOut:'地滚球',flyOut:'高飞球',lineOut:'平飞球',foulFlyOut:'界外飞球',infieldFly:'高飞球',sacrificeBunt:'触击',sacrificeFly:'高飞球'};
const runner=(start,number,name)=>({id:'r'+start,start,number,name});
export const scenarios=[
 {id:'single',title:'01 · 一垒安打',brief:'无人上垒，打者一垒安打。',bases:[],outs:0},
 {id:'ground',title:'02 · 游击传一垒',brief:'无人上垒，游击手传一垒，打者出局。',bases:[],outs:0},
 {id:'fly',title:'03 · 中外野接杀',brief:'无人上垒，中外野手接住高飞球。',bases:[],outs:0},
 {id:'advance',title:'04 · 安打，跑者到三垒',brief:'一垒有人；打者一垒安打，原一垒跑者跑到三垒。',bases:[1],outs:0},
 {id:'doubleplay',title:'05 · 6-4-3 双杀',brief:'0 出局、一垒有人；游击 → 二垒 → 一垒完成双杀。',bases:[1],outs:0},
 {id:'sacfly',title:'06 · 牺牲高飞',brief:'1 出局、三垒有人；中外野接杀，跑者合法再触垒后得分。',bases:[3],outs:1},
 {id:'error',title:'07 · 失误上垒',brief:'无人上垒，游击手接球失误，打者安全到一垒。',bases:[],outs:0},
 {id:'compound',title:'08 · 安打后进垒出局',brief:'二垒有人；一垒安打送回跑者，打者冲二垒被中外野传二垒触杀。',bases:[2],outs:0},
 {id:'steal',title:'09 · 盗二垒',brief:'一垒跑者成功盗二垒；没有新增投球。',bases:[1],outs:0},
 {id:'wild',title:'10 · 坏球后暴投',brief:'先记一颗坏球，再记暴投让一垒跑者到二垒；同一球只算一次。',bases:[1],outs:0},
 {id:'hbp',title:'11 · 满垒触身球',brief:'满垒；打者获触身球保送，原三垒跑者被迫回本垒得分。',bases:[1,2,3],outs:0},
 {id:'dropped',title:'12 · 三振未接住',brief:'2 出局、2 好球；第三好球未接住，打者安全到一垒。',bases:[],outs:2,strikes:2},
 {id:'timing',title:'13 · 第三出局与得分',brief:'1 出局、一三垒有人；高飞接杀后，一垒跑者离垒被触杀，三垒跑者先回本垒。',bases:[1,3],outs:1}
];
export const outcomes=[
 {id:'single',name:'一垒安打',group:'安打',dest:1}, {id:'double',name:'二垒安打',group:'安打',dest:2},
 {id:'triple',name:'三垒安打',group:'安打',dest:3}, {id:'homeRun',name:'本垒打',group:'安打',dest:4},
 {id:'groundOut',name:'滚地出局',group:'接杀与出局',dest:0,defense:'route'},
 {id:'flyOut',name:'高飞接杀',group:'接杀与出局',dest:0,defense:'fielder'},
 {id:'lineOut',name:'平飞接杀',group:'接杀与出局',dest:0,defense:'fielder'},
 {id:'foulFlyOut',name:'界外飞球接杀',group:'接杀与出局',dest:0,defense:'fielder'},
 {id:'infieldFly',name:'内野高飞必死',group:'接杀与出局',dest:0,defense:'fielder'},
 {id:'sacrificeBunt',name:'牺牲触击',group:'战术与多人出局',dest:0,defense:'route'},
 {id:'sacrificeFly',name:'牺牲高飞',group:'战术与多人出局',dest:0,defense:'fielder'},
 {id:'doublePlay',name:'双杀',group:'战术与多人出局',dest:0,defense:'double'},
 {id:'triplePlay',name:'三杀',group:'战术与多人出局',dest:0,defense:'triple'},
 {id:'error',name:'守备失误',group:'其他结果',dest:1,defense:'error'},
 {id:'fieldersChoice',name:'野手选择',group:'其他结果',dest:1,defense:'route'},
 {id:'runnerTagOut',name:'触杀其他跑者',group:'其他结果',dest:1,defense:'route'},
 {id:'pendingOut',name:'待确认出局',group:'待确认',dest:0},
 {id:'pending',name:'待确认上垒',group:'待确认',dest:1},
 {id:'other',name:'其他 / 待补',group:'待确认',dest:1}
];
export const fielders=['','投手','捕手','一垒手','二垒手','三垒手','游击手','左外野手','中外野手','右外野手'];
export const destName=n=>n===0?'出局':n===4?'得分':['','一垒','二垒','三垒'][n];
export const clone=x=>JSON.parse(JSON.stringify(x));
export function initial(id){const sc=scenarios.find(x=>x.id===id)||scenarios[0];return {scenario:sc.id,score:0,home:0,inning:1,top:true,outs:sc.outs,balls:0,strikes:sc.strikes||0,pitches:0,pitchCounts:[0,0],battingIndex:[4,0],bases:sc.bases.map(b=>runner(b,{1:18,2:7,3:23}[b],{1:'林子轩',2:'周子墨',3:'陈昊'}[b])),history:[],redo:[]};}
export function unavailable(id,s){
 if(id==='infieldFly'&&!(s.outs<2&&s.bases.some(x=>x.start===1)&&s.bases.some(x=>x.start===2)))return '需少于 2 出局，且一、二垒有人';
 if(id==='sacrificeFly'&&!(s.outs<2&&s.bases.some(x=>x.start===3)))return '此演示需少于 2 出局且三垒有人';
 if(id==='sacrificeBunt'&&!(s.outs<2&&s.bases.length))return '需少于 2 出局且垒上有人';
 if(id==='doublePlay'&&!(s.outs<2&&s.bases.length))return '需少于 2 出局且至少 1 名跑者';
 if(id==='triplePlay'&&!(s.outs===0&&s.bases.length>=2))return '需 0 出局且至少 2 名跑者';
 return '';
}
export function draftPlay(id,s){
 const o=outcomes.find(x=>x.id===id);if(!o)throw Error('Unknown outcome');
 const rows=[{...batterFor(s),dest:o.dest,cause:'本次击球'},...s.bases.map(r=>({...r,dest:r.start,cause:'本次击球'}))];
 for(const r of rows.slice(1)){
  if(['single','double','triple','homeRun'].includes(id))r.dest=Math.min(4,r.start+o.dest);
  if(id==='sacrificeFly'&&r.start===3)r.dest=4;
  if(id==='sacrificeBunt')r.dest=Math.min(4,r.start+1);
  if(id==='error'||id==='pending'){let forced=true;for(let i=1;i<=r.start;i++)if(!s.bases.some(b=>b.start===i))forced=false;if(forced)r.dest=r.start+1;}
  if(id==='doublePlay'&&s.bases.length===1)r.dest=0;
  if(id==='triplePlay')r.dest=0;
 }
 return {kind:'batted',outcome:id,label:o.name,route:[],rows,addPitch:1,requiresReview:s.bases.length>0||['error','pending','pendingOut','other','fieldersChoice','runnerTagOut','infieldFly'].includes(id),timing:null,ballType:inferredType[id]||''};
}
export function draftRunner(s,id){return {kind:'runner',outcome:'runner',label:'跑者变化',route:[],rows:s.bases.map(r=>({...r,dest:r.start,cause:''})),addPitch:0,selected:id||s.bases[0]?.id,timing:null};}
export function draftSpecial(s,id){
 const label={hbp:'触身球',ibb:'故意保送',dropped:'三振未接住'}[id];
 const d=draftPlay('single',s);d.kind='special';d.outcome=id;d.label=label;d.requiresReview=true;d.addPitch=id==='ibb'?0:1;
 for(const r of d.rows.slice(1)){r.dest=r.start; if(id!=='dropped'){let forced=true;for(let b=1;b<=r.start;b++)if(!s.bases.some(x=>x.start===b))forced=false;if(forced)r.dest=r.start+1;}r.cause=id==='dropped'?'本次跑垒':'被迫进垒';}
 return d;
}
// A location is an observation about the ball, never an inferred fielder.
// An explicit pending record keeps an uncertain play usable without inventing touches.
export function contactError(d){
 if(d.kind==='batted'&&!ballTypes.includes(d.ballType))return '请先记录球种，再提交本次击球。';
 if(d.kind==='batted'&&(!d.location||!Number.isFinite(d.location.x)||!Number.isFinite(d.location.y)||d.location.x<0||d.location.x>1||d.location.y<0||d.location.y>1))return '请先在球场上标记击球落点。';
 if(d.kind==='batted'&&inferredType[d.outcome]&&inferredType[d.outcome]!==d.ballType)return '球种与结果不一致，请改选球种或结果。';
 if(d.kind==='batted'&&d.ballType==='界外飞球'&&!['foulFlyOut','pendingOut','other'].includes(d.outcome))return '界外飞球不能记录为该结果，请核对球种。';
 if(d.kind!=='batted')return '';
 if(!Array.isArray(d.route)||d.route.some(n=>!Number.isInteger(n)||n<1||n>9))return '请核对守备员与传球顺序。';
 if(!['recorded','pending','noFielding'].includes(d.defenseStatus))return '请记录第一位处理球的守备员和传球顺序。';
 if(d.defenseStatus==='recorded'&&!d.route.length)return '请先点选处理球的守备员。';
 if(d.defenseStatus==='noFielding'&&(d.outcome!=='homeRun'||d.route.length))return '只有越过围墙、无人处理的本垒打可使用此选项。';
 if(d.errorFielder!==undefined&&(!Number.isInteger(d.errorFielder)||d.errorFielder<1||d.errorFielder>9))return '请核对失误责任人。';
 if(d.outcome==='error'&&d.defenseStatus!=='pending'&&!d.errorFielder)return '请在同一球场上标记失误责任人。';
 return '';
}
export function validate(d,s){
 const contact=contactError(d);if(contact)return contact;
 const safe=d.rows.filter(x=>x.dest>0&&x.dest<4);
 if(new Set(safe.map(x=>x.dest)).size!==safe.length)return '两名跑者不能停在同一垒，请调整去向。';
 if(d.rows.some(x=>x.dest>0&&x.dest<x.start))return '目的地不能早于起始垒。';
 const newOut=d.rows.filter(x=>x.dest===0).length;
 if(s.outs+newOut>3)return '本半局出局数超过 3，请核对出局者。';
 if(d.outcome==='doublePlay'&&newOut!==2)return '请明确本次双杀的两名出局者。';
 if(d.outcome==='triplePlay'&&newOut!==3)return '请明确本次三杀的三名出局者。';
 if(['groundOut','flyOut','lineOut','foulFlyOut','sacrificeBunt','sacrificeFly','infieldFly'].includes(d.outcome)&&d.rows[0].dest!==0)return '该结果要求打者出局；请改选打击结果。';
 if(d.kind==='runner'&&!d.rows.some(x=>x.dest!==x.start))return '还没有跑者变化，请先选择去向。';
 if(d.rows.some(x=>x.dest!==x.start&&!x.cause))return '请补充变化原因。';
 if(['wild','passed'].some(c=>d.rows.some(r=>r.cause===c))&&!s.history.at(-1)?.pitchOnly)return '先记录本次投球，再补暴投 / 捕逸。';
 if(d.outcome==='dropped'&&s.strikes!==2)return '请在两好球时记录三振未接住。';
 if(d.outcome==='dropped'&&s.outs<2&&s.bases.some(x=>x.start===1))return '少于两出局且一垒有人，打者不能由此上垒。';
 if(s.outs+newOut===3&&d.rows.some(x=>x.dest===4)&&!d.timing)return 'third-out';
 return '';
}
export function beforeState(s){const {history,redo,...snapshot}=s;return clone(snapshot);}
export function applyDraft(s,d){
 const err=validate(d,s);if(err)throw Error(err);
 const before=beforeState(s);let runs=d.rows.filter(x=>x.dest===4).length;
 if(d.timing==='force'||d.timing==='after')runs=0;
 if(s.top)s.score+=runs;else s.home+=runs;
 s.outs+=d.rows.filter(x=>x.dest===0).length;s.pitches+=d.addPitch;s.pitchCounts[s.top?1:0]+=d.addPitch;
 if(d.kind!=='runner'){s.balls=0;s.strikes=0;s.battingIndex[s.top?0:1]=(s.battingIndex[s.top?0:1]+1)%9;}
 s.bases=d.rows.filter(x=>x.dest>0&&x.dest<4).map(x=>({id:x.id==='b'?'b'+s.history.length:x.id,number:x.number,name:x.name,start:x.dest}));
 const note=d.rows.map(r=>`#${r.number} ${destName(r.dest)}`).join(' · ');
 s.history.push({before,draft:clone(d),label:d.label,note,runs,pitchOnly:false});s.redo=[];
 return s;
}
export function pitch(s,kind){
 if(s.outs===3)throw Error('先开始下半局');
 const before=beforeState(s);s.pitches++;s.pitchCounts[s.top?1:0]++;
 const labels={ball:'坏球',called:'看振',swing:'挥空',foul:'界外'};
 if(kind==='ball')s.balls++;else if(kind!=='foul'||s.strikes<2)s.strikes++;
 let label=labels[kind];
 if(s.balls===4){s.balls=0;s.strikes=0;label='四坏保送';const d=draftSpecial({...s,history:[]},'hbp');d.rows[0].id='b'+s.history.length;const scored=d.rows.filter(r=>r.dest===4).length;if(s.top)s.score+=scored;else s.home+=scored;s.bases=d.rows.filter(r=>r.dest>0&&r.dest<4).map(r=>({id:r.id,number:r.number,name:r.name,start:r.dest}));}
 if(s.strikes===3){s.outs++;s.balls=0;s.strikes=0;label=kind==='called'?'三振（看振）':'三振（挥空）';}
 if(label==='四坏保送'||label.startsWith('三振'))s.battingIndex[s.top?0:1]=(s.battingIndex[s.top?0:1]+1)%9;
 s.history.push({before,label,note:`本次投球 +1 · 已投 ${s.pitches} 球`,pitchOnly:true,kind});s.redo=[];return s;
}
export function undo(s){const event=s.history.pop();if(!event)return s;const current=beforeState(s);Object.assign(s,clone(event.before));s.redo.push({event,after:current});return s;}
export function redo(s){const item=s.redo.pop();if(!item)return s;Object.assign(s,clone(item.after));s.history.push(item.event);return s;}
