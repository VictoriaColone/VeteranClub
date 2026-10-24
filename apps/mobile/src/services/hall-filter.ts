import type {Order,ServiceType} from '@stellar/core';

export interface HallFilter {minHours?:number;maxHours?:number;minEarnings?:number;maxEarnings?:number}
export interface HallFilterDraft {minHours:string;maxHours:string;minPrice:string;maxPrice:string}
export const emptyHallFilterDraft=():HallFilterDraft=>({minHours:'',maxHours:'',minPrice:'',maxPrice:''});

export function parseHallFilter(type:ServiceType,draft:HallFilterDraft):HallFilter{
  const price=(raw:string)=>{
    const value=raw.trim();if(!value)return undefined;
    if(!/^\d+(?:\.\d{1,2})?$/.test(value)||!Number.isSafeInteger(Math.round(Number(value)*100)))throw new Error('价格请填写非负金额，最多两位小数');
    return Math.round(Number(value)*100);
  };
  const hours=(raw:string)=>{
    const value=raw.trim();if(!value)return undefined;
    if(!/^\d+$/.test(value)||Number(value)<1||Number(value)>8)throw new Error('时长请填写 1～8 的整数');
    return Number(value);
  };
  const result:HallFilter={minEarnings:price(draft.minPrice),maxEarnings:price(draft.maxPrice)};
  if(type==='hourly'){result.minHours=hours(draft.minHours);result.maxHours=hours(draft.maxHours);}
  if(result.minHours!==undefined&&result.maxHours!==undefined&&result.minHours>result.maxHours)throw new Error('最短时长不能大于最长时长');
  if(result.minEarnings!==undefined&&result.maxEarnings!==undefined&&result.minEarnings>result.maxEarnings)throw new Error('最低价格不能大于最高价格');
  return result;
}

export function matchesHallFilter(order:Order,filter:HallFilter){
  return (order.serviceType!=='hourly'||((filter.minHours===undefined||order.quantity>=filter.minHours)&&(filter.maxHours===undefined||order.quantity<=filter.maxHours)))
    &&(filter.minEarnings===undefined||order.earnings>=filter.minEarnings)
    &&(filter.maxEarnings===undefined||order.earnings<=filter.maxEarnings);
}
