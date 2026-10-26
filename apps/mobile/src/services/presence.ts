import {STORAGE_KEY} from '@stellar/core';

// H5 uses authenticated push invalidations; native/mock clients retain a small fallback refresh.
export function subscribeStatusChanges(changed:()=>void){
  let polling:ReturnType<typeof setInterval>|undefined;
  const fallback=()=>{if(!polling)polling=setInterval(changed,5000);};
  const stopPolling=()=>{if(polling){clearInterval(polling);polling=undefined;}};
  let cleanup=()=>{};
  // #ifdef H5
  const remote=['http','acceptance'].includes(import.meta.env.VITE_API_MODE||'');
  if(remote&&typeof EventSource!=='undefined'){
    const source=new EventSource((import.meta.env.VITE_API_BASE_URL||'').replace(/\/$/,'')+'/v1/players/events',{withCredentials:true});
    source.addEventListener('presence',changed);source.onopen=stopPolling;source.onerror=fallback;
    cleanup=()=>source.close();
  }else{
    const storageChanged=(event:StorageEvent)=>{if(event.key===STORAGE_KEY)changed();};
    window.addEventListener('storage',storageChanged);cleanup=()=>window.removeEventListener('storage',storageChanged);fallback();
  }
  // #endif
  // #ifdef MP-WEIXIN
  fallback();
  // #endif
  return ()=>{stopPolling();cleanup();};
}
