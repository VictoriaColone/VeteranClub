import test from 'node:test';
import assert from 'node:assert/strict';
import { createMockServer, MemoryStorage, ApiError, type Role, type Order, type Session, type Wallet, type Settlement, type Application, type Player, type Product, participantEarnings, STORAGE_KEY } from '../packages/core/src/index';
function fixture(qualifyPublisher=true){let now=Date.now();const storage=new MemoryStorage();const server=createMockServer(storage,()=>now);const login=(role:Role)=>server.dispatch('POST','/v1/auth/mock-login',{role}).data as Session;const boss=login('boss'),player=login('player'),admin=login('admin'),cs=login('customer_service'),finance=login('finance');const call=<T>(session:Session,method:string,path:string,body?:unknown)=>server.dispatch(method,path,body,session.token).data as T;if(qualifyPublisher){for(const serviceType of ['hourly','escort']){const a=call<Application>(boss,'POST','/v1/applications',{serviceType,gameIds:server.read().games.map(g=>g.id),introduction:'测试用户申请对应游戏的发单与接单资格。'});call(admin,'POST',`/v1/admin/applications/${a.id}/review`,{status:'assessment',note:'资料审核通过并安排专项考核'});call(admin,'POST',`/v1/admin/applications/${a.id}/review`,{status:'approved',score:90,note:'对应类型全部申请游戏考核通过'});}}const create=()=>call<Order>(boss,'POST','/v1/orders',{gameId:'valorant',region:'国服 · 华东',quantity:2});return {server,storage,boss,player,admin,cs,finance,call,create,advance:(ms:number)=>{now+=ms;}};}
test('用户付款、成员履约、用户确认后自动结算：完整闭环',()=>{const f=fixture();let o=f.create();assert.equal(o.status,'unpaid');o=f.call(f.boss,'POST',`/v1/orders/${o.id}/pay`);assert.equal(o.status,'waiting');o=f.call(f.player,'POST',`/v1/orders/${o.id}/accept`);assert.equal(o.playerId,'p1');o=f.call(f.player,'POST',`/v1/orders/${o.id}/start`);f.advance(2*3600000);o=f.call(f.player,'POST',`/v1/orders/${o.id}/finish`,{note:'已完成两小时服务'});assert.equal(o.status,'confirming');o=f.call(f.boss,'POST',`/v1/orders/${o.id}/confirm`);assert.equal(o.status,'completed');const wallet=f.call<Wallet>(f.player,'GET','/v1/player/wallet');const paid=wallet.settlements.find(b=>b.orderIds.includes(o.id))!;assert.equal(paid.status,'paid');assert.equal(paid.amount,o.earnings);assert.ok(paid.paidAt);assert.equal(o.team[0].settlementId,paid.id);});
test('重复支付通知不会创建第二笔收款',()=>{const f=fixture(),o=f.create();const first=f.call<Order>(f.boss,'POST',`/v1/orders/${o.id}/pay`);const second=f.call<Order>(f.boss,'POST',`/v1/orders/${o.id}/pay`);assert.equal(second.paymentId,first.paymentId);assert.equal(second.timeline.filter(t=>t.label==='模拟微信支付成功').length,1);});
test('同一订单只能被接取一次，成员不能同时接两笔未完成订单',()=>{const f=fixture();const a=f.create(),b=f.create();f.call(f.boss,'POST',`/v1/orders/${a.id}/pay`);f.call(f.boss,'POST',`/v1/orders/${b.id}/pay`);f.call(f.player,'POST',`/v1/orders/${a.id}/accept`);assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${a.id}/accept`),ApiError);assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${b.id}/accept`),/未完成订单/);});
test('客服可以派单，但不能审核退款、结算或修改配置',()=>{const f=fixture(),o=f.create();f.call(f.boss,'POST',`/v1/orders/${o.id}/pay`);const assigned=f.call<Order>(f.cs,'POST',`/v1/orders/${o.id}/assign`,{playerId:'p1',reason:'客户指定时间安排'});assert.equal(assigned.playerId,'p1');f.call(f.cs,'POST',`/v1/orders/${o.id}/refund`,{reason:'客户申请取消服务',amount:o.amount});assert.throws(()=>f.call(f.cs,'POST',`/v1/orders/${o.id}/review-refund`,{approve:true,note:'核实通过'}),/权限/);assert.throws(()=>f.call(f.cs,'POST','/v1/admin/settlements'),/权限/);assert.throws(()=>f.call(f.cs,'PATCH','/v1/admin/settings',{}),/权限/);});
test('部分退款冻结履约，审核后按净额重算报酬，拒绝重复退款',()=>{const f=fixture(),o=f.create();f.call(f.boss,'POST',`/v1/orders/${o.id}/pay`);f.call(f.boss,'POST',`/v1/orders/${o.id}/refund`,{reason:'希望取消一半时长',amount:6800});assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${o.id}/accept`),/不可接单|售后/);const result=f.call<Order>(f.finance,'POST',`/v1/orders/${o.id}/review-refund`,{approve:true,note:'已核实未服务部分'});assert.equal(result.refundStatus,'refunded');assert.equal(result.earnings,Math.floor((o.amount-6800)*.8));assert.throws(()=>f.call(f.finance,'POST',`/v1/orders/${o.id}/review-refund`,{approve:true,note:'重复审核'}),/已被处理/);});
test('拒绝退款保留原报酬，允许重新申请',()=>{const f=fixture(),o=f.create();f.call(f.boss,'POST',`/v1/orders/${o.id}/pay`);f.call(f.boss,'POST',`/v1/orders/${o.id}/refund`,{reason:'申请取消服务看看',amount:o.amount});const reviewed=f.call<Order>(f.finance,'POST',`/v1/orders/${o.id}/review-refund`,{approve:false,note:'请补充具体凭证'});assert.equal(reviewed.earnings,o.earnings);const reapplied=f.call<Order>(f.boss,'POST',`/v1/orders/${o.id}/refund`,{reason:'补充凭证后重新申请',amount:o.amount});assert.equal(reapplied.refundStatus,'pending');});
test('全额退款关闭订单且报酬归零',()=>{const f=fixture(),o=f.create();f.call(f.boss,'POST',`/v1/orders/${o.id}/pay`);f.call(f.boss,'POST',`/v1/orders/${o.id}/refund`,{reason:'不再需要本次服务',amount:o.amount});const r=f.call<Order>(f.finance,'POST',`/v1/orders/${o.id}/review-refund`,{approve:true,note:'核实同意'});assert.equal(r.status,'cancelled');assert.equal(r.earnings,0);});
test('交易规则固定且价格调整不会追溯修改已有订单',()=>{const f=fixture(),o=f.create();assert.throws(()=>f.call(f.admin,'PATCH','/v1/admin/settings',{name:'诺曼部落',commissionBps:3000,unpaidTimeoutMinutes:15,announcement:'测试公告'}),/交易规则固定/);const next=f.create();assert.equal(next.commissionBps,2000);assert.equal(next.earnings,Math.floor(next.amount*.8));f.call(f.admin,'PATCH','/v1/admin/players/p1',{price:9900});assert.equal(f.call<Order>(f.admin,'GET',`/v1/orders/${o.id}`).amount,o.amount);});
test('待支付订单恰好十分钟自动关闭，不能补付',()=>{const f=fixture(),o=f.create();f.advance(10*60000-1);assert.equal(f.call<Order>(f.admin,'GET',`/v1/orders/${o.id}`).status,'unpaid');f.advance(1);assert.throws(()=>f.call(f.boss,'POST',`/v1/orders/${o.id}/pay`),/已关闭/);const closed=f.call<Order>(f.admin,'GET',`/v1/orders/${o.id}`);assert.equal(closed.status,'cancelled');assert.equal(closed.timeline.filter(e=>e.label==='超时未支付，订单已关闭').length,1);});
test('退款金额、区服、角色及越权操作受到模拟接口约束',()=>{const f=fixture(),o=f.create();assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${o.id}/pay`),/自己的订单/);assert.throws(()=>f.call(f.boss,'POST','/v1/orders',{gameId:'valorant',region:'无效区服',quantity:1}),/区服/);f.call(f.boss,'POST',`/v1/orders/${o.id}/pay`);assert.throws(()=>f.call(f.boss,'POST',`/v1/orders/${o.id}/refund`,{reason:'申请超过金额退款',amount:o.amount+1}),/金额/);assert.throws(()=>f.call(f.player,'GET',`/v1/orders/${o.id}`),/无权/);});
test('同一存储的多个模拟服务共享订单，恢复种子后保留会话',()=>{const f=fixture(),o=f.create();const other=createMockServer(f.storage);assert.ok(other.read().orders.some(v=>v.id===o.id));f.call(f.admin,'POST','/v1/demo/reset');assert.ok(!f.server.read().orders.some(v=>v.id===o.id));assert.doesNotThrow(()=>f.call(f.admin,'GET','/v1/admin/dashboard'));});
test('自动结算排除待审退款，重复刷新不会重复结算，旧手动入口停用',()=>{const f=fixture();const pending=f.server.read().orders.find(o=>o.refundStatus==='pending')!;const batches=f.call<Settlement[]>(f.finance,'GET','/v1/admin/settlements');assert.ok(batches.length);assert.ok(batches.every(b=>b.status==='paid'));assert.ok(!batches.flatMap(b=>b.orderIds).includes(pending.id));assert.throws(()=>f.call(f.finance,'POST','/v1/admin/settlements'),/自动结算/);assert.throws(()=>f.call(f.finance,'POST',`/v1/admin/settlements/${batches[0].id}/pay`),/自动结算/);assert.deepEqual(f.call<Settlement[]>(f.finance,'GET','/v1/admin/settlements'),batches);});

const loginUser=(f:ReturnType<typeof fixture>,accountId:string)=>f.server.dispatch('POST','/v1/auth/mock-login',{role:'user',accountId}).data as Session;
const escort=(f:ReturnType<typeof fixture>,taskPrice=12800)=>f.call<Order>(f.boss,'POST','/v1/orders',{serviceType:'escort',gameId:'valorant',region:'国服 · 华东',quantity:1,task:'完成指定任务并成功撤离，以结算截图验收',taskPrice});
const payAndAccept=(f:ReturnType<typeof fixture>,o:Order)=>{f.call(f.boss,'POST',`/v1/orders/${o.id}/pay`);return f.call<Order>(f.player,'POST',`/v1/orders/${o.id}/accept`);};
test('统一用户：有接单资格的账号也能下单，不能接自己的订单',()=>{const f=fixture();const o=f.call<Order>(f.player,'POST','/v1/orders',{serviceType:'hourly',gameId:'valorant',region:'国服 · 华东',quantity:1});f.call(f.player,'POST',`/v1/orders/${o.id}/pay`);assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${o.id}/accept`),/自己/);assert.ok(!f.call<Order[]>(f.player,'GET','/v1/orders?scope=hall').some(v=>v.id===o.id));assert.ok(f.call<Order[]>(f.player,'GET','/v1/orders').some(v=>v.id===o.id));});
test('入驻必须先审核再考核；两类资格独立，并按申请游戏授权',()=>{const f=fixture(false);const user=loginUser(f,'u-boss'),other=loginUser(f,'u-p7');const make=(serviceType:string,gameId='valorant')=>{const o=f.call<Order>(other,'POST','/v1/orders',{serviceType,gameId,region:gameId==='lol'?'艾欧尼亚':'国服 · 华东',quantity:1,task:'完成指定任务并成功撤离',taskPrice:12800});f.call(other,'POST',`/v1/orders/${o.id}/pay`);return o;};const guard=make('escort');assert.throws(()=>f.call(user,'POST',`/v1/orders/${guard.id}/accept`),/审核/);const app=f.call<Application>(user,'POST','/v1/applications',{serviceType:'hourly',gameIds:['valorant'],introduction:'擅长战术沟通和团队协作，周末可参与考核。'});assert.throws(()=>f.call(user,'POST','/v1/applications',{serviceType:'hourly',gameIds:['valorant'],introduction:'擅长战术沟通和团队协作，周末可参与考核。'}),/已有申请/);assert.throws(()=>f.call(f.admin,'POST',`/v1/admin/applications/${app.id}/review`,{status:'approved',score:90,note:'能力符合要求'}),/先通过/);f.call(f.admin,'POST',`/v1/admin/applications/${app.id}/review`,{status:'assessment',note:'资料通过，安排周末考核'});assert.throws(()=>f.call(f.admin,'POST',`/v1/admin/applications/${app.id}/review`,{status:'approved',score:59,note:'成绩不足通过要求'}),/60/);f.call(f.admin,'POST',`/v1/admin/applications/${app.id}/review`,{status:'approved',score:85,note:'沟通及游戏能力考核通过'});f.call(user,'POST','/v1/player/availability',{available:true});assert.throws(()=>f.call(user,'POST',`/v1/orders/${guard.id}/accept`),/审核/);const unsupported=make('hourly','lol');assert.throws(()=>f.call(user,'POST',`/v1/orders/${unsupported.id}/accept`),/审核/);const supported=make('hourly');assert.equal(f.call<Order>(user,'POST',`/v1/orders/${supported.id}/accept`).playerId,'p0');assert.throws(()=>f.call(f.admin,'PATCH','/v1/admin/players/p0',{approved:true}),/考核流程/);assert.throws(()=>f.call(f.cs,'POST',`/v1/admin/applications/${app.id}/review`,{status:'rejected',note:'不应允许客服操作'}),/权限/);});
test('护航需要任务与整单价格，所有游戏都支持两类订单',()=>{const f=fixture();assert.throws(()=>f.call(f.boss,'POST','/v1/orders',{serviceType:'escort',gameId:'valorant',region:'国服 · 华东',quantity:1,taskPrice:12800}),/任务项/);assert.throws(()=>escort(f,NaN),/任务金额/);for(const g of f.server.read().games){for(const serviceType of ['escort','hourly']){const o=f.call<Order>(f.boss,'POST','/v1/orders',{serviceType,gameId:g.id,region:g.regions[0],quantity:1,task:'完成指定地图任务并成功撤离',taskPrice:10000});assert.equal(o.serviceType,serviceType);assert.ok(Number.isInteger(o.amount));}}});
test('时长订单不能提前提交；演示计时仅负责人可操作',()=>{const f=fixture(),o=payAndAccept(f,f.create());f.call(f.player,'POST',`/v1/orders/${o.id}/start`);assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${o.id}/finish`,{note:'提前提交完成'}),/时长尚未完成/);assert.throws(()=>f.call(f.boss,'POST',`/v1/orders/${o.id}/demo-elapse`),/分配给自己/);f.call(f.player,'POST',`/v1/orders/${o.id}/demo-elapse`);assert.equal(f.call<Order>(f.player,'POST',`/v1/orders/${o.id}/finish`,{note:'约定时长服务完成'}).status,'confirming');});
test('护航协作：成员加入、状态锁定、任务验收、各自钱包及独立结算',()=>{const f=fixture(),assistant=loginUser(f,'u-p7');let o=payAndAccept(f,escort(f,104));o=f.call(f.player,'POST',`/v1/orders/${o.id}/add-assistant`,{playerId:'p7'});assert.equal(o.team.length,2);assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${o.id}/add-assistant`,{playerId:'p7'}),/已在/);assert.throws(()=>f.call(assistant,'POST',`/v1/orders/${o.id}/add-assistant`,{playerId:'p3'}),/分配给自己/);assert.ok(f.call<Order[]>(assistant,'GET','/v1/orders').some(v=>v.id===o.id));assert.equal(f.call<Order>(assistant,'GET',`/v1/orders/${o.id}`).id,o.id);assert.equal(participantEarnings(o,'p1'),42);assert.equal(participantEarnings(o,'p7'),41);f.call(f.player,'POST',`/v1/orders/${o.id}/start`);assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${o.id}/remove-assistant`,{playerId:'p7'}),/不能变更/);assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${o.id}/finish`,{note:'提交任务完成记录'}),/确认指定任务/);assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${o.id}/demo-elapse`),/仅时长/);assert.throws(()=>f.call(assistant,'POST',`/v1/orders/${o.id}/finish`,{taskCompleted:true,note:'副手不能替负责人提交'}),/分配给自己/);f.call(f.player,'POST',`/v1/orders/${o.id}/finish`,{taskCompleted:true,note:'指定任务已经达成，以结算记录验收'});f.call(f.boss,'POST',`/v1/orders/${o.id}/confirm`);assert.equal(f.call<Wallet>(assistant,'GET','/v1/player/wallet').total,41);const lead=f.call<Settlement[]>(f.finance,'GET','/v1/admin/settlements').find(b=>b.playerId==='p1'&&b.orderIds.includes(o.id))!;const wallet=f.call<Wallet>(assistant,'GET','/v1/player/wallet');assert.equal(lead.status,'paid');assert.equal(lead.amount,42);assert.equal(wallet.settlements.find(b=>b.orderIds.includes(o.id))!.amount,41);assert.equal(wallet.available,0);assert.equal(wallet.settled,41);assert.equal(f.call<{pendingPayout:number}>(f.admin,'GET','/v1/admin/dashboard').pendingPayout,0);});
test('副手同样受资质、占单、自接单及售后冻结约束，时长单不能多人接取',()=>{const f=fixture();const o=payAndAccept(f,escort(f));assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${o.id}/add-assistant`,{playerId:'p0'}),/考核/);assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${o.id}/add-assistant`,{playerId:'p3'}),/未完成订单/);f.call(f.player,'POST',`/v1/orders/${o.id}/add-assistant`,{playerId:'p7'});const next=f.call<Order>(f.boss,'POST','/v1/orders',{gameId:'valorant',region:'国服 · 华东',quantity:1});f.call(f.boss,'POST',`/v1/orders/${next.id}/pay`);assert.throws(()=>f.call(loginUser(f,'u-p7'),'POST',`/v1/orders/${next.id}/accept`),/未完成订单/);f.call(f.player,'POST',`/v1/orders/${o.id}/remove-assistant`,{playerId:'p7'});const hourly=f.call<Order>(loginUser(f,'u-p7'),'POST',`/v1/orders/${next.id}/accept`);assert.throws(()=>f.call(loginUser(f,'u-p7'),'POST',`/v1/orders/${hourly.id}/add-assistant`,{playerId:'p1'}),/仅待开始的护航/);f.call(f.boss,'POST',`/v1/orders/${o.id}/refund`,{amount:100,reason:'核实本次服务内容'});assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${o.id}/add-assistant`,{playerId:'p7'}),/仅待开始/);});
test('护航部分退款后重新均分，分配之和始终等于净报酬',()=>{const f=fixture();let o=payAndAccept(f,escort(f,10001));o=f.call(f.player,'POST',`/v1/orders/${o.id}/add-assistant`,{playerId:'p7'});f.call(f.boss,'POST',`/v1/orders/${o.id}/refund`,{amount:17,reason:'协商退还未完成部分'});o=f.call(f.finance,'POST',`/v1/orders/${o.id}/review-refund`,{approve:true,note:'核实后部分退款'});const shares=o.team.map(m=>participantEarnings(o,m.playerId));assert.equal(shares.reduce((a,b)=>a+b,0),o.earnings);assert.ok(Math.max(...shares)-Math.min(...shares)<=1);});
test('旧版模拟数据升级时保留原订单、会话、已结算份额并转换历史文案',()=>{const f=fixture();const db:any=f.server.read();db.schema=1;delete db.applications;db.players.forEach((p:any)=>delete p.qualifications);db.orders.forEach((o:any)=>{delete o.serviceType;delete o.team;delete o.task;});db.orders[0].timeline[0].label='老板下单，打手接单';f.storage.setItem(STORAGE_KEY,JSON.stringify(db));const upgraded=createMockServer(f.storage).read();assert.equal(upgraded.schema,2);assert.equal(upgraded.orders.length,db.orders.length);assert.equal(upgraded.sessions.length,db.sessions.length);assert.equal(upgraded.orders[0].timeline[0].label,'用户下单，队友接单');const settled=upgraded.orders.find(o=>o.settlementId)!;assert.equal(settled.team[0].settlementId,settled.settlementId);assert.doesNotThrow(()=>f.call(f.admin,'GET','/v1/admin/dashboard'));});

for(const serviceType of ['hourly','escort'] as const){
  test(`${serviceType}：新户、资料审核中、考核中及未通过时均无商品发布权限`,()=>{
    const f=fixture(false),user=loginUser(f,'u-boss');
    const input={serviceType,gameId:'valorant',title:'测试服务商品',description:'认真提供对应类型的游戏服务',price:12800,task:'100w纯带出'};
    const blocked=()=>assert.throws(()=>f.call(user,'POST','/v1/products',input),(e:unknown)=>e instanceof ApiError&&e.code===403&&e.message.includes('商品发布资格'));
    assert.deepEqual(f.server.read().players.find(p=>p.userId===user.userId)!.qualifications,[]);blocked();
    const application=f.call<Application>(user,'POST','/v1/applications',{serviceType,gameIds:['valorant'],introduction:'申请无畏契约对应类型的发单与接单考核。'});blocked();
    f.call(f.admin,'POST',`/v1/admin/applications/${application.id}/review`,{status:'assessment',note:'资料审核通过，等待专项考核'});blocked();
    f.call(f.admin,'POST',`/v1/admin/applications/${application.id}/review`,{status:'rejected',note:'本次考核未达到通过标准'});blocked();
  });
  test(`${serviceType}：考核仅解锁该类型及游戏的商品发布、接单权限，另一类型保持锁定`,()=>{
    const f=fixture(false),user=loginUser(f,'u-boss'),other=loginUser(f,'u-p7');
    const input=(type:string,gameId='valorant')=>({serviceType:type,gameId,region:gameId==='valorant'?'国服 · 华东':'艾欧尼亚',quantity:1,task:'完成指定任务并成功撤离',taskPrice:12800,title:'测试服务商品',description:'认真提供对应类型的游戏服务',price:12800});
    const application=f.call<Application>(user,'POST','/v1/applications',{serviceType,gameIds:['valorant'],introduction:'申请无畏契约对应类型的发单与接单考核。'});
    f.call(f.admin,'POST',`/v1/admin/applications/${application.id}/review`,{status:'assessment',note:'资料审核通过，安排专项考核'});
    f.call(f.admin,'POST',`/v1/admin/applications/${application.id}/review`,{status:'approved',score:90,note:'本次申请游戏的专项考核通过'});
    assert.equal(f.call<Product>(user,'POST','/v1/products',input(serviceType)).serviceType,serviceType);
    assert.throws(()=>f.call(user,'POST','/v1/products',input(serviceType,'lol')),/商品发布资格/);
    const otherType=serviceType==='hourly'?'escort':'hourly';
    assert.throws(()=>f.call(user,'POST','/v1/products',input(otherType)),/商品发布资格/);
    const accepted=f.call<Order>(other,'POST','/v1/orders',input(serviceType));f.call(other,'POST',`/v1/orders/${accepted.id}/pay`);
    const locked=f.call<Order>(other,'POST','/v1/orders',input(otherType));f.call(other,'POST',`/v1/orders/${locked.id}/pay`);
    f.call(user,'POST','/v1/player/availability',{available:true});
    assert.throws(()=>f.call(user,'POST',`/v1/orders/${locked.id}/accept`),/审核/);
    assert.equal(f.call<Order>(user,'POST',`/v1/orders/${accepted.id}/accept`).playerId,'p0');
    f.call(f.admin,'PATCH','/v1/admin/players/p0',{approved:false});
    assert.throws(()=>f.call(user,'POST','/v1/products',input(serviceType)),/商品发布资格/);
  });
}

test('普通新户可快速下单及模拟支付，客户资格撤销不影响其购物权限',()=>{const f=fixture(false);for(const serviceType of ['hourly','escort']){const o=f.call<Order>(f.boss,'POST','/v1/orders',{serviceType,gameId:'valorant',region:'国服 · 华东',quantity:1,task:'100w纯带出',taskPrice:12800});assert.equal(f.call<Order>(f.boss,'POST',`/v1/orders/${o.id}/pay`).status,'waiting');assert.equal(f.call<Order>(f.boss,'POST',`/v1/orders/${o.id}/pay`).status,'waiting');}assert.throws(()=>f.call(f.boss,'POST','/v1/products',{serviceType:'hourly',gameId:'valorant',title:'尚未认证商品',description:'未通过考核不能发布该商品',price:6800}),/商品发布资格/);});

test('四种资格状态均可独立保存，单资格用户仅可发布对应类型商品、接取对应类型订单',()=>{
  const f=fixture(false),publisher=loginUser(f,'u-p7');
  const profiles=f.server.read().players;
  assert.deepEqual(profiles.find(p=>p.userId==='u-boss')!.qualifications,[]);
  assert.deepEqual(profiles.find(p=>p.userId==='u-hourly')!.qualifications,['hourly']);
  assert.deepEqual(profiles.find(p=>p.userId==='u-escort')!.qualifications,['escort']);
  assert.deepEqual(profiles.find(p=>p.userId==='u-player')!.qualifications,['hourly','escort']);
  for(const [accountId,allowed,locked] of [['u-hourly','hourly','escort'],['u-escort','escort','hourly']]){
    const user=loginUser(f,accountId);
    const input=(serviceType:string)=>({serviceType,gameId:'valorant',region:'国服 · 华东',quantity:1,task:'完成指定任务并成功撤离',taskPrice:12800,title:'测试服务商品',description:'认真提供对应类型的游戏服务',price:12800});
    assert.equal(f.call<Product>(user,'POST','/v1/products',input(allowed)).serviceType,allowed);
    assert.throws(()=>f.call(user,'POST','/v1/products',input(locked)),/商品发布资格/);
    const lockedOrder=f.call<Order>(publisher,'POST','/v1/orders',input(locked));f.call(publisher,'POST',`/v1/orders/${lockedOrder.id}/pay`);
    assert.throws(()=>f.call(user,'POST',`/v1/orders/${lockedOrder.id}/accept`),/审核/);
    const allowedOrder=f.call<Order>(publisher,'POST','/v1/orders',input(allowed));f.call(publisher,'POST',`/v1/orders/${allowedOrder.id}/pay`);
    assert.equal(f.call<Order>(user,'POST',`/v1/orders/${allowedOrder.id}/accept`).playerId,profiles.find(p=>p.userId===accountId)!.id);
    const app=f.call<Application>(user,'POST','/v1/applications',{serviceType:locked,gameIds:['valorant'],introduction:'独立申请尚未开通类型的专项考核资格。'});
    f.call(f.admin,'POST',`/v1/admin/applications/${app.id}/review`,{status:'rejected',note:'本次新方向考核未通过'});
    assert.equal(f.call<Product>(user,'POST','/v1/products',input(allowed)).serviceType,allowed);
    assert.throws(()=>f.call(user,'POST','/v1/products',input(locked)),/商品发布资格/);
  }
});
test('单资格样例升级不覆盖已有用户资格、游戏范围、订单和审核记录',()=>{
  const f=fixture();const db=f.server.read();const p=db.players.find(p=>p.id==='p0')!;p.qualifications=['escort'];p.qualificationGames={escort:['lol']};db.schema=2;db.players=db.players.filter(p=>!['p-hourly','p-escort'].includes(p.id));f.storage.setItem(STORAGE_KEY,JSON.stringify(db));
  const migrated=f.server.read();assert.equal(migrated.schema,2);assert.deepEqual(migrated.players.find(p=>p.id==='p0')!.qualifications,['escort']);assert.deepEqual(migrated.players.find(p=>p.id==='p0')!.qualificationGames,{escort:['lol']});assert.deepEqual(migrated.orders,db.orders);assert.deepEqual(migrated.applications,db.applications);assert.deepEqual(migrated.sessions,db.sessions);assert.equal(migrated.players.filter(p=>p.id==='p-hourly').length,1);assert.equal(f.server.read().players.length,migrated.players.length);
});

test('游戏目录仅三角洲、无畏契约、英雄联盟，每款均支持时长组队与任务护航',()=>{
  const f=fixture();assert.deepEqual(f.server.read().games.map(g=>g.name),['三角洲','无畏契约','英雄联盟']);
  for(const game of f.server.read().games){
    const hourly=f.call<Order>(f.boss,'POST','/v1/orders',{serviceType:'hourly',gameId:game.id,region:game.regions[0],quantity:3,task:'组队忽略任务项'});
    assert.equal(hourly.quantity,3);assert.equal(hourly.amount,hourly.unitPrice*3);assert.equal(hourly.task,'');
    const guard=f.call<Order>(f.boss,'POST','/v1/orders',{serviceType:'escort',gameId:game.id,region:game.regions[0],quantity:1,task:'100w纯带出',taskPrice:12800});assert.equal(guard.task,'100w纯带出');assert.equal(guard.amount,12800);
  }
  for(const gameId of ['hok','pubg'])assert.throws(()=>f.call(f.boss,'POST','/v1/orders',{serviceType:'hourly',gameId,region:'微信区',quantity:1}),/请选择游戏/);
  assert.ok(f.server.read().orders.filter(o=>o.serviceType==='escort'&&o.id!=='').every(o=>typeof o.task==='string'&&o.task.length>0));
});
test('护航任务项严格要求非空字符串，不能将数字、对象或数组强制转换成任务',()=>{
  const f=fixture();const input={serviceType:'escort',gameId:'delta',region:'国服',quantity:1,taskPrice:12800};
  for(const task of [undefined,null,'','  \n ',123,['100w纯带出'],{task:'100w纯带出'},'x'.repeat(201)])assert.throws(()=>f.call(f.boss,'POST','/v1/orders',{...input,task}),/任务项/);
  assert.equal(f.call<Order>(f.boss,'POST','/v1/orders',{...input,task:' 100w纯带出 '}).task,'100w纯带出');
  assert.equal(f.call<Order>(f.boss,'POST','/v1/orders',{...input,task:'撤离'}).task,'撤离');
});
test('新样例护航任务统一默认100w纯带出，旧目录迁移保留自定义任务和财务信息',()=>{
  const f=fixture();assert.ok(f.server.read().orders.filter(o=>o.serviceType==='escort').every(o=>o.task==='100w纯带出'));
  const custom=f.call<Order>(f.boss,'POST','/v1/orders',{serviceType:'escort',gameId:'valorant',region:'国服 · 华东',quantity:1,task:'完成自定义地图目标',taskPrice:10000});
  const db=f.server.read();delete db.catalogRevision;db.games=[{id:'hok',name:'王者荣耀',short:'K',color:'#abc',tagline:'旧样例',regions:['微信区']}];
  const original=db.orders.find(o=>o.id!==custom.id&&o.serviceType==='escort')!;original.gameId='hok';original.gameName='王者荣耀';original.region='微信区';original.task='完成指定地图任务并成功撤离';const financial={amount:original.amount,earnings:original.earnings,status:original.status,team:original.team};
  const p=db.players.find(p=>p.id==='p0')!;p.gameIds=['hok','pubg','valorant'];p.qualificationGames={hourly:['hok'],escort:['pubg']};f.storage.setItem(STORAGE_KEY,JSON.stringify(db));
  const migrated=f.server.read();const o=migrated.orders.find(o=>o.id===original.id)!;assert.equal(o.gameName,'三角洲');assert.equal(o.gameId,'delta');assert.equal(o.task,'100w纯带出');assert.deepEqual({amount:o.amount,earnings:o.earnings,status:o.status,team:o.team},financial);assert.equal(migrated.orders.find(o=>o.id===custom.id)!.task,custom.task);assert.ok(o.timeline.some(e=>e.label==='演示游戏配置已更新'));
  assert.deepEqual(migrated.players.find(p=>p.id==='p0')!.gameIds,['delta','valorant']);assert.deepEqual(migrated.players.find(p=>p.id==='p0')!.qualificationGames,{hourly:['delta'],escort:['delta']});assert.equal(f.server.read().orders.find(v=>v.id===o.id)!.timeline.length,o.timeline.length);
});

const goodsInput=(serviceType='hourly',gameId='valorant')=>({serviceType,gameId,title:'默契游戏服务',description:'认真沟通，按约定完成对应游戏服务。',price:serviceType==='hourly'?9900:12800,task:'100w纯带出'});
test('发布商品独立于订单和钱包，能持久化、上下架且只能本人管理',()=>{
  const f=fixture(false);const before=f.server.read();const p=f.call<Product>(f.player,'POST','/v1/products',goodsInput());const after=f.server.read();assert.equal(after.orders.length,before.orders.length);assert.equal(after.settlements.length,before.settlements.length);assert.equal(p.playerId,'p1');assert.equal(p.price,9900);assert.equal(p.task,'');assert.ok(f.call<Product[]>(f.boss,'GET','/v1/products').some(v=>v.id===p.id));assert.ok(f.call<Product[]>(f.player,'GET','/v1/products?scope=mine').some(v=>v.id===p.id));
  assert.throws(()=>f.call(f.boss,'PATCH',`/v1/products/${p.id}`,{active:false}),/自己的商品/);f.call(f.player,'PATCH',`/v1/products/${p.id}`,{active:false});assert.ok(!f.call<Product[]>(f.boss,'GET','/v1/products').some(v=>v.id===p.id));f.call(f.player,'PATCH',`/v1/products/${p.id}`,{active:true});assert.ok(f.call<Product[]>(f.boss,'GET','/v1/products').some(v=>v.id===p.id));
});
test('商品下单快照采用服务端商品价格、任务和服务成员，不能篡改或购买自己商品',()=>{
  const f=fixture(false),provider=loginUser(f,'u-escort');const product=f.call<Product>(provider,'POST','/v1/products',goodsInput('escort','delta'));
  const input={productId:product.id,serviceType:'escort',gameId:'delta',region:'国服',quantity:1,task:'恶意替换任务',taskPrice:100};const order=f.call<Order>(f.boss,'POST','/v1/orders',input);assert.equal(order.amount,product.price);assert.equal(order.task,product.task);assert.equal(order.preferredPlayerId,product.playerId);assert.equal(order.productTitle,product.title);assert.throws(()=>f.call(provider,'POST','/v1/orders',input),/不可预约/);
  assert.throws(()=>f.call(f.boss,'POST','/v1/orders',{...input,playerId:'p1'}),/服务成员不匹配/);assert.throws(()=>f.call(f.boss,'POST','/v1/orders',{...input,gameId:'valorant'}),/不一致/);f.call(provider,'PATCH',`/v1/products/${product.id}`,{active:false});assert.throws(()=>f.call(f.boss,'POST',`/v1/orders/${order.id}/pay`),/下架/);assert.throws(()=>f.call(f.boss,'POST','/v1/orders',input),/下架/);
});
test('公共大厅排除所有指定成员订单，指定人只能从自己的邀请中接受',()=>{
  const f=fixture(false);const input={serviceType:'hourly',gameId:'valorant',region:'国服 · 华东',quantity:1};const publicOrder=f.call<Order>(f.boss,'POST','/v1/orders',input),privateOrder=f.call<Order>(f.boss,'POST','/v1/orders',{...input,playerId:'p1'});for(const o of [publicOrder,privateOrder])f.call(f.boss,'POST',`/v1/orders/${o.id}/pay`);
  const list=f.call<Order[]>(f.player,'GET','/v1/orders?scope=hall');assert.ok(list.some(o=>o.id===publicOrder.id));assert.ok(list.every(o=>!o.preferredPlayerId));assert.ok(!list.some(o=>o.id===privateOrder.id));assert.ok(f.call<Order[]>(f.player,'GET','/v1/orders').some(o=>o.id===privateOrder.id));assert.equal(f.call<Order>(f.player,'GET',`/v1/orders/${privateOrder.id}`).id,privateOrder.id);assert.throws(()=>f.call(loginUser(f,'u-hourly'),'GET',`/v1/orders/${privateOrder.id}`),/无权/);assert.equal(f.call<Order>(f.player,'POST',`/v1/orders/${privateOrder.id}/accept`).playerId,'p1');
});
test('立即抢单遵守游戏、类型、公开状态、占单及资格，不能重复抢取',()=>{
  const f=fixture(false);const user=loginUser(f,'u-hourly');const input={serviceType:'hourly',gameId:'valorant',region:'国服 · 华东',quantity:1};const privateOrder=f.call<Order>(f.boss,'POST','/v1/orders',{...input,playerId:'p-hourly'});f.call(f.boss,'POST',`/v1/orders/${privateOrder.id}/pay`);const publicOrder=f.call<Order>(f.boss,'POST','/v1/orders',input);f.call(f.boss,'POST',`/v1/orders/${publicOrder.id}/pay`);
  assert.throws(()=>f.call(f.boss,'POST','/v1/orders/grab',{serviceType:'hourly',gameId:'valorant'}),/开启接单|考核/);assert.throws(()=>f.call(user,'POST','/v1/orders/grab',{serviceType:'escort',gameId:'valorant'}),/考核/);const accepted=f.call<Order>(user,'POST','/v1/orders/grab',{serviceType:'hourly',gameId:'valorant'});assert.equal(accepted.id,publicOrder.id);assert.equal(accepted.preferredPlayerId,undefined);assert.equal(accepted.playerId,'p-hourly');assert.throws(()=>f.call(user,'POST','/v1/orders/grab',{serviceType:'hourly',gameId:'valorant'}),/未完成订单/);assert.equal(f.call<Order>(f.admin,'GET',`/v1/orders/${privateOrder.id}`).status,'waiting');
});
test('商品资格撤销后不再公开展示或上架，旧版升级补充商品但不重置业务数据',()=>{
  const f=fixture(false);const product=f.call<Product>(f.player,'POST','/v1/products',goodsInput());f.call(f.admin,'PATCH','/v1/admin/players/p1',{approved:false});assert.ok(!f.call<Product[]>(f.boss,'GET','/v1/products').some(p=>p.id===product.id));assert.throws(()=>f.call(f.player,'PATCH',`/v1/products/${product.id}`,{active:true}),/考核资格/);
  const before=f.server.read();delete before.products;f.storage.setItem(STORAGE_KEY,JSON.stringify(before));const upgraded=f.server.read();assert.ok(upgraded.products!.length>0);assert.deepEqual(upgraded.orders,before.orders);assert.deepEqual(upgraded.sessions,before.sessions);assert.deepEqual(upgraded.applications,before.applications);
});

test('组队商品价格按所选小时数快照计费，指定商品订单不进入公共大厅',()=>{const f=fixture(false);const p=f.call<Product>(f.player,'POST','/v1/products',goodsInput('hourly'));const o=f.call<Order>(f.boss,'POST','/v1/orders',{productId:p.id,serviceType:'hourly',gameId:p.gameId,region:'国服 · 华东',quantity:3,taskPrice:1});assert.equal(o.amount,29700);assert.equal(o.unitPrice,9900);assert.equal(o.quantity,3);assert.equal(o.task,'');f.call(f.boss,'POST',`/v1/orders/${o.id}/pay`);assert.ok(!f.call<Order[]>(f.player,'GET','/v1/orders?scope=hall').some(v=>v.id===o.id));});

for(const serviceType of ['hourly','escort'] as const)test(`${serviceType}：不能直接指定自己下单，也不能通过自己的商品绕过限制`,()=>{
  const f=fixture(false),member=f.player;
  const product=f.call<Product>(member,'POST','/v1/products',{serviceType,gameId:'valorant',title:'自购拦截测试商品',description:'验证同一用户不能作为自己的服务成员。',price:12800,task:'100w纯带出'});
  const input={serviceType,gameId:'valorant',region:'国服 · 华东',quantity:1,task:'100w纯带出',taskPrice:12800};
  const before=f.server.read().orders.length;
  assert.throws(()=>f.call(member,'POST','/v1/orders',{...input,playerId:member.playerId}),/不可预约/);
  assert.throws(()=>f.call(member,'POST','/v1/orders',{...input,productId:product.id}),/不可预约/);
  assert.equal(f.server.read().orders.length,before);
  const other=f.call<Order>(f.boss,'POST','/v1/orders',{...input,productId:product.id});
  assert.equal(other.preferredPlayerId,member.playerId);assert.notEqual(other.bossId,member.userId);
});

for(const serviceType of ['hourly','escort'] as const)test(`${serviceType}：个人服务商品可重复预约，服务中与离线不下架、不阻止邀请`,()=>{
  const f=fixture(false),member=f.player,other=loginUser(f,'u-hourly');
  const product=f.call<Product>(member,'POST','/v1/products',goodsInput(serviceType,'valorant'));
  const input={productId:product.id,serviceType,gameId:'valorant',region:'国服 · 华东',quantity:1,task:'100w纯带出',taskPrice:100};
  const catalog=()=>f.call<Product[]>(f.boss,'GET','/v1/products');
  const profile=()=>f.call<Player[]>(f.boss,'GET','/v1/players').find(p=>p.id===member.playerId)!;
  const createPaid=(buyer:Session)=>{const order=f.call<Order>(buyer,'POST','/v1/orders',input);return f.call<Order>(buyer,'POST',`/v1/orders/${order.id}/pay`);};
  const first=createPaid(f.boss);f.call(member,'POST',`/v1/orders/${first.id}/accept`);f.call(member,'POST',`/v1/orders/${first.id}/start`);
  assert.equal(profile().busy,true);assert.ok(catalog().some(p=>p.id===product.id));
  const second=createPaid(other);assert.equal(second.status,'waiting');assert.equal(second.preferredPlayerId,member.playerId);
  assert.throws(()=>f.call(member,'POST',`/v1/orders/${second.id}/accept`),/未完成订单/);
  f.call(member,'POST','/v1/player/availability',{available:false});
  const third=createPaid(f.boss);assert.equal(third.status,'waiting');assert.equal(profile().available,false);
  assert.ok(catalog().some(p=>p.id===product.id));assert.equal(f.call<Order[]>(other,'GET','/v1/orders?scope=hall').some(o=>o.id===third.id),false);
  if(serviceType==='hourly')f.call(member,'POST',`/v1/orders/${first.id}/demo-elapse`);
  f.call(member,'POST',`/v1/orders/${first.id}/finish`,{note:'本次服务任务已完成',taskCompleted:true});f.call(f.boss,'POST',`/v1/orders/${first.id}/confirm`);
  assert.equal(profile().busy,false);assert.equal(profile().available,false);
  const offline=createPaid(other);assert.equal(offline.status,'waiting');
  assert.throws(()=>f.call(member,'POST',`/v1/orders/${second.id}/accept`),/休息/);
  f.call(member,'POST','/v1/player/availability',{available:true});
  assert.equal(f.call<Order>(member,'POST',`/v1/orders/${second.id}/accept`).status,'accepted');
  assert.ok(catalog().some(p=>p.id===product.id));
  f.call(member,'PATCH',`/v1/products/${product.id}`,{active:false});
  assert.ok(!catalog().some(p=>p.id===product.id));assert.throws(()=>createPaid(f.boss),/下架/);
});

test('快速下单可指定离线成员，实际接单仍需上线且具备对应资格',()=>{
  const f=fixture(false);f.call(f.player,'POST','/v1/player/availability',{available:false});
  const order=f.call<Order>(f.boss,'POST','/v1/orders',{serviceType:'hourly',gameId:'valorant',region:'国服 · 华东',quantity:1,playerId:'p1'});
  f.call(f.boss,'POST',`/v1/orders/${order.id}/pay`);assert.equal(order.preferredPlayerId,'p1');
  assert.throws(()=>f.call(f.player,'POST',`/v1/orders/${order.id}/accept`),/休息/);
  f.call(f.player,'POST','/v1/player/availability',{available:true});
  assert.equal(f.call<Order>(f.player,'POST',`/v1/orders/${order.id}/accept`).status,'accepted');
});
