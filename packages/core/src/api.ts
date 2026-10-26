import { createMockTransport, ApiError } from './mock';
import type { PaymentAttempt, ClubUser, Product, ProductInput, Application, ServiceType, ClubSettings, Dashboard, Game, Order, OrderFilters, OrderInput, Player, Role, Session, Settlement, Transport, Wallet, Audit } from './types';
export class ClubApi {
  constructor(private transport:Transport=createMockTransport(),public session:Session|null=null){}
  private pendingRequests=new Map<string,string>();
  private request<T>(method:string,path:string,body?:unknown,idempotencyKey?:string){return this.transport<T>(method,path,body,this.session?.token,idempotencyKey);}
  private async createIdempotent<T>(path:string,body:object):Promise<T>{
    const fingerprint=path+':'+JSON.stringify(body,Object.keys(body).sort());
    const key=this.pendingRequests.get(fingerprint)||requestKey();this.pendingRequests.set(fingerprint,key);
    try{const data=await this.request<T>('POST',path,body,key);this.pendingRequests.delete(fingerprint);return data;}
    catch(error){if(error instanceof ApiError&&error.code<500)this.pendingRequests.delete(fingerprint);throw error;}
  }
  async login(role:Role,accountId?:string){const s=await this.request<Session>('POST','/v1/auth/mock-login',{role,accountId});this.session=s;return s;}
  currentSession(){return this.request<Session>('GET','/v1/auth/session');}
  sendSms(phone:string){return this.request<{expiresIn:number;retryAfter:number;delivery:string}>('POST','/v1/auth/sms/send',{phone});}
  async smsLogin(phone:string,code:string){const session=await this.request<Session>('POST','/v1/auth/sms/login',{phone,code});this.session=session;return session;}
  async staffLogin(username:string,password:string){const session=await this.request<Session>('POST','/v1/auth/staff/login',{username,password});this.session=session;return session;}
  paymentChannels(){return this.request<{channel:string;enabled:boolean;message:string}[]>('GET','/v1/payments/channels');}
  createPayment(orderId:string,channel:string){return this.createIdempotent<PaymentAttempt>(`/v1/orders/${orderId}/payment-attempts`,{channel});}
  payment(id:string){return this.request<PaymentAttempt>('GET',`/v1/payments/${id}`);}
  async logout(){await this.request('POST','/v1/auth/logout');this.session=null;}
  games(){return this.request<Game[]>('GET','/v1/catalog/games');}
  settings(){return this.request<ClubSettings>('GET','/v1/club');}
  players(gameId?:string){return this.request<Player[]>('GET',`/v1/players${gameId?'?gameId='+encodeURIComponent(gameId):''}`);}
  users(){return this.request<ClubUser[]>('GET','/v1/admin/users');}
  orders(filters:OrderFilters={}){const qs=Object.entries(filters).filter(([,v])=>v).map(([k,v])=>`${encodeURIComponent(k)}=${encodeURIComponent(v!)}`).join('&');return this.request<Order[]>('GET',`/v1/orders?${qs}`);}
  order(id:string){return this.request<Order>('GET',`/v1/orders/${id}`);}
  createOrder(input:OrderInput){return this.createIdempotent<Order>('/v1/orders',input);}
  act(id:string,action:string,body?:unknown){return this.request<Order>('POST',`/v1/orders/${id}/${action}`,body);}
  products(scope:'public'|'mine'='public'){return this.request<Product[]>('GET',`/v1/products?scope=${scope}`);}
  publishProduct(input:ProductInput){return this.request<Product>('POST','/v1/products',input);}
  updateProduct(id:string,active:boolean){return this.request<Product>('PATCH',`/v1/products/${id}`,{active});}
  grab(input:{gameId?:string;serviceType:ServiceType}){return this.request<Order>('POST','/v1/orders/grab',input);}
  applications(){return this.request<Application[]>('GET','/v1/applications');}
  apply(input:{serviceType:ServiceType;gameIds:string[];introduction:string}){return this.request<Application>('POST','/v1/applications',input);}
  reviewApplication(id:string,input:{status:'assessment'|'approved'|'rejected';score?:number;note:string}){return this.request<Application>('POST',`/v1/admin/applications/${id}/review`,input);}
  availability(available:boolean){return this.request<Player>('POST','/v1/player/availability',{available});}
  wallet(){return this.request<Wallet>('GET','/v1/player/wallet');}
  dashboard(){return this.request<Dashboard>('GET','/v1/admin/dashboard');}
  updatePlayer(id:string,data:Partial<Pick<Player,'available'|'approved'|'price'>>){return this.request<Player>('PATCH',`/v1/admin/players/${id}`,data);}
  settlements(){return this.request<Settlement[]>('GET','/v1/admin/settlements');}
  audit(){return this.request<Audit[]>('GET','/v1/admin/audit');}
  reset(){return this.request('POST','/v1/demo/reset');}
}
export function csrfToken(audience:'user'|'staff'){
  if(typeof document==='undefined')return '';
  const name=`norman-${audience}-csrf=`;return document.cookie.split(';').map(value=>value.trim()).find(value=>value.startsWith(name))?.slice(name.length)||'';
}
export function requestKey(){return `req_${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}_${Math.random().toString(36).slice(2)}`;}
// An explicitly built private acceptance client; normal HTTP never rewrites auth/payment routes.
export function createAcceptanceTransport(baseUrl:string,audience:'user'|'staff'='user'):Transport {
  const http=createHttpTransport(baseUrl,audience);
  return <T>(method:string,path:string,body?:unknown,token?:string,idempotencyKey?:string)=>{
    if(path==='/v1/auth/mock-login'){if(audience==='staff')throw new ApiError(403,'管理后台请使用账号密码登录');path='/v1/auth/sms/login/acceptance';}
    else if(method==='POST'&&/^\/v1\/orders\/[A-Za-z0-9_-]+\/(pay|demo-elapse)$/.test(path))path=path.replace('/v1/orders/','/v1/acceptance/orders/');
    return http<T>(method,path,body,token,idempotencyKey);
  };
}
export function createHttpTransport(baseUrl:string,audience:'user'|'staff'='user'):Transport {
  return async<T>(method:string,path:string,body?:unknown,token?:string,idempotencyKey?:string):Promise<T>=>{
    const csrf=csrfToken(audience);const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),15000);
    try{
      const response=await fetch(baseUrl.replace(/\/$/,'')+path,{method,credentials:'include',signal:controller.signal,headers:{'Content-Type':'application/json','X-Session-Audience':audience,'X-Client-Type':'h5',...(csrf?{'X-CSRF-Token':csrf}:{}),...(token?{Authorization:`Bearer ${token}`}:{}) ,...(method==='POST'&&(path==='/v1/orders'||path.endsWith('/payment-attempts'))?{'Idempotency-Key':idempotencyKey||requestKey()}:{})},body:body===undefined?undefined:JSON.stringify(body)});
      const result=await response.json();if(!response.ok||result.code!==0)throw new ApiError(result.code||response.status,result.message||'请求失败');return result.data as T;
    }catch(error){if(error instanceof ApiError)throw error;throw new ApiError(503,'网络连接失败，请稍后重试');}finally{clearTimeout(timer);}
  };
}
