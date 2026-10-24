import type {Player} from '@stellar/core';

export function serviceAvailability(player?:Pick<Player,'available'|'busy'>){
  if(!player)return {state:'unknown',label:'状态更新中',action:'提交邀请',hint:'服务时间需等待成员确认。'};
  if(player.busy)return {state:'busy',label:'服务中 · 可预约',action:'预约邀请',hint:'正在为其他用户服务，可以先下单邀请，服务时间需等待成员确认。'};
  if(!player.available)return {state:'offline',label:'离线 · 可预约',action:'预约邀请',hint:'成员暂未在线，可以先下单邀请，待成员上线后确认服务时间。'};
  return {state:'idle',label:'在线 · 空闲',action:'立即邀请',hint:'当前空闲，可以下单邀请，服务开始时间以成员确认接单为准。'};
}
