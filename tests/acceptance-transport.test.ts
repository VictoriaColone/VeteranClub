import test from 'node:test';
import assert from 'node:assert/strict';
import {createAcceptanceTransport,createHttpTransport} from '../packages/core/src/api';

test('只有显式验收客户端才重写身份与模拟付款接口，普通 HTTP 保持真实接口路径',async()=>{
  const previous=globalThis.fetch;const calls:string[]=[];
  globalThis.fetch=async(input)=>{calls.push(String(input));return new Response(JSON.stringify({code:0,data:{}}),{status:200});};
  try{
    const acceptance=createAcceptanceTransport('/api');
    await acceptance('POST','/v1/auth/mock-login',{role:'user'});
    await acceptance('POST','/v1/orders/NC_1/pay');
    await acceptance('POST','/v1/orders/NC_1/demo-elapse');
    await acceptance('POST','/v1/orders/NC_1/confirm');
    await createHttpTransport('/api')('POST','/v1/orders/NC_1/pay');
    assert.deepEqual(calls,['/api/v1/auth/sms/login/acceptance','/api/v1/acceptance/orders/NC_1/pay','/api/v1/acceptance/orders/NC_1/demo-elapse','/api/v1/orders/NC_1/confirm','/api/v1/orders/NC_1/pay']);
  }finally{globalThis.fetch=previous;}
});

test('验收后台拒绝免密演示身份，密码登录保持真实服务端路径',async()=>{
  const previous=globalThis.fetch;const calls:string[]=[];
  globalThis.fetch=async(input)=>{calls.push(String(input));return new Response(JSON.stringify({code:0,data:{role:'admin'}}),{status:200});};
  try{
    const staff=createAcceptanceTransport('/api','staff');
    assert.throws(()=>staff('POST','/v1/auth/mock-login',{role:'admin'}),/账号密码登录/);
    await staff('POST','/v1/auth/staff/login',{username:'test_manager',password:'test-only'});
    assert.deepEqual(calls,['/api/v1/auth/staff/login']);
  }finally{globalThis.fetch=previous;}
});
