#!/usr/bin/env python3
"""Change a listed extension to a validated new .cs3 file and increase version.

--file: host your own authorized plugin under extensions/<internalName>.cs3
--url: refer to another HTTPS plugin download; verifies checksum before publishing.
Does not bypass site access controls or build code from third parties.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
from urllib.parse import urlparse
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
MAX_SIZE = 25 * 1024 * 1024


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--internal-name', required=True)
    p.add_argument('--version', type=int, required=True)
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument('--file', type=Path, help='Local trusted .cs3 build')
    src.add_argument('--url', help='HTTPS URL of trusted .cs3 build')
    p.add_argument('--github-user', help='Required for --file mode')
    p.add_argument('--repo', default='cloudstream-leque')
    args = p.parse_args()

    path = ROOT/'sumber.json'
    plugins = json.loads(path.read_text(encoding='utf-8'))
    item = next((x for x in plugins if x.get('internalName') == args.internal_name), None)
    if item is None:
        p.error('No extension with that internalName. Import/add a valid entry first.')
    if args.version <= item['version']:
        p.error(f'New version must be greater than current ({item["version"]}).')

    if args.file:
        if not args.github_user or not re.fullmatch(r'[A-Za-z0-9-]+', args.github_user):
            p.error('--github-user is required for --file')
        if args.file.suffix.lower() != '.cs3' or not args.file.is_file():
            p.error('--file must point to a .cs3 file')
        if args.file.stat().st_size > MAX_SIZE:
            p.error(f'Plugin is too large (>{MAX_SIZE} bytes)')
        raw = args.file.read_bytes()
        safe_name = re.sub(r'[^A-Za-z0-9_.-]', '_', args.internal_name)
        target = ROOT/'extensions'/f'{safe_name}.cs3'
        target.parent.mkdir(exist_ok=True)
        target.write_bytes(raw)
        url = f'https://raw.githubusercontent.com/{args.github_user}/{args.repo}/main/extensions/{safe_name}.cs3'
    else:
        purl = urlparse(args.url)
        if purl.scheme != 'https' or not purl.hostname or purl.username or purl.password:
            p.error('--url must be a valid HTTPS URL')
        req = Request(args.url, headers={'User-Agent': 'LequePluginIntegrityCheck/1.0'})
        with urlopen(req, timeout=25) as response:
            raw = response.read(MAX_SIZE + 1)
        if not raw or len(raw) > MAX_SIZE:
            p.error('Unexpected download size')
        url = args.url
    digest = hashlib.sha256(raw).hexdigest()
    item.update({'url': url, 'version': args.version, 'status': 1,
                 'fileSize': len(raw), 'fileHash': 'sha256-'+digest})
    path.write_text(json.dumps(plugins, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(f'Updated {args.internal_name}: version {args.version}, bytes={len(raw)}, sha256={digest}')
    print('Commit sumber.json and any extensions/*.cs3 to your own GitHub repository.')

if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError) as e:
        print(f'ERROR: {e}', file=sys.stderr)
        sys.exit(1)
