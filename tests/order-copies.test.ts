import test from 'node:test';
import assert from 'node:assert/strict';
import {ClubApi,MemoryStorage,createMockServer,STORAGE_KEY,orderCopies,orderUnits,orderSummary,totalOrderHours,participantEarnings,type Order,type ServiceType,type Transport} from '../packages/core/src';

async function fixture(){
  let now=Date.now();const storage=new MemoryStorage(),server=createMockServer(storage,()=>now),db=server.read();db.orders=[];storage.setItem(STORAGE_KEY,JSON.stringify(db));
  const transport:Transport=async<T>(method:string,path:string,body?:unknown,token?:string)=>server.dispatch(method,path,body,token).data as T;
  const buyer=new ClubApi(transport),member=new ClubApi(transport);
  await buyer.login('user','u-boss');await member.login('user','u-player');
  return {storage,server,buyer,member,transport,advance:(ms:number)=>now+=ms};
}

for(const type of ['hourly','escort'] as ServiceType[])for(const privateOrder of [false,true])test(`${type} ${privateOrder?'指定邀约':'公共单'}：多份合并一单，总价、收入与履约一致`,async()=>{
  const f=await fixture(),quantity=type==='hourly'?2:1,copies=3;
  const product=(await (privateOrder?f.member.products('mine'):f.buyer.products())).find(p=>p.gameId==='valorant'&&p.serviceType===type&&(privateOrder?p.userId==='u-player':p.sellerType==='club'))!;
  const order=await f.buyer.createOrder({productId:product.id,serviceType:type,gameId:'valorant',region:'国服 · 华东',quantity,copies});
  assert.equal(f.server.read().orders.length,1);assert.equal(order.copies,3);assert.equal(order.quantity,quantity);
  assert.equal(order.amount,product.price*quantity*copies);assert.equal(order.earnings,Math.floor(order.amount*.8));
  assert.equal(orderUnits(order),`${type==='hourly'?'2小时':product.task} ×3`);
  await f.buyer.act(order.id,'pay');
  const listed=await f.member.orders(privateOrder?{}:{scope:'hall'});assert.equal(listed.filter(o=>o.id===order.id).length,1);assert.equal(listed.find(o=>o.id===order.id)!.earnings,order.earnings);
  await f.member.act(order.id,'accept');await f.member.act(order.id,'start');
  if(type==='hourly'){
    assert.equal(totalOrderHours(order),6);f.advance(2*3600000);await assert.rejects(f.member.act(order.id,'finish',{note:'只完成了其中一份'}),/时长尚未完成/);f.advance(4*3600000);
  }else await assert.rejects(f.member.act(order.id,'finish',{note:'尚未全部完成',taskCompleted:false}),/指定任务/);
  await f.member.act(order.id,'finish',{note:'已完成全部三份服务',taskCompleted:true});const completed=await f.buyer.act(order.id,'confirm');
  const team=completed.team;assert.equal(team.reduce((sum,m)=>sum+participantEarnings(completed,m.playerId),0),order.earnings);
  assert.equal((await f.member.wallet()).settlements.find(s=>s.orderIds.includes(order.id))?.amount,order.earnings);
});

test('购买份数缺省为一份，拒绝小数、字符串、越界和空值',async()=>{
  const f=await fixture();const input={gameId:'valorant',region:'国服 · 华东',quantity:1};
  const old=await f.buyer.createOrder(input);assert.equal(old.copies,1);assert.equal(old.amount,old.unitPrice);
  for(const copies of [0,-1,100,1.5,'3','1 OR 1=1',null,true])await assert.rejects(f.buyer.createOrder({...input,copies:copies as any}),/购买份数/);
  assert.equal(f.server.read().orders.length,1);
  const max=await f.buyer.createOrder({...input,quantity:8,copies:99});assert.equal(max.amount,max.unitPrice*8*99);
});

test('旧本地订单默认一份，保留历史金额；展示时明确每份时长和总时长',()=>{
  const storage=new MemoryStorage(),db=createMockServer(storage).read(),amounts=db.orders.map(o=>o.amount);
  db.orders.forEach(o=>delete o.copies);storage.setItem(STORAGE_KEY,JSON.stringify(db));const migrated=createMockServer(storage).read();
  assert.ok(migrated.orders.every(o=>o.copies===1));assert.deepEqual(migrated.orders.map(o=>o.amount),amounts);
  const legacy={...migrated.orders[0],serviceType:'hourly',quantity:2,copies:undefined} as Order;
  assert.equal(orderCopies(legacy),1);assert.equal(orderUnits(legacy),'2小时 ×1');assert.equal(totalOrderHours(legacy),2);
  assert.equal(orderSummary({...legacy,copies:3}),'2小时 ×3 · 共6小时');
});

test('多份护航的部分退款按整单重算收入，副手分账之和保持一致',async()=>{
  const f=await fixture(),finance=new ClubApi(f.transport);await finance.login('finance');
  const product=await f.member.publishProduct({gameId:'valorant',serviceType:'escort',title:'多份任务护航',description:'验证多份服务退款后的分账尾差',price:101,task:'100w纯带出'});
  const order=await f.buyer.createOrder({productId:product.id,gameId:'valorant',serviceType:'escort',region:'国服 · 华东',quantity:1,copies:3});assert.equal(order.amount,303);
  await f.buyer.act(order.id,'pay');await f.member.act(order.id,'accept');await f.member.act(order.id,'add-assistant',{playerId:'p7'});
  await f.buyer.act(order.id,'refund',{amount:17,reason:'部分任务协商退款'});const revised=await finance.act(order.id,'review-refund',{approve:true,note:'按总订单金额退还部分款项'});
  assert.equal(revised.earnings,228);assert.equal(revised.team.reduce((sum,m)=>sum+participantEarnings(revised,m.playerId),0),228);
});
