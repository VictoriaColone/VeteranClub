import { qualifiedGames } from './types';
import { CLUB_NAME } from './brand';
import type { Database, Game, Product, Order, OrderStatus, Player } from './types';
export const STORAGE_KEY = 'stellar-club:database:v1';
export const DEFAULT_ESCORT_TASK = '100w纯带出';
export const CATALOG_REVISION = 1;
export const DEFAULT_COMMISSION_BPS = 2000;
export const UNPAID_TIMEOUT_MINUTES = 10;
export const SUPPORTED_GAMES: Game[] = [
  {id:'delta',name:'三角洲',short:'D',color:'#90c6aa',tagline:'明确目标，默契撤离',regions:['国服']},
  {id:'valorant',name:'无畏契约',short:'V',color:'#fa6c76',tagline:'默契配合，漂亮拿下',regions:['国服 · 华东','国服 · 华南','国服 · 华北']},
  {id:'lol',name:'英雄联盟',short:'L',color:'#d7ad65',tagline:'下一局，一起翻盘',regions:['艾欧尼亚','峡谷之巅','黑色玫瑰']},
];
export function createProducts(players:Player[],games:Game[],now:number):Product[]{
  return players.filter(p=>p.approved).flatMap(p=>p.qualifications.flatMap(serviceType=>qualifiedGames(p,serviceType).filter(id=>games.some(g=>g.id===id)).map(gameId=>({id:`SP-${p.id}-${serviceType}-${gameId}`,userId:p.userId,playerId:p.id,sellerName:p.name,gameId,serviceType,title:`${games.find(g=>g.id===gameId)!.name} · ${serviceType==='escort'?'任务护航':'时长组队'}`,description:p.bio,price:serviceType==='hourly'?p.price:12800,task:serviceType==='escort'?DEFAULT_ESCORT_TASK:'',active:true,createdAt:now-3600000}))));
}
export function createSeed(now = Date.now()): Database {
  const games: Game[] = SUPPORTED_GAMES.map(g=>({...g,regions:[...g.regions]}));
  const players: Player[] = [
    {id:'p1',userId:'u-player',name:'岚',subtitle:'无畏契约 · 超凡入圣',gameIds:['valorant','lol'],tags:['温柔耐心','指挥在线'],rating:4.98,completed:328,price:6800,color:'#bbd7b2',avatar:'L',available:true,qualifications:['hourly','escort'],approved:true,bio:'把每一局都认真打好。擅长控场和团队沟通，新手也可以放心来。'},
    {id:'p2',userId:'u-p2',name:'十一',subtitle:'三角洲 · 战术协作',gameIds:['delta','lol'],tags:['气氛担当','甜系声线'],rating:4.99,completed:516,price:5800,color:'#e4b8cb',avatar:'11',available:true,qualifications:['hourly','escort'],approved:true,bio:'快乐是第一目标，默契是额外奖励。一起度过轻松的游戏时间。'},
    {id:'p3',userId:'u-p3',name:'Echo',subtitle:'英雄联盟 · 傲世宗师',gameIds:['lol','valorant'],tags:['全能补位','细节拉满'],rating:4.96,completed:267,price:7800,color:'#acbddd',avatar:'E',available:true,qualifications:['hourly','escort'],approved:true,bio:'主玩中野，习惯复盘。喜欢认真配合，也喜欢有来有回的聊天。'},
    {id:'p4',userId:'u-p4',name:'小满',subtitle:'三角洲 · 任务协作',gameIds:['delta'],tags:['搜物资达人','超有耐心'],rating:4.97,completed:189,price:4800,color:'#d6c3a2',avatar:'M',available:false,qualifications:['hourly','escort'],approved:true,bio:'山顶见也行，决赛圈见更好。接住你的每一个标点。'},
    {id:'p5',userId:'u-p5',name:'北辰',subtitle:'无畏契约 · 耀世战魂',gameIds:['valorant','delta','lol'],tags:['沉稳指挥','实力队友'],rating:4.95,completed:402,price:8800,color:'#bcb0d8',avatar:'N',available:true,qualifications:['hourly','escort'],approved:true,bio:'不开压力局，只打有配合的局。擅长战术指挥和局内信息沟通。'},
    {id:'p6',userId:'u-p6',name:'柚子',subtitle:'三角洲 · 默契组队',gameIds:['delta','lol'],tags:['新人推荐','快乐双排'],rating:4.99,completed:96,price:5800,color:'#dbce9a',avatar:'Y',available:true,qualifications:[],approved:false,bio:'快乐游戏，真诚陪伴。资料审核中，期待和你见面。'},
  ];
  const statuses: OrderStatus[] = ['waiting','serving','accepted','unpaid','confirming','completed','waiting','completed','waiting','completed','completed','cancelled','completed','completed','completed','completed','completed','completed','completed','completed'];
  const orders: Order[] = statuses.map((status,index) => {
    const game=games[index%games.length];const isEscort=index%2===0&&index!==6; const player=players.filter(p=>p.approved&&p.gameIds.includes(game.id))[index%2%players.filter(p=>p.approved&&p.gameIds.includes(game.id)).length];
    const createdAt=status==='unpaid'?now-3*60000:now-(index<9?index*31*60000+12*60000:(index-7)*11*3600000);
    const amount=player.price*(index%3===0?2:1);
    const hasPlayer=!['waiting','unpaid','cancelled'].includes(status);
    const bossId=index%3===0&&index!==0&&index!==6?'u-boss':'u-boss-2';
    const paid=!['unpaid','cancelled'].includes(status);
    const order: Order={serviceType:isEscort?'escort':'hourly',task:isEscort?DEFAULT_ESCORT_TASK:'',team:hasPlayer?[{playerId:player.id,name:player.name}]:[],startedAt:['serving','confirming','completed'].includes(status)?createdAt+5*60000:undefined,id:`XY${new Date(now).toISOString().slice(2,10).replace(/-/g,'')}${String(1028-index).padStart(4,'0')}`,bossId,bossName:bossId==='u-boss'?'林序':'周予',gameId:game.id,gameName:game.name,region:game.regions[0],playerId:hasPlayer?player.id:undefined,playerName:hasPlayer?player.name:undefined,unitPrice:isEscort?amount:player.price,quantity:1,amount,commissionBps:DEFAULT_COMMISSION_BPS,earnings:Math.floor(amount*.8),status,refundStatus:index===7?'pending':'none',refundReason:index===7?'实际服务时长不足，希望退还未服务部分。':undefined,refundAmount:index===7?Math.round(amount/2):undefined,note:['想找个好沟通的队友，轻松玩两局。','晚上有时间，一起快乐开黑。','新手友好，麻烦多带一下。'][index%3],createdAt,paidAt:paid?createdAt+60000:undefined,finishedAt:status==='completed'?createdAt+3600000:undefined,paymentId:paid?`WXMOCK${index+1000}`:undefined,timeline:[{at:createdAt,label:'订单已创建',actor:bossId==='u-boss'?'林序':'周予'}]};
    if(paid)order.timeline.push({at:createdAt+60000,label:'模拟支付成功',actor:'模拟微信支付'});
    if(hasPlayer)order.timeline.push({at:createdAt+3*60000,label:'队友已接单',actor:player.name});
    if(['serving','confirming','completed'].includes(status))order.timeline.push({at:createdAt+5*60000,label:'服务已开始',actor:player.name});
    if(['confirming','completed'].includes(status))order.timeline.push({at:createdAt+55*60000,label:'服务已结束',actor:player.name});
    if(status==='completed')order.timeline.push({at:createdAt+60*60000,label:'用户确认完成',actor:order.bossName});
    if(order.refundStatus==='pending')order.timeline.push({at:createdAt+65*60000,label:'已申请部分退款',actor:order.bossName,note:order.refundReason});
    return order;
  });
  // Keep one open job for the logged-in player; avoid an existing unfinished assignment.
  orders.filter(o=>o.playerId==='p1'&&['accepted','serving','confirming'].includes(o.status)).forEach(o=>{o.playerId='p5';o.playerName='北辰';o.team=[{playerId:'p5',name:'北辰'}];});
  players.push({id:'p0',userId:'u-boss',name:'林序',subtitle:`欢迎加入${CLUB_NAME}`,gameIds:games.map(g=>g.id),tags:['新朋友'],rating:5,completed:0,price:6800,color:'#b6c89a',avatar:'X',available:false,approved:false,qualifications:[],bio:'每一场游戏，都值得认真对待。'});
  players.push({id:'p7',userId:'u-p7',name:'远山',subtitle:'全能战术协作',gameIds:games.map(g=>g.id),tags:['默契协作'],rating:4.98,completed:132,price:6800,color:'#94b9c5',avatar:'Y',available:true,approved:true,qualifications:['hourly','escort'],bio:'可靠的信息沟通，认真完成每次约定。'});
  players.push({id:'p-hourly',userId:'u-hourly',name:'小屿',subtitle:'默契组队 · 认真同行',gameIds:games.map(g=>g.id),tags:['沟通耐心'],rating:4.98,completed:86,price:6800,color:'#cabfe2',avatar:'I',available:true,approved:true,qualifications:['hourly'],bio:'喜欢有来有回的配合，一起享受游戏过程。'});
  players.push({id:'p-escort',userId:'u-escort',name:'凛',subtitle:'任务护航 · 稳定协作',gameIds:games.map(g=>g.id),tags:['目标明确'],rating:4.97,completed:108,price:6800,color:'#a6c5be',avatar:'R',available:true,approved:true,qualifications:['escort'],bio:'认真沟通任务目标，和团队一起完成约定。'});
  return {schema:2,products:createProducts(players,games,now),catalogRevision:CATALOG_REVISION,applications:[],revision:0,games,players,orders,settlements:[],sequence:1029,settings:{name:CLUB_NAME,commissionBps:DEFAULT_COMMISSION_BPS,unpaidTimeoutMinutes:UNPAID_TIMEOUT_MINUTES,announcement:'一起出发。下单前请确认游戏区服、任务目标或服务时长。'},audit:[{id:'seed-1',at:now-5*60000,actor:'客服 · 许知',action:'处理售后',target:orders[7].id,detail:'收到用户退款申请，等待财务审核。'},{id:'seed-2',at:now-18*60000,actor:'北辰',action:'开始服务',target:orders[1].id,detail:'队友已开始服务。'},{id:'seed-3',at:now-32*60000,actor:'运营 · 陈安',action:'审核队友',target:'十一',detail:'技能与资料审核通过。'}],sessions:[]};
}
