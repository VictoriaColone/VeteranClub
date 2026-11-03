"""Isolated tests. Never connect to the cloud or change this machine's VPN."""
import argparse
import base64
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location('admin_vpn', Path(__file__).with_name('admin_vpn.py'))
vpn = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vpn)
PUBLIC = Path(__file__).parents[1] / 'acceptance/nginx.conf'
KEY = base64.b64encode(b'k' * 32).decode()
PRIVATE = base64.b64encode(b'p' * 32).decode()


class ModelTest(unittest.TestCase):
    def test_gateway_enable_rechecks_certificate_and_nginx_before_start(self):
        with patch.object(vpn, 'load', return_value={}), patch.object(vpn, 'check_certificate') as certificate, patch.object(vpn, 'run') as run, patch.object(vpn, 'status', return_value={'gatewayActive': True}):
            self.assertTrue(vpn.enable_gateway()['gatewayActive'])
            certificate.assert_called_once()
            self.assertEqual(run.call_args_list[0].args[:2], ('nginx', '-t'))
            self.assertEqual(run.call_args_list[-1].args, ('systemctl', 'enable', '--now', 'norman-staff-gateway'))
        with patch.object(vpn, 'load', return_value={}), patch.object(vpn, 'check_certificate', side_effect=RuntimeError('fixture invalid certificate')), patch.object(vpn, 'run') as run:
            with self.assertRaises(RuntimeError):
                vpn.enable_gateway()
            run.assert_not_called()

    def test_public_ingress_removes_pages_login_and_admin_api_and_staff_sessions(self):
        value = vpn.isolate_public(PUBLIC.read_text())
        self.assertIn('location = /admin { return 404; }', value)
        self.assertIn('location ^~ /admin/ { return 404; }', value)
        self.assertIn('location ^~ /api/v1/admin/ { return 404; }', value)
        self.assertIn('location ^~ /api/v1/auth/staff/ { return 404; }', value)
        self.assertIn('$http_x_session_audience ~* "staff"', value)
        self.assertIn('location /mobile/ { try_files', value)
        self.assertIn('proxy_pass http://127.0.0.1:8091/', value)
        for bad in (value, 'server { location /admin/ {} }', PUBLIC.read_text() * 2):
            with self.assertRaises(ValueError):
                vpn.isolate_public(bad)

    def test_wireguard_peers_have_unique_device_addresses_not_lan_or_default_routes(self):
        value = vpn.wg_config(PRIVATE, {'owner-mac': {'publicKey': KEY, 'address': '10.77.42.2'}})
        self.assertIn('AllowedIPs = 10.77.42.2/32', value)
        self.assertIn('MTU = 1280', value)
        self.assertNotIn('0.0.0.0/0', value)
        self.assertNotIn('MASQUERADE', value)
        self.assertNotIn('SaveConfig', value)
        for address in ('10.77.42.1', '10.77.42.0', '10.77.42.255', '192.168.1.2', '::1'):
            with self.subTest(address=address), self.assertRaises((ValueError, TypeError)):
                vpn.wg_config(PRIVATE, {'owner-mac': {'publicKey': KEY, 'address': address}})
        for invalid in ('', 'abc', base64.b64encode(bytes(32)).decode(), KEY + '\n'):
            with self.assertRaises(ValueError):
                vpn.key(invalid)

    def test_peer_lifecycle_preserves_server_key_and_does_not_return_secrets(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            state = {'peers': {}, 'serverPublicKey': KEY, 'endpoint': '192.0.2.10', 'cutover': None}
            with patch.object(vpn, 'ROOT', root), patch.object(vpn, 'WG', root / 'wg.conf'), patch.object(vpn, 'run') as run:
                vpn.save(state)
                vpn.atomic(vpn.WG, vpn.wg_config(PRIVATE, {}))
                result = vpn.enroll(argparse.Namespace(name='owner-mac', public_key=KEY))
                self.assertEqual(result['address'], '10.77.42.2/32')
                self.assertEqual(result['allowedIPs'], '10.77.42.1/32')
                self.assertNotIn(PRIVATE, json.dumps(result))
                self.assertEqual(vpn.WG.stat().st_mode & 0o777, 0o600)
                with self.assertRaises(ValueError):
                    vpn.enroll(argparse.Namespace(name='other-mac', public_key=KEY))
                vpn.enroll(argparse.Namespace(name='owner-mac'), revoke=True)
                self.assertEqual(vpn.load()['peers'], {})
                self.assertEqual(vpn.private_key(), PRIVATE)
                self.assertEqual(run.call_args.args, ('wg', 'syncconf', 'wg-norman', '/dev/stdin'))

    def test_cutover_requires_a_real_recent_handshake(self):
        for devices in ([], [{'lastHandshake': 0}], [{'lastHandshake': int(time.time()) - 1000}]):
            with patch.object(vpn, 'load', return_value={'cutover': None}), patch.object(vpn, 'status', return_value={'gatewayActive': True, 'devices': devices}):
                with self.assertRaises(ValueError):
                    vpn.cutover()

    def test_verification_waits_for_reload_and_checks_mobile_and_staff_policy(self):
        requests = []
        def open_request(request, timeout):
            requests.append(request)
            path = request.selector
            staff = any(k.lower() == 'x-session-audience' and v == 'staff' for k, v in request.header_items())
            code = 404 if path.startswith(('/admin/', '/api/v1/admin/', '/api/v1/auth/staff/')) or staff else 200
            if len(requests) == 1:
                code = 200  # The old worker still served /admin/ after reload.
            response = MagicMock()
            response.__enter__.return_value.status = code
            return response
        opener = MagicMock()
        opener.open.side_effect = open_request
        with patch.object(vpn.urllib.request, 'build_opener', return_value=opener), patch.object(vpn.time, 'sleep') as sleep:
            vpn.verify_public_ingress()
        sleep.assert_called_once_with(0.25)
        self.assertEqual(len(requests), 12)
        self.assertEqual(requests[-1].selector, '/mobile/')

    def test_failed_policy_verification_restores_original_ingresses(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            files = (root / 'one.conf', root / 'two.conf')
            original = PUBLIC.read_text()
            for file in files:
                file.write_text(original)
            state = {'cutover': None}
            response = MagicMock()
            response.__enter__.return_value.status = 200  # Persistent staff exposure.
            opener = MagicMock()
            opener.open.return_value = response
            with patch.object(vpn, 'ROOT', root), patch.object(vpn, 'PUBLIC_FILES', files), patch.object(vpn, 'load', return_value=state), patch.object(vpn, 'status', return_value={'gatewayActive': True, 'devices': [{'lastHandshake': int(time.time()) - 1}]}), patch.object(vpn, 'run'), patch.object(vpn.urllib.request, 'build_opener', return_value=opener), patch.object(vpn.time, 'monotonic', side_effect=[0, 11]):
                with self.assertRaisesRegex(ValueError, 'Ingress verification failed'):
                    vpn.cutover()
            self.assertTrue(all(file.read_text() == original for file in files))
            self.assertIsNone(state['cutover'])
            self.assertFalse((root / 'ingress-before').exists())

    def test_nginx_validation_failure_restores_both_ingresses_without_touching_mobile(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            files = (root / 'one.conf', root / 'two.conf')
            original = PUBLIC.read_text()
            for file in files:
                file.write_text(original)
            state = {'cutover': None}
            commands = []
            def run(*args, **kwargs):
                commands.append(args)
                if args == ('nginx', '-t') and commands.count(args) == 1:
                    raise RuntimeError('fixture validation failure')
                return subprocess.CompletedProcess(args, 0, '', '')
            with patch.object(vpn, 'ROOT', root), patch.object(vpn, 'PUBLIC_FILES', files), patch.object(vpn, 'load', return_value=state), patch.object(vpn, 'status', return_value={'gatewayActive': True, 'devices': [{'lastHandshake': int(time.time()) - 1}]}), patch.object(vpn, 'run', side_effect=run):
                with self.assertRaises(RuntimeError):
                    vpn.cutover()
            self.assertTrue(all(file.read_text() == original for file in files))
            self.assertIsNone(state['cutover'])
            self.assertFalse((root / 'ingress-before').exists())
            self.assertIn(('systemctl', 'reload', 'nginx'), commands)


NGINX = shutil.which('nginx') or ('/opt/homebrew/bin/nginx' if Path('/opt/homebrew/bin/nginx').exists() else None)


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


@unittest.skipUnless(NGINX, 'A local Nginx binary is required for actual ingress tests')
class IngressTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.processes = []
        self.hits = []
        hits = self.hits
        class Backend(BaseHTTPRequestHandler):
            def do_GET(self):
                hits.append(dict(self.headers))
                self.send_response(200)
                self.send_header('Set-Cookie', 'norman-staff-session=fixture; HttpOnly; Path=/')
                self.end_headers()
                self.wfile.write(b'{"fixture":true}')
            do_POST = do_GET
            def log_message(self, *args):
                pass
        self.backend = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
        self.thread = threading.Thread(target=self.backend.serve_forever, daemon=True)
        self.thread.start()
        for section in ('admin', 'mobile'):
            (self.root / section).mkdir()
            (self.root / section / 'index.html').write_text(section + '-fixture')

    def tearDown(self):
        for process in self.processes:
            process.terminate()
            process.communicate(timeout=5)
        self.backend.shutdown()
        self.backend.server_close()
        self.tmp.cleanup()

    def start(self, text, listen):
        file = self.root / (str(listen) + '.conf')
        file.write_text(text)
        test = subprocess.run([NGINX, '-t', '-c', str(file)], capture_output=True, text=True)
        self.assertEqual(test.returncode, 0, test.stderr)
        process = subprocess.Popen([NGINX, '-c', str(file), '-g', 'daemon off;'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.processes.append(process)
        for _ in range(50):
            if process.poll() is not None:
                self.fail(process.communicate()[1].decode())
            try:
                with socket.create_connection(('127.0.0.1', listen), timeout=.1):
                    return
            except OSError:
                time.sleep(.05)
        self.fail('Fixture Nginx did not start')

    def request(self, listen, path, headers=()):
        connection = http.client.HTTPConnection('127.0.0.1', listen, timeout=3)
        connection.putrequest('GET', path)
        for name, value in headers:
            connection.putheader(name, value)
        connection.endheaders()
        response = connection.getresponse()
        result = response.status
        response.read()
        connection.close()
        return result

    def test_public_nginx_rejects_encoded_paths_and_duplicate_staff_headers(self):
        listen = port()
        source = vpn.isolate_public(PUBLIC.read_text())
        source = source.replace('127.0.0.1:8088', '127.0.0.1:' + str(listen)).replace('/opt/norman-acceptance/current/web', str(self.root)).replace('include /etc/nginx/norman-acceptance-extra/*.conf;', '').replace('access_log /var/log/nginx/norman-acceptance.log combined;', 'access_log off;').replace('127.0.0.1:8091', '127.0.0.1:' + str(self.backend.server_port))
        self.start(f'pid {self.root}/public.pid; error_log stderr; events {{}} http {{ {source} }}', listen)
        for path in ('/admin', '/admin/', '/admin/assets/app.js', '/%61dmin/', '/api/v1/admin', '/api/v1/admin/users', '/api/v1/%61dmin/users', '/api/v1/auth/staff/login', '//api/v1/admin/users'):
            self.assertEqual(self.request(listen, path), 404, path)
        for headers in ([('X-Session-Audience', 'staff')], [('X-Session-Audience', 'staff'), ('X-Session-Audience', 'user')], [('X-Session-Audience', 'user'), ('X-Session-Audience', 'staff')]):
            self.assertEqual(self.request(listen, '/api/v1/club', headers), 404)
        self.assertEqual(self.hits, [])
        self.assertEqual(self.request(listen, '/mobile/'), 200)
        self.assertEqual(self.request(listen, '/api/v1/club'), 200)
        self.assertEqual(len(self.hits), 1)

    def test_private_gateway_uses_tls_and_preserves_origin_and_secure_cookies(self):
        cert = self.root / 'cert'
        cert.mkdir()
        generated = subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(cert / 'privkey.pem'), '-out', str(cert / 'fullchain.pem'), '-days', '1', '-subj', '/CN=' + vpn.DOMAIN, '-addext', 'subjectAltName=DNS:' + vpn.DOMAIN], capture_output=True)
        self.assertEqual(generated.returncode, 0)
        listen = port()
        source = vpn.private_nginx(cert, self.root, 'http://127.0.0.1:4190')
        source = source.replace('user www-data;', '').replace('/run/norman-staff-nginx.pid', str(self.root / 'private.pid')).replace('/var/log/nginx/norman-staff-error.log', str(self.root / 'private-error.log')).replace('/var/log/nginx/norman-staff-access.log', str(self.root / 'private-access.log')).replace('include /etc/nginx/mime.types;', '').replace('10.77.42.1:9443', '127.0.0.1:' + str(listen)).replace('allow 10.77.42.0/24;', 'allow 127.0.0.1/32;').replace(vpn.DOMAIN + ':9443', vpn.DOMAIN + ':' + str(listen)).replace('127.0.0.1:8091', '127.0.0.1:' + str(self.backend.server_port))
        self.start(source, listen)
        context = ssl.create_default_context(cafile=str(cert / 'fullchain.pem'))
        def request(path, origin=None, host=None):
            with socket.create_connection(('127.0.0.1', listen), timeout=3) as raw:
                with context.wrap_socket(raw, server_hostname=vpn.DOMAIN) as connection:
                    headers = f'GET {path} HTTP/1.1\r\nHost: {host or vpn.DOMAIN}\r\nConnection: close\r\n'
                    if origin:
                        headers += 'Origin: ' + origin + '\r\n'
                    connection.sendall((headers + '\r\n').encode())
                    response = http.client.HTTPResponse(connection)
                    response.begin()
                    result = (response.status, dict(response.getheaders()))
                    response.read()
                    return result
        self.assertEqual(request('/admin/')[0], 200)
        self.assertEqual(request('/mobile/')[0], 404)
        self.assertEqual(request('/admin/', 'https://evil.example')[0], 403)
        self.assertEqual(request('/admin/', host='evil.example')[0], 404)
        status, headers = request('/api/v1/auth/session', f'https://{vpn.DOMAIN}:{listen}')
        self.assertEqual(status, 200)
        self.assertIn('Secure', headers.get('Set-Cookie', ''))
        self.assertEqual(self.hits[-1]['Origin'], 'http://127.0.0.1:4190')
        self.assertEqual(self.hits[-1]['X-Real-IP'], '127.0.0.1')
        self.assertNotIn('X-Forwarded-For', self.hits[-1])


if __name__ == '__main__':
    unittest.main()
