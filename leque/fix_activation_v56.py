#!/usr/bin/env python3
"""CloudXtream V5.0.6 preview: follow the server's 300-second online lease."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.6 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50005"','versionCode = "50006"')
patch("gradle/libs.versions.toml",'versionName = "5.0.5"','versionName = "5.0.6"')
# The 45-second timer keeps running, but it contacts the Worker only when the
# server lease ends within 60 seconds, so access never lapses between ticks.
for path,owner in (
    ("app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt","MainActivity"),
    ("app/src/main/java/com/lagradost/cloudstream3/ui/player/DownloadedPlayerActivity.kt","DownloadedPlayerActivity"),
):
    patch(path,
          "if (!CxWorkerActivation.validateOnline(this@"+owner+"))",
          "if (CxWorkerActivation.needsRenewal(60_000L) &&\n"
          "                    !CxWorkerActivation.validateOnline(this@"+owner+"))")
for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.6 server-lease activation checks",len(files),"files")
