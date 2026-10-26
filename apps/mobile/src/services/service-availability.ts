import type {Player} from '@stellar/core';

export function receivingOrders(player?:Pick<Player,'available'|'busy'>){return !!player?.available&&!player.busy;}

export function serviceAvailability(player?:Pick<Player,'available'|'busy'>){
  if(!player)return {state:'unknown',label:'状态更新中',action:'提交邀请',hint:'服务时间需等待成员确认。'};
  if(!receivingOrders(player))return {state:'busy',label:'服务中 · 可预约',action:'预约邀请',hint:'当前暂不接新单，可以先下单预约，服务时间需等待成员确认。'};
  return {state:'idle',label:'接单中',action:'立即邀请',hint:'当前可接单，服务开始时间以成员确认接单为准。'};
}
