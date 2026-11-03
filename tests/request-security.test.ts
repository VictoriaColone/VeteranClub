import test from 'node:test';
import assert from 'node:assert/strict';
import {apiRequestUrl,createHttpTransport} from '../packages/core/src/api';
import {createUniHttpTransport} from '../apps/mobile/src/services/transport';

test('接口请求限定在API根路径，拒绝外站跳转、路径穿越与公网明文传输',()=>{
  assert.equal(apiRequestUrl('/api','/v1/orders?query=%E5%B2%9A'),'/api/v1/orders?query=%E5%B2%9A');
  assert.equal(apiRequestUrl('https://api.example.com/api/','/v1/auth/session'),'https://api.example.com/api/v1/auth/session');
  assert.equal(apiRequestUrl('http://127.0.0.1:4190/api','/v1/club'),'http://127.0.0.1:4190/api/v1/club');
  for(const base of ['//evil.example','http://api.example.com','http://127.0.0.1.evil.example','https://user:pass@api.example.com','https://api.example.com/api?next=','https://api.example.com:65536','/api/../other'])assert.throws(()=>apiRequestUrl(base,'/v1/auth/staff/login'));
  for(const path of ['https://evil.example/v1/auth/login','//evil.example','/v1/../auth','/v1/%2e%2e/auth','/v1/orders#secret','/v1/orders\\evil','/v1/orders?x=\nsecret'])assert.throws(()=>apiRequestUrl('/api',path));
});

test('用户端和后台阻止自动重定向，禁止缓存，保留Cookie和CSRF隔离',async()=>{
  const oldFetch=globalThis.fetch,oldDocument=Object.getOwnPropertyDescriptor(globalThis,'document');const calls:RequestInit[]=[];
  try{
    Object.defineProperty(globalThis,'document',{configurable:true,value:{cookie:'norman-user-csrf=user-csrf; norman-staff-csrf=staff-csrf'}});
    globalThis.fetch=async(_url,init)=>{calls.push(init!);return new Response(JSON.stringify({code:0,data:{}}));};
    await createHttpTransport('/api','staff')('POST','/v1/auth/staff/login',{username:'test',password:'test-only'});
    await createUniHttpTransport('/api',false)('POST','/v1/auth/logout');
    for(const init of calls){assert.equal(init.redirect,'error');assert.equal(init.cache,'no-store');assert.equal(init.credentials,'include');assert.ok(init.signal);}
    assert.equal((calls[0].headers as Record<string,string>)['X-CSRF-Token'],'staff-csrf');
    assert.equal((calls[1].headers as Record<string,string>)['X-CSRF-Token'],'user-csrf');
    await assert.rejects(createHttpTransport('http://evil.example')('POST','/v1/auth/staff/login',{},'secret'));
    assert.equal(calls.length,2);
    globalThis.fetch=async()=>{throw new TypeError('redirect blocked');};
    await assert.rejects(createHttpTransport('/api')('POST','/v1/auth/sms/login'),/网络连接失败/);
  }finally{globalThis.fetch=oldFetch;if(oldDocument)Object.defineProperty(globalThis,'document',oldDocument);else delete (globalThis as any).document;}
});

test('小程序没有URL全局时仍校验地址，拒绝把Bearer发往非安全接口',async()=>{
  const oldUni=globalThis.uni,oldURL=globalThis.URL;let calls=0;
  try{
    globalThis.URL=undefined as any;
    globalThis.uni={request(options:any){calls++;options.success({statusCode:200,data:{code:0,data:{}}});}} as any;
    await createUniHttpTransport('https://api.example.com',true)('GET','/v1/club',undefined,'secret');
    await assert.rejects(createUniHttpTransport('http://api.example.com',true)('GET','/v1/auth/session',undefined,'secret'));
    assert.equal(calls,1);
  }finally{globalThis.uni=oldUni;globalThis.URL=oldURL;}
});
