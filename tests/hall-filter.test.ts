import test from 'node:test';
import assert from 'node:assert/strict';
import {ClubApi,createMockTransport,createMockServer,MemoryStorage,STORAGE_KEY,type Order,type Player} from '../packages/core/src/index';
import {emptyHallFilterDraft,parseHallFilter,matchesHallFilter,receivableHallOrders} from '../apps/mobile/src/services/hall-filter';

const db=createMockServer(new MemoryStorage()).read();
const player:Player={...db.players.find(p=>p.userId==='u-player')!,approved:true,qualifications:['hourly','escort'],qualificationGames:{hourly:['delta'],escort:['delta']}};
const sample:Order={...db.orders[0],id:'sample',gameId:'delta',serviceType:'hourly',bossId:'another-user',status:'waiting',paidAt:1,playerId:undefined,team:[],preferredPlayerId:undefined,refundStatus:'none'};

test('游戏与订单类型取交集，全部类型同时展示组队和护航',()=>{
  const filter=parseHallFilter({gameId:'delta',serviceType:'escort'});
  assert.equal(matchesHallFilter({...sample,serviceType:'escort'},filter),true);
  assert.equal(matchesHallFilter(sample,filter),false);
  assert.equal(matchesHallFilter({...sample,gameId:'lol',serviceType:'escort'},filter),false);
  assert.equal(matchesHallFilter(sample,emptyHallFilterDraft()),true);
  assert.equal(matchesHallFilter({...sample,serviceType:'escort'},emptyHallFilterDraft()),true);
  assert.equal(matchesHallFilter(sample,{gameId:'lol',serviceType:'escort'},false),true);
});

test('过滤只受游戏与类型影响，时长和价格不再影响订单列表',()=>{
  const filter=parseHallFilter({gameId:'delta',serviceType:'all',minHours:'8',maxPrice:'0'} as any);
  assert.deepEqual(filter,{gameId:'delta',serviceType:'all'});
  assert.equal(matchesHallFilter({...sample,quantity:1,earnings:10000},filter),true);
  assert.equal(matchesHallFilter({...sample,quantity:8,earnings:1},filter),true);
  assert.throws(()=>parseHallFilter({gameId:'unsupported',serviceType:'all'}),/游戏/);
  assert.throws(()=>parseHallFilter({gameId:'all',serviceType:'unknown'} as any),/类型/);
});

test('公共单与私有单互斥，私有单只显示指定当前成员的待接邀请',()=>{
  const invited={...sample,id:'invited',preferredPlayerId:player.id,createdAt:sample.createdAt+1};
  const other={...invited,id:'other',preferredPlayerId:'another-player'};
  const source=[sample,invited,other,{...sample,id:'own',bossId:player.userId}];
  assert.deepEqual(receivableHallOrders(source,'public',player,player.userId).map(o=>o.id),['sample']);
  assert.deepEqual(receivableHallOrders(source,'private',player,player.userId).map(o=>o.id),['invited']);
  assert.deepEqual(receivableHallOrders(source,'private',undefined,player.userId),[]);
  assert.deepEqual(receivableHallOrders(source,'private',player,'another-user'),[]);
});

test('即使关闭过滤，也不展示其他游戏、未入驻类型或不可接取状态的私有单',()=>{
  const invited={...sample,preferredPlayerId:player.id};
  const source=[invited,{...invited,id:'other-game',gameId:'lol'},...(['unpaid','accepted','serving','confirming','completed','cancelled'] as const).map(status=>({...invited,id:status,status})),...(['pending','processing','refunded'] as const).map(refundStatus=>({...invited,id:refundStatus,refundStatus})),{...invited,id:'missing-payment',paidAt:undefined},{...invited,id:'assigned',playerId:player.id},{...invited,id:'team',team:[{playerId:player.id,name:player.name}]}];
  const orders=receivableHallOrders(source,'private',player,player.userId).filter(o=>matchesHallFilter(o,{gameId:'lol',serviceType:'escort'},false));
  assert.deepEqual(orders.map(o=>o.id),['sample']);
  const hourlyOnly={...player,qualifications:['hourly'] as Player['qualifications'],qualificationGames:{hourly:['delta']}};
  assert.deepEqual(receivableHallOrders([{...invited,serviceType:'escort'}],'private',hourlyOnly,player.userId),[]);
});

test('指定商品订单付款后进入私有单，售后与接单后移出，其他成员不能接受邀请',async()=>{
  const storage=new MemoryStorage(),server=createMockServer(storage),data=server.read();data.orders=[];storage.setItem(STORAGE_KEY,JSON.stringify(data));
  const buyer=new ClubApi(createMockTransport(storage,0)),member=new ClubApi(createMockTransport(storage,0)),other=new ClubApi(createMockTransport(storage,0));
  await buyer.login('user');await member.login('user','u-hourly');await other.login('user','u-player');
  const profile=(await member.players()).find(p=>p.userId==='u-hourly')!;
  const product=(await member.products('mine')).find(p=>p.gameId==='valorant'&&p.serviceType==='hourly')!;
  const privateOrders=async()=>receivableHallOrders(await member.orders(),'private',profile,profile.userId);
  const buy=()=>buyer.createOrder({productId:product.id,gameId:product.gameId,serviceType:product.serviceType,region:'国服 · 华东',quantity:1});
  const first=await buy();assert.equal((await privateOrders()).length,0);
  await buyer.act(first.id,'pay');assert.deepEqual((await privateOrders()).map(o=>o.id),[first.id]);
  assert.ok(!(await member.orders({scope:'hall'})).some(o=>o.id===first.id));assert.ok(!(await other.orders()).some(o=>o.id===first.id));
  await assert.rejects(other.act(first.id,'accept'),/指定/);
  await buyer.act(first.id,'refund',{amount:first.amount,reason:''});assert.equal((await privateOrders()).length,0);
  const second=await buy();await buyer.act(second.id,'pay');assert.deepEqual((await privateOrders()).map(o=>o.id),[second.id]);
  assert.equal((await member.act(second.id,'accept')).status,'accepted');assert.equal((await privateOrders()).length,0);
  assert.ok((await member.orders()).some(o=>o.id===second.id&&o.playerId===profile.id));
});
