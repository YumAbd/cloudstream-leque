#!/usr/bin/env python3
"""CloudXtream V5.0.22: PIN entered once (no license checks), 8-character PINs, reinstall with the
same PIN on the same device, and a one-time popup listing extensions the admin added or removed.

The activation behaviour lives in leque/android/CxWorkerActivation.kt and CxActivationActivity.kt
(copied by enable_activation_v54.py); the popup in leque/android/CxExtensionNews.kt (copied here)."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
HERE=Path(__file__).resolve().parent
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.22 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50020"','versionCode = "50022"')
patch("gradle/libs.versions.toml",'versionName = "5.0.20"','versionName = "5.0.22"')

files["app/src/main/java/com/lagradost/cloudstream3/plugins/CxExtensionNews.kt"]=(HERE/"android"/"CxExtensionNews.kt").read_text(encoding="utf8")
patch("app/src/main/java/com/lagradost/cloudstream3/plugins/LequePluginSync.kt",
      "                cxApplyDefaultHome(activity, chosen)\n",
      "                cxApplyDefaultHome(activity, chosen)\n"
      "                // Extensions added or removed by the admin: shown once (never NSFW ones).\n"
      "                CxExtensionNews.check(activity, all)\n")

base="app/src/main/java/com/lagradost/cloudstream3/activation/"
for name,needle in (("CxWorkerActivation.kt",'put("hardware_id",it)'),
                    ("CxWorkerActivation.kt",'fun needsRenewal(withinMs: Long): Boolean = false'),
                    ("CxActivationActivity.kt",'InputFilter.LengthFilter(8)')):
    if needle not in (ROOT/(base+name)).read_text(encoding="utf8"):
        sys.exit("FAIL: V5.0.22 "+name+" is missing: "+needle)
if "replace_device_id" in (ROOT/(base+"CxWorkerActivation.kt")).read_text(encoding="utf8"):
    sys.exit("FAIL: V5.0.22 the app must not replace devices")

for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.22 permanent PIN activation and extension news",len(files),"files")
