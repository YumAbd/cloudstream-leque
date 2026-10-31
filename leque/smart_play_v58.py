#!/usr/bin/env python3
"""CloudXtream V5.0.8 Smart Play: skip a source that does not start playing in time."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.8 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50007"','versionCode = "50008"')
patch("gradle/libs.versions.toml",'versionName = "5.0.7"','versionName = "5.0.8"')
p="app/src/main/java/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt"
patch(p,"import android.widget.Toast\n","import android.os.Handler\nimport android.os.Looper\nimport android.widget.Toast\n")
patch(p,"""    private fun loadLink(link: VideoLink?, sameEpisode: Boolean) {""",
"""    // CloudXtream Smart Play: player errors already move to the next source; this also
    // moves on when a source is still not playing after CX_STALL_MS (stuck loading).
    private val cxStallHandler = Handler(Looper.getMainLooper())
    private val cxStallMs = 20_000L
    private fun cxArmStallWatchdog(link: VideoLink, isTorrent: Boolean) {
        cxStallHandler.removeCallbacksAndMessages(null)
        if (isTorrent) return
        cxStallHandler.postDelayed({
            if (!isAdded || currentSelectedLink !== link || player.getIsPlaying()) return@postDelayed
            if (currentPlayerStatus == CSPlayerLoading.IsPaused) return@postDelayed
            if (!hasNextMirror()) return@postDelayed
            showToast("Source not responding, trying the next one", Toast.LENGTH_SHORT)
            nextMirror()
        }, cxStallMs)
    }

    private fun loadLink(link: VideoLink?, sameEpisode: Boolean) {""")
patch(p,"""        currentSelectedLink = link\n""","""        currentSelectedLink = link\n        cxArmStallWatchdog(link, isTorrent)\n""")
patch(p,"""            viewModel.forceClearCache = false\n""","""            viewModel.forceClearCache = false\n            cxStallHandler.removeCallbacksAndMessages(null)\n""")
patch(p,"""    override fun onDestroy() {
        ResultFragment.updateUI()""","""    override fun onDestroy() {
        cxStallHandler.removeCallbacksAndMessages(null)
        ResultFragment.updateUI()""")
for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.8 Smart Play stall watchdog",len(files),"files")
