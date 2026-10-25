#!/usr/bin/env python3
"""Forward only local loopback ports over a host-key-verified SSH connection."""
import argparse
import select
import socket
import socketserver
import threading
from pathlib import Path
import paramiko

parser = argparse.ArgumentParser()
parser.add_argument('--host', required=True)
parser.add_argument('--user', default='norman-preview')
parser.add_argument('--key', type=Path, required=True)
parser.add_argument('--known-hosts', type=Path, required=True)
parser.add_argument('--port', type=int, default=4190)
args = parser.parse_args()
client = paramiko.SSHClient()
client.load_host_keys(str(args.known_hosts))
client.set_missing_host_key_policy(paramiko.RejectPolicy())
client.connect(args.host, username=args.user, key_filename=str(args.key), sock=socket.create_connection((args.host,22),timeout=10), look_for_keys=False, allow_agent=False, timeout=10, auth_timeout=10)
transport = client.get_transport()
transport.set_keepalive(30)

class Forward(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            channel = transport.open_channel('direct-tcpip', ('127.0.0.1', 8088), self.client_address)
            with channel:
                while transport.is_active():
                    readable, _, _ = select.select([self.request, channel], [], [], 1)
                    if self.request in readable:
                        data = self.request.recv(65536)
                        if not data: break
                        channel.sendall(data)
                    if channel in readable:
                        data = channel.recv(65536)
                        if not data: break
                        self.request.sendall(data)
        except (OSError, EOFError, paramiko.SSHException):
            pass

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

with Server(('127.0.0.1', args.port), Forward) as server:
    print(f'Server acceptance: http://127.0.0.1:{args.port}/mobile/ and /admin/', flush=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        while transport.is_active(): thread.join(1)
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        client.close()
