import test from 'node:test';
import assert from 'node:assert/strict';
import {ApiError,ClubApi,type Transport,type OrderInput} from '../packages/core/src/index';

test('下单响应丢失时重试沿用请求号，成功后新的下单使用新的请求号',async()=>{
  const keys:string[]=[];let first=true;
  const transport:Transport=async<T>(_method,_path,_body,_token,key)=>{keys.push(key!);if(first){first=false;throw new ApiError(503,'response lost after commit');}return {id:'server-order'} as T;};
  const api=new ClubApi(transport);const input:OrderInput={gameId:'delta',region:'国服',quantity:1,serviceType:'escort',task:'100w纯带出',taskPrice:10000};
  await assert.rejects(api.createOrder(input));await api.createOrder({...input});await api.createOrder({...input});
  assert.ok(keys[0]);assert.equal(keys[0],keys[1]);assert.notEqual(keys[1],keys[2]);
});

test('支付尝试网络错误可安全重试，明确的校验失败会释放请求号',async()=>{
  const keys:string[]=[];let call=0;
  const transport:Transport=async<T>(_method,_path,_body,_token,key)=>{keys.push(key!);call++;if(call===1)throw new ApiError(503,'response lost');if(call===2)throw new ApiError(400,'validation failed');return {id:'payment'} as T;};
  const api=new ClubApi(transport);await assert.rejects(api.createPayment('order-1','wechat'));await assert.rejects(api.createPayment('order-1','wechat'));await api.createPayment('order-1','wechat');
  assert.equal(keys[0],keys[1]);assert.notEqual(keys[1],keys[2]);
});
