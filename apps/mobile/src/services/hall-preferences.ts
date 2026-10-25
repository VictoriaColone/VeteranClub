import type {StorageAdapter} from '@stellar/core';
import {emptyHallFilterDraft,parseHallFilter,type HallFilterDraft,type HallScope} from './hall-filter';

export type HallPreferences=Record<HallScope,{enabled:boolean;conditions:HallFilterDraft}>;
export const emptyHallPreferences=():HallPreferences=>({
  public:{enabled:true,conditions:emptyHallFilterDraft()},
  private:{enabled:true,conditions:emptyHallFilterDraft()},
});
// v1 contained price/hour ranges, which no longer apply to either order pool.
const key=(userId:string)=>`stellar-club:hall-filters:v2:${userId}`;

export function loadHallPreferences(storage:StorageAdapter,userId:string):HallPreferences{
  const result=emptyHallPreferences();
  try{
    const raw=storage.getItem(key(userId));if(!raw)return result;
    const saved=JSON.parse(raw);if(saved?.version!==2)return result;
    for(const scope of ['public','private'] as const){
      const value=saved[scope];if(!value||typeof value!=='object')continue;
      if(typeof value.enabled==='boolean')result[scope].enabled=value.enabled;
      if(!value.conditions||typeof value.conditions!=='object')continue;
      try{result[scope].conditions=parseHallFilter(value.conditions);}catch{/* Discard corrupt conditions for this pool only. */}
    }
  }catch{/* Missing, corrupt or unavailable storage must not block receiving orders. */}
  return result;
}

export function saveHallPreferences(storage:StorageAdapter,userId:string,value:HallPreferences):boolean{
  try{storage.setItem(key(userId),JSON.stringify({version:2,...value}));return true;}catch{return false;}
}
