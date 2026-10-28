#!/usr/bin/env python3
"""Validate local CloudStream repository catalog before publishing."""
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = {'url', 'status', 'version', 'name', 'internalName', 'authors', 'apiVersion'}


def https_url(value):
    try:
        p = urlparse(value)
        return p.scheme == 'https' and bool(p.hostname) and not p.username and not p.password
    except (TypeError, ValueError):
        return False


def main():
    errs = []
    try:
        manifest = json.loads((ROOT/'repo.json').read_text(encoding='utf-8'))
        catalog = json.loads((ROOT/'sumber.json').read_text(encoding='utf-8'))
    except (OSError, json.JSONDecodeError) as e:
        print(f'ERROR JSON: {e}', file=sys.stderr)
        return 1
    if not isinstance(manifest, dict) or manifest.get('manifestVersion') != 1:
        errs.append('repo.json: invalid manifestVersion')
    elif not isinstance(manifest.get('pluginLists'), list) or not manifest['pluginLists']:
        errs.append('repo.json: pluginLists must be non-empty array')
    elif not all(https_url(x) for x in manifest['pluginLists']):
        errs.append('repo.json: pluginLists must contain valid HTTPS URLs')
    if not isinstance(catalog, list):
        errs.append('sumber.json: must be an array')
        catalog = []
    seen = set()
    for index, plugin in enumerate(catalog):
        tag = f'sumber.json[{index}]'
        if not isinstance(plugin, dict):
            errs.append(f'{tag}: plugin metadata must be an object')
            continue
        missing = REQUIRED - plugin.keys()
        if missing:
            errs.append(f'{tag}: missing keys {sorted(missing)}')
        name = plugin.get('internalName')
        if not isinstance(name, str) or not name:
            errs.append(f'{tag}: invalid internalName')
        elif name in seen:
            errs.append(f'{tag}: duplicate internalName {name}')
        else:
            seen.add(name)
        if not https_url(plugin.get('url')):
            errs.append(f'{tag}: URL must be HTTPS')
        if type(plugin.get('status')) is not int or plugin['status'] not in range(4):
            errs.append(f'{tag}: invalid status (0-3)')
        if type(plugin.get('version')) is not int or plugin['version'] < 1:
            errs.append(f'{tag}: version must be integer >= 1')
        if type(plugin.get('apiVersion')) is not int or plugin['apiVersion'] != 1:
            errs.append(f'{tag}: apiVersion must equal 1')
        if not isinstance(plugin.get('authors'), list) or not all(isinstance(x, str) for x in plugin['authors']):
            errs.append(f'{tag}: authors must be array of strings')
        digest = plugin.get('fileHash')
        if digest is not None and not re.fullmatch(r'sha256-[a-fA-F0-9]{64}', digest):
            errs.append(f'{tag}: invalid sha256 fileHash')
        if plugin.get('fileSize') is not None and (type(plugin['fileSize']) is not int or plugin['fileSize'] <= 0):
            errs.append(f'{tag}: invalid fileSize')
    for error in errs:
        print('ERROR:', error, file=sys.stderr)
    if errs:
        print(f'FAIL: {len(errs)} issue(s)', file=sys.stderr)
        return 1
    print(f'PASS: repository format valid; {len(catalog)} extensions indexed')
    return 0

if __name__ == '__main__':
    sys.exit(main())
