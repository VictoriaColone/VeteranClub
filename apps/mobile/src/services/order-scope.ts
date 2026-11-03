import {orderMembers,type Order} from '@stellar/core';

export type PersonalOrderScope='all'|'created'|'assigned';
export const personalOrderScopes=[{id:'all',label:'全部订单'},{id:'created',label:'我下单的'},{id:'assigned',label:'派给我的'}] as const;

export function matchesPersonalOrderScope(order:Order,scope:PersonalOrderScope,userId?:string,playerId?:string){
  if(scope==='all')return true;
  if(scope==='created')return !!userId&&order.bossId===userId;
  if(!playerId)return false;
  return orderMembers(order).some(member=>member.playerId===playerId)
    ||(order.status==='waiting'&&order.preferredPlayerId===playerId);
}
