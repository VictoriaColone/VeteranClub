import type {Player} from '@stellar/core';

export function receivingOrders(player?:Pick<Player,'available'|'busy'>){return !!player?.available&&!player.busy;}

export function receivingBlockReason(player?:Pick<Player,'available'|'busy'>){
  if(!player)return '成员状态尚未加载，请刷新后重试';
  if(player.busy)return '当前服务尚未完成';
  return player.available?'':'平台已暂停你的接单，请联系平台开启';
}

export function serviceAvailability(player?:Pick<Player,'available'|'busy'>){
  if(!player)return {state:'unknown',label:'状态更新中',profileLabel:'状态更新中',action:'提交邀请',hint:'服务时间需等待成员确认。'};
  if(player.busy)return {state:'busy',label:'服务中 · 可预约',profileLabel:'服务中',action:'预约邀请',hint:'当前暂不接新单，可以先下单预约，服务时间需等待成员确认。'};
  if(!player.available)return {state:'paused',label:'暂停接单 · 可预约',profileLabel:'暂停接单',action:'预约邀请',hint:'平台已暂停该成员接单，可先下单预约，服务时间需等待平台开启接单后由成员确认。'};
  return {state:'idle',label:'接单中',profileLabel:'接单中',action:'立即邀请',hint:'当前可接单，服务开始时间以成员确认接单为准。'};
}
