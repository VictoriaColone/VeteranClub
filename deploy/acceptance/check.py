#!/usr/bin/env python3
"""Synthetic end-to-end smoke check; invoke only on the acceptance server itself."""
import http.cookiejar
import json
import urllib.request
import uuid

origin = 'http://127.0.0.1:4190'
base = 'http://127.0.0.1:8088/api'
jar = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
audience = 'user'

def request(method, path, body=None):
    headers = {'Origin': origin, 'X-Client-Type': 'h5', 'X-Session-Audience': audience, 'Content-Type': 'application/json'}
    for cookie in jar:
        if cookie.name == 'norman-' + audience + '-csrf': headers['X-CSRF-Token'] = cookie.value
    if method == 'POST' and path == '/v1/orders': headers['Idempotency-Key'] = str(uuid.uuid4())
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    with client.open(urllib.request.Request(base + path, data=data, headers=headers, method=method), timeout=15) as response:
        value = json.load(response)
    assert value['code'] == 0, value
    return value['data']

def login(role='user', user='u-boss'):
    global audience
    audience = 'user' if role == 'user' else 'staff'
    return request('POST', '/v1/auth/sms/login/acceptance', {'role': role, 'accountId': user})

login()
order = request('POST', '/v1/orders', {'gameId':'delta', 'region':'国服', 'serviceType':'escort', 'task':'100w纯带出', 'taskPrice':12800, 'quantity':1, 'note':'部署验收自动检查 · 仅模拟收付款'})
order_id = order['id']
assert request('POST', '/v1/acceptance/orders/' + order_id + '/pay', {})['status'] == 'waiting'
login(user='u-player')
request('POST', '/v1/orders/' + order_id + '/accept', {})
request('POST', '/v1/orders/' + order_id + '/add-assistant', {'playerId':'p7'})
request('POST', '/v1/orders/' + order_id + '/start', {})
request('POST', '/v1/orders/' + order_id + '/finish', {'taskCompleted':True, 'note':'服务器内部验收：任务已完成'})
login()
completed = request('POST', '/v1/orders/' + order_id + '/confirm', {})
assert completed['status'] == 'completed' and completed['settlementStatus'] == 'credited', completed
login('admin', '')
assert any(item['id'] == order_id for item in request('GET', '/v1/orders?scope=all'))
assert len(request('GET', '/v1/admin/users')) == 5
print(json.dumps({'result':'PASS', 'orderId':order_id, 'status':'completed', 'settlement':'credited, awaiting real transfer SDK', 'sharedDatabase':True}, ensure_ascii=False))
