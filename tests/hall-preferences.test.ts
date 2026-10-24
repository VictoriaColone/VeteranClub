import test from 'node:test';
import assert from 'node:assert/strict';
import {MemoryStorage,createMockServer,type Order} from '../packages/core/src/index';
import {createUniStorage} from '../apps/mobile/src/services/storage';
import {parseHallFilter,matchesHallFilter} from '../apps/mobile/src/services/hall-filter';
import {emptyHallPreferences,loadHallPreferences,saveHallPreferences} from '../apps/mobile/src/services/hall-preferences';

test('本地记录按用户和订单类型隔离，重建客户端恢复条件及关闭状态',()=>{
  const values=new Map<string,unknown>();
  const storage=()=>createUniStorage({getStorageSync:key=>values.get(key),setStorageSync:(key,value)=>{values.set(key,value);}});
  const prefs=emptyHallPreferences();
  prefs.hourly={enabled:false,conditions:{minHours:'2',maxHours:'4',minPrice:'50.50',maxPrice:'100'}};
  prefs.escort.conditions={minHours:'',maxHours:'',minPrice:'200',maxPrice:'300'};
  assert.equal(saveHallPreferences(storage(),'user-a',prefs),true);
  assert.deepEqual(loadHallPreferences(storage(),'user-a'),prefs);
  assert.deepEqual(loadHallPreferences(storage(),'user-b'),emptyHallPreferences());
  const restored=loadHallPreferences(storage(),'user-a');restored.escort.enabled=false;
  assert.equal(saveHallPreferences(storage(),'user-a',restored),true);
  assert.deepEqual(loadHallPreferences(storage(),'user-a').hourly,prefs.hourly);
  assert.equal(loadHallPreferences(storage(),'user-a').escort.enabled,false);
});

test('关闭过滤时条件仍保存，重开后按原范围过滤，零元上限不会丢失',()=>{
  const storage=new MemoryStorage(),prefs=emptyHallPreferences();
  prefs.hourly.conditions={minHours:'3',maxHours:'4',minPrice:'0',maxPrice:'0'};
  prefs.hourly.enabled=false;saveHallPreferences(storage,'user',prefs);
  const restored=loadHallPreferences(storage,'user');
  const order:Order={...createMockServer(new MemoryStorage()).read().orders[0],serviceType:'hourly',quantity:1,earnings:10000};
  const filter=parseHallFilter('hourly',restored.hourly.conditions);
  assert.equal(matchesHallFilter(order,filter,restored.hourly.enabled),true);
  restored.hourly.enabled=true;
  assert.equal(matchesHallFilter(order,filter,restored.hourly.enabled),false);
  assert.equal(restored.hourly.conditions.maxPrice,'0');
});

test('清除条件会写入本地，刷新后不重新出现，开关状态保持不变',()=>{
  const storage=new MemoryStorage(),prefs=emptyHallPreferences();
  prefs.escort.conditions.minPrice='100';prefs.escort.enabled=false;
  saveHallPreferences(storage,'user',prefs);
  const restored=loadHallPreferences(storage,'user');restored.escort.conditions=emptyHallPreferences().escort.conditions;
  saveHallPreferences(storage,'user',restored);
  const next=loadHallPreferences(storage,'user');
  assert.deepEqual(next.escort.conditions,emptyHallPreferences().escort.conditions);
  assert.equal(next.escort.enabled,false);
});

test('无效缓存安全回退，只丢弃损坏类型的条件',()=>{
  const storage=new MemoryStorage(),prefs=emptyHallPreferences();
  prefs.hourly.conditions={minHours:'4',maxHours:'1',minPrice:'100',maxPrice:'50'};
  prefs.hourly.enabled=false;prefs.escort.conditions.minPrice='128';
  saveHallPreferences(storage,'user',prefs);
  const restored=loadHallPreferences(storage,'user');
  assert.deepEqual(restored.hourly.conditions,emptyHallPreferences().hourly.conditions);
  assert.equal(restored.hourly.enabled,false);assert.equal(restored.escort.conditions.minPrice,'128');
  for(const raw of ['not-json','null','{"version":2}','[]']){
    const broken={getItem:()=>raw,setItem:()=>{}};
    assert.deepEqual(loadHallPreferences(broken,'user'),emptyHallPreferences());
  }
});

test('本地存储不可用时可继续使用，保存失败返回可提示的结果',()=>{
  const unavailable={getItem(){throw new Error('denied');},setItem(){throw new Error('full');}};
  assert.deepEqual(loadHallPreferences(unavailable,'user'),emptyHallPreferences());
  assert.equal(saveHallPreferences(unavailable,'user',emptyHallPreferences()),false);
});
