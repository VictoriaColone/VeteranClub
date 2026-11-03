import test from 'node:test';
import assert from 'node:assert/strict';
import {createMockServer,MemoryStorage,type Order} from '../packages/core/src';
import {matchesPersonalOrderScope,personalOrderScopes} from '../apps/mobile/src/services/order-scope';

const sample=createMockServer(new MemoryStorage()).read().orders[0];
const base:Order={...sample,bossId:'boss',status:'waiting',preferredPlayerId:undefined,playerId:undefined,team:[]};
const belongs=(order:Order)=>matchesPersonalOrderScope(order,'assigned','user','player');

test('派给我的合并待接定向招募、负责人和协作订单，完成后仍保留',()=>{
  assert.deepEqual(personalOrderScopes.map(scope=>scope.label),['全部订单','我下单的','派给我的']);
  assert.equal(belongs({...base,preferredPlayerId:'player'}),true);
  for(const status of ['accepted','serving','confirming','completed','cancelled'] as const){
    assert.equal(belongs({...base,status,playerId:'player'}),true);
    assert.equal(belongs({...base,status,playerId:'lead',team:[{playerId:'lead',name:'负责人'},{playerId:'player',name:'副手'}]}),true);
  }
});

test('派给我的不混入未领取公共单、别人的邀请、未付款或已改派邀请',()=>{
  assert.equal(belongs(base),false);
  assert.equal(belongs({...base,preferredPlayerId:'other'}),false);
  assert.equal(belongs({...base,status:'unpaid',preferredPlayerId:'player'}),false);
  assert.equal(belongs({...base,status:'accepted',preferredPlayerId:'player',playerId:'other'}),false);
  assert.equal(matchesPersonalOrderScope(base,'assigned','user'),false);
  assert.equal(matchesPersonalOrderScope({...base,bossId:'user'},'created','user','player'),true);
  assert.equal(matchesPersonalOrderScope(base,'created','user','player'),false);
  assert.equal(matchesPersonalOrderScope(base,'all','user','player'),true);
});
