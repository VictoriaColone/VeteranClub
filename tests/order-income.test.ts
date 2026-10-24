import test from 'node:test';
import assert from 'node:assert/strict';
import {createMockServer,MemoryStorage,participantEarnings,type Order,type Session,type Settlement,type Wallet} from '../packages/core/src/index';
import {orderIncome} from '../apps/mobile/src/services/order-income';

test('确认后的个人报酬显示自动结算结果，旧多订单批次不会冒充本单份额',()=>{
  const server=createMockServer(new MemoryStorage());
  const member=server.dispatch('POST','/v1/auth/mock-login',{role:'player'}).data as Session;
  const wallet=()=>server.dispatch('GET','/v1/player/wallet',{},member.token).data as Wallet;
  const before=wallet(),order=before.orders.find(o=>o.refundStatus==='none')!;
  const income=orderIncome(order,member.playerId,before.settlements)!;
  assert.equal(income.label,'已自动结算');assert.equal(income.settlement?.status,'paid');assert.ok(income.settlement?.paidAt);
  const legacyBatch={...income.settlement!,amount:income.amount+10000,orderIds:[order.id,'older-order']};
  assert.equal(orderIncome(order,member.playerId,[legacyBatch])!.amount,participantEarnings(order,member.playerId));
  assert.deepEqual(wallet().settlements,before.settlements);
});

test('护航订单仅显示本人均分份额，售后冻结、未完成与取消状态不冒充已赚取',()=>{
  const base=createMockServer(new MemoryStorage()).read().orders[0];
  const order:Order={...base,status:'completed',earnings:10001,team:[{playerId:'lead',name:'负责人'},{playerId:'assistant',name:'副手'}]};
  assert.equal(orderIncome(order,'lead',[])?.amount,5001);assert.equal(orderIncome(order,'assistant',[])?.amount,5000);
  assert.equal(orderIncome({...order,refundStatus:'pending'},'lead',[])?.label,'售后处理中 · 暂缓结算');
  const unfinished=orderIncome({...order,status:'serving'},'lead',[])!;
  assert.equal(unfinished.amountLabel,'预计报酬');assert.equal(unfinished.label,'确认完成后自动结算');
  const cancelled=orderIncome({...order,status:'cancelled'},'lead',[])!;
  assert.equal(cancelled.amount,0);assert.equal(cancelled.label,'未产生报酬');
});

test('下单用户或尚未接受邀请的用户无个人报酬卡，不匹配的结算记录不显示已付款',()=>{
  const base=createMockServer(new MemoryStorage()).read().orders[0];
  const order:Order={...base,status:'completed',preferredPlayerId:'invited',team:[{playerId:'lead',name:'负责人',settlementId:'JS-1'}]};
  assert.equal(orderIncome(order,undefined,[]),null);assert.equal(orderIncome(order,'buyer',[]),null);assert.equal(orderIncome(order,'invited',[]),null);
  const unrelated:Settlement={id:'JS-1',playerId:'other',playerName:'其他成员',orderIds:[order.id],amount:100,status:'paid',createdAt:1};
  const income=orderIncome(order,'lead',[unrelated])!;
  assert.equal(income.label,'自动结算处理中');assert.equal(income.settlement,undefined);
});
