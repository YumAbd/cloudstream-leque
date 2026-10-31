#!/usr/bin/env python3
"""CloudXtream V5.0.16: Android TV player crash fix (the More button exists in the TV layout too)."""
from pathlib import Path
import re, sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.16 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50015"','versionCode = "50016"')
patch("gradle/libs.versions.toml",'versionName = "5.0.15"','versionName = "5.0.16"')

# FullScreenPlayer binds the TV player layout with the phone layout's binding class, so every
# view of the phone layout must also exist in the TV layout (V5.0.14 added More to phone only).
tv="app/src/main/res/layout/player_custom_layout_tv.xml"
patch(tv,'''                            android:text="@string/compressor"
                            app:icon="@drawable/ic_compressor_24" />
                    </LinearLayout>''','''                            android:text="@string/compressor"
                            app:icon="@drawable/ic_compressor_24" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/player_cx_more_btt"
                            style="@style/VideoButtonTV"
                            android:nextFocusLeft="@id/player_compressor_btt"
                            android:nextFocusUp="@id/player_pause_play"
                            android:text="@string/cx_player_more"
                            app:icon="@drawable/ic_baseline_more_vert_24" />
                    </LinearLayout>''')

ids=lambda text:set(re.findall(r'android:id="@\+?id/([A-Za-z0-9_]+)"',text))
phone=ids((ROOT/"app/src/main/res/layout/player_custom_layout.xml").read_text(encoding="utf8"))
port=ROOT/"app/src/main/res/layout-port/player_custom_layout.xml"
if port.exists(): phone&=ids(port.read_text(encoding="utf8"))  # views not in every variant are optional
missing=sorted(phone-ids(files[tv]))
if missing: sys.exit("FAIL: V5.0.16 views missing from the TV player layout: "+", ".join(missing))

# Instrumentation test (run on the Android 8 emulator): binds the phone and TV player layouts.
test="app/src/androidTest/java/com/lagradost/cloudstream3/CxLayoutTest.kt"
if (ROOT/test).exists(): sys.exit("FAIL: duplicate CxLayoutTest")
files[test]=(Path(__file__).resolve().parent/"androidTest"/"CxLayoutTest.kt").read_text(encoding="utf8")

for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.16 TV player layout has the More button",len(files),"files")
