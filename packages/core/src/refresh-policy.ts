export const CATALOG_TTL_MS = 5 * 60_000;

export type MobileResource = 'players' | 'orders' | 'wallet' | 'applications' | 'products' | 'myProducts' | 'hall';
export function mobileRefreshResources(tab: string, sheet: string | null, canReceive: boolean, full = false): MobileResource[] {
  const resources = new Set<MobileResource>(['players', 'orders']);
  if (full) ['wallet', 'applications', 'products', 'myProducts'].forEach(key => resources.add(key as MobileResource));
  if (tab === 'home') resources.add('products');
  if (tab === 'me') ['wallet', 'applications', 'myProducts'].forEach(key => resources.add(key as MobileResource));
  if (tab === 'orders' || ['order', 'pay', 'refund', 'finish', 'assistants'].includes(sheet || '')) resources.add('wallet');
  if (sheet === 'apply') resources.add('applications');
  if (sheet === 'my-products') resources.add('myProducts');
  if (canReceive && (full || tab === 'hall')) resources.add('hall');
  return [...resources];
}

export type AdminResource = 'orders' | 'players' | 'dashboard' | 'users' | 'applications' | 'audit';
export function adminRefreshResources(page: string, admin: boolean, full = false): AdminResource[] {
  const resources = new Set<AdminResource>(['dashboard']);
  if (full || ['dashboard', 'orders', 'customers', 'refunds'].includes(page)) resources.add('orders');
  if (full || ['dashboard', 'orders', 'players', 'customers'].includes(page)) resources.add('players');
  if (full || page === 'customers') { resources.add('users'); resources.add('applications'); }
  if (admin && (full || page === 'audit')) resources.add('audit');
  return [...resources];
}

// Delay each completed refresh, with jitter to avoid synchronized bursts.
export function refreshDelay(surface: 'mobile' | 'admin', page: string, random = Math.random()): number {
  const base = surface === 'mobile' && ['hall', 'orders'].includes(page) ? 10_000 : 30_000;
  return base + Math.floor(Math.max(0, Math.min(1, random)) * 3_000);
}
