#!/usr/bin/env python3
"""Read-only Adminer smoke check on the server; credentials and cookies stay in memory."""
import http.cookiejar
import json
from html.parser import HTMLParser
from pathlib import Path
import urllib.parse
import urllib.request
import urllib.error

base = 'http://127.0.0.1:8088/database/'
settings = dict(line.split('=', 1) for line in Path('/etc/norman-acceptance/mysql.env').read_text().splitlines() if '=' in line)
assert settings['MYSQL_DATABASE'] == 'norman_acceptance'
query = urllib.parse.urlencode({'server': '127.0.0.1:3308', 'username': settings['MYSQL_USER'], 'db': settings['MYSQL_DATABASE'], 'lang': 'zh'})
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def page(suffix='', fields=None, login_page=False):
    data = None if fields is None else urllib.parse.urlencode(fields).encode()
    request = urllib.request.Request(base + '?' + query + suffix, data=data)
    request.add_header('Accept-Language', 'zh-CN,zh;q=0.9')
    try:
        response = client.open(request, timeout=20)
    except urllib.error.HTTPError as error:
        if error.code != 403 or not login_page:
            raise
        response = error
    with response:
        assert response.status == 200 or (login_page and response.status == 403)
        return response.read().decode('utf-8')

class Inputs(HTMLParser):
    def __init__(self):
        super().__init__()
        self.hidden = {}
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'input' and attrs.get('type') == 'hidden' and attrs.get('name'):
            self.hidden[attrs['name']] = attrs.get('value', '')

login = page(login_page=True)
assert 'auth[password]' in login and 'name="tables[]"' not in login, 'Database authentication must be required'
form = Inputs()
form.feed(login)
fields = form.hidden | {'auth[driver]': 'server', 'auth[server]': '127.0.0.1:3308', 'auth[username]': settings['MYSQL_USER'], 'auth[password]': settings['MYSQL_PASSWORD'], 'auth[db]': settings['MYSQL_DATABASE']}
tables = page(fields=fields)
assert 'club_order' in tables and 'service_qualification' in tables, 'Authenticated database table listing failed'
orders = page('&select=club_order')
assert 'boss_name' in orders and 'amount' in orders and 'status' in orders, 'Order table browser failed'
print(json.dumps({'result': 'PASS', 'adminer': '6.2.0', 'database': 'norman_acceptance', 'authenticationRequired': True, 'tableList': True, 'orderTableBrowser': True, 'dataModified': False}))
