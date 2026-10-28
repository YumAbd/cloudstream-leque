#!/usr/bin/env python3
"""Patch a CLEAN checkout of official CloudStream sources (not a precompiled APK).

Patches the default local profile name and pins Leque's own extension index as
PREBUILT_REPOSITORIES. Does NOT auto-install unreviewed third-party plugins.
"""
import argparse
from pathlib import Path
import re
import sys

KOTLIN_PATH='app/src/main/java/com/lagradost/cloudstream3/plugins/RepositoryManager.kt'
STRINGS_PATH='app/src/main/res/values/donottranslate-strings.xml'
GRADLE_PATH='app/build.gradle.kts'
MANIFEST_PATH='app/src/main/AndroidManifest.xml'


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--source', required=True, type=Path, help='Root of official CloudStream source checkout')
    p.add_argument('--github-user', required=True)
    p.add_argument('--repo', default='cloudstream-leque')
    p.add_argument('--app-id', default='com.leque.cloudstreamv5', help='Unique Android app ID')
    args=p.parse_args()
    if not re.fullmatch('[A-Za-z0-9-]+',args.github_user) or not re.fullmatch('[A-Za-z0-9_.-]+',args.repo):
        p.error('Invalid GitHub username or repository')
    if not re.fullmatch(r'[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){1,}',args.app_id):
        p.error('Invalid app ID')
    root=args.source.resolve()
    files=[root/KOTLIN_PATH,root/STRINGS_PATH,root/GRADLE_PATH,root/MANIFEST_PATH]
    for f in files:
        if not f.is_file():
            p.error(f'Missing expected source file: {f}')
    original=[f.read_text(encoding='utf-8') for f in files]
    url=f'https://raw.githubusercontent.com/{args.github_user}/{args.repo}/main/repo.json'
    old='''    val PREBUILT_REPOSITORIES: Array<RepositoryData> by lazy {
        getKey<Array<RepositoryData>>("PREBUILT_REPOSITORIES") ?: emptyArray()
    }'''
    new=f'''    val PREBUILT_REPOSITORIES: Array<RepositoryData> by lazy {{
        arrayOf(RepositoryData("CloudStream AutoPlaylist V5 by Leque", "{url}"))
    }}'''
    profile_old='<string name="default_account">@string/action_default</string>'
    profile_new='<string name="default_account">Mod v5 by Leque</string>\n    <string name="leque_app_name">CloudStream Leque V5</string>'
    app_id_old='applicationId = "com.lagradost.cloudstream3"'
    app_id_new=f'applicationId = "{args.app_id}"'
    label_old='android:label="@string/app_name"'
    label_new='android:label="@string/leque_app_name"'
    changes=[(old,new),(profile_old,profile_new),(app_id_old,app_id_new),(label_old,label_new)]
    for content,(needle,replacement),path in zip(original,changes,files):
        if needle not in content:
            p.error(f'Expected original code was not found in {path}; source version differs. NO FILES MODIFIED.')
        if content.count(needle)!=1:
            p.error(f'Ambiguous matching code in {path}. NO FILES MODIFIED.')
    for path, content, (needle,replacement) in zip(files,original,changes):
        path.write_text(content.replace(needle,replacement,1),encoding='utf-8')
    print('PATCHED:',KOTLIN_PATH,STRINGS_PATH,GRADLE_PATH,MANIFEST_PATH, sep='\n - ')
    print('Default profile:', 'Mod v5 by Leque')
    print('Application label:', 'CloudStream Leque V5')
    print('Repository:', url)
    print('Android app ID:', args.app_id)
    print('Build & test with Android Studio / Gradle, then sign your OWN release APK.')

if __name__=='__main__':
    main()
