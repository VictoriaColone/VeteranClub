#!/usr/bin/env python3
"""100 foreground virtual visitors, paced home polling, private acceptance only.

Creates four test login sessions and otherwise makes GET requests only.
No order/payment/profile changes and no security limits are disabled.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from http.cookiejar import CookieJar
import json
import math
from pathlib import Path
import threading
import time
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, Request, build_opener, urlopen

BASE = 'http://127.0.0.1:8091'
ORIGIN = 'http://127.0.0.1:4190'
HEADERS = {'Origin': ORIGIN, 'X-Client-Type': 'h5', 'X-Session-Audience': 'user', 'Content-Type': 'application/json'}
PATHS = ['/v1/players', '/v1/orders', '/v1/products?scope=public']

def login(account):
    cookies = CookieJar()
    opener = build_opener(HTTPCookieProcessor(cookies))
    request = Request(BASE + '/v1/auth/sms/login/acceptance', data=json.dumps({'role': 'user', 'accountId': account}).encode(), headers=HEADERS)
    with opener.open(request, timeout=10) as response:
        if json.load(response).get('code') != 0:
            raise RuntimeError('Private acceptance login failed')
    return '; '.join(cookie.name + '=' + cookie.value for cookie in cookies)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--duration', type=int, default=65)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if not 35 <= args.duration <= 90:
        parser.error('Use a bounded 35-90 second test')
    cookies = [login(account) for account in ['u-boss', 'u-hourly', 'u-escort', 'u-player']]
    start = time.monotonic() + 1
    samples = []
    lock = threading.Lock()

    def visitor(index):
        next_refresh = start + index * .30
        while next_refresh < start + args.duration:
            time.sleep(max(0, next_refresh - time.monotonic()))
            for path in PATHS:
                began = time.monotonic()
                status = 0
                try:
                    request = Request(BASE + path, headers={**HEADERS, 'Cookie': cookies[index % len(cookies)]})
                    with urlopen(request, timeout=10) as response:
                        status = response.status
                        if json.load(response).get('code') != 0:
                            status = 500
                except HTTPError as error:
                    status = error.code
                except Exception:
                    status = 0
                with lock:
                    samples.append((path, status, (time.monotonic() - began) * 1000))
            next_refresh += 30

    with ThreadPoolExecutor(max_workers=100) as pool:
        list(pool.map(visitor, range(100)))
    elapsed = time.monotonic() - start
    latencies = sorted(sample[2] for sample in samples)
    percentile = lambda proportion: round(latencies[min(len(latencies)-1, math.ceil(len(latencies)*proportion)-1)], 2)
    errors = sum(status != 200 for _, status, _ in samples)
    report = {'virtual_users': 100, 'distinct_test_accounts': 4, 'scenario': 'home polling, 3 GET requests per visitor per 30s, ramp over 30s',
              'seconds': round(elapsed, 2), 'requests': len(samples), 'requests_per_second': round(len(samples)/elapsed, 2),
              'errors': errors, 'p50_ms': percentile(.5), 'p95_ms': percentile(.95), 'p99_ms': percentile(.99),
              'max_ms': round(max(latencies), 2), 'status_counts': {str(status): sum(item[1] == status for item in samples) for status in sorted({item[1] for item in samples})}}
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))
    if errors:
        raise SystemExit(1)

if __name__ == '__main__':
    main()
