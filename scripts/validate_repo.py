#!/usr/bin/env python3
"""Validate Leque's manifest and self-hosted extension catalog."""
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
RAW = 'https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/'
OWN_REPO = 'https://github.com/YumAbd/cloudstream-leque'
REQUIRED = {'url', 'status', 'version', 'name', 'internalName', 'authors', 'apiVersion', 'fileSize', 'fileHash'}


def main():
    errors = []
    try:
        manifest = json.loads((ROOT / 'repo.json').read_text(encoding='utf-8'))
        catalog = json.loads((ROOT / 'sumber.json').read_text(encoding='utf-8'))
    except (OSError, json.JSONDecodeError) as exc:
        print('ERROR: invalid or missing JSON:', exc, file=sys.stderr)
        return 1

    if not isinstance(manifest, dict):
        errors.append('repo.json must be a JSON object')
    else:
        if manifest.get('manifestVersion') != 1:
            errors.append('repo.json: manifestVersion must be 1')
        if manifest.get('pluginLists') != [RAW + 'sumber.json']:
            errors.append('repo.json: pluginLists must point only to Leque sumber.json')

    if not isinstance(catalog, list):
        errors.append('sumber.json must be a JSON array')
        catalog = []
    seen = set()
    for index, entry in enumerate(catalog):
        tag = f'sumber.json[{index}]'
        if not isinstance(entry, dict):
            errors.append(tag + ' must be an object')
            continue
        missing = REQUIRED - entry.keys()
        if missing:
            errors.append(f'{tag}: missing fields {sorted(missing)}')
        name = entry.get('internalName')
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.-]{0,99}', name):
            errors.append(tag + ': invalid internalName')
            continue
        if name in seen:
            errors.append(tag + ': duplicate internalName')
        seen.add(name)
        if entry.get('url') != RAW + 'extensions/' + name + '.cs3':
            errors.append(tag + ': extension file must be hosted at Leque extensions/')
        if entry.get('repositoryUrl') not in (None, OWN_REPO):
            errors.append(tag + ': repositoryUrl must be Leque-owned')
        if type(entry.get('status')) is not int or entry.get('status') not in (0, 1, 2, 3):
            errors.append(tag + ': status must be 0..3')
        if type(entry.get('version')) is not int or entry.get('version') < 1:
            errors.append(tag + ': version must be integer >= 1')
        if type(entry.get('apiVersion')) is not int or entry.get('apiVersion') != 1:
            errors.append(tag + ': apiVersion must equal 1')
        if not isinstance(entry.get('name'), str) or not entry['name'].strip():
            errors.append(tag + ': name must be non-empty')
        if not isinstance(entry.get('authors'), list) or not entry['authors'] or not all(isinstance(a, str) and a.strip() for a in entry['authors']):
            errors.append(tag + ': authors must be non-empty array')
        if type(entry.get('fileSize')) is not int or entry.get('fileSize') <= 0:
            errors.append(tag + ': fileSize must be positive integer')
        if not isinstance(entry.get('fileHash'), str) or not re.fullmatch(r'sha256-[a-f0-9]{64}', entry['fileHash']):
            errors.append(tag + ': fileHash must be SHA-256')

    for error in errors:
        print('ERROR:', error, file=sys.stderr)
    if errors:
        print(f'FAIL: {len(errors)} issue(s)', file=sys.stderr)
        return 1
    print(f'PASS: repository format valid; {len(catalog)} extensions indexed')
    return 0


if __name__ == '__main__':
    sys.exit(main())
