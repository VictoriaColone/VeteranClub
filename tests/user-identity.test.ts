import test from 'node:test';
import assert from 'node:assert/strict';
import {ClubApi,createMockServer,MemoryStorage,STORAGE_KEY,type Session,type Transport,type UserIdentity} from '../packages/core/src/index';

test('客户端区分注册、短信登录、密码登录和找回，并绑定短信用途',async()=>{
  const calls:{method:string;path:string;body:any}[]=[];
  const transport:Transport=async<T>(method:string,path:string,body?:unknown)=>{calls.push({method,path,body});return {} as T;};
  const api=new ClubApi(transport);
  await api.sendSms('13800000000','register');await api.smsRegister('13800000000','123456','Synthetic_42');
  await api.passwordLogin('13800000000','Synthetic_42');await api.resetUserPassword('13800000000','123456','Synthetic_43');
  assert.deepEqual(calls.map(c=>c.path),['/v1/auth/sms/send','/v1/auth/sms/register','/v1/auth/password/login','/v1/auth/password/reset']);
  assert.equal(calls[0].body.purpose,'register');assert.equal(calls[1].body.password,'Synthetic_42');
});

test('本地演示的年龄状态只用于模拟，缺少成年登记和入驻时阻止下单',()=>{
  const storage=new MemoryStorage(),server=createMockServer(storage);
  const boss=server.dispatch('POST','/v1/auth/mock-login',{role:'user',accountId:'u-boss'}).data as Session;
  const set=(value:boolean|undefined)=>{const db=server.read();db.demoIdentityAdults=value===undefined?{}:{'u-boss':value};storage.setItem(STORAGE_KEY,JSON.stringify(db));};
  const current=()=>server.dispatch('GET','/v1/account/identity',{},boss.token).data as UserIdentity;
  set(undefined);assert.equal(current().canOrder,false);assert.equal(current().status,'missing');
  assert.throws(()=>server.dispatch('POST','/v1/orders',{gameId:'delta',region:'国服',quantity:1,contactPhone:'13800000000'},boss.token),/成年登记/);
  set(false);assert.equal(current().adult,false);assert.equal(current().canOrder,false);
  set(true);assert.equal(current().canOrder,true);
  assert.throws(()=>server.dispatch('POST','/v1/account/identity',{name:'测试身份',idNumber:'not-a-real-id'},boss.token),/不接收真实证件/);
  assert.ok(!storage.getItem(STORAGE_KEY)!.includes('not-a-real-id'));
});
