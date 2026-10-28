#!/usr/bin/env python3
"""Optional one-time metadata import; does not redistribute .cs3 binaries.

Check permission/licensing of upstream metadata and all extension authors first.
Do not run repeatedly after making your own changes, as --force overwrites them.
"""
import argparse
import json
from pathlib import Path
import sys
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM = 'https://raw.githubusercontent.com/gvisionXcloudstream/bayustecu/refs/heads/main/sumber.json'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--include-nsfw', action='store_true', help='Include adult-labelled extensions; disabled by default')
    parser.add_argument('--exclude-phisher', action='store_true', help='Omit entries whose .cs3 is hosted by phisher98; this removes some providers such as MovieBox')
    parser.add_argument('--force', action='store_true', help='Overwrite populated sumber.json; use with care')
    args = parser.parse_args()
    path = ROOT/'sumber.json'
    current = json.loads(path.read_text(encoding='utf-8'))
    if current and not args.force:
        parser.error('sumber.json already contains entries; use --force ONLY if you mean to overwrite')
    request = Request(UPSTREAM, headers={'User-Agent': 'LequeCatalogImporter/1.0'})
    with urlopen(request, timeout=20) as r:
        payload = r.read(2 * 1024 * 1024)
    entries = json.loads(payload)
    if not isinstance(entries, list):
        raise ValueError('Unexpected upstream format')
    if not args.include_nsfw:
        entries = [p for p in entries if 'NSFW' not in p.get('tvTypes', [])]
    if args.exclude_phisher:
        entries = [p for p in entries if '/phisher98/' not in p.get('url', '').lower()]
    # Metadata index only: URLs still point to externally hosted .cs3 files.
    path.write_text(json.dumps(entries, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
    print('Imported entries:', len(entries))
    print('IMPORTANT: verify author rights and sources; missing external URLs are not mirrored.')
    print('Run: python scripts/validate_repo.py')

if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        print(f'IMPORT FAILED: {e}', file=sys.stderr)
        sys.exit(1)
