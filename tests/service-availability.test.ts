import test from 'node:test';
import assert from 'node:assert/strict';
import {ClubApi,createMockTransport,createMockServer,MemoryStorage,STORAGE_KEY} from '../packages/core/src';
import {receivingOrders,serviceAvailability} from '../apps/mobile/src/services/service-availability';

test('只读状态标记和商品卡使用同一状态，已占用订单优先于平台接单设置',()=>{
  for(const player of [{available:false,busy:false},{available:true,busy:true},{available:false,busy:true}]){
    assert.equal(receivingOrders(player),false);assert.equal(serviceAvailability(player).label,'服务中 · 可预约');
  }
  assert.equal(receivingOrders({available:true,busy:false}),true);assert.equal(serviceAvailability({available:true,busy:false}).label,'接单中');
  assert.equal(receivingOrders(undefined),false);
});

for(const privateOrder of [false,true])test(`${privateOrder?'接受指定邀请':'抢公共单'}后自动服务中，不能强制接单，完成后恢复`,async()=>{
  const storage=new MemoryStorage(),server=createMockServer(storage),db=server.read();db.orders=[];storage.setItem(STORAGE_KEY,JSON.stringify(db));
  const buyer=new ClubApi(createMockTransport(storage,0)),member=new ClubApi(createMockTransport(storage,0)),platform=new ClubApi(createMockTransport(storage,0));
  await buyer.login('user','u-boss');await member.login('user','u-escort');await platform.login('admin');await platform.updatePlayer(member.session!.playerId!,{available:true});
  const profile=async()=>(await buyer.players()).find(p=>p.userId==='u-escort')!;
  const product=(await member.products('mine')).find(p=>p.serviceType==='escort'&&p.gameId==='delta')!;
  const order=await buyer.createOrder({productId:privateOrder?product.id:'club-escort-delta',gameId:'delta',serviceType:'escort',region:'国服',quantity:1});await buyer.act(order.id,'pay');
  if(privateOrder)await member.act(order.id,'accept');else await member.grab({serviceType:'escort',gameId:'delta'});
  assert.equal(receivingOrders(await profile()),false);await assert.rejects(member.availability(true),/平台管理/);
  await platform.updatePlayer(member.session!.playerId!,{available:true});assert.equal(receivingOrders(await profile()),false,'平台接单标志也不能绕过已有占单');
  assert.ok((await buyer.products()).some(p=>p.id===product.id),'服务中仍可预约商品');
  await member.act(order.id,'start');await member.act(order.id,'finish',{taskCompleted:true,note:'指定任务已完成'});await buyer.act(order.id,'confirm');
  assert.equal(receivingOrders(await profile()),true);
  for(const value of [false,true]){await assert.rejects(member.availability(value),/平台管理/);assert.equal(receivingOrders(await profile()),true,'成员不能修改空闲状态');}
  await platform.updatePlayer(member.session!.playerId!,{available:false});assert.equal(receivingOrders(await profile()),false);
  await assert.rejects(member.availability(true),/平台管理/);assert.equal(receivingOrders(await profile()),false,'成员不能自行重新开启接单');
  await platform.updatePlayer(member.session!.playerId!,{available:true});assert.equal(receivingOrders(await profile()),true);
  await assert.rejects(buyer.availability(true),/平台管理/);
});
