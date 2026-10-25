import test from 'node:test';
import assert from 'node:assert/strict';
import { adminRefreshResources, mobileRefreshResources, refreshDelay } from '../packages/core/src/refresh-policy';

test('100 home visitors generate at most ten polling requests per second on average before jitter', () => {
  const requests = mobileRefreshResources('home', null, false);
  assert.ok(requests.includes('products') && requests.includes('players') && requests.includes('orders'));
  assert.ok(requests.length * 100 / (refreshDelay('mobile', 'home', 0) / 1000) <= 10);
  for (const key of ['wallet', 'applications', 'myProducts', 'hall']) assert.ok(!requests.includes(key as any));
});

test('public hall is never polled without receiving qualifications', () => {
  for (const full of [false, true]) assert.ok(!mobileRefreshResources('hall', null, false, full).includes('hall'));
  assert.ok(mobileRefreshResources('hall', null, true).includes('hall'));
});

test('order details refresh income while profile and product data stay scoped to their views', () => {
  assert.ok(mobileRefreshResources('home', 'order', true).includes('wallet'));
  assert.ok(mobileRefreshResources('me', null, false).includes('applications'));
  assert.ok(mobileRefreshResources('home', 'my-products', true).includes('myProducts'));
  assert.ok(mobileRefreshResources('orders', null, true, true).includes('products'), 'mutations can force a complete refresh');
});

test('admin polling skips customer dossiers and audit history outside the relevant views', () => {
  const orders = adminRefreshResources('orders', true);
  assert.ok(orders.includes('orders') && orders.includes('players'));
  assert.ok(!orders.includes('users') && !orders.includes('applications') && !orders.includes('audit'));
  assert.ok(adminRefreshResources('customers', true).includes('applications'));
  assert.ok(!adminRefreshResources('audit', false, true).includes('audit'), 'read-only staff never request admin audit');
});

test('active order views refresh faster and every page has bounded jitter', () => {
  assert.equal(refreshDelay('mobile', 'orders', 0), 10_000);
  assert.equal(refreshDelay('mobile', 'hall', 1), 13_000);
  assert.equal(refreshDelay('admin', 'orders', 0), 30_000);
  assert.equal(refreshDelay('mobile', 'home', 1), 33_000);
});
