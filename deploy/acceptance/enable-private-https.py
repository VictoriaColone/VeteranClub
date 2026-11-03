#!/usr/bin/env python3
"""Install a loopback-only TLS entry for the existing restricted SSH preview.

Run as the existing server administrator, after the owner approves the
certificate agreement and the additional loopback forwarding destination.
No public listener, application release, database or password is changed.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import time

DOMAIN = "preview.veteranclub.icu"
PORT = 8443
CONFIG = Path("/etc/nginx/conf.d/norman-private-preview.conf")
SSH = Path("/etc/ssh/sshd_config")
KEYS = Path("/var/lib/norman-preview/.ssh/authorized_keys")
CERT = Path("/etc/letsencrypt/live") / DOMAIN

NGINX = r'''# Private preview: this port is reachable only through authorized SSH.
map $http_origin $norman_preview_origin_ok {
    default 0;
    "" 1;
    "https://preview.veteranclub.icu:8443" 1;
}
map $http_origin $norman_preview_upstream_origin {
    default "";
    "https://preview.veteranclub.icu:8443" "http://127.0.0.1:4190";
}
server {
    listen 127.0.0.1:8443 ssl;
    server_name preview.veteranclub.icu;
    ssl_certificate /etc/letsencrypt/live/preview.veteranclub.icu/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/preview.veteranclub.icu/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:NormanPrivatePreview:1m;
    ssl_session_timeout 10m;
    ssl_session_tickets off;
    client_header_timeout 10s;
    client_max_body_size 64k;
    client_body_timeout 10s;
    if ($host != "preview.veteranclub.icu") { return 403; }
    if ($norman_preview_origin_ok = 0) { return 403; }
    location / {
        proxy_pass http://127.0.0.1:8088;
        proxy_http_version 1.1;
        proxy_set_header Host 127.0.0.1:8088;
        proxy_set_header Connection "";
        # Translate only this exact private origin after rejecting all others.
        # Existing backend CSRF/session checks remain in effect.
        proxy_set_header Origin $norman_preview_upstream_origin;
        proxy_set_header X-Forwarded-For "";
        proxy_set_header X-Forwarded-Proto "";
        proxy_connect_timeout 3s;
        proxy_read_timeout 75s;
        proxy_buffering off;
        proxy_cookie_flags ~^norman- secure;
        proxy_redirect http://127.0.0.1:8088/ https://preview.veteranclub.icu:8443/;
    }
}
'''


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def extend_keys(value):
    old = 'permitopen="127.0.0.1:8088"'
    added = 'permitopen="127.0.0.1:8443"'
    result = []
    for line in value.splitlines(keepends=True):
        if not line.strip() or line.lstrip().startswith("#"):
            result.append(line)
            continue
        # Validate options without printing any public-key material.
        options = line.split(" ssh-", 1)[0]
        if (not options.startswith("restrict,port-forwarding,") or old not in options
                or 'command="/usr/bin/false"' not in options):
            raise ValueError("Unexpected preview key restrictions; review manually")
        if added not in options:
            line = line.replace(old, old + "," + added, 1)
        result.append(line)
    if not result:
        raise ValueError("Preview authorized_keys is empty")
    return "".join(result)


def extend_ssh(value):
    old = "PermitOpen 127.0.0.1:8088"
    new = old + " 127.0.0.1:8443"
    blocks = list(re.finditer(r"(?m)^Match[^\n]*(?:\n|$)", value))
    selected = [index for index, match in enumerate(blocks)
                if match.group().strip() == "Match User norman-preview"]
    if len(selected) != 1:
        raise ValueError("Expected exactly one norman-preview SSH Match block")
    index = selected[0]
    start = blocks[index].end()
    end = blocks[index + 1].start() if index + 1 < len(blocks) else len(value)
    block = value[start:end]
    if re.search(r"(?m)^[ \t]*" + re.escape(new) + r"[ \t]*$", block):
        return value
    # Fail closed if the administrator's current rules differ.
    pattern = r"(?m)^([ \t]*)" + re.escape(old) + r"[ \t]*$"
    if len(re.findall(pattern, block)) != 1:
        raise ValueError("Unexpected SSH PermitOpen configuration; review manually")
    changed = re.sub(pattern, lambda match: match.group(1) + new, block, count=1)
    return value[:start] + changed + value[end:]


def main():
    if os.geteuid() != 0:
        raise PermissionError("Run using the existing server administrator")
    if not Path("/etc/systemd/system/norman-acceptance.service.d/20-rds.conf").is_file():
        raise ValueError("Expected the existing private RDS acceptance environment")
    for name in ("fullchain.pem", "privkey.pem"):
        if not (CERT / name).is_file():
            raise ValueError("Obtain the approved domain certificate first")
    run("/usr/bin/openssl", "x509", "-in", str(CERT / "fullchain.pem"),
        "-noout", "-checkhost", DOMAIN)
    run("/usr/bin/openssl", "x509", "-in", str(CERT / "fullchain.pem"),
        "-noout", "-checkend", "86400")
    before = {SSH: SSH.read_bytes(), KEYS: KEYS.read_bytes(),
              CONFIG: CONFIG.read_bytes() if CONFIG.exists() else None}
    changes = {SSH: extend_ssh(before[SSH].decode()).encode(),
               KEYS: extend_keys(before[KEYS].decode()).encode(),
               CONFIG: NGINX.encode()}
    backup = Path("/var/backups/norman-private-https") / str(time.time_ns())
    backup.mkdir(parents=True, mode=0o700)
    for path, data in before.items():
        if data is not None:
            target = backup / path.name
            target.write_bytes(data)
            target.chmod(0o600)
    try:
        for path, data in changes.items():
            path.write_bytes(data)
        KEYS.chmod(0o600)
        CONFIG.chmod(0o644)
        run("/usr/sbin/sshd", "-t")
        run("/usr/sbin/nginx", "-t")
        run("/usr/bin/systemctl", "reload", "ssh")
        run("/usr/bin/systemctl", "reload", "nginx")
        effective = run("/usr/sbin/sshd", "-T", "-C",
                        "user=norman-preview,host=localhost,addr=127.0.0.1")
        if "permitopen 127.0.0.1:8088 127.0.0.1:8443" not in effective:
            raise ValueError("SSH destinations did not match the approved scope")
        url = f"https://{DOMAIN}:{PORT}/api/v1/club"
        common = ["/usr/bin/curl", "--silent", "--show-error", "--max-time", "10",
                  "--resolve", f"{DOMAIN}:{PORT}:127.0.0.1"]
        run(*common, "--fail", url)
        rejected = run(*common, "--output", "/dev/null", "--write-out", "%{http_code}",
                       "--header", "Origin: https://untrusted.invalid", url)
        if rejected != "403":
            raise ValueError("Private gateway did not reject an untrusted origin")
    except Exception:
        for path, data in before.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)
        run("/usr/sbin/sshd", "-t")
        run("/usr/sbin/nginx", "-t")
        run("/usr/bin/systemctl", "reload", "ssh")
        run("/usr/bin/systemctl", "reload", "nginx")
        raise
    print(json.dumps({"private_url": f"https://{DOMAIN}:{PORT}/mobile/",
                      "listen": f"127.0.0.1:{PORT}",
                      "ssh_destinations": ["127.0.0.1:8088", "127.0.0.1:8443"],
                      "untrusted_origin": "rejected", "backup": str(backup)}))


if __name__ == "__main__":
    main()
