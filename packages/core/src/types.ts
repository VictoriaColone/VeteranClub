export type Role = 'boss' | 'player' | 'admin' | 'customer_service' | 'finance';
export type OrderStatus = 'unpaid' | 'waiting' | 'accepted' | 'serving' | 'confirming' | 'completed' | 'cancelled';
export type RefundStatus = 'none' | 'pending' | 'refunded' | 'rejected';
export interface Session { token: string; role: Role; userId: string; name: string; playerId?: string }
export interface Game { id: string; name: string; short: string; color: string; tagline: string; regions: string[] }
export interface Player { id: string; userId: string; name: string; subtitle: string; gameIds: string[]; tags: string[]; rating: number; completed: number; price: number; color: string; avatar: string; available: boolean; approved: boolean; bio: string }
export interface TimelineEvent { at: number; label: string; actor: string; note?: string }
export interface Order { id: string; bossId: string; bossName: string; gameId: string; gameName: string; region: string; playerId?: string; playerName?: string; preferredPlayerId?: string; preferredPlayerName?: string; unitPrice: number; quantity: number; amount: number; commissionBps: number; earnings: number; status: OrderStatus; refundStatus: RefundStatus; refundReason?: string; refundAmount?: number; refundReviewNote?: string; note: string; createdAt: number; paidAt?: number; finishedAt?: number; paymentId?: string; settlementId?: string; timeline: TimelineEvent[] }
export interface Settlement { id: string; playerId: string; playerName: string; orderIds: string[]; amount: number; status: 'pending' | 'paid'; createdAt: number; paidAt?: number }
export interface Audit { id: string; at: number; actor: string; action: string; target: string; detail: string }
export interface ClubSettings { name: string; commissionBps: number; unpaidTimeoutMinutes: number; announcement: string }
export interface Database { schema: number; revision: number; games: Game[]; players: Player[]; orders: Order[]; settlements: Settlement[]; audit: Audit[]; settings: ClubSettings; sessions: Session[]; sequence: number }
export interface OrderFilters { status?: string; gameId?: string; query?: string; scope?: 'mine' | 'hall' | 'all'; refund?: string }
export interface OrderInput { gameId: string; region: string; playerId?: string; quantity: number; note?: string }
export interface Dashboard { todayRevenue: number; todayOrders: number; liveOrders: number; refundPending: number; onlinePlayers: number; totalPlayers: number; availablePlayers: number; unassigned: number; pendingPayout: number; revenueTrend: {label: string; revenue: number; orders: number}[]; gameDistribution: {name: string; orders: number; color: string}[]; recentOrders: Order[]; activity: Audit[] }
export interface Wallet { total: number; pending: number; settled: number; available: number; orders: Order[]; settlements: Settlement[] }
export interface StorageAdapter { getItem(key: string): string | null; setItem(key: string, value: string): void }
export interface ApiResponse<T> { code: number; message: string; data: T; requestId: string }
export type Transport = <T>(method: string, path: string, body?: unknown, token?: string) => Promise<T>;
export const statusLabels: Record<OrderStatus, string> = {unpaid:'待付款',waiting:'待接单',accepted:'待开始',serving:'服务中',confirming:'待确认',completed:'已完成',cancelled:'已取消'};
export const roleLabels: Record<Role,string> = {boss:'老板',player:'打手',admin:'管理员',customer_service:'客服',finance:'财务'};
export const money = (cents: number) => (cents / 100).toFixed(2);
export const compactMoney = (cents: number) => Number((cents / 100).toFixed(2)).toLocaleString('zh-CN');
export const time = (value: number) => new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(value);
export const isStaff = (role: Role) => ['admin','customer_service','finance'].includes(role);
