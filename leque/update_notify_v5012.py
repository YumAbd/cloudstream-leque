#!/usr/bin/env python3
"""CloudXtream V5.0.12: phone notification when a new version is released."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
HERE=Path(__file__).resolve().parent
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.12 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50011"','versionCode = "50012"')
patch("gradle/libs.versions.toml",'versionName = "5.0.11"','versionName = "5.0.12"')

# Hourly background check of the release feed (CxUpdateNotifier), started on every app open;
# the open app's own update prompt replaces a pending notification.
patch("app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt",
      "        ioSafe {\n            runAutoUpdate()\n        }\n",
      "        com.lagradost.cloudstream3.services.CxUpdateNotifier.schedule(this)\n"
      "        com.lagradost.cloudstream3.services.CxUpdateNotifier.dismiss(this)\n"
      "        ioSafe {\n            runAutoUpdate()\n        }\n")
p="app/src/main/java/com/lagradost/cloudstream3/services/CxUpdateNotifier.kt"
if (ROOT/p).exists(): sys.exit("FAIL: duplicate class CxUpdateNotifier")
files[p]=(HERE/"android"/"CxUpdateNotifier.kt").read_text(encoding="utf8")

for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.12 release notification",len(files),"files")
