import { randomUUID } from 'node:crypto';
export function fixture(final=false, mode='standard') {
 const home={id:randomUUID(),name:'主队',shortName:'主队',players:[]},away={id:randomUUID(),name:'客队',shortName:'客队',players:[]};
 const id=randomUUID();
 return {formatVersion:1,payload:{id,createdAt:800000000,updatedAt:800000000,seasonID:'2026',rules:{mode},status:final?1:0,state:{
 homeTeam:home,awayTeam:away,inning:1,isTop:true,balls:0,strikes:0,outs:0,homeRunsByInning:[0],awayRunsByInning:[0],homeHits:0,awayHits:0,homeErrors:0,awayErrors:0,
 homeBattingOrderIDs:[],awayBattingOrderIDs:[],isFinal:final,batting:[],pitching:[],fielding:[]}},projection:{schema:1,gameID:id,revision:0,mode,isFinal:final,endedAt:final?Date.now():null,
 inning:1,isTop:true,balls:0,strikes:0,outs:0,home:{name:'主队',runs:0,hits:0,errors:0,innings:[0]},away:{name:'客队',runs:0,hits:0,errors:0,innings:[0]},
 batter:null,pitcher:null,pitchCount:null,appearancePitchCount:0,pitchLimit:null,bases:[],currentAppearanceID:null,notice:'',entries:[]}};
}
