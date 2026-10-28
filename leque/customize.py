#!/usr/bin/env python3
"""Leque debug customization of an audited official CloudStream checkout."""
from pathlib import Path
import re
import subprocess
import sys

SHA = "5024ace2922a148d38df2a816b483b529789cf58"
ROOT = Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
if subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True).strip()!=SHA:
    sys.exit("FAIL: official source SHA mismatch")
if subprocess.check_output(["git", "-C", str(ROOT), "status", "--porcelain"], text=True).strip():
    sys.exit("FAIL: source checkout is dirty")
changes={}
def patch(path,old,new):
    current=changes.get(path)
    if current is None:
        current=(ROOT/path).read_text(encoding="utf-8")
    n=current.count(old)
    if n!=1:
        sys.exit(f"FAIL: anchor mismatch {path} expected 1, got {n}: {old[:70]}")
    changes[path]=current.replace(old,new,1)

p="app/src/main/java/com/lagradost/cloudstream3/plugins/RepositoryManager.kt"
patch(p,'''    val PREBUILT_REPOSITORIES: Array<RepositoryData> by lazy {
        getKey<Array<RepositoryData>>("PREBUILT_REPOSITORIES") ?: emptyArray()
    }''','''    val PREBUILT_REPOSITORIES: Array<RepositoryData> by lazy {
        arrayOf(
            RepositoryData(
                iconUrl = null,
                name = "CloudStream AutoPlaylist by Leque",
                url = "https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/repo.json",
                description = "Repository extension by Leque"
            )
        )
    }''')
p="app/src/main/res/values/donottranslate-strings.xml"
patch(p,'<string name="default_account">@string/action_default</string>','<string name="default_account">AutoPlaylist by Leque</string>\n    <string name="leque_app_name">CloudStream by Leque</string>')
p="app/src/main/AndroidManifest.xml"
patch(p,'android:label="@string/app_name"','android:label="@string/leque_app_name"')
patch(p,'android:taskAffinity="com.lagradost.cloudstream3.downloadedplayer"','android:taskAffinity="com.leque.cloudstreamv5.downloadedplayer"')
p="app/build.gradle.kts"
patch(p,'applicationId = "com.lagradost.cloudstream3"','applicationId = "com.leque.cloudstreamv5"')
p="gradle/libs.versions.toml"
patch(p,'versionCode = "68"','versionCode = "50001"')
patch(p,'versionName = "4.8.0"','versionName = "5.0.1"')
p="app/src/main/java/com/lagradost/cloudstream3/ui/settings/GithubReleases.kt"
patch(p,'const val APK_USERNAME = "recloudstream"','const val APK_USERNAME = "YumAbd"')
patch(p,'const val APK_REPOSITORY = "cloudstream"','const val APK_REPOSITORY = "cloudstream-leque"')
p="app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt"
patch(p,'private const val GITHUB_USER_NAME = "recloudstream"','private const val GITHUB_USER_NAME = "YumAbd"')
patch(p,'private const val GITHUB_REPO = "cloudstream"','private const val GITHUB_REPO = "cloudstream-leque"')
patch(p,'private const val PRERELEASE_PACKAGE_NAME = "com.lagradost.cloudstream3.prerelease"','private const val PRERELEASE_PACKAGE_NAME = "com.leque.cloudstreamv5.prerelease"')
p="shared/src/commonMain/kotlin/com/lagradost/cloudstream4/DevicePreferenceStore.kt"
patch(p,'val showAppUpdates = preferences.getBoolean("auto_update", true)','val showAppUpdates = preferences.getBoolean("auto_update", false)')
if 'val autoUpdate = preferences.getBoolean("auto_update_plugins", true)' not in changes[p]:
    sys.exit("FAIL: extension auto-update unexpectedly changed")
p="app/src/main/res/xml/settings_updates.xml"
patch(p,'android:title="@string/updates_settings"\n            app:defaultValue="true"','android:title="@string/updates_settings"\n            app:defaultValue="false"')
p="app/src/main/res/values/strings.xml"
patch(p,'<string name="app_name">CloudStream</string>','<string name="app_name">CloudStream by Leque</string>')
p="app/src/debug/res/values/strings.xml"
patch(p,'<string name="app_name">CloudStream Debug</string>','<string name="app_name">CloudStream by Leque</string>')
p="shared/src/commonMain/composeResources/values/strings.xml"
patch(p,'<string name="app_name">CloudStream</string>','<string name="app_name">CloudStream by Leque</string>')
p="app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsUpdates.kt"
patch(p,'pref.isVisible = BuildConfig.FLAVOR == "stable"','pref.isVisible = false // no Leque prerelease yet')
p="app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsUpdatesScreen.kt"
patch(p,'enabled = BuildConfig.FLAVOR == "stable",','enabled = false, // no Leque prerelease yet')
for path, content in changes.items():
    (ROOT/path).write_text(content,encoding="utf-8")
print("PASS: patched",len(changes),"files; Leque V5 app, default profile, repo and APK-feed isolation")
