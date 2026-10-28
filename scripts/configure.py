#!/usr/bin/env python3
"""Configure stable GitHub owner/repository used by the repository manifest."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('username', help='Username/organization GitHub pemilik repository')
    p.add_argument('--repo', default='cloudstream-leque', help='Nama GitHub repository')
    args = p.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?', args.username):
        p.error('Invalid GitHub username')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', args.repo):
        p.error('Invalid repository name')

    path = ROOT/'repo.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    url = f'https://raw.githubusercontent.com/{args.username}/{args.repo}/main/sumber.json'
    data['pluginLists'] = [url]
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('Manifest updated:', path)
    print('Install URL:', f'https://raw.githubusercontent.com/{args.username}/{args.repo}/main/repo.json')

if __name__ == '__main__':
    main()
