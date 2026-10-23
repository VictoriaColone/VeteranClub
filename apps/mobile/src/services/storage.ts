import type {StorageAdapter} from '@stellar/core';
type UniStorage=Pick<UniNamespace.Uni,'getStorageSync'|'setStorageSync'>;
// Persist the same JSON database using the native Mini Program storage API.
// An invalid value is treated as absent, matching localStorage's string contract.
export function createUniStorage(storage:UniStorage):StorageAdapter{
  return {getItem(key){const value=storage.getStorageSync(key);return typeof value==='string'&&value?value:null;},
    setItem(key,value){storage.setStorageSync(key,value);}};
}
