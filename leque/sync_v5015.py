#!/usr/bin/env python3
"""CloudXtream V5.0.15: new catalog extensions reach the app within about 5 minutes."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.15 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50014"','versionCode = "50015"')
patch("gradle/libs.versions.toml",'versionName = "5.0.14"','versionName = "5.0.15"')

# The catalog (served fresh by the CloudXtream Worker) is checked at most every 5 minutes.
patch("app/src/main/java/com/lagradost/cloudstream3/plugins/LequePluginSync.kt",
      "    private const val INTERVAL_MS = 15 * 60 * 1000L","    private const val INTERVAL_MS = 5 * 60 * 1000L")

# Besides app start and return, keep checking while the app stays open (Android does not
# allow loading extensions in the background, and background jobs run at most every 15 min).
p="app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt"
patch(p,'''        // Recheck on returning to the app, throttled to once in 15 minutes.
        if (!PluginManager.isSafeMode() && lastError == null) {
            ioSafe { LequePluginSync.refreshIfDue(this@MainActivity) }
        }''','''        // Recheck on returning to the app and while it stays open, at most every 5 minutes.
        if (!PluginManager.isSafeMode() && lastError == null) {
            ioSafe { LequePluginSync.refreshIfDue(this@MainActivity) }
            cxSyncHandler.removeCallbacksAndMessages(null)
            cxSyncHandler.postDelayed(cxSyncLoop, 60_000L)
        }''')
patch(p,'''    override fun onPause() {
        cxCheckHandler.removeCallbacksAndMessages(null)''','''    private val cxSyncHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cxSyncLoop = object : Runnable {
        override fun run() {
            ioSafe { LequePluginSync.refreshIfDue(this@MainActivity) }
            cxSyncHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onPause() {
        cxSyncHandler.removeCallbacksAndMessages(null)
        cxCheckHandler.removeCallbacksAndMessages(null)''')

for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.15 catalog sync every 5 minutes",len(files),"files")
