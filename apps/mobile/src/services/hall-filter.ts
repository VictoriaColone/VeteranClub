import {canServe,orderMembers,type Order,type Player,type ServiceType} from '@stellar/core';

export type HallScope='public'|'private';
export interface HallFilterDraft {gameId:string;serviceType:'all'|ServiceType}
export type HallFilter=HallFilterDraft;
export const emptyHallFilterDraft=():HallFilterDraft=>({gameId:'all',serviceType:'all'});

export function parseHallFilter(draft:HallFilterDraft):HallFilter{
  if(!['all','delta','valorant','lol'].includes(draft.gameId))throw new Error('请选择支持的游戏');
  if(!['all','hourly','escort'].includes(draft.serviceType))throw new Error('请选择有效的订单类型');
  return {gameId:draft.gameId,serviceType:draft.serviceType};
}

export function matchesHallFilter(order:Order,filter:HallFilter,enabled=true){
  return !enabled||((filter.gameId==='all'||order.gameId===filter.gameId)&&(filter.serviceType==='all'||order.serviceType===filter.serviceType));
}

// Optional preferences never bypass invitation ownership or per-game onboarding.
export function receivableHallOrders(source:Order[],scope:HallScope,player:Player|undefined,userId:string|undefined):Order[]{
  if(!player||!userId||player.userId!==userId)return [];
  return source.filter(o=>o.status==='waiting'&&!!o.paidAt&&!o.playerId&&!orderMembers(o).length
    &&o.bossId!==userId&&!['pending','processing','refunded'].includes(o.refundStatus)
    &&canServe(player,o.serviceType,o.gameId)
    &&(scope==='public'?!o.preferredPlayerId:o.preferredPlayerId===player.id))
    .sort((a,b)=>b.createdAt-a.createdAt);
}
