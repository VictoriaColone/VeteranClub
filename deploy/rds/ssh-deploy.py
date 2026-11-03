#!/usr/bin/env python3
"""Deploy through a separate, pinned-host-key, fixed-command SSH account."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import socket
import sys
import paramiko

ROOT = Path(__file__).resolve().parents[2]


def execute(client, command, bundle=None):
    channel = client.get_transport().open_session(timeout=15)
    channel.settimeout(200)
    try:
        channel.exec_command(command)
        if bundle:
            with bundle.open("rb") as source:
                while chunk := source.read(65536):
                    channel.sendall(chunk)
        channel.shutdown_write()
        stdout = channel.makefile("rb").read(65536)
        stderr = channel.makefile_stderr("rb").read(8192)
        code = channel.recv_exit_status()
        if code:
            raise RuntimeError(stdout.decode("utf8", "replace") or stderr.decode("utf8", "replace") or "Deployment command failed")
        return json.loads(stdout)
    finally:
        channel.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--status", action="store_true")
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--release")
    args = parser.parse_args()
    if not args.status and (not args.bundle or not args.bundle.is_file() or not args.release or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{6,63}", args.release)):
        parser.error("Specify --status, or a bundle and a valid --release")
    host = json.loads((ROOT / ".local/acceptance-tunnel.json").read_text())["host"]
    client = paramiko.SSHClient()
    client.load_host_keys(str(ROOT / ".local/aliyun-host-key"))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    try:
        client.connect(host, username="norman-deploy", key_filename=str(ROOT / ".local/norman-deploy"),
                       sock=socket.create_connection((host, 22), timeout=10), look_for_keys=False,
                       allow_agent=False, timeout=10, auth_timeout=10)
        client.get_transport().set_keepalive(30)
        if args.status:
            print(json.dumps(execute(client, "status"), ensure_ascii=False))
            return
        with args.bundle.open("rb") as source:
            sha = hashlib.file_digest(source, "sha256").hexdigest()
        size = args.bundle.stat().st_size
        if not 0 < size <= 80 * 1024 * 1024:
            raise ValueError("Deployment bundle must be at most 80 MiB")
        print(json.dumps(execute(client, f"upload {sha} {size}", args.bundle), ensure_ascii=False), flush=True)
        print(json.dumps(execute(client, f"deploy {args.release} {sha}"), ensure_ascii=False), flush=True)
    finally:
        client.close()


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, paramiko.SSHException) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
