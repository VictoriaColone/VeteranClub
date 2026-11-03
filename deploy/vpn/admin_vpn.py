#!/usr/bin/env python3
"""Root-owned, project-specific WireGuard ingress. Never prints private keys.

Install prepares the VPN and the private HTTPS gateway. Cutover is a separate
operation, allowed only after an enrolled device has actually connected.
"""
import argparse
import base64
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.error

ROOT = Path('/etc/norman-vpn')
WG = Path('/etc/wireguard/wg-norman.conf')
INTERFACE = 'wg-norman'
ADDRESS = '10.77.42.1'
NETWORK = ipaddress.ip_network('10.77.42.0/24')
DOMAIN = 'admin.veteranclub.icu'
PORT = 9443
UDP_PORT = 51820
PROGRAM = Path('/usr/local/lib/norman-vpn/admin_vpn.py')
PUBLIC_FILES = (Path('/etc/nginx/sites-enabled/norman-acceptance'),
                Path('/etc/nginx/sites-enabled/default'))
DENIAL = '''    # BEGIN NORMAN VPN ONLY
    if ($http_x_session_audience ~* "staff") { return 404; }
    location = /api/v1/admin { return 404; }
    location ^~ /api/v1/admin/ { return 404; }
    location ^~ /api/v1/auth/staff/ { return 404; }
    # END NORMAN VPN ONLY
'''


def run(*args, data=None, required=True):
    result = subprocess.run(args, input=data, text=True, capture_output=True, timeout=60)
    if required and result.returncode:
        # No raw command output: wg configuration / command failures can contain secrets.
        raise RuntimeError(f'{Path(args[0]).name} failed (exit {result.returncode}); inspect locally')
    return result


def atomic(path, data, mode=0o600):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.norman-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as output:
            os.fchmod(output.fileno(), mode)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def key(value):
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, TypeError):
        raise ValueError('Expected a WireGuard public key') from None
    if len(decoded) != 32 or decoded == bytes(32) or base64.b64encode(decoded).decode() != value:
        raise ValueError('Expected a canonical 32-byte WireGuard key')
    return value


def load():
    return json.loads((ROOT / 'state.json').read_text())


def save(state):
    atomic(ROOT / 'state.json', json.dumps(state, indent=2) + '\n')


def wg_config(private, peers, hooks=True):
    key(private)
    lines = ['[Interface]', f'PrivateKey = {private}', f'ListenPort = {UDP_PORT}']
    if hooks:
        lines += [f'Address = {ADDRESS}/24', 'MTU = 1280',
                  f'PostUp = {PROGRAM} firewall-up', f'PostDown = {PROGRAM} firewall-down']
    for name, peer in sorted(peers.items()):
        key(peer['publicKey'])
        if not re.fullmatch(r'[a-z0-9][a-z0-9-]{1,39}', name):
            raise ValueError('Invalid device label')
        address = ipaddress.ip_address(peer['address'])
        if address not in NETWORK or int(address) - int(NETWORK.network_address) not in range(2, 255):
            raise ValueError('Invalid peer address')
        lines += ['', f'# Device: {name}', '[Peer]', f'PublicKey = {peer["publicKey"]}',
                  f'AllowedIPs = {address}/32']
    return '\n'.join(lines) + '\n'


def private_key():
    values = re.findall(r'^PrivateKey\s*=\s*(\S+)\s*$', WG.read_text(), re.M)
    if len(values) != 1:
        raise ValueError('Unexpected managed WireGuard configuration')
    return key(values[0])


def private_nginx(cert, web_root, backend_origin):
    if not re.fullmatch(r'/[A-Za-z0-9_./-]+', str(cert)) or not re.fullmatch(r'/[A-Za-z0-9_./-]+', str(web_root)):
        raise ValueError('Unsupported path')
    if not re.fullmatch(r'https?://(?:127\.0\.0\.1|localhost):[1-9][0-9]{1,4}', backend_origin):
        raise ValueError('The acceptance upstream origin must remain loopback-only')
    return f'''# Separate process: VPN failure must not stop the user website.
user www-data;
worker_processes 1;
pid /run/norman-staff-nginx.pid;
error_log /var/log/nginx/norman-staff-error.log warn;
events {{ worker_connections 256; }}
http {{
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    server_tokens off;
    log_format safe '$remote_addr $request_method $uri $status';
    access_log /var/log/nginx/norman-staff-access.log safe;
    map $http_origin $staff_origin_ok {{ default 0; "" 1; "https://{DOMAIN}:{PORT}" 1; }}
    map $http_origin $staff_upstream_origin {{ default ""; "https://{DOMAIN}:{PORT}" "{backend_origin}"; }}
    server {{
        listen {ADDRESS}:{PORT} ssl;
        server_name {DOMAIN};
        ssl_certificate {cert}/fullchain.pem;
        ssl_certificate_key {cert}/privkey.pem;
        ssl_protocols TLSv1.2 TLSv1.3;
        ssl_session_tickets off;
        root {web_root};
        index index.html;
        client_max_body_size 64k;
        client_body_timeout 10s;
        client_header_timeout 10s;
        add_header Cache-Control no-store always;
        add_header X-Content-Type-Options nosniff always;
        add_header X-Frame-Options DENY always;
        add_header Referrer-Policy no-referrer always;
        if ($host != "{DOMAIN}") {{ return 404; }}
        if ($staff_origin_ok = 0) {{ return 403; }}
        allow {NETWORK};
        deny all;
        location = / {{ return 302 /admin/; }}
        location = /admin {{ return 302 /admin/; }}
        location /admin/ {{ try_files $uri $uri/ /admin/index.html; }}
        location /api/ {{
            proxy_pass http://127.0.0.1:8091/;
            proxy_http_version 1.1;
            proxy_set_header Host 127.0.0.1:8091;
            proxy_set_header Origin $staff_upstream_origin;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For "";
            proxy_set_header X-Forwarded-Proto "";
            proxy_set_header Connection "";
            proxy_cookie_flags ~^norman- secure;
            proxy_connect_timeout 3s;
            proxy_read_timeout 75s;
            proxy_buffering off;
        }}
        location / {{ return 404; }}
        location ~ /\\. {{ deny all; }}
    }}
}}
'''


def isolate_public(value):
    if '# BEGIN NORMAN VPN ONLY' in value:
        raise ValueError('Ingress already managed; use the recorded cutover state')
    exact = r'location\s*=\s*/admin\s*\{[^{}]*\}'
    prefix = r'location\s+(?:\^~\s+)?/admin/\s*\{[^{}]*\}'
    if len(re.findall(exact, value)) != 1 or len(re.findall(prefix, value)) != 1:
        raise ValueError('Unexpected admin locations; refusing an unreviewed ingress change')
    value = re.sub(exact, 'location = /admin { return 404; }', value)
    value = re.sub(prefix, 'location ^~ /admin/ { return 404; }', value)
    if value.count('server {') != 1:
        raise ValueError('Expected one server block')
    return value.replace('server {', 'server {\n' + DENIAL, 1)


def iptables_rule(action, chain, *rule):
    exists = run('iptables', '-w', '5', '-C', chain, *rule, required=False).returncode == 0
    if action == 'up' and not exists:
        run('iptables', '-w', '5', '-I', chain, '1', *rule)
    elif action == 'down' and exists:
        run('iptables', '-w', '5', '-D', chain, *rule)


def firewall(action):
    # No global policy changes, NAT, default routes or IP forwarding changes.
    chain = 'NORMAN_VPN_INPUT'
    jump = ('-i', INTERFACE, '-m', 'comment', '--comment', 'norman-vpn', '-j', chain)
    handshake = ('-p', 'udp', '--dport', str(UDP_PORT), '-m', 'comment', '--comment', 'norman-vpn', '-j', 'ACCEPT')
    if action == 'up':
        exists = run('iptables', '-w', '5', '-S', chain, required=False).returncode == 0
        if exists:
            raise ValueError('Reserved VPN firewall chain already exists; inspect stale state')
        run('iptables', '-w', '5', '-N', chain)
        run('iptables', '-w', '5', '-A', chain, '-d', ADDRESS, '-p', 'tcp', '--dport', str(PORT), '-j', 'ACCEPT')
        run('iptables', '-w', '5', '-A', chain, '-j', 'DROP')
    iptables_rule(action, 'INPUT', *handshake)
    iptables_rule(action, 'INPUT', *jump)
    for direction in ('-i', '-o'):
        iptables_rule(action, 'FORWARD', direction, INTERFACE, '-m', 'comment', '--comment', 'norman-vpn', '-j', 'DROP')
    if action == 'down':
        run('iptables', '-w', '5', '-F', chain, required=False)
        run('iptables', '-w', '5', '-X', chain, required=False)


def check_certificate():
    cert = Path('/etc/letsencrypt/live') / DOMAIN
    run('openssl', 'x509', '-in', str(cert / 'fullchain.pem'), '-noout', '-checkhost', DOMAIN)
    run('openssl', 'x509', '-in', str(cert / 'fullchain.pem'), '-noout', '-checkend', '86400')
    if not (cert / 'privkey.pem').is_file():
        raise ValueError('Expected the matching certificate private key')
    return cert


def enable_gateway():
    load()  # Refuse to start an unprepared gateway.
    check_certificate()
    run('nginx', '-t', '-c', str(ROOT / 'nginx.conf'))
    run('systemctl', 'daemon-reload')
    run('systemctl', 'enable', '--now', 'norman-staff-gateway')
    return status()


def install(args):
    if (ROOT / 'state.json').exists() or WG.exists():
        raise ValueError('VPN already exists; refusing to replace live keys or configuration')
    for name in ('wg', 'wg-quick', 'iptables', 'nginx', 'openssl'):
        if not shutil.which(name):
            raise ValueError(f'Install the official Ubuntu package providing {name} first')
    cert = Path('/etc/letsencrypt/live') / DOMAIN
    if not args.prepare_only:
        check_certificate()
    if not Path('/opt/norman-acceptance/current/web/admin/index.html').is_file():
        raise ValueError('Expected the existing admin build')
    # This installer targets the reviewed existing private acceptance server only.
    if not Path('/etc/systemd/system/norman-acceptance.service.d/20-rds.conf').is_file():
        raise ValueError('Expected the reviewed server layout')
    routes = json.loads(run('ip', '-json', 'route', 'show').stdout)
    for route in routes:
        destination = route.get('dst', 'default')
        if destination != 'default' and NETWORK.overlaps(ipaddress.ip_network(destination, strict=False)):
            raise ValueError('VPN subnet overlaps an existing server route')
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    ROOT.chmod(0o700)
    PROGRAM.parent.mkdir(parents=True, exist_ok=True)
    atomic(PROGRAM, Path(__file__).read_text(), 0o755)
    generated = run('wg', 'genkey').stdout.strip()
    public = run('wg', 'pubkey', data=generated + '\n').stdout.strip()
    state = {'domain': DOMAIN, 'serverAddress': ADDRESS, 'endpoint': args.endpoint,
             'serverPublicKey': key(public), 'peers': {}, 'cutover': None}
    atomic(WG, wg_config(generated, {}))
    atomic(ROOT / 'nginx.conf', private_nginx(cert, '/opt/norman-acceptance/current/web', 'http://127.0.0.1:4190'), 0o644)
    atomic('/etc/systemd/system/norman-staff-gateway.service', '''[Unit]
Description=Norman private staff HTTPS gateway
Requires=wg-quick@wg-norman.service
After=network-online.target wg-quick@wg-norman.service
[Service]
Type=forking
PIDFile=/run/norman-staff-nginx.pid
ExecStartPre=/usr/sbin/nginx -t -c /etc/norman-vpn/nginx.conf
ExecStart=/usr/sbin/nginx -c /etc/norman-vpn/nginx.conf
ExecReload=/usr/sbin/nginx -c /etc/norman-vpn/nginx.conf -s reload
ExecStop=/usr/sbin/nginx -c /etc/norman-vpn/nginx.conf -s quit
Restart=on-failure
[Install]
WantedBy=multi-user.target
''', 0o644)
    save(state)
    try:
        run('systemctl', 'daemon-reload')
        run('systemctl', 'enable', '--now', 'wg-quick@wg-norman')
        if not args.prepare_only:
            enable_gateway()
    except Exception:
        run('systemctl', 'disable', '--now', 'norman-staff-gateway', 'wg-quick@wg-norman', required=False)
        firewall('down')
        raise
    return {'installed': True, 'staffUrl': f'https://{DOMAIN}:{PORT}/admin/', 'cutover': False,
            'serverPublicKey': public, 'peers': 0, 'gatewayEnabled': not args.prepare_only}


def enroll(args, revoke=False):
    state = load()
    name = args.name
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{1,39}', name or ''):
        raise ValueError('Use a device label such as owner-mac or staff01-windows')
    peers = dict(state['peers'])
    if revoke:
        if name not in peers:
            raise ValueError('Device is not enrolled')
        del peers[name]
    else:
        public = key(args.public_key)
        if name in peers or any(p['publicKey'] == public for p in peers.values()):
            raise ValueError('Device label or public key is already enrolled')
        occupied = {p['address'] for p in peers.values()}
        available = [str(NETWORK.network_address + n) for n in range(2, 255)
                     if str(NETWORK.network_address + n) not in occupied]
        if not available:
            raise ValueError('Device address pool exhausted')
        peers[name] = {'publicKey': public, 'address': available[0]}
    private = private_key()
    before = WG.read_text()
    atomic(WG, wg_config(private, peers))
    try:
        run('wg', 'syncconf', INTERFACE, '/dev/stdin', data=wg_config(private, peers, hooks=False))
        state['peers'] = peers
        save(state)
    except Exception:
        atomic(WG, before)
        run('wg', 'syncconf', INTERFACE, '/dev/stdin', data=wg_config(private, load()['peers'], hooks=False), required=False)
        raise
    if revoke:
        return {'revoked': name}
    return {'device': name, 'address': peers[name]['address'] + '/32',
            'serverPublicKey': state['serverPublicKey'],
            'endpoint': state['endpoint'] + ':' + str(UDP_PORT),
            'allowedIPs': ADDRESS + '/32', 'persistentKeepalive': 25, 'mtu': 1280,
            'staffUrl': f'https://{DOMAIN}:{PORT}/admin/'}


def status():
    state = load()
    recent = {}
    result = run('wg', 'show', INTERFACE, 'latest-handshakes', required=False)
    if result.returncode == 0:
        recent = {line.split()[0]: int(line.split()[1]) for line in result.stdout.splitlines()}
    return {'staffUrl': f'https://{DOMAIN}:{PORT}/admin/', 'serverPublicKey': state['serverPublicKey'],
            'gatewayActive': run('systemctl', 'is-active', '--quiet', 'norman-staff-gateway', required=False).returncode == 0,
            'cutover': bool(state['cutover']),
            'devices': [{'name': name, 'address': peer['address'],
                         'lastHandshake': recent.get(peer['publicKey'], 0)} for name, peer in state['peers'].items()]}


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def verify_public_ingress():
    # systemctl reload returns after signalling Nginx, before new workers accept
    # requests. Wait for the whole policy, rather than treating old workers as a
    # failed deployment or accepting a healthy mobile page alone.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    probes = [('/admin/', None, 404), ('/api/v1/admin/dashboard', None, 404),
              ('/api/v1/auth/staff/login', None, 404),
              ('/api/v1/club', {'X-Session-Audience': 'staff'}, 404),
              ('/api/v1/club', None, 200), ('/mobile/', None, 200)]
    deadline = time.monotonic() + 10
    while True:
        failures = []
        for path, headers, expected in probes:
            request = urllib.request.Request('http://127.0.0.1:8088' + path, headers=headers or {})
            try:
                with opener.open(request, timeout=2) as response:
                    code = response.status
            except urllib.error.HTTPError as error:
                code = error.code
                error.close()
            except (urllib.error.URLError, TimeoutError, OSError):
                code = 0
            if code != expected:
                failures.append(f'{path}: expected {expected}, received {code}')
        if not failures:
            return
        if time.monotonic() >= deadline:
            raise ValueError('Ingress verification failed: ' + '; '.join(failures))
        time.sleep(0.25)


def cutover():
    state = load()
    if state['cutover']:
        return {'alreadyIsolated': True}
    live = status()
    if not live['gatewayActive'] or not any(0 < time.time() - p['lastHandshake'] < 900 for p in live['devices']):
        raise ValueError('Connect an enrolled staff device and verify HTTPS before cutover')
    run('curl', '--fail', '--silent', '--show-error', '--max-time', '10', '--resolve',
        f'{DOMAIN}:{PORT}:{ADDRESS}', f'https://{DOMAIN}:{PORT}/admin/')
    backups = ROOT / 'ingress-before'
    changes = {}
    originals = {}
    for index, path in enumerate(PUBLIC_FILES):
        original = path.read_text()
        updated = isolate_public(original)
        originals[index] = original
        changes[str(path)] = {'index': index, 'before': digest(original), 'after': digest(updated)}
    backups.mkdir(mode=0o700)
    for index, original in originals.items():
        atomic(backups / str(index), original)
    try:
        for path in PUBLIC_FILES:
            # Resolve the enabled symlink; do not replace the site symlink itself.
            atomic(path.resolve(), isolate_public(path.read_text()), 0o644)
        run('nginx', '-t')
        run('systemctl', 'reload', 'nginx')
        verify_public_ingress()
    except Exception:
        for path, record in changes.items():
            atomic(Path(path).resolve(), (backups / str(record['index'])).read_text(), 0o644)
        run('nginx', '-t')
        run('systemctl', 'reload', 'nginx')
        shutil.rmtree(backups)
        raise
    state['cutover'] = changes
    save(state)
    return {'isolated': True, 'staffUrl': f'https://{DOMAIN}:{PORT}/admin/', 'mobile': 'unchanged'}


def restore():
    state = load()
    changes = state.get('cutover')
    if not changes:
        raise ValueError('There is no recorded ingress cutover')
    for path, record in changes.items():
        if digest(Path(path).read_text()) != record['after']:
            raise ValueError('Ingress changed after cutover; manual review required')
        original = (ROOT / 'ingress-before' / str(record['index'])).read_text()
        if digest(original) != record['before']:
            raise ValueError('Ingress backup checksum mismatch')
    managed = {path: Path(path).read_text() for path in changes}
    try:
        for path, record in changes.items():
            atomic(Path(path).resolve(), (ROOT / 'ingress-before' / str(record['index'])).read_text(), 0o644)
        run('nginx', '-t')
        run('systemctl', 'reload', 'nginx')
    except Exception:
        for path, value in managed.items():
            atomic(Path(path).resolve(), value, 0o644)
        run('nginx', '-t')
        run('systemctl', 'reload', 'nginx')
        raise
    state['cutover'] = None
    save(state)
    shutil.rmtree(ROOT / 'ingress-before')
    return {'ingressRestored': True, 'vpnRetained': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['install', 'gateway-enable', 'status', 'peer-add', 'peer-revoke', 'cutover', 'restore', 'firewall-up', 'firewall-down'])
    parser.add_argument('--prepare-only', action='store_true', help='Start only the VPN while DNS/certificate validation is pending')
    parser.add_argument('--endpoint', default='8.133.228.181')
    parser.add_argument('--name')
    parser.add_argument('--public-key')
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error('Use the existing server administrator')
    try:
        if not ipaddress.ip_address(args.endpoint).is_global:
            raise ValueError('Endpoint must be the approved public server address')
        os.umask(0o077)
        if args.action.startswith('firewall-'):
            firewall(args.action.split('-')[1])
            return
        with Path('/run/norman-vpn-operation.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if args.action == 'install':
                result = install(args)
            elif args.action == 'gateway-enable':
                result = enable_gateway()
            elif args.action == 'status':
                result = status()
            elif args.action.startswith('peer-'):
                result = enroll(args, revoke=args.action == 'peer-revoke')
            elif args.action == 'cutover':
                result = cutover()
            else:
                result = restore()
            print(json.dumps(result, ensure_ascii=False))
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        # Tracebacks and command output must not reveal WireGuard private material.
        print('VPN operation failed; existing administrator must inspect the local state. No private key was printed.', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
