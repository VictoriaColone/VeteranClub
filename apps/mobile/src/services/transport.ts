import { ApiError, createMockTransport, type ApiResponse, type Transport } from '@stellar/core';
export function createUniHttpTransport(baseUrl:string):Transport {
  return <T>(method:string,path:string,body?:unknown,token?:string)=>new Promise<T>((resolve,reject)=>{
    uni.request({url:baseUrl.replace(/\/$/,'')+path,method:method as 'GET'|'POST'|'PUT'|'DELETE',data:body as any,header:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},success(response){const result=response.data as ApiResponse<T>;if(response.statusCode>=200&&response.statusCode<300&&result.code===0)resolve(result.data);else reject(new ApiError(result.code||response.statusCode,result.message||'服务暂时不可用'));},fail(){reject(new ApiError(503,'网络连接失败，请稍后重试'));}});
  });
}
export function getMobileTransport():Transport {
  if(import.meta.env.VITE_API_MODE==='http')return createUniHttpTransport(import.meta.env.VITE_API_BASE_URL||'');
  // #ifdef MP-WEIXIN
  return createMockTransport({getItem(key){return uni.getStorageSync(key)||null;},setItem(key,value){uni.setStorageSync(key,value);}});
  // #endif
  // #ifdef H5
  return createMockTransport();
  // #endif
}
