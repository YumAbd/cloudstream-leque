#!/usr/bin/env python3
"""CloudXtream V5.0.9: in-app update prompt from the CloudXtream release feed."""
from pathlib import Path
import re, sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.9 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50008"','versionCode = "50009"')
patch("gradle/libs.versions.toml",'versionName = "5.0.8"','versionName = "5.0.9"')

# Read the latest version from the Worker instead of the GitHub API (rate limited per IP),
# and download the APK from the CloudXtream domain (served from Cloudflare R2).
p="app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt"
src=(ROOT/p).read_text(encoding="utf8")
start=src.index("    private suspend fun Activity.getReleaseUpdate(): Update {")
end=src.index("    private suspend fun Activity.getPreReleaseUpdate(): Update {")
files[p]=src[:start]+'''    // CloudXtream release feed. Android only installs the APK over this app when its
    // signing certificate matches, so the feed cannot replace the app with another one.
    private const val CX_UPDATE_FEED = "https://cloudxpanel.infopc.xyz/api/app/latest"
    private const val CX_DOWNLOAD_PREFIX = "https://cloudxpanel.infopc.xyz/"

    private suspend fun Activity.getReleaseUpdate(): Update {
        val feed = org.json.JSONObject(
            app.get(CX_UPDATE_FEED, headers = mapOf("Accept" to "application/json")).text
        )
        val versionRegex = Regex("^(\\\\d+)\\\\.(\\\\d+)\\\\.(\\\\d+)")
        fun code(version: String?): Int? = version?.let { versionRegex.find(it) }?.groupValues?.let {
            it[1].toInt() * 100_000_000 + it[2].toInt() * 10_000 + it[3].toInt()
        }
        val remoteVersion = feed.optString("version")
        val apkUrl = feed.optString("apk_url")
        val remoteCode = code(remoteVersion)
        if (!feed.optBoolean("ok") || remoteCode == null || !apkUrl.startsWith(CX_DOWNLOAD_PREFIX)) {
            return Update(false, null, null, null, null)
        }
        val localCode = code(packageName?.let { packageManager.getPackageInfo(it, 0) }?.versionName)
        return Update(
            localCode != null && localCode < remoteCode,
            apkUrl,
            remoteVersion,
            feed.optString("changelog", ""),
            feed.optString("tag", remoteVersion)
        )
    }

'''+src[end:]

# Check every time the app opens. Updates were switched off by default in V5.0.1, so turn
# them on once for existing installs; the user can still switch them off in Settings.
p="app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt"
patch(p,"""        //ioSafe {
        //    runAutoUpdate()
        //}""","""        val cxUpdatePrefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!cxUpdatePrefs.getBoolean("cx_auto_update_enabled_v59", false)) {
            cxUpdatePrefs.edit()
                .putBoolean(getString(R.string.auto_update_key), true)
                .putBoolean("cx_auto_update_enabled_v59", true)
                .apply()
        }
        ioSafe {
            runAutoUpdate()
        }""")
patch("shared/src/commonMain/kotlin/com/lagradost/cloudstream4/DevicePreferenceStore.kt",
      'val showAppUpdates = preferences.getBoolean("auto_update", false)',
      'val showAppUpdates = preferences.getBoolean("auto_update", true)')
patch("app/src/main/res/xml/settings_updates.xml",
      'android:title="@string/updates_settings"\n            app:defaultValue="false"',
      'android:title="@string/updates_settings"\n            app:defaultValue="true"')
for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.9 in-app update from the CloudXtream feed",len(files),"files")
