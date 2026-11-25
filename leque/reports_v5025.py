#!/usr/bin/env python3
"""CloudXtream V5.0.25: reports with device / network / player details; Android TV download-folder fix.

- "Report a problem" and crash reports carry the device, OS, memory, network type, DNS, the
  extension, title, link, source, codecs and how the extension's website answers (CloudXtream
  Control adds the ISP and shows everything in one layout on Telegram and in the admin panel).
- Settings > General > Download folder: devices without a folder picker (many Android TV boxes)
  get a message instead of a crash.
"""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
HERE=Path(__file__).resolve().parent
files={}
def read(path):
    return files[path] if path in files else (ROOT/path).read_text(encoding="utf8")
def patch(path,before,after):
    current=read(path)
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.25 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

src="app/src/main/java/com/lagradost/cloudstream3/"
files[src+"activation/CxDiag.kt"]=(HERE/"android"/"CxDiag.kt").read_text(encoding="utf8")

p=src+"ui/player/GeneratorPlayer.kt"
patch(p,"""                val appContext = ctx.applicationContext
                showToast(R.string.cx_report_sending, Toast.LENGTH_SHORT)
                ioSafe {
                    val sent = com.lagradost.cloudstream3.activation.CxWorkerActivation.reportProblem(
                        appContext, title, source, reasons[which], com.lagradost.cloudstream3.BuildConfig.VERSION_NAME
                    )""","""                val appContext = ctx.applicationContext
                // What was playing, read now (on the main thread): extension, link, source and codecs.
                val ep = currentMeta as? ResultEpisode
                val link = currentSelectedLink?.first
                val tracks = runCatching { player.getVideoTracks() }.getOrNull()
                val video = tracks?.currentVideoTrack
                val audio = tracks?.currentAudioTrack
                val playerText = "ExoPlayer" +
                    (video?.let { v -> " | Video: " + (v.sampleMimeType?.substringAfter('/') ?: "?") + (if (v.width != null && v.height != null) " (" + v.width + "x" + v.height + ")" else "") } ?: "") +
                    (audio?.let { a -> " | Audio: " + (a.sampleMimeType?.substringAfter('/') ?: "?") + (a.channelCount?.let { c -> " " + c + "ch" } ?: "") } ?: "")
                val apiName = ep?.apiName
                val siteUrl = apiName?.let { com.lagradost.cloudstream3.APIHolder.getApiFromNameNull(it)?.mainUrl }
                val sourceText = link?.let { l -> l.name + (if (l.quality > 0) " (" + l.quality + "p)" else "") }
                showToast(R.string.cx_report_sending, Toast.LENGTH_SHORT)
                ioSafe {
                    val diag = com.lagradost.cloudstream3.activation.CxDiag.base(appContext).apply {
                        apiName?.let { put("extension", it) }
                        put("media", title)
                        ep?.data?.let { put("link", it.take(300)) }
                        sourceText?.let { put("source", it) }
                        if (link != null) put("player", playerText)
                        com.lagradost.cloudstream3.activation.CxDiag.ping(siteUrl)?.let { put("ping", it) }
                    }
                    val sent = com.lagradost.cloudstream3.activation.CxWorkerActivation.reportProblem(
                        appContext, title, source, reasons[which], com.lagradost.cloudstream3.BuildConfig.VERSION_NAME, diag = diag
                    )""")

p=src+"ui/settings/SettingsGeneralScreen.kt"
patch(p,"""                            selectFileSelector.launch(Uri.EMPTY)""","""                            try {
                                selectFileSelector.launch(Uri.EMPTY)
                            } catch (e: android.content.ActivityNotFoundException) {
                                // Many Android TV boxes have no folder picker.
                                android.widget.Toast.makeText(context, com.lagradost.cloudstream3.ui.live.CxLiveManage.t(context,
                                    "Peranti ini tiada pengurus fail untuk memilih folder. Muat turun disimpan dalam folder lalai.",
                                    "This device has no file manager to choose a folder. Downloads are saved in the default folder."),
                                    android.widget.Toast.LENGTH_LONG).show()
                            }""")

# Android TV: arrow-key focus moves are done by the app (no crash when a list is redrawn under the remote).
patch(src+"MainActivity.kt","""    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (com.lagradost.cloudstream3.ui.CxInput.onKey(this, event, true)) true
        else CommonActivity.dispatchKeyEvent(this, event) ?: super.dispatchKeyEvent(event)""","""    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (com.lagradost.cloudstream3.ui.CxInput.onKey(this, event, true)) return true
        val handled = try {
            CommonActivity.dispatchKeyEvent(this, event) ?: super.dispatchKeyEvent(event)
        } catch (e: IllegalArgumentException) {
            android.util.Log.w("CxInput", "Key skipped: a list changed under the remote"); true
        }
        return handled || com.lagradost.cloudstream3.ui.CxInput.moveFocus(this, event)
    }""")

for path,source in files.items():
    target=ROOT/path
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(source,encoding="utf8")
print("PASS: V5.0.25 reports",len(files),"files")
