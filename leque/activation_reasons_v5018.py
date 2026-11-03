#!/usr/bin/env python3
"""CloudXtream V5.0.18: clear activation messages and replacing a device when the license is full.

The behaviour lives in leque/android/CxWorkerActivation.kt and CxActivationActivity.kt (copied by
enable_activation_v54.py); this step bumps the version and checks the copied sources have it."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.18 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50017"','versionCode = "50018"')
patch("gradle/libs.versions.toml",'versionName = "5.0.17"','versionName = "5.0.18"')
base="app/src/main/java/com/lagradost/cloudstream3/activation/"
for name,needle in (("CxWorkerActivation.kt",'put("replace_device_id",replaceDeviceId)'),
                    ("CxActivationActivity.kt",'"pin_expired" ->')):
    if needle not in (ROOT/(base+name)).read_text(encoding="utf8"):
        sys.exit("FAIL: V5.0.18 "+name+" is missing the activation reasons")

for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.18 activation reasons and device replacement",len(files),"files")
