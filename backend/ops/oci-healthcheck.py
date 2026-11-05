#!/usr/bin/python3
"""Read-only readiness check used inside the OCI deployment transaction."""
import json
import sys
import urllib.request

base = sys.argv[1].rstrip('/')

def get(path):
    with urllib.request.urlopen(base + path, timeout=8) as response:
        return json.load(response)

health = get('/api/health')
assert get('/api/health/ready')['status'] == 'ready'
assert health['status'] == 'ok'
assert health['matchingEnabled'] is True and health['batchBotsEnabled'] is True
assert health['botScheduler']['started'] is True and health['botScheduler']['tasks'] > 0
stocks = get('/api/stocks')
assert isinstance(stocks, list) and stocks
book = get('/api/orderbook/' + stocks[0]['code'])
assert isinstance(book['bids'], list) and isinstance(book['asks'], list)
print('API_READY')
