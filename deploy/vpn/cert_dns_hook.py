#!/usr/bin/env python3
"""Certbot manual DNS hook; an administrator updates DNS, then marks it ready.

Run by certbot on the server. The terminal remains available because certbot
can run detached. Challenge bytes are displayed as integers to avoid O/0/l/I
transcription errors. No account or certificate private key is exported.
"""
import json
import os
from pathlib import Path
import re
import time


def main():
    domain = os.environ.get('CERTBOT_DOMAIN', '')
    validation = os.environ.get('CERTBOT_VALIDATION', '')
    if os.geteuid() != 0 or domain != 'admin.veteranclub.icu' or not re.fullmatch(r'[A-Za-z0-9_-]{43}', validation):
        raise SystemExit('Unexpected certificate challenge')
    os.umask(0o077)
    root = Path('/run/norman-admin-cert')
    root.mkdir(mode=0o700, exist_ok=True)
    ready = root / 'ready'
    ready.unlink(missing_ok=True)
    now = time.time()
    wait = int(os.environ.get('NORMAN_CERT_DNS_WAIT_SECONDS', '30'))
    wait = max(0, min(900, wait))
    record = {'dnsName': '_acme-challenge.' + domain,
              'validationAscii': list(map(ord, validation)),
              'createdAt': int(now), 'notBefore': int(now) + wait}
    (root / 'challenge.json').write_text(json.dumps(record) + '\n')
    (root / 'challenge.json').chmod(0o600)
    deadline = time.monotonic() + 1800
    while time.monotonic() < deadline:
        if time.time() >= now + wait and ready.is_file() and ready.read_text().strip() == validation:
            return
        time.sleep(2)
    raise SystemExit('DNS verification was not marked ready before timeout')


if __name__ == '__main__':
    main()
