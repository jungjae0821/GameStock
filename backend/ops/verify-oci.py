#!/usr/bin/env python3
"""Verify the public HTTPS API and both WebSocket upgrade paths without credentials."""
import base64
import hashlib
import json
import os
import socket
import ssl
import sys
import urllib.parse
import urllib.request

base = sys.argv[1].rstrip('/')
with urllib.request.urlopen(base + '/api/health', timeout=20) as response:
    health = json.load(response)
assert health['status'] == 'ok' and health['matchingEnabled'] and health['batchBotsEnabled']
assert health['botScheduler']['started']
with urllib.request.urlopen(base + '/api/health/ready', timeout=20) as response:
    assert json.load(response)['status'] == 'ready'
with urllib.request.urlopen(base + '/api/stocks', timeout=20) as response:
    stocks = json.load(response)
assert stocks
host = urllib.parse.urlsplit(base).hostname
context = ssl.create_default_context()
for path in ('/ws/market', '/ws/stream'):
    with socket.create_connection((host, 443), timeout=15) as raw:
        with context.wrap_socket(raw, server_hostname=host) as conn:
            key = base64.b64encode(os.urandom(16)).decode()
            request = (f'GET {path} HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\n'
                       f'Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n')
            conn.sendall(request.encode())
            data = b''
            while b'\r\n\r\n' not in data:
                chunk = conn.recv(4096)
                if not chunk:
                    raise RuntimeError('WebSocket closed before upgrade')
                data += chunk
                assert len(data) < 65536
            headers = data.split(b'\r\n\r\n', 1)[0].decode()
            expected = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
            assert headers.startswith('HTTP/1.1 101') and expected in headers
    print(path, 'WSS_OK')
print('PUBLIC_OCI_READY', len(stocks), 'stocks')
