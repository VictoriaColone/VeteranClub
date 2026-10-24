import type {ServiceType,StorageAdapter} from '@stellar/core';
import {emptyHallFilterDraft,parseHallFilter,type HallFilterDraft} from './hall-filter';

export type HallPreferences=Record<ServiceType,{enabled:boolean;conditions:HallFilterDraft}>;
export const emptyHallPreferences=():HallPreferences=>({
  hourly:{enabled:true,conditions:emptyHallFilterDraft()},
  escort:{enabled:true,conditions:emptyHallFilterDraft()},
});
const key=(userId:string)=>`stellar-club:hall-filters:v1:${userId}`;

export function loadHallPreferences(storage:StorageAdapter,userId:string):HallPreferences{
  const result=emptyHallPreferences();
  try{
    const raw=storage.getItem(key(userId));if(!raw)return result;
    const saved=JSON.parse(raw);if(saved?.version!==1)return result;
    for(const type of ['hourly','escort'] as const){
      const value=saved[type];if(!value||typeof value!=='object')continue;
      if(typeof value.enabled==='boolean')result[type].enabled=value.enabled;
      const conditions=emptyHallFilterDraft();
      if(!value.conditions||typeof value.conditions!=='object')continue;
      for(const field of Object.keys(conditions) as (keyof HallFilterDraft)[]){
        const text=value.conditions[field];
        conditions[field]=typeof text==='string'&&text.length<=10?text:'';
      }
      try{parseHallFilter(type,conditions);result[type].conditions=conditions;}catch{/* Discard an invalid range without losing the other service type. */}
    }
  }catch{/* Missing, corrupt or unavailable local storage must not block the hall. */}
  return result;
}

export function saveHallPreferences(storage:StorageAdapter,userId:string,value:HallPreferences):boolean{
  try{storage.setItem(key(userId),JSON.stringify({version:1,...value}));return true;}catch{return false;}
}
