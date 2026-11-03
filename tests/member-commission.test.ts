import test from 'node:test';
import assert from 'node:assert/strict';
import {ClubApi,createMockServer,MemoryStorage,STORAGE_KEY,participantEarnings,type Order,type Transport} from '../packages/core/src';

async function fixture(){
  const storage=new MemoryStorage(),server=createMockServer(storage),db=server.read();db.orders=[];storage.setItem(STORAGE_KEY,JSON.stringify(db));
  const transport:Transport=async<T>(method:string,path:string,body?:unknown,token?:string)=>server.dispatch(method,path,body,token).data as T;
  const admin=new ClubApi(transport),buyer=new ClubApi(transport),lead=new ClubApi(transport),assistant=new ClubApi(transport),finance=new ClubApi(transport);
  await admin.login('admin');await buyer.login('user','u-boss');await lead.login('user','u-player');await assistant.login('user','u-p7');await finance.login('finance');
  return {server,admin,buyer,lead,assistant,finance};
}

test('成员抽佣默认20%，仅管理员可调整；商品价格仍由发布者设置',async()=>{
  const f=await fixture();assert.equal((await f.admin.players()).find(p=>p.id==='p1')!.commissionBps,2000);
  for(const commissionBps of [-1,10001,12.5,'2000',null,true])await assert.rejects(f.admin.updatePlayer('p1',{commissionBps:commissionBps as any}),/抽佣/);
  await assert.rejects(f.lead.updatePlayer('p1',{commissionBps:0}),/权限/);
  await assert.rejects(f.admin.updatePlayer('p1',{price:100} as any),/字段无效/);
  for(const commissionBps of [0,10000,1234])assert.equal((await f.admin.updatePlayer('p1',{commissionBps})).commissionBps,commissionBps);
  const product=await f.lead.publishProduct({gameId:'valorant',serviceType:'escort',title:'独立商品定价',description:'成员发布商品独立设置单价',price:12345,task:'约定任务'});assert.equal(product.price,12345);
});

test('多人收入按各自加入时的抽佣计算，预览、成员增减、退款和最终钱包一致',async()=>{
  const f=await fixture();await f.admin.updatePlayer('p1',{commissionBps:1000});await f.admin.updatePlayer('p7',{commissionBps:3000});
  const product=await f.lead.publishProduct({gameId:'valorant',serviceType:'escort',title:'分成员抽佣测试',description:'验证协作金额均分后按成员分别扣佣',price:10001,task:'约定任务'});
  const order=await f.buyer.createOrder({contactPhone:'13800000000',productId:'club-escort-valorant',serviceType:'escort',gameId:'valorant',region:'国服 · 华东',quantity:1});await f.buyer.act(order.id,'pay');
  assert.equal((await f.lead.orders({scope:'hall'})).find(o=>o.id===order.id)!.earnings,11520);
  assert.equal((await f.assistant.orders({scope:'hall'})).find(o=>o.id===order.id)!.earnings,8960);
  const privateOrder=await f.buyer.createOrder({contactPhone:'13800000000',productId:product.id,serviceType:'escort',gameId:'valorant',region:'国服 · 华东',quantity:1});await f.buyer.act(privateOrder.id,'pay');
  let assigned=await f.lead.act(privateOrder.id,'accept');assert.equal(assigned.earnings,9000);assert.equal(assigned.team[0].commissionBps,1000);
  await f.admin.updatePlayer('p1',{commissionBps:4000});assigned=await f.lead.act(privateOrder.id,'add-assistant',{playerId:'p7'});
  assert.equal(participantEarnings(assigned,'p1'),4500);assert.equal(participantEarnings(assigned,'p7'),3500);assert.equal(assigned.earnings,8000);
  await f.admin.updatePlayer('p7',{commissionBps:2000});assert.equal((await f.lead.order(privateOrder.id)).team[1].commissionBps,3000);
  assigned=await f.lead.act(privateOrder.id,'remove-assistant',{playerId:'p7'});assert.equal(assigned.earnings,9000);
  assigned=await f.lead.act(privateOrder.id,'add-assistant',{playerId:'p7'});assert.equal(assigned.team[1].commissionBps,2000);assert.equal(assigned.earnings,8500);
  await f.buyer.act(privateOrder.id,'refund',{amount:1001,reason:''});assigned=await f.finance.act(privateOrder.id,'review-refund',{approve:true,note:'退还协商金额'});assert.equal(assigned.earnings,7650);
  await f.lead.act(privateOrder.id,'start');await f.lead.act(privateOrder.id,'finish',{taskCompleted:true,note:'任务已经全部完成'});await f.buyer.act(privateOrder.id,'confirm');
  assert.equal((await f.lead.wallet()).total,4050);assert.equal((await f.assistant.wallet()).total,3600);
});

test('旧订单仍按原净报酬均分，新的配置不影响历史结算；新算法按分向下取整',()=>{
  const sample=createMockServer(new MemoryStorage()).read().orders[0];
  const legacy:Order={...sample,amount:104,earnings:83,memberCommission:false,team:[{playerId:'a',name:'A',commissionBps:0},{playerId:'b',name:'B',commissionBps:10000}]};
  assert.equal(participantEarnings(legacy,'a'),42);assert.equal(participantEarnings(legacy,'b'),41);
  const current={...legacy,memberCommission:true,team:[{playerId:'a',name:'A',commissionBps:2000},{playerId:'b',name:'B',commissionBps:2000}]};
  assert.equal(participantEarnings(current,'a'),41);assert.equal(participantEarnings(current,'b'),41);
});
