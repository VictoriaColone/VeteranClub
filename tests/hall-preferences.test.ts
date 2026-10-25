import test from 'node:test';
import assert from 'node:assert/strict';
import {MemoryStorage,createMockServer,type Order} from '../packages/core/src/index';
import {createUniStorage} from '../apps/mobile/src/services/storage';
import {parseHallFilter,matchesHallFilter,emptyHallFilterDraft} from '../apps/mobile/src/services/hall-filter';
import {emptyHallPreferences,loadHallPreferences,saveHallPreferences} from '../apps/mobile/src/services/hall-preferences';

test('按用户与公共/私有单分别记录，关闭重开客户端恢复游戏、类型和过滤开关',()=>{
  const values=new Map<string,unknown>();
  const storage=()=>createUniStorage({getStorageSync:key=>values.get(key),setStorageSync:(key,value)=>{values.set(key,value);}});
  const prefs=emptyHallPreferences();prefs.public={enabled:false,conditions:{gameId:'delta',serviceType:'escort'}};prefs.private.conditions={gameId:'valorant',serviceType:'hourly'};
  assert.equal(saveHallPreferences(storage(),'user-a',prefs),true);
  assert.deepEqual(loadHallPreferences(storage(),'user-a'),prefs);
  assert.deepEqual(loadHallPreferences(storage(),'user-b'),emptyHallPreferences());
  const restored=loadHallPreferences(storage(),'user-a');restored.private.enabled=false;saveHallPreferences(storage(),'user-a',restored);
  assert.deepEqual(loadHallPreferences(storage(),'user-a').public,prefs.public);assert.equal(loadHallPreferences(storage(),'user-a').private.enabled,false);
});

test('关闭过滤保留选择，重新开启后恢复按游戏和类型过滤',()=>{
  const storage=new MemoryStorage(),prefs=emptyHallPreferences();prefs.public={enabled:false,conditions:{gameId:'delta',serviceType:'escort'}};saveHallPreferences(storage,'user',prefs);
  const restored=loadHallPreferences(storage,'user'),order:Order={...createMockServer(new MemoryStorage()).read().orders[0],gameId:'lol',serviceType:'hourly'};
  const filter=parseHallFilter(restored.public.conditions);assert.equal(matchesHallFilter(order,filter,restored.public.enabled),true);
  restored.public.enabled=true;assert.equal(matchesHallFilter(order,filter,restored.public.enabled),false);assert.deepEqual(restored.public.conditions,prefs.public.conditions);
});

test('清除条件会写入本地，刷新后不重新出现，开关状态保持不变',()=>{
  const storage=new MemoryStorage(),prefs=emptyHallPreferences();prefs.private.conditions={gameId:'lol',serviceType:'hourly'};prefs.private.enabled=false;saveHallPreferences(storage,'user',prefs);
  const restored=loadHallPreferences(storage,'user');restored.private.conditions=emptyHallFilterDraft();saveHallPreferences(storage,'user',restored);
  assert.deepEqual(loadHallPreferences(storage,'user').private,{enabled:false,conditions:emptyHallFilterDraft()});
});

test('旧时长价格缓存不再生效，无效缓存仅重置损坏范围',()=>{
  const storage=new MemoryStorage();storage.setItem('stellar-club:hall-filters:v1:user',JSON.stringify({version:1,hourly:{enabled:true,conditions:{minHours:'8',maxPrice:'0'}}}));
  assert.deepEqual(loadHallPreferences(storage,'user'),emptyHallPreferences());
  const prefs=emptyHallPreferences();prefs.public={enabled:false,conditions:{gameId:'unsupported',serviceType:'all'}};prefs.private.conditions={gameId:'delta',serviceType:'escort'};saveHallPreferences(storage,'user',prefs);
  const restored=loadHallPreferences(storage,'user');assert.deepEqual(restored.public.conditions,emptyHallFilterDraft());assert.equal(restored.public.enabled,false);assert.deepEqual(restored.private.conditions,prefs.private.conditions);
  for(const raw of ['not-json','null','{"version":1}','[]'])assert.deepEqual(loadHallPreferences({getItem:()=>raw,setItem:()=>{}},'user'),emptyHallPreferences());
});

test('本地存储不可用时可继续使用，保存失败返回可提示的结果',()=>{
  const unavailable={getItem(){throw new Error('denied');},setItem(){throw new Error('full');}};
  assert.deepEqual(loadHallPreferences(unavailable,'user'),emptyHallPreferences());assert.equal(saveHallPreferences(unavailable,'user',emptyHallPreferences()),false);
});
