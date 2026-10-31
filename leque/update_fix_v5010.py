#!/usr/bin/env python3
"""CloudXtream V5.0.10: one update prompt, and only for a newer version."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.10 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50009"','versionCode = "50010"')
patch("gradle/libs.versions.toml",'versionName = "5.0.9"','versionName = "5.0.10"')
# Upstream's Compose updater compared the release asset name ("CloudXtream-by-Leque-v5.0.9")
# with the app version ("5.0.9"), so it always offered an update. Startup checks now come only
# from InAppUpdater (CloudXtream feed + R2 download).
patch("app/src/main/java/com/lagradost/cloudstream3/MainActivityScreen.kt",
      "                    onAction(AutoSearchForUpdate)\n",
      "                    // CloudXtream: the startup update check is InAppUpdater (CloudXtream feed).\n")
# Any manual check through this updater compares version numbers and only offers newer ones.
p="shared/src/commonMain/kotlin/com/lagradost/cloudstream4/viewmodels/GithubViewModel.kt"
patch(p,"        if (!prerelease && release.displayName == versionName) {",
        "        if (!prerelease && cxVersionNumber(release.displayName) <= cxVersionNumber(versionName)) {")
files[p]=files[p].rstrip("\n")+"""

/** "CloudXtream-by-Leque-v5.0.9" or "5.0.9" -> comparable number; unparsable -> -1. */
private fun cxVersionNumber(text: String): Long =
    Regex("(\\\\d+)\\\\.(\\\\d+)\\\\.(\\\\d+)").find(text)?.destructured?.let { (major, minor, patch) ->
        major.toLong() * 1_000_000L + minor.toLong() * 1_000L + patch.toLong()
    } ?: -1L
"""
for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.10 single, version-aware update prompt",len(files),"files")
