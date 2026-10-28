#!/usr/bin/env python3
"""Update an already-indexed extension using a reviewed local .cs3 build."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/extensions/'
MAX_SIZE = 25 * 1024 * 1024


def main():
    p = argparse.ArgumentParser(description='Update Leque self-hosted extension')
    p.add_argument('--file', type=Path, required=True, help='Reviewed local .cs3 build')
    p.add_argument('--internal-name', required=True)
    p.add_argument('--version', type=int, required=True)
    args = p.parse_args()

    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.-]{0,99}', args.internal_name):
        p.error('Invalid internalName')
    if args.file.suffix.lower() != '.cs3' or not args.file.is_file():
        p.error('--file must be a local .cs3 file')
    raw = args.file.read_bytes()
    if not raw or len(raw) > MAX_SIZE:
        p.error('Empty or oversized .cs3 file')
    path = ROOT / 'sumber.json'
    catalog = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(catalog, list):
        p.error('sumber.json must be an array')
    entry = next((x for x in catalog if isinstance(x, dict) and x.get('internalName') == args.internal_name), None)
    if entry is None:
        p.error('Extension not found; use add_plugin.py to add it')
    if args.version <= entry.get('version', 0):
        p.error('New version must be greater than current version')

    target = ROOT / 'extensions' / (args.internal_name + '.cs3')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)
    entry.update({
        'url': PREFIX + args.internal_name + '.cs3',
        'version': args.version,
        'status': 1,
        'fileSize': len(raw),
        'fileHash': 'sha256-' + hashlib.sha256(raw).hexdigest(),
        'repositoryUrl': 'https://github.com/YumAbd/cloudstream-leque',
    })
    path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Updated {args.internal_name} to v{args.version}; bytes={len(raw)}.')
    print('Review and commit both sumber.json and extensions/ to GitHub.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print('ERROR:', exc, file=sys.stderr)
        sys.exit(1)
