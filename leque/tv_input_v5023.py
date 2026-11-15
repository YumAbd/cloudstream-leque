#!/usr/bin/env python3
"""CloudXtream V5.0.23: Android TV works well with a mouse (or air mouse) and a keyboard; the remote
(D-pad) keeps working exactly as before.

- focus follows the mouse (only fully visible views, so lists never scroll by themselves)
- moving the mouse in the player shows its controls
- Esc goes back; typing a letter outside the player opens Search with it
The behaviour lives in leque/android/CxInput.kt (copied here)."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
HERE=Path(__file__).resolve().parent
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.23 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50022"','versionCode = "50023"')
patch("gradle/libs.versions.toml",'versionName = "5.0.22"','versionName = "5.0.23"')
base="app/src/main/java/com/lagradost/cloudstream3/"
files[base+"ui/CxInput.kt"]=(HERE/"android"/"CxInput.kt").read_text(encoding="utf8")

hooks='''    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        com.lagradost.cloudstream3.ui.CxInput.onGenericMotion(this, ev)
        return super.dispatchGenericMotionEvent(ev)
    }

'''
for path,search in ((base+"MainActivity.kt","true"),(base+"ui/player/DownloadedPlayerActivity.kt","false")):
    patch(path,"""    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        CommonActivity.dispatchKeyEvent(this, event) ?: super.dispatchKeyEvent(event)""",
        hooks+"""    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (com.lagradost.cloudstream3.ui.CxInput.onKey(this, event, """+search+""")) true
        else CommonActivity.dispatchKeyEvent(this, event) ?: super.dispatchKeyEvent(event)""")

patch(base+"ui/account/AccountSelectActivity2.kt",
      "class AccountSelectActivity2 : FragmentActivity(), BiometricCallback {\n",
      "class AccountSelectActivity2 : FragmentActivity(), BiometricCallback {\n"+hooks+
      """    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        com.lagradost.cloudstream3.ui.CxInput.onKey(this, event, false) || super.dispatchKeyEvent(event)

""")

p=base+"ui/player/FullScreenPlayer.kt"
patch(p,"""        setupKeyEventListener()
        playerHostView?.verifyVolume()""","""        setupKeyEventListener()
        // Mouse on Android TV: moving it shows the controls (and keeps them up while it moves).
        com.lagradost.cloudstream3.ui.CxInput.onMouseMove = {
            if (isResumed && !isLocked && !isShowingEpisodeOverlay) {
                if (!isShowing) onClickChange() else autoHide()
            }
        }
        playerHostView?.verifyVolume()""")
patch(p,"""    override fun onDestroyView() {
        playerHostView?.releaseOverlayLayoutListener()""","""    override fun onDestroyView() {
        com.lagradost.cloudstream3.ui.CxInput.onMouseMove = null
        playerHostView?.releaseOverlayLayoutListener()""")

for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.23 mouse and keyboard on Android TV",len(files),"files")
