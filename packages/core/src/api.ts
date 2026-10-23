import { createMockTransport, ApiError } from './mock';
import type { Application, ServiceType, ClubSettings, Dashboard, Game, Order, OrderFilters, OrderInput, Player, Role, Session, Settlement, Transport, Wallet, Audit } from './types';
export class ClubApi {
  constructor(private transport:Transport=createMockTransport(),public session:Session|null=null){}
  private request<T>(method:string,path:string,body?:unknown){return this.transport<T>(method,path,body,this.session?.token);}
  async login(role:Role,accountId?:string){const s=await this.request<Session>('POST','/v1/auth/mock-login',{role,accountId});this.session=s;return s;}
  async logout(){await this.request('POST','/v1/auth/logout');this.session=null;}
  games(){return this.request<Game[]>('GET','/v1/catalog/games');}
  settings(){return this.request<ClubSettings>('GET','/v1/club');}
  players(gameId?:string){return this.request<Player[]>('GET',`/v1/players${gameId?'?gameId='+encodeURIComponent(gameId):''}`);}
  orders(filters:OrderFilters={}){const qs=Object.entries(filters).filter(([,v])=>v).map(([k,v])=>`${encodeURIComponent(k)}=${encodeURIComponent(v!)}`).join('&');return this.request<Order[]>('GET',`/v1/orders?${qs}`);}
  order(id:string){return this.request<Order>('GET',`/v1/orders/${id}`);}
  createOrder(input:OrderInput){return this.request<Order>('POST','/v1/orders',input);}
  act(id:string,action:string,body?:unknown){return this.request<Order>('POST',`/v1/orders/${id}/${action}`,body);}
  applications(){return this.request<Application[]>('GET','/v1/applications');}
  apply(input:{serviceType:ServiceType;gameIds:string[];introduction:string}){return this.request<Application>('POST','/v1/applications',input);}
  reviewApplication(id:string,input:{status:'assessment'|'approved'|'rejected';score?:number;note:string}){return this.request<Application>('POST',`/v1/admin/applications/${id}/review`,input);}
  availability(available:boolean){return this.request<Player>('POST','/v1/player/availability',{available});}
  wallet(){return this.request<Wallet>('GET','/v1/player/wallet');}
  dashboard(){return this.request<Dashboard>('GET','/v1/admin/dashboard');}
  updatePlayer(id:string,data:Partial<Pick<Player,'available'|'approved'|'price'>>){return this.request<Player>('PATCH',`/v1/admin/players/${id}`,data);}
  settlements(){return this.request<Settlement[]>('GET','/v1/admin/settlements');}
  createSettlements(playerId?:string){return this.request<Settlement[]>('POST','/v1/admin/settlements',{playerId});}
  paySettlement(id:string){return this.request<Settlement>('POST',`/v1/admin/settlements/${id}/pay`);}
  audit(){return this.request<Audit[]>('GET','/v1/admin/audit');}
  updateSettings(settings:ClubSettings){return this.request<ClubSettings>('PATCH','/v1/admin/settings',settings);}
  reset(){return this.request('POST','/v1/demo/reset');}
}
export function createHttpTransport(baseUrl:string):Transport {
  return async<T>(method:string,path:string,body?:unknown,token?:string):Promise<T>=>{const response=await fetch(baseUrl.replace(/\/$/,'')+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:body===undefined?undefined:JSON.stringify(body)});const json=await response.json();if(!response.ok||json.code!==0)throw new ApiError(json.code||response.status,json.message||'请求失败');return json.data as T;};
}
