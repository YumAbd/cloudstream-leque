#!/usr/bin/env python3
"""CloudXtream V5.0.11: Settings > Updates without backup and extension test entries."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.11 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50010"','versionCode = "50011"')
patch("gradle/libs.versions.toml",'versionName = "5.0.10"','versionName = "5.0.11"')

# The menu is now just "Updates" (the app always runs in English).
patch("app/src/main/res/values/strings.xml",
      '<string name="category_updates">Updates and Backup</string>',
      '<string name="category_updates">Updates</string>')
patch("app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsFragmentScreen.kt",
      "                R.string.pref_category_app_updates,\n                R.string.pref_category_backup,\n",
      "                R.string.pref_category_app_updates,\n")

# Compose screen: drop the Backup group and "Test all extensions".
p="app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsUpdatesScreen.kt"
source=files.get(p) or (ROOT/p).read_text(encoding="utf8")
start_anchor="            Preference.PreferenceGroup(\n                title = stringResource(R.string.pref_category_backup),\n"
end_anchor="            Preference.PreferenceGroup(\n                title = stringResource(R.string.pref_category_extensions),\n"
if source.count(start_anchor)!=1 or source.count(end_anchor)!=1: sys.exit("FAIL: V5.0.11 backup group anchors in "+p)
start=source.index(start_anchor); end=source.index(end_anchor)
if end<start: sys.exit("FAIL: V5.0.11 backup group order in "+p)
files[p]=source[:start]+source[end:]
patch(p,""",*/
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.test_extensions),
                        subtitle = stringResource(R.string.test_extensions_summary),
                        icon = painterResource(R.drawable.baseline_network_ping_24),
                        onClick = {
                            activity?.navigate(R.id.navigation_test_providers)
                        })
""",""",*/
""")

# Legacy XML screen: keep the preferences (the fragment looks them up by key) but hide them.
patch("app/src/main/res/xml/settings_updates.xml",
      '    <PreferenceCategory\n        android:title="@string/pref_category_backup">',
      '    <PreferenceCategory\n        app:isPreferenceVisible="false"\n        android:title="@string/pref_category_backup">')

for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.11 Updates settings cleanup",len(files),"files")
