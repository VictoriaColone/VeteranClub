import test from 'node:test';
import assert from 'node:assert/strict';
import {ClubApi,createHttpTransport,createMockTransport,ApiError,type Transport} from '../packages/core/src/index';
import {createUniHttpTransport} from '../apps/mobile/src/services/transport';

test('提现响应丢失时沿用显式请求号，客户端不缓存密码指纹',async()=>{
 const keys:string[]=[];let calls=0;const transport:Transport=async<T>(_method,_path,_body,_token,key)=>{keys.push(key!);if(calls++===0)throw new ApiError(503,'response lost');return {id:'withdrawal'} as T;};
 const api=new ClubApi(transport);await assert.rejects(api.withdraw(100,'fixture-password','wallet-stable-key'));await api.withdraw(100,'new-password','wallet-stable-key');assert.deepEqual(keys,['wallet-stable-key','wallet-stable-key']);assert.equal((api as any).pendingRequests.size,0);
});
test('H5提现带原请求号与CSRF并禁止重定向和缓存',async()=>{
 const saved=globalThis.fetch,doc=Object.getOwnPropertyDescriptor(globalThis,'document');let call:RequestInit|undefined;
 try{Object.defineProperty(globalThis,'document',{configurable:true,value:{cookie:'norman-user-csrf=fixture-csrf'}});globalThis.fetch=async(_url,options)=>{call=options;return new Response(JSON.stringify({code:0,data:{id:'w'}}),{status:200});};
 await new ClubApi(createHttpTransport('/api')).withdraw(100,'fixture-password','wallet-stable-key');assert.equal((call?.headers as Record<string,string>)['Idempotency-Key'],'wallet-stable-key');assert.equal((call?.headers as Record<string,string>)['X-CSRF-Token'],'fixture-csrf');assert.equal(call?.cache,'no-store');assert.equal(call?.redirect,'error');
 }finally{globalThis.fetch=saved;if(doc)Object.defineProperty(globalThis,'document',doc);else delete (globalThis as any).document;}
});
test('原生提现也透传显式请求号，服务端仍决定余额',async()=>{
 const old=(globalThis as any).uni;let header:any;
 try{(globalThis as any).uni={request(options:any){header=options.header;options.success({statusCode:200,data:{code:0,data:{id:'w'}}});}};await new ClubApi(createUniHttpTransport('https://api.example.com',true)).withdraw(100,'fixture-password','wallet-native-key');assert.equal(header['Idempotency-Key'],'wallet-native-key');}
 finally{(globalThis as any).uni=old;}
});
test('纯Mock钱包不可把演示收益提现或保存真实银行卡',async()=>{
 const api=new ClubApi(createMockTransport());await api.login('user');const wallet=await api.memberWallet();assert.equal(wallet.available,0);assert.equal(wallet.total,0);assert.deepEqual(wallet.withdrawals,[]);await assert.rejects(api.withdraw(100,'fixture-password','wallet-demo-key'),/演示/);await assert.rejects(api.saveBank({holder:'测试姓名',bankName:'测试银行',bankNumber:'4111111111111111',password:'fixture-password',consent:true}),/演示/);
});
