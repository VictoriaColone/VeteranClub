import {orderMembers,participantEarnings,type Order,type Settlement} from '@stellar/core';
export function orderIncome(order:Order,playerId:string|undefined,settlements:Settlement[]){
  const member=orderMembers(order).find(m=>m.playerId===playerId);
  if(!member)return null;
  const amount=order.status==='cancelled'?0:participantEarnings(order,playerId);
  const amountLabel=order.status==='completed'?'个人报酬':'预计报酬';
  const settlement=settlements.find(s=>s.id===member.settlementId&&s.playerId===playerId&&s.orderIds.includes(order.id));
  if(order.status==='cancelled')return {amount,amountLabel:'个人报酬',status:'cancelled',label:'未产生报酬'};
  if(['pending','processing'].includes(order.refundStatus))return {amount,amountLabel,status:'frozen',label:'售后处理中 · 暂缓结算'};
  if(order.status!=='completed')return {amount,amountLabel,status:'estimated',label:'确认完成后自动结算'};
  if(settlement)return {amount,amountLabel,status:settlement.status,label:settlement.status==='paid'?'已自动结算':settlement.status==='credited'?'已记账 · 待渠道转账':'自动结算处理中',settlement};
  return {amount,amountLabel,status:'pending',label:'自动结算处理中'};
}
