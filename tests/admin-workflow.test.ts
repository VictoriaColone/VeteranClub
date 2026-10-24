import test from 'node:test';
import assert from 'node:assert/strict';
import {createMockServer,createSeed,MemoryStorage,STORAGE_KEY,type Application,type ClubSettings,type ClubUser,type Order,type Session,type Settlement} from '../packages/core/src/index';

test('客户目录包含未下单的注册用户；申请始终关联同一个用户，普通用户无权读取客户目录',()=>{
  const server=createMockServer(new MemoryStorage());
  const admin=server.dispatch('POST','/v1/auth/mock-login',{role:'admin'}).data as Session;
  const user=server.dispatch('POST','/v1/auth/mock-login',{role:'user',accountId:'u-p6'}).data as Session;
  const users=()=>server.dispatch('GET','/v1/admin/users',{},admin.token).data as ClubUser[];
  assert.ok(users().some(u=>u.id===user.userId&&u.playerId===user.playerId));
  assert.ok(!server.read().orders.some(o=>o.bossId===user.userId));
  assert.equal(new Set(users().map(u=>u.id)).size,users().length);
  assert.throws(()=>server.dispatch('GET','/v1/admin/users',{},user.token),/无权/);
  const apply=(serviceType:string)=>server.dispatch('POST','/v1/applications',{serviceType,gameIds:['delta'],introduction:'申请三角洲对应方向的独立资格，接受专项考核。'},user.token).data as Application;
  const hourly=apply('hourly'),escort=apply('escort');
  const review=(a:Application,status:string)=>server.dispatch('POST',`/v1/admin/applications/${a.id}/review`,{status,score:90,note:'资料或考核结果核实通过'},admin.token);
  review(hourly,'assessment');review(hourly,'approved');
  const history=server.dispatch('GET','/v1/applications',{},admin.token).data as Application[];
  assert.equal(history.find(a=>a.id===hourly.id)!.status,'approved');
  assert.equal(history.find(a=>a.id===escort.id)!.status,'pending');
  assert.equal(users().filter(u=>u.id===user.userId).length,1);
});

test('旧品牌与交易配置自动迁移，历史订单仍保留原费率及净报酬',()=>{
  const storage=new MemoryStorage(),db=createSeed();
  db.settings.name='星曜电竞';db.players.find(p=>p.id==='p0')!.subtitle='欢迎加入星曜';
  db.settings.commissionBps=3000;db.settings.unpaidTimeoutMinutes=30;
  const order=db.orders.find(o=>o.status==='completed'&&o.refundStatus==='none')!;
  order.commissionBps=3000;order.earnings=Math.floor(order.amount*.7);
  storage.setItem(STORAGE_KEY,JSON.stringify(db));
  const server=createMockServer(storage),admin=server.dispatch('POST','/v1/auth/mock-login',{role:'admin'}).data as Session;
  const settings=server.dispatch('GET','/v1/club',{},admin.token).data as ClubSettings;
  assert.equal(settings.name,'诺曼部落');
  assert.equal(JSON.parse(storage.getItem(STORAGE_KEY)!).settings.name,'诺曼部落');
  assert.equal(server.read().players.find(p=>p.id==='p0')!.subtitle,'欢迎加入诺曼部落');
  assert.equal(settings.commissionBps,2000);assert.equal(settings.unpaidTimeoutMinutes,10);
  const historical=server.dispatch('GET',`/v1/orders/${order.id}`,{},admin.token).data as Order;
  assert.equal(historical.commissionBps,3000);assert.equal(historical.earnings,order.earnings);
  assert.equal(server.read().settlements.find(b=>b.orderIds.includes(order.id))!.amount,order.earnings);
});

test('已有已付款记录不重复付款，历史待付款记录自动完成且沿用原批次',()=>{
  const storage=new MemoryStorage(),db=createSeed(),now=Date.now();
  db.orders=db.orders.filter(o=>o.status==='completed'&&o.refundStatus==='none').slice(0,2);
  db.settlements=db.orders.map((o,i)=>{
    const member=o.team[0],id=`legacy-${i}`;
    o.settlementId=id;member.settlementId=id;
    return {id,playerId:member.playerId,playerName:member.name,orderIds:[o.id],amount:o.earnings,status:i===0?'paid':'pending',createdAt:now-60000,...(i===0?{paidAt:now-30000}:{})} as Settlement;
  });
  storage.setItem(STORAGE_KEY,JSON.stringify(db));
  const server=createMockServer(storage,()=>now),finance=server.dispatch('POST','/v1/auth/mock-login',{role:'finance'}).data as Session;
  const batches=()=>server.dispatch('GET','/v1/admin/settlements',{},finance.token).data as Settlement[];
  assert.equal(batches().length,2);assert.equal(batches().find(b=>b.id==='legacy-0')!.paidAt,now-30000);
  assert.equal(batches().find(b=>b.id==='legacy-1')!.paidAt,now);
  assert.ok(batches().every(b=>b.status==='paid'));
  const before=batches();const second=createMockServer(storage,()=>now+1000);
  second.dispatch('GET','/v1/admin/settlements',{},finance.token);
  assert.deepEqual(batches(),before);
  assert.ok(server.read().orders.every(o=>o.timeline.filter(e=>e.label==='系统自动结算完成（模拟）').length===1));
});

test('待审售后暂停自动结算，部分退款批准后按净额自动结算，已结算订单拒绝再次退款',()=>{
  const storage=new MemoryStorage(),db=createSeed();
  const order=db.orders.find(o=>o.status==='completed'&&o.refundStatus==='pending')!;
  db.orders=[order];storage.setItem(STORAGE_KEY,JSON.stringify(db));
  const server=createMockServer(storage),admin=server.dispatch('POST','/v1/auth/mock-login',{role:'admin'}).data as Session;
  assert.equal(server.read().settlements.length,0);
  const reviewed=server.dispatch('POST',`/v1/orders/${order.id}/review-refund`,{approve:true,note:'核实后退还未服务部分'},admin.token).data as Order;
  const batch=server.read().settlements[0];
  assert.equal(batch.status,'paid');assert.equal(batch.amount,Math.floor((order.amount-order.refundAmount!)*.8));
  assert.equal(reviewed.team[0].settlementId,batch.id);
  assert.throws(()=>server.dispatch('POST',`/v1/orders/${order.id}/refund`,{reason:'再次申请重复退款',amount:1},admin.token),/已有退款申请|结算/);
});
