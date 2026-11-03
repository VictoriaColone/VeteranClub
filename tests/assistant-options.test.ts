import test from 'node:test';
import assert from 'node:assert/strict';
import {createMockServer,MemoryStorage,type Player,type Order} from '../packages/core/src';
import {assistantOptions} from '../apps/mobile/src/services/assistant-options';

test('副手列表搜索昵称和标签，同时保持游戏入驻、空闲和归属限制',()=>{
  const db=createMockServer(new MemoryStorage()).read();
  const player:Player={...db.players[0],id:'eligible',userId:'eligible-user',name:'远山',tags:['指挥在线'],approved:true,available:true,busy:false,qualifications:['escort'],qualificationGames:{escort:['delta']}};
  const order:Order={...db.orders[0],bossId:'buyer',gameId:'delta',team:[{playerId:'lead',name:'负责人'}]};
  const list=[player,{...player,id:'busy',busy:true},{...player,id:'off',available:false},{...player,id:'other-game',qualificationGames:{escort:['lol']}},{...player,id:'lead'},{...player,id:'buyer',userId:'buyer'}];
  assert.deepEqual(assistantOptions(list,order,'远').map(p=>p.id),['eligible']);
  assert.deepEqual(assistantOptions(list,order,'指挥').map(p=>p.id),['eligible']);
  assert.deepEqual(assistantOptions(list,order,'不存在'),[]);assert.deepEqual(assistantOptions(list,null),[]);
});
