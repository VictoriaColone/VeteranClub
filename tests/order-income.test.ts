import test from 'node:test';
import assert from 'node:assert/strict';
import {createMockServer,MemoryStorage,participantEarnings,type Order,type Session,type Settlement,type Wallet} from '../packages/core/src/index';
import {orderIncome} from '../apps/mobile/src/services/order-income';

test('订单中的个人报酬跟随真实模拟结算流程更新，不误用多订单批次总额',()=>{
  const server=createMockServer(new MemoryStorage());
  const login=(role:string)=>server.dispatch('POST','/v1/auth/mock-login',{role}).data as Session;
  const member=login('player'),finance=login('finance');
  const wallet=()=>server.dispatch('GET','/v1/player/wallet',{},member.token).data as Wallet;
  const read=(id:string)=>server.dispatch('GET',`/v1/orders/${id}`,{},member.token).data as Order;
  const before=wallet();const order=before.orders.find(o=>o.refundStatus==='none')!;assert.ok(order);
  assert.equal(orderIncome(order,member.playerId,before.settlements)?.label,'待结算');
  const batches=server.dispatch('POST','/v1/admin/settlements',{playerId:member.playerId},finance.token).data as Settlement[];
  const batch=batches.find(s=>s.orderIds.includes(order.id))!;assert.ok(batch.orderIds.length>1);
  const pending=orderIncome(read(order.id),member.playerId,wallet().settlements)!;
  assert.equal(pending.label,'待付款');assert.equal(pending.settlement?.id,batch.id);
  assert.equal(pending.amount,participantEarnings(order,member.playerId));assert.ok(pending.amount<batch.amount);
  server.dispatch('POST',`/v1/admin/settlements/${batch.id}/pay`,{},finance.token);
  const paid=orderIncome(read(order.id),member.playerId,wallet().settlements)!;
  assert.equal(paid.label,'已结算');assert.ok(paid.settlement?.paidAt);assert.equal(paid.amount,pending.amount);
});

test('护航订单仅显示本人均分份额，售后冻结、未完成与取消状态不冒充已赚取',()=>{
  const base=createMockServer(new MemoryStorage()).read().orders[0];
  const order:Order={...base,status:'completed',earnings:10001,team:[{playerId:'lead',name:'负责人'},{playerId:'assistant',name:'副手'}]};
  assert.equal(orderIncome(order,'lead',[])?.amount,5001);assert.equal(orderIncome(order,'assistant',[])?.amount,5000);
  assert.equal(orderIncome({...order,refundStatus:'pending'},'lead',[])?.label,'售后处理中 · 暂缓结算');
  const unfinished=orderIncome({...order,status:'serving'},'lead',[])!;
  assert.equal(unfinished.amountLabel,'预计报酬');assert.equal(unfinished.label,'完成并确认后结算');
  const cancelled=orderIncome({...order,status:'cancelled'},'lead',[])!;
  assert.equal(cancelled.amount,0);assert.equal(cancelled.label,'未产生报酬');
});

test('下单用户或尚未接受邀请的用户无个人报酬卡，不匹配的结算记录不显示已付款',()=>{
  const base=createMockServer(new MemoryStorage()).read().orders[0];
  const order:Order={...base,status:'completed',preferredPlayerId:'invited',team:[{playerId:'lead',name:'负责人',settlementId:'JS-1'}]};
  assert.equal(orderIncome(order,undefined,[]),null);assert.equal(orderIncome(order,'buyer',[]),null);assert.equal(orderIncome(order,'invited',[]),null);
  const unrelated:Settlement={id:'JS-1',playerId:'other',playerName:'其他成员',orderIds:[order.id],amount:100,status:'paid',createdAt:1};
  const income=orderIncome(order,'lead',[unrelated])!;
  assert.equal(income.label,'结算处理中');assert.equal(income.settlement,undefined);
});
