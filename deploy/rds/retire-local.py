#!/usr/bin/env python3
"""Inspect or remove the old, dedicated local DB after a verified RDS cutover.

Run inspect first. Removal requires explicit owner confirmation at action time.
Keeps SQL exports, configuration recovery copies, system snapshots and RDS data.
"""
import argparse
import gzip
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import time
import migrate

CONTAINERS = ['norman-acceptance-mysql', 'norman-acceptance-mysql-init-failed-20261025']
PATHS = [
    '/var/lib/norman-acceptance/mysql',
    '/var/lib/norman-acceptance/mysql-init-failed-20261025',
    '/opt/norman-db-admin', '/var/lib/norman-db-admin', '/var/log/norman-db-admin',
    '/etc/nginx/norman-acceptance-extra/database.conf',
    '/etc/php/8.3/fpm/pool.d/norman-db-admin.conf',
    '/etc/norman-acceptance/mysql.env',
    '/home/admin/db-admin', '/home/admin/adminer-6.2.0-mysql.php',
    '/home/admin/norman-db-admin.tar.gz',
]

def run(args, **kwargs):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)

def guard():
    if os.geteuid() != 0:
        raise ValueError('Run on the cloud server with sudo')
    values = migrate.credentials()
    settings = {}
    for line in (migrate.CONFIG / 'api.env').read_text().splitlines():
        key, separator, value = line.partition('=')
        if separator and key in ('DB_URL', 'ACCEPTANCE_RDS_HOST'):
            settings[key] = shlex.split(value)[0]
    if settings.get('ACCEPTANCE_RDS_HOST') != values['RDS_HOST'] or not settings.get('DB_URL', '').startswith('jdbc:mysql://' + values['RDS_HOST'] + ':3306/norman_acceptance?'):
        raise ValueError('API must already use the intended RDS endpoint')
    if not migrate.DROPIN.is_file() or migrate.FLAG.exists():
        raise ValueError('RDS cutover must be complete without a maintenance flag')
    if 'docker.service' in run(['systemctl', 'show', migrate.SERVICE, '-p', 'Requires', '--value']).stdout.decode():
        raise ValueError('Remove the old Docker dependency from the main service unit first')
    migrate.health()
    folders = sorted(folder for folder in migrate.BACKUPS.glob('rds-cutover-*') if (folder / 'SWITCHED').is_file())
    if not folders or not (folders[-1] / 'manifest.json').is_file():
        raise ValueError('A verified cutover backup is required')
    with gzip.open(folders[-1] / 'database.sql.gz', 'rb') as backup:
        while backup.read(1024 * 1024):
            pass
    report = json.loads((migrate.BACKUPS / 'capacity-rds-20261025.json').read_text())
    if report.get('errors') != 0 or report.get('virtual_users') != 100:
        raise ValueError('The bounded 100-visitor test must pass first')
    if int(migrate.query('target', 'SELECT COUNT(*) FROM club_order')) < 3:
        raise ValueError('Verify the preserved orders and the post-migration synthetic order first')
    return folders[-1]

def inspect():
    backup = guard()
    names = set(run(['docker', 'ps', '-a', '--format', '{{.Names}}']).stdout.decode().splitlines())
    print(json.dumps({'ready': True, 'recovery_backup': str(backup),
                      'containers_to_remove': [name for name in CONTAINERS if name in names],
                      'paths_to_remove': [name for name in PATHS if Path(name).exists()],
                      'mysql_image_to_remove': migrate.IMAGE,
                      'preserved': ['RDS database', '/var/backups/norman-acceptance', 'cloud system snapshot', 'API and web application']}, ensure_ascii=False, indent=2))

def retire():
    guard()
    folder = migrate.BACKUPS / ('local-db-retired-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()))
    folder.mkdir(mode=0o700)
    # Preserve configuration needed to restore the source SQL export privately.
    for source in [migrate.CONFIG / 'mysql.env', Path('/etc/nginx/norman-acceptance-extra/database.conf'), Path('/etc/php/8.3/fpm/pool.d/norman-db-admin.conf')]:
        if source.is_file():
            shutil.copy2(source, folder / source.name)
            (folder / source.name).chmod(0o600)
    for name in CONTAINERS:
        existing = subprocess.run(['docker', 'container', 'inspect', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if existing.returncode == 0:
            run(['docker', 'update', '--restart=no', name])
            run(['docker', 'stop', name])
            run(['docker', 'rm', name])
    for name in PATHS:
        file = Path(name)
        if file.is_symlink() or file.is_file():
            file.unlink()
        elif file.is_dir():
            shutil.rmtree(file)
    run(['nginx', '-t'])
    run(['systemctl', 'reload', 'nginx'])
    # Stop PHP only when Nginx has no remaining active FastCGI upstream.
    nginx = run(['nginx', '-T']).stdout.decode()
    import re
    if not re.search(r'^\s*fastcgi_pass\s+', nginx, re.MULTILINE):
        run(['systemctl', 'disable', '--now', 'php8.3-fpm'])
    else:
        run(['systemctl', 'reload', 'php8.3-fpm'])
    run(['docker', 'image', 'rm', migrate.IMAGE])
    if not run(['docker', 'ps', '-aq']).stdout.strip():
        run(['systemctl', 'disable', '--now', 'docker.service', 'docker.socket'])
    migrate.health()
    (folder / 'RETIRED').write_text('Dedicated local DB, image and Adminer removed; RDS and recovery exports retained.\n')
    print('PASS: old local DB and Adminer removed; RDS API healthy; protected recovery backups retained.')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['inspect', 'retire'])
    parser.add_argument('--confirmed-removal', action='store_true')
    args = parser.parse_args()
    os.umask(0o077)
    if args.action == 'retire' and not args.confirmed_removal:
        parser.error('Obtain owner confirmation after showing the inspect output, then pass --confirmed-removal')
    if args.action == 'inspect':
        inspect()
    else:
        retire()
