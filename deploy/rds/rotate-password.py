#!/usr/bin/env python3
"""Stage an owner-entered password, then synchronize it after an RDS console reset.

This tool never changes a database account. The owner completes that operation
in the RDS console. No credentials are accepted on the command line or printed.
"""
import argparse
import fcntl
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.request import urlopen
import zipfile

CONFIG = Path('/etc/norman-acceptance')
BACKUPS = Path('/var/backups/norman-acceptance')
FLAG = Path('/run/norman-acceptance-maintenance')
HOST = 'rm-uf67qfnp5xu28wo8j.mysql.rds.aliyuncs.com'
ACCOUNT = 'norman_service'
SERVICE = 'norman-acceptance.service'
APP_JAR = Path('/opt/norman-acceptance/current/norman-acceptance.jar')


def private_file(path):
    info = path.lstat()
    if path.is_symlink() or not path.is_file() or info.st_uid != 0 or info.st_mode & 0o077:
        raise ValueError('Expected a root-owned private regular file')
    return path.read_text()


def parse_env(text, quoted=False):
    values = {}
    for line in text.splitlines():
        if not line or line.startswith('#'):
            continue
        key, separator, value = line.partition('=')
        if not separator or key in values:
            raise ValueError('Invalid environment file')
        values[key] = shlex.split(value)[0] if quoted else value
    return values


def replace_password(text, key, password, quoted=False):
    value = '"' + password.replace('\\', '\\\\').replace('"', '\\"') + '"' if quoted else password
    if sum(line.startswith(key + '=') for line in text.splitlines()) != 1:
        raise ValueError('Expected exactly one password field')
    return ''.join(key + '=' + value + '\n' if line.startswith(key + '=') else line
                   for line in text.splitlines(keepends=True))


def write_private(path, text):
    fd, name = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as output:
            output.write(text)
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def current():
    raw = {name: private_file(CONFIG / name) for name in ('rds.env', 'api.env')}
    rds = parse_env(raw['rds.env'])
    api = parse_env(raw['api.env'], quoted=True)
    if rds.get('RDS_HOST') != HOST or rds.get('RDS_USERNAME') != ACCOUNT:
        raise ValueError('Unexpected RDS target')
    if (api.get('ACCEPTANCE_RDS_HOST') != HOST or api.get('DB_USERNAME') != ACCOUNT
            or not api.get('DB_URL', '').startswith('jdbc:mysql://' + HOST + ':3306/norman_acceptance?')
            or 'sslMode=VERIFY_IDENTITY' not in api['DB_URL']):
        raise ValueError('Unexpected API database configuration')
    return raw


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def stage():
    raw = current()
    pending = CONFIG / 'rds-password.pending.json'
    if pending.exists() or FLAG.exists():
        raise ValueError('A staged update or maintenance operation already exists')
    password = getpass.getpass('New norman_service password (hidden; enter it yourself): ')
    confirmation = getpass.getpass('Confirm the same password (hidden): ')
    groups = [r'[A-Z]', r'[a-z]', r'[0-9]', r'[!@#$%^&*()_+\-=]']
    if (password != confirmation or not re.fullmatch(r'[A-Za-z0-9!@#$%^&*()_+\-=]{8,32}', password)
            or sum(bool(re.search(group, password)) for group in groups) < 3):
        raise ValueError('Passwords must match the RDS password requirements')
    rds = parse_env(raw['rds.env'])
    api = parse_env(raw['api.env'], quoted=True)
    if rds['RDS_PASSWORD'] != api['DB_PASSWORD'] or password == rds['RDS_PASSWORD']:
        raise ValueError('Existing configurations must match and the new password must differ')
    write_private(pending, json.dumps({'host': HOST, 'account': ACCOUNT, 'password': password,
                                     'before': {name: digest(text) for name, text in raw.items()}}))
    print('STAGED root:0600. Application unchanged. Now reset ONLY norman_service in RDS to this same password.')


def run(args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=45)


def check_health():
    for attempt in range(60):
        try:
            with urlopen('http://127.0.0.1:8091/actuator/health', timeout=2) as response:
                if json.load(response).get('status') == 'UP':
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('Application health verification failed')


def verify_connection(payload, raw):
    if payload.get('host') != HOST or payload.get('account') != ACCOUNT:
        raise ValueError('Unexpected staged target')
    api = parse_env(raw['api.env'], quoted=True)
    # Reuse the application's official Connector/J dependency and verified TLS URL.
    # No DB client/server installation or change to the running application is needed.
    with tempfile.TemporaryDirectory(prefix='norman-rds-probe-') as directory:
        folder = Path(directory)
        with zipfile.ZipFile(APP_JAR) as application:
            drivers = [name for name in application.namelist()
                       if re.fullmatch(r'BOOT-INF/lib/mysql-connector-j-[A-Za-z0-9.\-]+\.jar', name)]
            if len(drivers) != 1:
                raise ValueError('Expected one application MySQL JDBC driver')
            (folder / 'mysql-driver.jar').write_bytes(application.read(drivers[0]))
        classpath = str(folder / 'mysql-driver.jar') + ':' + str(Path(__file__).resolve().parent)
        result = subprocess.run(['java', '-Xmx64m', '-cp', classpath, 'RdsPasswordProbe'],
                                input=api['DB_URL'] + '\n' + ACCOUNT + '\n' + payload['password'] + '\n',
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=20)
        try:
            report = json.loads(result.stdout)
        except ValueError:
            raise RuntimeError('Connection probe failed') from None
        if result.returncode or report.get('connected') is not True:
            print('CHECK: staged password is not yet verified for RDS; application unchanged.')
            raise RuntimeError('Connection probe failed')
        print('CHECK PASS: staged password connects to the intended RDS account over verified TLS; order count:', int(report['order_count']))


def check():
    payload = json.loads(private_file(CONFIG / 'rds-password.pending.json'))
    verify_connection(payload, current())


def apply(resume=False):
    pending = CONFIG / 'rds-password.pending.json'
    payload = json.loads(private_file(pending))
    raw = current()
    if payload.get('host') != HOST or payload.get('account') != ACCOUNT:
        raise ValueError('Unexpected staged target')
    if not resume and (FLAG.exists() or payload.get('before') != {name: digest(text) for name, text in raw.items()}):
        raise ValueError('Configuration changed since staging; inspect before continuing')
    password = payload['password']
    folder = None
    if resume:
        if not FLAG.exists():
            raise ValueError('Resume requires an interrupted maintenance operation')
        candidates = []
        for candidate in BACKUPS.glob('rds-password-*'):
            originals = {name: private_file(candidate / (name + '.before')) for name in raw}
            if {name: digest(text) for name, text in originals.items()} == payload.get('before'):
                candidates.append((candidate, originals))
        if not candidates:
            raise ValueError('A matching protected original backup is required')
        folder, originals = max(candidates, key=lambda item: item[0].stat().st_mtime_ns)
        expected = {'rds.env': replace_password(originals['rds.env'], 'RDS_PASSWORD', password),
                    'api.env': replace_password(originals['api.env'], 'DB_PASSWORD', password, quoted=True)}
        if any(raw[name] not in (originals[name], expected[name]) for name in raw):
            raise ValueError('Unexpected changes require manual inspection')
        raw = originals
    # Refuse to stop the working application if the console reset was not completed.
    verify_connection(payload, raw)
    # A backup includes both original files; it is retained even if health fails.
    if folder is None:
        BACKUPS.mkdir(mode=0o700, parents=True, exist_ok=True)
        folder = Path(tempfile.mkdtemp(prefix='rds-password-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-', dir=BACKUPS))
        for name in raw:
            shutil.copy2(CONFIG / name, folder / (name + '.before'))
            (folder / (name + '.before')).chmod(0o600)
    FLAG.write_text('Database credential update in progress\n')
    FLAG.chmod(0o644)
    run(['systemctl', 'stop', SERVICE])
    write_private(CONFIG / 'rds.env', replace_password(raw['rds.env'], 'RDS_PASSWORD', password))
    write_private(CONFIG / 'api.env', replace_password(raw['api.env'], 'DB_PASSWORD', password, quoted=True))
    run(['systemctl', 'start', SERVICE])
    check_health()
    (folder / 'VERIFIED').write_text('RDS password synchronized; API health UP.\n')
    pending.unlink()
    FLAG.unlink()
    print('PASS: norman_service synchronized in both root:0600 files; API health UP; maintenance removed.')
    print('Protected configuration backup:', folder)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['stage', 'check', 'apply', 'resume'])
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise ValueError('Run with sudo on the application server')
    os.umask(0o077)
    with (CONFIG / 'rds-password.lock').open('a') as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        {'stage': stage, 'check': check, 'apply': apply, 'resume': lambda: apply(resume=True)}[args.action]()


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt):
        # Do not print exception data, process arguments, passwords or configuration.
        # Restoring an old config after RDS reset would restore an invalid password.
        print('NOT COMPLETED. No database password was changed by this tool. Inspect staged/config files securely; maintenance is retained if apply failed.', file=sys.stderr)
        sys.exit(1)
