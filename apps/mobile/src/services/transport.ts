import { ApiError, createMockTransport, createAcceptanceTransport, csrfToken, requestKey, type ApiResponse, type Transport } from '@stellar/core';
import {createUniStorage} from './storage';
import {isWeixinMiniProgram} from './platform';
export function createUniHttpTransport(baseUrl:string,miniProgram=isWeixinMiniProgram):Transport {
  return <T>(method:string,path:string,body?:unknown,token?:string,idempotencyKey?:string)=>new Promise<T>((resolve,reject)=>{
    // wx.request has no PATCH method. The HTTP backend honors this override
    // only on the two authenticated PATCH routes; mock mode is unchanged.
    const override=miniProgram&&method==='PATCH';
    const csrf=miniProgram?'':csrfToken('user');
    uni.request({url:baseUrl.replace(/\/$/,'')+path,method:(override?'POST':method) as 'GET'|'POST'|'PUT'|'DELETE',timeout:15000,withCredentials:true,data:body as any,header:{'Content-Type':'application/json','X-Session-Audience':'user','X-Client-Type':miniProgram?'mini-program':'h5',...(csrf?{'X-CSRF-Token':csrf}:{}),...(token?{Authorization:`Bearer ${token}`}:{}) ,...(override?{'X-HTTP-Method-Override':'PATCH'}:{}),...(method==='POST'&&(path==='/v1/orders'||path.endsWith('/payment-attempts'))?{'Idempotency-Key':idempotencyKey||requestKey()}:{})},success(response){const result=response.data as ApiResponse<T>|null;if(response.statusCode>=200&&response.statusCode<300&&result&&result.code===0)resolve(result.data);else reject(new ApiError(result?.code||response.statusCode,result?.message||'服务暂时不可用'));},fail(){reject(new ApiError(503,'网络连接失败，请稍后重试'));}});
  });
}
export function getMobileTransport():Transport {
  if(import.meta.env.VITE_API_MODE==='acceptance')return createAcceptanceTransport(import.meta.env.VITE_API_BASE_URL||'/api');
  if(import.meta.env.VITE_API_MODE==='http')return createUniHttpTransport(import.meta.env.VITE_API_BASE_URL||'');
  // #ifdef MP-WEIXIN
  return createMockTransport(createUniStorage(uni));
  // #endif
  // #ifdef H5
  return createMockTransport();
  // #endif
}
