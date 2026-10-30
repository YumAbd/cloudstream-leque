#!/usr/bin/env python3
"""CloudXtream V5.0.5 API26 online license and diagnostics preview."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
HERE=Path(__file__).resolve().parent
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.5 anchor count="+str(count)+" in "+path)
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50004"','versionCode = "50005"')
patch("gradle/libs.versions.toml",'versionName = "5.0.4"','versionName = "5.0.5"')
# Cannot overwrite debug-signed V5.0.4 without the original signing key.
patch("app/build.gradle.kts",
      'applicationIdSuffix = ".debug.activationpreview"',
      'applicationIdSuffix = ".debug.activationfix"')
test="app/src/androidTest/java/com/lagradost/cloudstream3/activation/CxKeystoreProofTest.kt"
if (ROOT/test).exists():sys.exit("FAIL: Android instrumentation test already exists")
files[test]=(HERE/"androidTest/CxKeystoreProofTest.kt").read_text(encoding="utf8")
for path,source in files.items():
    f=ROOT/path
    f.parent.mkdir(parents=True,exist_ok=True)
    f.write_text(source,encoding="utf8")
print("PASS: V5.0.5 activation fix and Android 8 keystore test",len(files),"files")
