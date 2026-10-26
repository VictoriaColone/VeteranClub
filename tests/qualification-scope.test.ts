import test from 'node:test';
import assert from 'node:assert/strict';
import {createMockServer,MemoryStorage,STORAGE_KEY,canServe,qualifiedGames,type Application,type Session,type Player,type Order,type ServiceType} from '../packages/core/src/index';
function fixture(){
 const storage=new MemoryStorage(),server=createMockServer(storage);const db=server.read();db.orders=[];storage.setItem(STORAGE_KEY,JSON.stringify(db));
 const login=(role:string,accountId?:string)=>server.dispatch('POST','/v1/auth/mock-login',{role,accountId}).data as Session;
 const user=login('user','u-boss'),buyer=login('user','u-p7'),admin=login('admin');
 const call=<T>(session:Session,method:string,path:string,body?:unknown)=>server.dispatch(method,path,body,session.token).data as T;
 const apply=(type:ServiceType,game:string)=>call<Application>(user,'POST','/v1/applications',{serviceType:type,gameIds:[game],introduction:'申请该游戏该服务的独立资格，参加专项考核。'});
 const approve=(a:Application)=>{call(admin,'POST',`/v1/admin/applications/${a.id}/review`,{status:'assessment',note:'资料审核通过，安排独立考核'});call(admin,'POST',`/v1/admin/applications/${a.id}/review`,{status:'approved',score:90,note:'该游戏该服务专项考核通过'});};
 return {storage,server,user,buyer,admin,call,apply,approve,profile:()=>server.read().players.find(p=>p.id==='p0')!};
}

test('同一类型可同时申请不同游戏，通过后可继续申请；新增组队游戏不会扩展护航权限',()=>{
 const f=fixture(),delta=f.apply('hourly','delta'),valorant=f.apply('hourly','valorant'),escort=f.apply('escort','delta');
 assert.throws(()=>f.apply('hourly','delta'),/已有申请/);f.approve(delta);f.approve(escort);
 assert.deepEqual(qualifiedGames(f.profile(),'hourly'),['delta']);assert.deepEqual(qualifiedGames(f.profile(),'escort'),['delta']);
 assert.equal(canServe(f.profile(),'hourly','valorant'),false);assert.throws(()=>f.apply('hourly','delta'),/已通过考核/);
 f.approve(valorant);assert.deepEqual(qualifiedGames(f.profile(),'hourly'),['delta','valorant']);assert.deepEqual(qualifiedGames(f.profile(),'escort'),['delta']);
 assert.equal(canServe(f.profile(),'escort','valorant'),false);assert.equal(f.apply('hourly','lol').status,'pending');assert.equal(f.apply('escort','valorant').status,'pending');
 assert.equal(f.profile().qualifications.filter(t=>t==='hourly').length,1);
});
for(const game of ['delta','valorant','lol'])for(const type of ['hourly','escort'] as const)test(`仅三角洲双资格：${game} ${type} 发布、抢单、派单按游戏独立校验`,()=>{
 const f=fixture();f.approve(f.apply('hourly','delta'));f.approve(f.apply('escort','delta'));f.call(f.admin,'PATCH',`/v1/admin/players/${f.user.playerId}`,{available:true});
 const g=f.server.read().games.find(g=>g.id===game)!;
 const order=f.call<Order>(f.buyer,'POST','/v1/orders',{productId:`club-${type}-${game}`,serviceType:type,gameId:game,region:g.regions[0],quantity:1});f.call(f.buyer,'POST',`/v1/orders/${order.id}/pay`);
 const publish=()=>f.call(f.user,'POST','/v1/products',{serviceType:type,gameId:game,title:'专项资格测试商品',description:'仅允许通过该游戏该类型考核后发布。',price:6800,task:'100w纯带出'});
 const hall=f.call<Order[]>(f.user,'GET','/v1/orders?scope=hall');
 if(game==='delta'){assert.ok(hall.some(o=>o.id===order.id));assert.doesNotThrow(publish);assert.equal(f.call<Order>(f.user,'POST',`/v1/orders/${order.id}/accept`).status,'accepted');}
 else {assert.ok(!hall.some(o=>o.id===order.id));assert.throws(publish,/游戏.*考核/);assert.throws(()=>f.call(f.user,'POST',`/v1/orders/${order.id}/accept`),/不支持该游戏/);assert.throws(()=>f.call(f.admin,'POST',`/v1/orders/${order.id}/assign`,{playerId:'p0',reason:'越界派单校验'}),/不支持该游戏/);assert.throws(()=>f.call(f.user,'POST','/v1/orders/grab',{serviceType:type,gameId:game}),/游戏.*资格/);}
});

test('每项申请只能提交一个游戏，未提供明确游戏授权的客户端档案不推导接单资格',()=>{
 const f=fixture();assert.throws(()=>f.call(f.user,'POST','/v1/applications',{serviceType:'hourly',gameIds:['delta','lol'],introduction:'多游戏必须拆成独立申请才能分别考核。'}),/一个.*游戏/);
 const p={...f.profile(),approved:true,qualifications:['hourly'] as ServiceType[],gameIds:['delta'],qualificationGames:undefined};assert.deepEqual(qualifiedGames(p,'hourly'),[]);assert.equal(canServe(p,'hourly','delta'),false);
});

test('旧多游戏待审申请拆成独立记录，历史已处理申请和既有订单保留',()=>{
 const f=fixture(),db=f.server.read();const a=f.apply('hourly','delta');const old=f.server.read();old.applications[0].gameIds=['delta','lol'];old.applications.push({...a,id:'legacy-approved',status:'approved',gameIds:['delta','valorant']});
 old.players.find(p=>p.id==='p0')!.qualifications=['escort'];old.players.find(p=>p.id==='p0')!.gameIds=['delta'];delete old.players.find(p=>p.id==='p0')!.qualificationGames;
 f.storage.setItem(STORAGE_KEY,JSON.stringify(old));const migrated=f.server.read();assert.equal(migrated.applications.filter(a=>a.status==='pending').length,2);assert.ok(migrated.applications.filter(a=>a.status==='pending').every(a=>a.gameIds.length===1));
 assert.deepEqual(migrated.applications.find(a=>a.id==='legacy-approved')!.gameIds,['delta','valorant']);assert.deepEqual(migrated.orders,db.orders);assert.deepEqual(qualifiedGames(f.profile(),'escort'),['delta']);assert.equal(canServe(f.profile(),'escort','lol'),false);
});
