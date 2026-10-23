import test from 'node:test';
import assert from 'node:assert/strict';
import {miniProgramLayout} from '../apps/mobile/src/services/layout';
import {createUniStorage} from '../apps/mobile/src/services/storage';
import {createUniHttpTransport} from '../apps/mobile/src/services/transport';
import {parseMockPath,shanghaiDay,clubTime,formatCompactMoney} from '../packages/core/src/runtime';
import {ClubApi,createMockServer,createMockTransport,STORAGE_KEY} from '../packages/core/src/index';

test('胶囊导航、安全区与横屏弹窗计算不会遮挡微信原生控件',()=>{
  const layout=miniProgramLayout({windowWidth:390,windowHeight:844,statusBarHeight:47,safeAreaInsets:{bottom:34}},{top:51,bottom:83,height:32,left:296});
  assert.equal(layout.navHeight,87);assert.equal(layout.topPadding,51);assert.equal(layout.rightPadding,106);assert.equal(layout.safeBottom,34);
  assert.ok(layout.sheetHeight<844-layout.navHeight);
  const fallback=miniProgramLayout({windowWidth:375,windowHeight:667,statusBarHeight:20},{top:0,bottom:0,height:0,left:0});
  assert.equal(fallback.navHeight,64);assert.equal(fallback.safeBottom,0);
  assert.ok(miniProgramLayout({windowWidth:844,windowHeight:390}).sheetHeight<390-44);
});

test('小程序本地存储支持关闭后重建客户端，保留购买、支付与抢单结果',async()=>{
  const saved=new Map<string,unknown>();
  const storage=createUniStorage({getStorageSync:key=>saved.get(key),setStorageSync:(key,value)=>{saved.set(key,value);}});
  assert.equal(storage.getItem('missing'),null);saved.set('invalid',{});assert.equal(storage.getItem('invalid'),null);
  const server=createMockServer(storage);const db=server.read();db.orders=[];storage.setItem(STORAGE_KEY,JSON.stringify(db));
  const buyer=new ClubApi(createMockTransport(storage,0));await buyer.login('user');
  const order=await buyer.createOrder({gameId:'valorant',serviceType:'hourly',region:'国服 · 华东',quantity:1});
  await buyer.act(order.id,'pay');
  const member=new ClubApi(createMockTransport(createUniStorage({getStorageSync:key=>saved.get(key),setStorageSync:(key,value)=>{saved.set(key,value);}}),0));
  await member.login('user','u-hourly');assert.equal((await member.orders({scope:'hall'}))[0].id,order.id);
  const taken=await member.grab({serviceType:'hourly',gameId:'valorant'});assert.equal(taken.id,order.id);assert.equal(taken.status,'accepted');
  assert.equal((await buyer.order(order.id)).playerId,taken.playerId);assert.equal((await member.orders({scope:'hall'})).length,0);
});

test('模拟接口和时间金额工具在没有 URL 与 Intl 的运行环境仍可执行',()=>{
  const oldURL=globalThis.URL,oldIntl=globalThis.Intl;
  try{
    globalThis.URL=undefined as any;globalThis.Intl=undefined as any;
    const values=new Map<string,string>();const server=createMockServer({getItem:key=>values.get(key)||null,setItem:(key,value)=>{values.set(key,value);}});
    const login=server.dispatch('POST','/v1/auth/mock-login',{role:'user'}).data as {token:string};
    assert.equal((server.dispatch('GET','/v1/products?scope=public',{},login.token).data as unknown[]).length>0,true);
    assert.equal(clubTime(Date.parse('2026-12-31T16:05:00Z')),'01/01 00:05');
    assert.equal(shanghaiDay(Date.parse('2026-12-31T16:05:00Z')),'2027-01-01');
    assert.equal(formatCompactMoney(123456789),'1,234,567.89');assert.equal(formatCompactMoney(6800),'68');assert.equal(formatCompactMoney(6850),'68.5');
  }finally{globalThis.URL=oldURL;globalThis.Intl=oldIntl;}
});

test('平台无关路由解析保留筛选、中文、加号、等号以及第一个重复参数',()=>{
  const {route,query}=parseMockPath('/v1/orders/?query=%E4%B8%89%E8%A7%92%E6%B4%B2+%2B&scope=hall&scope=mine&note=a=b#ignored');
  assert.equal(route,'/v1/orders');assert.equal(query.get('query'),'三角洲 +');assert.equal(query.get('scope'),'hall');assert.equal(query.get('note'),'a=b');
});

test('微信 HTTP 将 PATCH 转成带覆盖声明的 POST，H5 保留原方法',async()=>{
  const old=globalThis.uni;const requests:any[]=[];
  try{
    globalThis.uni={request(options:any){requests.push(options);options.success({statusCode:200,data:{code:0,data:{active:false}}});}} as any;
    const mobile=createUniHttpTransport('https://api.example.com/',true);
    assert.deepEqual(await mobile('PATCH','/v1/products/SP-1',{active:false},'session'),{active:false});
    assert.equal(requests[0].url,'https://api.example.com/v1/products/SP-1');assert.equal(requests[0].method,'POST');
    assert.equal(requests[0].header['X-HTTP-Method-Override'],'PATCH');assert.equal(requests[0].header.Authorization,'Bearer session');assert.equal(requests[0].timeout,15000);
    await createUniHttpTransport('https://api.example.com',false)('PATCH','/v1/products/SP-1');assert.equal(requests[1].method,'PATCH');assert.equal(requests[1].header['X-HTTP-Method-Override'],undefined);
  }finally{globalThis.uni=old;}
});

test('网络失败和不合法服务响应会拒绝请求，不报告模拟支付成功',async()=>{
  const old=globalThis.uni;
  try{
    globalThis.uni={request(options:any){options.fail({errMsg:'timeout'});}} as any;
    await assert.rejects(createUniHttpTransport('https://api.example.com',true)('GET','/v1/orders'),/网络连接失败/);
    globalThis.uni={request(options:any){options.success({statusCode:502,data:null});}} as any;
    await assert.rejects(createUniHttpTransport('https://api.example.com',true)('GET','/v1/orders'),/服务暂时不可用/);
  }finally{globalThis.uni=old;}
});
