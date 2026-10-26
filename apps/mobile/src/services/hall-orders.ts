import {canServe,orderMembers,type Order,type Player} from '@stellar/core';

export type HallScope='public'|'private';
// Onboarding and invitation ownership determine which orders this member can receive.
export function receivableHallOrders(source:Order[],scope:HallScope,player:Player|undefined,userId:string|undefined):Order[]{
  if(!player||!userId||player.userId!==userId)return [];
  return source.filter(o=>o.status==='waiting'&&!!o.paidAt&&!o.playerId&&!orderMembers(o).length
    &&o.bossId!==userId&&!['pending','processing','refunded'].includes(o.refundStatus)
    &&canServe(player,o.serviceType,o.gameId)
    &&(scope==='public'?!o.preferredPlayerId:o.preferredPlayerId===player.id))
    .sort((a,b)=>b.createdAt-a.createdAt);
}
