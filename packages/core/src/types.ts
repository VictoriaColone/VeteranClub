import {clubTime,formatCompactMoney} from './runtime';
export interface Product { id:string; userId:string|null; playerId:string|null; sellerType?:'club'|'member'; sellerName:string; gameId:string; serviceType:ServiceType; title:string; description:string; price:number; task:string; active:boolean; createdAt:number }
export const isClubProduct=(product:Product)=>product.sellerType==='club';
export interface ProductInput { gameId:string; serviceType:ServiceType; title:string; description:string; price:number; task?:string }
export type ServiceType = 'escort' | 'hourly';
export const serviceLabels: Record<ServiceType,string> = {escort:'护航订单',hourly:'组队订单'};
export interface Application { id:string; userId:string; playerId:string; name:string; serviceType:ServiceType; gameIds:string[]; introduction:string; status:'pending'|'assessment'|'approved'|'rejected'; createdAt:number; score?:number; reviewNote?:string; reviewedAt?:number }
export interface TeamMember { playerId:string; name:string; settlementId?:string }
export type Role = 'user' | 'boss' | 'player' | 'admin' | 'customer_service' | 'finance';
export type OrderStatus = 'unpaid' | 'waiting' | 'accepted' | 'serving' | 'confirming' | 'completed' | 'cancelled';
export type RefundStatus = 'none' | 'pending' | 'processing' | 'refunded' | 'rejected';
export interface Session { token: string; role: Role; userId: string; name: string; playerId?: string; expiresAt?:number }
export interface PaymentAttempt { id:string; orderId:string; channel:string; amount:number; currency:string; status:string; clientParams?:Record<string,unknown> }
export interface Game { id: string; name: string; short: string; color: string; tagline: string; regions: string[] }
export interface Player { id: string; userId: string; name: string; subtitle: string; gameIds: string[]; tags: string[]; rating: number; completed: number; price: number; color: string; avatar: string; available: boolean; busy?:boolean; approved: boolean; qualifications: ServiceType[]; qualificationGames?: Partial<Record<ServiceType,string[]>>; bio: string }
export interface ClubUser { id:string; name:string; playerId?:string }
export interface TimelineEvent { at: number; label: string; actor: string; note?: string }
export interface Order { productId?:string; productTitle?:string; serviceType:ServiceType; task:string; team:TeamMember[]; startedAt?:number; demoElapsedMs?:number; taskCompleted?:boolean; id: string; bossId: string; bossName: string; gameId: string; gameName: string; region: string; playerId?: string; playerName?: string; preferredPlayerId?: string; preferredPlayerName?: string; unitPrice: number; quantity: number; amount: number; commissionBps: number; earnings: number; status: OrderStatus; refundStatus: RefundStatus; refundReason?: string; refundAmount?: number; refundReviewNote?: string; note: string; createdAt: number; paidAt?: number; finishedAt?: number; paymentId?: string; settlementId?: string; settlementStatus?:'credited'|'paid'; timeline: TimelineEvent[] }
export interface Settlement { id: string; playerId: string; playerName: string; orderIds: string[]; amount: number; status: 'pending' | 'credited' | 'paid'; createdAt: number; paidAt?: number }
export interface Audit { id: string; at: number; actor: string; action: string; target: string; detail: string }
export interface ClubSettings { name: string; commissionBps: number; unpaidTimeoutMinutes: number; announcement: string }
export interface Database { schema: number; products?:Product[]; catalogRevision?:number; applications:Application[]; revision: number; games: Game[]; players: Player[]; orders: Order[]; settlements: Settlement[]; audit: Audit[]; settings: ClubSettings; sessions: Session[]; sequence: number }
export interface OrderFilters { status?: string; gameId?: string; query?: string; scope?: 'mine' | 'hall' | 'all'; serviceType?:ServiceType; refund?: string }
export interface OrderInput { productId?:string; serviceType?:ServiceType; task?:string; taskPrice?:number; gameId: string; region: string; playerId?: string; quantity: number; note?: string }
export interface Dashboard { todayRevenue: number; todayOrders: number; liveOrders: number; refundPending: number; onlinePlayers: number; totalPlayers: number; availablePlayers: number; unassigned: number; pendingPayout: number; revenueTrend: {label: string; revenue: number; orders: number}[]; gameDistribution: {name: string; orders: number; color: string}[]; recentOrders: Order[]; activity: Audit[] }
export interface Wallet { total: number; pending: number; settled: number; available: number; orders: Order[]; settlements: Settlement[] }
export interface StorageAdapter { getItem(key: string): string | null; setItem(key: string, value: string): void }
export interface ApiResponse<T> { code: number; message: string; data: T; requestId: string }
export type Transport = <T>(method: string, path: string, body?: unknown, token?: string, idempotencyKey?:string) => Promise<T>;
export const statusLabels: Record<OrderStatus, string> = {unpaid:'待付款',waiting:'待接单',accepted:'待开始',serving:'服务中',confirming:'待确认',completed:'已完成',cancelled:'已取消'};
export const roleLabels: Record<Role,string> = {user:'用户',boss:'用户',player:'用户',admin:'管理员',customer_service:'客服',finance:'财务'};
export const money = (cents: number) => (cents / 100).toFixed(2);
export const compactMoney = formatCompactMoney;
export const time = clubTime;
export const isStaff = (role: Role) => ['admin','customer_service','finance'].includes(role);

export const orderMembers=(o:Order):TeamMember[]=>o.team?.length?o.team:(o.playerId?[{playerId:o.playerId,name:o.playerName||''}]:[]);
export const participantEarnings=(o:Order,playerId?:string)=>{const team=orderMembers(o);const index=team.findIndex(m=>m.playerId===playerId);return index<0?0:Math.floor(o.earnings/team.length)+(index<o.earnings%team.length?1:0);};
export const orderSummary=(o:Order)=>o.serviceType==='escort'?o.task:`${o.quantity} 小时 · 按时长完成`;

export const qualifiedGames=(p:Player,type:ServiceType):string[]=>p.qualifications.includes(type)?(p.qualificationGames?.[type]??[]):[];
export const canServe=(p:Player,type:ServiceType,gameId:string)=>p.approved&&qualifiedGames(p,type).includes(gameId);
