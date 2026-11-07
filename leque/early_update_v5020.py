#!/usr/bin/env python3
"""CloudXtream V5.0.20: the update prompt appears right after activation, on the profile picker.

With more than one profile the app first shows the profile picker, and the update check only ran
in MainActivity, after a profile was chosen. The picker now checks too (once per app start), and
MainActivity skips its check when the picker already ran it."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.20 update-prompt anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

p="app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt"
patch(p,"    fun Activity.installPreReleaseIfNeeded() = ioSafe {","""    /** Set once the update check ran in this app start, so the prompt is not shown twice. */
    @Volatile var cxCheckedThisStart = false

    /** Update check for the first screen after activation (the profile picker). */
    fun Activity.cxCheckForUpdateOnce() {
        if (cxCheckedThisStart) return
        cxCheckedThisStart = true
        ioSafe { runAutoUpdate() }
    }

    fun Activity.installPreReleaseIfNeeded() = ioSafe {""")

p="app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt"
patch(p,"""        com.lagradost.cloudstream3.ui.CxHints.remindExpiryIfNeeded(this)
        ioSafe {
            runAutoUpdate()
        }
""","""        com.lagradost.cloudstream3.ui.CxHints.remindExpiryIfNeeded(this)
        // Already checked on the profile picker in this app start: do not prompt twice.
        with(com.lagradost.cloudstream3.utils.InAppUpdater) { cxCheckForUpdateOnce() }
""")

p="app/src/main/java/com/lagradost/cloudstream3/ui/account/AccountSelectActivity2.kt"
patch(p,"""        /**
         * If this app is locked with biometrics,
         * then require that on a startup that is not skipped!
         * */""","""        // CloudXtream: the profile picker is the first screen after activation; offer an app
        // update here instead of only after a profile was chosen.
        if (!isFromMainActivity && !isEditingFromMainActivity) {
            with(com.lagradost.cloudstream3.utils.InAppUpdater) { cxCheckForUpdateOnce() }
        }

        /**
         * If this app is locked with biometrics,
         * then require that on a startup that is not skipped!
         * */""")

for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.20 update prompt right after activation",len(files),"files")
