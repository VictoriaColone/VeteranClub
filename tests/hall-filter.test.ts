import test from 'node:test';
import assert from 'node:assert/strict';
import {createMockServer,MemoryStorage,type Order} from '../packages/core/src/index';
import {emptyHallFilterDraft,parseHallFilter,matchesHallFilter} from '../apps/mobile/src/services/hall-filter';

const sample=createMockServer(new MemoryStorage()).read().orders[0];
const hourly:Order={...sample,serviceType:'hourly',quantity:2,amount:10000,earnings:8000};

test('组队时长与价格取交集，价格使用卡片净报酬，边界包含在内',()=>{
  const filter=parseHallFilter('hourly',{minHours:'2',maxHours:'3',minPrice:'80',maxPrice:'80.00'});
  assert.equal(matchesHallFilter(hourly,filter),true);
  assert.equal(matchesHallFilter({...hourly,quantity:1},filter),false);
  assert.equal(matchesHallFilter({...hourly,quantity:4},filter),false);
  assert.equal(matchesHallFilter({...hourly,earnings:7999},filter),false);
  assert.equal(matchesHallFilter({...hourly,earnings:8001},filter),false);
});

test('护航仅按团队净报酬过滤，不受组队时长或无效时长草稿影响',()=>{
  const filter=parseHallFilter('escort',{minHours:'8',maxHours:'invalid',minPrice:'79.99',maxPrice:'80.01'});
  assert.equal(filter.minHours,undefined);assert.equal(filter.maxHours,undefined);
  const escort={...hourly,serviceType:'escort' as const,quantity:1};
  assert.equal(matchesHallFilter(escort,{...filter,minHours:8}),true);
  assert.equal(matchesHallFilter({...escort,earnings:8002},filter),false);
});

test('空范围与单边范围有效，零元不被当作未填，金额按分精确比较',()=>{
  assert.equal(matchesHallFilter(hourly,parseHallFilter('hourly',emptyHallFilterDraft())),true);
  assert.equal(matchesHallFilter(hourly,parseHallFilter('hourly',{...emptyHallFilterDraft(),minHours:'3'})),false);
  assert.equal(matchesHallFilter(hourly,parseHallFilter('hourly',{...emptyHallFilterDraft(),maxPrice:'0'})),false);
  assert.equal(matchesHallFilter({...hourly,earnings:29},parseHallFilter('hourly',{...emptyHallFilterDraft(),minPrice:'0.29',maxPrice:'0.29'})),true);
});

test('非法金额、时长和倒置区间不会成为有效筛选',()=>{
  for(const minPrice of ['-1','1.234','abc','Infinity','99999999999999999999'])assert.throws(()=>parseHallFilter('hourly',{...emptyHallFilterDraft(),minPrice}),/价格/);
  for(const minHours of ['0','9','1.5','abc'])assert.throws(()=>parseHallFilter('hourly',{...emptyHallFilterDraft(),minHours}),/时长/);
  assert.throws(()=>parseHallFilter('hourly',{...emptyHallFilterDraft(),minHours:'3',maxHours:'2'}),/最短/);
  assert.throws(()=>parseHallFilter('escort',{...emptyHallFilterDraft(),minPrice:'100',maxPrice:'50'}),/最低/);
});
