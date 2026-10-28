#!/usr/bin/env python3
"""Read-only check of plugin file URLs and sha256 digests. No auto replacement."""
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import sys
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CAP = 25 * 1024 * 1024


def check_plugin(entry):
    name = entry.get('internalName', '<unknown>')
    url = entry['url']
    try:
        req = Request(url, headers={'User-Agent': 'LequeCatalogMonitor/1.0'})
        with urlopen(req, timeout=18) as response:
            if response.status != 200:
                raise ValueError(f'HTTP {response.status}')
            payload = response.read(CAP + 1)
        if not payload or len(payload) > CAP:
            raise ValueError('invalid size / exceeds safety cap')
        digest = entry.get('fileHash')
        actual = 'sha256-'+hashlib.sha256(payload).hexdigest()
        if digest and digest.lower() != actual.lower():
            raise ValueError('sha256 differs from index; do not auto-trust replacement')
        return name, None
    except Exception as exc:
        return name, str(exc)


def main():
    data = json.loads((ROOT/'sumber.json').read_text(encoding='utf-8'))
    print(f'Checking {len(data)} extension download URLs (not the streaming sites themselves).')
    errors = []
    with ThreadPoolExecutor(max_workers=5) as pool:
        for result in as_completed(pool.submit(check_plugin, x) for x in data):
            name, error = result.result()
            print(('BROKEN' if error else 'OK')+f' {name}'+(f': {error}' if error else ''))
            if error:
                errors.append((name, error))
    if errors:
        print(f'FAILED: {len(errors)} broken/mismatched downloads.')
        return 1
    print('PASS: all indexed files downloaded and verified.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
