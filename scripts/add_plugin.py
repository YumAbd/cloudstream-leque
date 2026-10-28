#!/usr/bin/env python3
"""Publish a reviewed, authorized CloudStream .cs3 in Leque's own repository."""
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
    p = argparse.ArgumentParser(description='Add self-hosted Leque extension metadata')
    p.add_argument('--file', type=Path, required=True, help='Reviewed local .cs3 build')
    p.add_argument('--internal-name', required=True, help='Must match the compiled extension internalName')
    p.add_argument('--name', required=True, help='Display name')
    p.add_argument('--author', action='append', required=True, help='Actual author; repeat if needed')
    p.add_argument('--version', type=int, required=True)
    p.add_argument('--language', help='BCP47 language code, for example ms')
    p.add_argument('--tv-type', action='append', dest='tv_types', default=[], help='Repeat for supported content type')
    args = p.parse_args()

    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.-]{0,99}', args.internal_name):
        p.error('Invalid internalName')
    if args.version < 1:
        p.error('version must be >= 1')
    if args.file.suffix.lower() != '.cs3' or not args.file.is_file():
        p.error('--file must be a local .cs3 file')
    raw = args.file.read_bytes()
    if not raw or len(raw) > MAX_SIZE:
        p.error('Empty or oversized .cs3 file')
    path = ROOT / 'sumber.json'
    catalog = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(catalog, list):
        p.error('sumber.json must be an array')
    if any(item.get('internalName') == args.internal_name for item in catalog if isinstance(item, dict)):
        p.error('internalName already exists; use replace_plugin.py for version upgrades')
    target = ROOT / 'extensions' / (args.internal_name + '.cs3')
    if target.exists():
        p.error('extensions/ filename already exists; refusing to overwrite')
    entry = {
        'url': PREFIX + args.internal_name + '.cs3',
        'status': 1,
        'version': args.version,
        'name': args.name.strip(),
        'internalName': args.internal_name,
        'authors': [author.strip() for author in args.author],
        'apiVersion': 1,
        'fileSize': len(raw),
        'fileHash': 'sha256-' + hashlib.sha256(raw).hexdigest(),
        'repositoryUrl': 'https://github.com/YumAbd/cloudstream-leque',
    }
    if not entry['name'] or not all(entry['authors']):
        p.error('Non-empty name and author required')
    if args.language:
        entry['language'] = args.language
    if args.tv_types:
        entry['tvTypes'] = args.tv_types
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)
    catalog.append(entry)
    path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Added {args.internal_name}; bytes={len(raw)}; sha256 verified locally.')
    print('Review and commit both sumber.json and extensions/ to GitHub.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print('ERROR:', exc, file=sys.stderr)
        sys.exit(1)
