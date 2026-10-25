import {CLUB_NAME} from './brand';
import type {Product,ServiceType} from './types';

// Demo equivalents of the official catalog installed by Flyway V3. Prices are in fen.
export const clubProductId=(type:ServiceType,game:string)=>`club-${type}-${game}`;
export function createClubProducts():Product[]{
  return ['delta','valorant','lol'].flatMap(gameId=>(['hourly','escort'] as const).map(serviceType=>({
    id:clubProductId(serviceType,gameId),userId:null,playerId:null,sellerType:'club' as const,sellerName:CLUB_NAME,
    gameId,serviceType,title:serviceType==='hourly'?'诺曼部落 · 时长组队':'诺曼部落 · 任务护航',
    description:'俱乐部官方服务，付款后由通过对应游戏与类型考核的成员抢单。',
    price:serviceType==='hourly'?6800:12800,task:serviceType==='escort'?'100w纯带出':'',active:true,createdAt:0,
  })));
}
