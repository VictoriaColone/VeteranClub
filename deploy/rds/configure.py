#!/usr/bin/env python3
"""Run interactively yourself on the cloud server; do not send passwords in chat."""
import getpass
import os
from pathlib import Path
import re
import sys

if os.geteuid() != 0:
    sys.exit('Run with sudo on the cloud server')
os.umask(0o077)
host = input('RDS internal endpoint: ').strip()
account = input('RDS ordinary account [norman_service]: ').strip() or 'norman_service'
if not re.fullmatch(r'rm-[a-z0-9-]+\.mysql\.rds\.aliyuncs\.com', host):
    sys.exit('Unexpected RDS endpoint')
if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{1,31}', account):
    sys.exit('Unexpected account name')
password = getpass.getpass('Existing RDS password (hidden): ')
if not password or any(char in password for char in '\r\n\x00'):
    sys.exit('Invalid password')
file = Path('/etc/norman-acceptance/rds.env')
if file.exists():
    sys.exit('rds.env already exists; refusing to overwrite credentials')
with file.open('x') as output:
    output.write(f'RDS_HOST={host}\nRDS_USERNAME={account}\nRDS_PASSWORD={password}\n')
file.chmod(0o600)
print('Saved on this server as root:0600. Password was not displayed.')
