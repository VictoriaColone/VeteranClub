#!/usr/bin/env python3
"""Private acceptance DB cutover. Credentials stay in root-only files on the server.

check: private DNS, verified TLS, correct schema and empty target, no writes.
backup-source: export the local database without RDS credentials or a cutover.
migrate: maintenance -> backup -> import -> content comparison -> switch -> health.
No deletion of source containers, data, images, backups or Adminer is performed.
"""
import argparse
import gzip
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
from urllib.request import urlopen

CONFIG = Path('/etc/norman-acceptance')
CA_DIR = Path('/etc/norman-rds-ca')
NGINX = Path('/etc/nginx/sites-available/norman-acceptance')
BACKUPS = Path('/var/backups/norman-acceptance')
FLAG = Path('/run/norman-acceptance-maintenance')
SERVICE = 'norman-acceptance.service'
CONTAINER = 'norman-acceptance-mysql'
IMAGE = 'public.ecr.aws/docker/library/mysql:8.4'
SCHEMA = 'norman_acceptance'
DROPIN = Path('/etc/systemd/system/norman-acceptance.service.d/20-rds.conf')

def run(args, **kwargs):
    return subprocess.run(args, check=True, stderr=subprocess.PIPE, **kwargs)

def private_rds_host(host):
    if not re.fullmatch(r'rm-[a-z0-9-]+\.mysql\.rds\.aliyuncs\.com', host):
        raise ValueError('Use the exact RDS MySQL endpoint, not a URL or an IP')
    addresses = {item[4][0] for item in socket.getaddrinfo(host, 3306, type=socket.SOCK_STREAM)}
    networks = [ipaddress.ip_network(cidr) for cidr in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')]
    if not addresses or not all(any(ipaddress.ip_address(ip) in network for network in networks) for ip in addresses):
        raise ValueError('RDS must resolve exclusively to RFC1918 private addresses')

def credentials():
    file = CONFIG / 'rds.env'
    if file.stat().st_uid != 0 or file.stat().st_mode & 0o077:
        raise ValueError('rds.env must be root-owned with mode 0600')
    values = dict(line.split('=', 1) for line in file.read_text().splitlines() if line and not line.startswith('#'))
    if not all(values.get(key) for key in ('RDS_HOST', 'RDS_USERNAME', 'RDS_PASSWORD')):
        raise ValueError('RDS host, account and password must be configured on the server')
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{1,31}', values['RDS_USERNAME']):
        raise ValueError('Unexpected RDS account name')
    private_rds_host(values['RDS_HOST'])
    for name in ('rds-ca.pem', 'rds-ca.jks'):
        if not (CA_DIR / name).is_file():
            raise ValueError('Download the RDS public CA PEM and JKS files first')
    return values

def mysql_command(target):
    if target == 'source':
        return ['docker', 'exec', CONTAINER, 'sh', '-c',
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names norman_acceptance "$@"', 'mysql']
    return ['docker', 'run', '--rm', '-i', '--network', 'host', '--env-file', str(CONFIG / 'rds.env'),
            '--mount', f'type=bind,src={CA_DIR / "rds-ca.pem"},dst=/run/rds-ca.pem,readonly', IMAGE, 'sh', '-c',
            'MYSQL_PWD="$RDS_PASSWORD" exec mysql --host="$RDS_HOST" --port=3306 --user="$RDS_USERNAME" '
            '--ssl-mode=VERIFY_IDENTITY --ssl-ca=/run/rds-ca.pem --connect-timeout=5 '
            '--default-character-set=utf8mb4 --batch --skip-column-names norman_acceptance "$@"', 'mysql']

def query(target, sql):
    return run(mysql_command(target) + ['--execute', sql], stdout=subprocess.PIPE).stdout

def tables(target):
    names = query(target, 'SHOW TABLES').decode().splitlines()
    if not all(re.fullmatch(r'[A-Za-z0-9_]+', name) for name in names):
        raise ValueError('Unexpected table identifier')
    return sorted(names)

def manifest(target):
    result = {}
    for table in tables(target):
        columns = query(target, f"SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='{SCHEMA}' AND TABLE_NAME='{table}' ORDER BY ORDINAL_POSITION").decode().splitlines()
        if not all(re.fullmatch(r'[A-Za-z0-9_]+', name) for name in columns):
            raise ValueError('Unexpected column identifier')
        names = ','.join('`' + name + '`' for name in columns)
        rows = query(target, f"SET time_zone='+00:00'; SELECT JSON_ARRAY({names}) FROM `{table}` ORDER BY {names}")
        count = int(query(target, f'SELECT COUNT(*) FROM `{table}`'))
        result[table] = {'rows': count, 'sha256': hashlib.sha256(rows).hexdigest()}
    return result

def preflight():
    values = credentials()
    result = query('target', 'SELECT DATABASE(); SHOW SESSION STATUS LIKE "Ssl_cipher"').decode()
    if not result.startswith(SCHEMA + '\n') or not re.search(r'Ssl_cipher\t\S+', result):
        raise ValueError('Wrong database or TLS connection was not established')
    if tables('target'):
        raise ValueError('Target must be an empty dedicated database; no tables will be dropped automatically')
    return values

def backup(folder):
    destination = folder / 'database.sql.gz'
    command = ['docker', 'exec', CONTAINER, 'sh', '-c',
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces '
               '--routines --events --triggers --hex-blob --set-gtid-purged=OFF --skip-add-locks norman_acceptance']
    with gzip.open(destination, 'wb') as output:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        shutil.copyfileobj(process.stdout, output)
        errors = process.stderr.read()
        if process.wait():
            raise RuntimeError('Source export failed')
    with gzip.open(destination, 'rb') as file:
        while file.read(1024 * 1024):
            pass
    if destination.stat().st_size < 1000:
        raise ValueError('Source export is unexpectedly small')
    return destination

def import_dump(dump):
    process = subprocess.Popen(mysql_command('target'), stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    try:
        with gzip.open(dump, 'rb') as source:
            shutil.copyfileobj(source, process.stdin)
        process.stdin.close()
        errors = process.stderr.read()
        if process.wait():
            raise RuntimeError('Target import failed; source is retained and target requires inspection')
    except BaseException:
        if process.poll() is None:
            process.kill()
        process.wait()
        raise

def quote_environment(value):
    if any(char in value for char in ('\n', '\r', '\x00')):
        raise ValueError('Environment values cannot contain line breaks')
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


def atomic_write(file, data, mode=0o600):
    temporary = file.with_name(file.name + '.next')
    temporary.write_text(data)
    temporary.chmod(mode)
    temporary.replace(file)

def health():
    for attempt in range(45):
        try:
            with urlopen('http://127.0.0.1:8091/actuator/health', timeout=2) as response:
                if json.load(response).get('status') == 'UP':
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('API health check failed after switching database')

def migrate():
    values = preflight()
    nginx = NGINX.read_text()
    if str(FLAG) not in nginx or FLAG.exists() or DROPIN.exists():
        raise ValueError('Install the maintenance-aware Nginx config; do not overwrite an existing cutover')
    api = CONFIG / 'api.env'
    original = dict(line.split('=', 1) for line in api.read_text().splitlines() if '=' in line and not line.startswith('#'))
    if not original.get('DB_URL', '').startswith('jdbc:mysql://127.0.0.1:3308/norman_acceptance?'):
        raise ValueError('This tool only migrates the existing local acceptance database')
    folder = BACKUPS / ('rds-cutover-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()))
    folder.mkdir(parents=True, mode=0o700)
    shutil.copy2(api, folder / 'api.env.before')
    switched = False
    FLAG.write_text('RDS migration in progress\n')
    FLAG.chmod(0o644)
    try:
        run(['systemctl', 'stop', SERVICE])
        dump = backup(folder)
        source = manifest('source')
        import_dump(dump)
        if source != manifest('target'):
            raise ValueError('Target content does not match source; refusing to switch')
        (folder / 'manifest.json').write_text(json.dumps(source, indent=2))
        settings = {**original, 'ACCEPTANCE_RDS_HOST': values['RDS_HOST'], 'DB_USERNAME': values['RDS_USERNAME'],
                    'DB_PASSWORD': values['RDS_PASSWORD'], 'DB_URL': 'jdbc:mysql://' + values['RDS_HOST'] + ':3306/' + SCHEMA +
                    '?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&sslMode=VERIFY_IDENTITY&'
                    'trustCertificateKeyStoreUrl=file:/etc/norman-rds-ca/rds-ca.jks&trustCertificateKeyStoreType=JKS&trustCertificateKeyStorePassword=apsaradb'}
        atomic_write(api, ''.join(key + '=' + quote_environment(value) + '\n' for key, value in settings.items()))
        DROPIN.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(Path(__file__).with_name('api-rds.conf'), DROPIN)
        DROPIN.chmod(0o644)
        run(['systemctl', 'daemon-reload'])
        run(['systemctl', 'start', SERVICE])
        health()
        switched = True
        (folder / 'SWITCHED').write_text('RDS data verified and API healthy\n')
        print('PASS: RDS data matches source; API is healthy. Local DB retained for controlled retirement.')
    finally:
        if not switched:
            run(['systemctl', 'stop', SERVICE])
            shutil.copy2(folder / 'api.env.before', api)
            if DROPIN.exists():
                DROPIN.rename(folder / 'failed-rds-service.conf')
            run(['systemctl', 'daemon-reload'])
            run(['systemctl', 'start', SERVICE])
            health()
            print('Source database restored; inspect the partial RDS import before retrying.', file=sys.stderr)
        FLAG.rename(folder / 'maintenance-finished')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['check', 'migrate', 'backup-source'])
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise ValueError('Run on the cloud server with sudo')
    os.umask(0o077)
    if args.action == 'backup-source':
        folder = BACKUPS / ('pre-rds-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()))
        folder.mkdir(parents=True, mode=0o700)
        dump = backup(folder)
        (folder / 'manifest.json').write_text(json.dumps(manifest('source'), indent=2))
        print('PASS: verified source backup:', dump)
        print('SHA256:', hashlib.sha256(dump.read_bytes()).hexdigest())
    elif args.action == 'check':
        preflight()
        print('PASS: private DNS, verified TLS and empty dedicated RDS database')
    else:
        migrate()

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Database-client stderr can contain SQL or credentials; keep it off UI logs.
        print(type(error).__name__ + ': migration not completed; inspect configuration securely on the server.', file=sys.stderr)
        sys.exit(1)
