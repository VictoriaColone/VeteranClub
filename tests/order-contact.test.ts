import test from 'node:test';
import assert from 'node:assert/strict';
import {ClubApi,MemoryStorage,createMockServer,type Transport} from '../packages/core/src';

async function fixture(){
  const server=createMockServer(new MemoryStorage());
  const transport:Transport=async<T>(method,path,body,token)=>server.dispatch(method,path,body,token).data as T;
  const buyer=new ClubApi(transport),member=new ClubApi(transport),admin=new ClubApi(transport);
  await buyer.login('user','u-boss');await member.login('user','u-player');await admin.login('admin');
  return {buyer,member,admin,server};
}
for(const serviceType of ['hourly','escort'] as const)test(`${serviceType}：电话必填，微信选填，持久化且公共池隐藏联系方式`,async()=>{
  const f=await fixture(),input={serviceType,gameId:'valorant',region:'国服 · 华东',quantity:1,contactPhone:'13800000000',contactWechat:'test_wechat'};
  for(const contactPhone of ['',undefined,null,123,'abc','123','1'.repeat(25)])await assert.rejects(f.buyer.createOrder({...input,contactPhone:contactPhone as any}),/联系电话/);
  await assert.rejects(f.buyer.createOrder({...input,contactWechat:'x'.repeat(65)}),/微信/);
  const order=await f.buyer.createOrder(input);
  assert.equal(order.contactPhone,input.contactPhone);assert.equal(order.contactWechat,input.contactWechat);
  assert.equal((await f.buyer.order(order.id)).contactPhone,input.contactPhone);
  await f.buyer.act(order.id,'pay');
  const listed=(await f.member.orders({scope:'hall'})).find(o=>o.id===order.id)!;
  assert.ok(listed);assert.equal(listed.contactPhone,undefined);assert.equal(listed.contactWechat,undefined);
  const accepted=await f.member.act(order.id,'accept');assert.equal(accepted.contactPhone,input.contactPhone);
  assert.equal((await f.admin.order(order.id)).contactWechat,input.contactWechat);
  const noWechat=await f.buyer.createOrder({...input,contactPhone:'010-12345678',contactWechat:undefined});assert.equal(noWechat.contactWechat,undefined);
});

test('指定邀请在接单前隐藏联系方式，接单后向负责人提供',async()=>{
  const f=await fixture();
  const product=(await f.member.products('mine')).find(p=>p.gameId==='valorant'&&p.serviceType==='escort')!;
  const order=await f.buyer.createOrder({productId:product.id,serviceType:'escort',gameId:product.gameId,region:'国服 · 华东',quantity:1,contactPhone:'13800000000'});
  await f.buyer.act(order.id,'pay');
  assert.equal((await f.member.order(order.id)).contactPhone,undefined);
  assert.equal((await f.member.act(order.id,'accept')).contactPhone,'13800000000');
});


test('体验账号无短信注册身份，联系电话留空，不编造默认号码',async()=>{
  const f=await fixture();assert.deepEqual(await f.buyer.registrationContact(),{userId:'u-boss',phone:''});
  await assert.rejects(f.admin.registrationContact(),/用户账号/);
});
