#!/usr/bin/env python3
"""CloudXtream V5.0.19: user-facing texts name "admin CloudXtream" instead of the license owner.

The texts live in leque/android/CxActivationActivity.kt and features_v5014.py; this step bumps the
version and checks no user-facing text still says "pemilik"."""
from pathlib import Path
import re, sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.19 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50018"','versionCode = "50019"')
patch("gradle/libs.versions.toml",'versionName = "5.0.18"','versionName = "5.0.19"')
for path in ("app/src/main/java/com/lagradost/cloudstream3/activation/CxActivationActivity.kt",
             "app/src/main/res/values-b+ms/strings.xml"):
    strings=re.findall(r'"(?:[^"\\]|\\.)*"|>[^<]*<',(ROOT/path).read_text(encoding="utf8"))
    if any(re.search(r"\bpemilik\b",s,re.I) for s in strings):
        sys.exit("FAIL: V5.0.19 user-facing text still says 'pemilik' in "+path)

for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.19 texts name admin CloudXtream",len(files),"files")
