import test from 'node:test';
import assert from 'node:assert/strict';
import {ClubApi,createMockTransport,MemoryStorage,createMockServer,STORAGE_KEY,isClubProduct,CLUB_NAME} from '../packages/core/src/index';

function fixture(){const storage=new MemoryStorage(),server=createMockServer(storage);const db=server.read();db.orders=[];storage.setItem(STORAGE_KEY,JSON.stringify(db));return {storage,buyer:new ClubApi(createMockTransport(storage,0)),member:new ClubApi(createMockTransport(storage,0))};}

test('官方商品置顶且展示俱乐部身份，无资格用户可购买、不能篡改价格或任务',async()=>{
  const {buyer,member}=fixture();await buyer.login('user');await member.login('user','u-hourly');
  const catalog=await buyer.products();assert.equal(catalog.filter(isClubProduct).length,6);assert.ok(catalog.slice(0,6).every(isClubProduct));
  const item=catalog.find(p=>p.id==='club-escort-delta')!;assert.equal(item.sellerName,CLUB_NAME);assert.equal(item.playerId,null);assert.equal(item.userId,null);
  const o=await buyer.createOrder({productId:item.id,serviceType:'escort',gameId:'delta',region:'国服',quantity:1,taskPrice:1,task:'改成简单任务'});
  assert.equal(o.amount,12800);assert.equal(o.task,'100w纯带出');assert.equal(o.preferredPlayerId,undefined);
  await assert.rejects(buyer.orders({scope:'hall'}),/资格/);assert.equal((await member.orders({scope:'hall'})).length,0);
  await buyer.act(o.id,'pay');assert.equal((await member.orders({scope:'hall'})).length,0); // 仅组队资格不能看护航单。
  await member.login('user','u-escort');assert.ok((await member.orders({scope:'hall'})).some(p=>p.id===o.id));
  const again=await buyer.createOrder({productId:item.id,serviceType:'escort',gameId:'delta',region:'国服',quantity:1});assert.notEqual(again.id,o.id);
  await assert.rejects(buyer.createOrder({productId:item.id,serviceType:'escort',gameId:'delta',region:'国服',quantity:1,playerId:'p-escort'}),/成员不匹配/);
});

for(const reason of [undefined,'','退'])test(`售后原因 ${JSON.stringify(reason)} 可直接进入审核并移出公共池`,async()=>{
  const {buyer,member}=fixture();await buyer.login('user');await member.login('user','u-hourly');
  const o=await buyer.createOrder({productId:'club-hourly-valorant',serviceType:'hourly',gameId:'valorant',region:'国服 · 华东',quantity:2,taskPrice:99999});assert.equal(o.amount,13600);
  await buyer.act(o.id,'pay');assert.ok((await member.orders({scope:'hall'})).some(p=>p.id===o.id));
  const refund=await buyer.act(o.id,'refund',{amount:o.amount,reason});assert.equal(refund.refundStatus,'pending');assert.equal(refund.refundReason,reason||'用户申请售后');
  assert.equal((await member.orders({scope:'hall'})).length,0);await assert.rejects(member.act(o.id,'accept'),/售后/);
});

test('成员商品订单保存指定成员快照并始终不进入公共池',async()=>{
  const {buyer,member}=fixture();await buyer.login('user');await member.login('user','u-hourly');
  const p=await member.publishProduct({gameId:'valorant',serviceType:'hourly',title:'指定成员组队',description:'约定时长，认真配合每一局。',price:8800});
  const o=await buyer.createOrder({productId:p.id,gameId:p.gameId,serviceType:p.serviceType,region:'国服 · 华东',quantity:2});
  assert.equal(o.preferredPlayerId,p.playerId);assert.equal(o.preferredPlayerName,p.sellerName);assert.equal(o.amount,17600);await buyer.act(o.id,'pay');
  assert.equal((await member.orders({scope:'hall'})).length,0);assert.ok((await member.orders()).some(p=>p.id===o.id));
  assert.equal((await member.act(o.id,'accept')).status,'accepted');assert.equal((await buyer.order(o.id)).preferredPlayerName,p.sellerName);
});
