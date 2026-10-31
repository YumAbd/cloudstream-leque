#!/usr/bin/env python3
"""CloudXtream V5.0.13: in-app download progress, then Android's installer replaces the app."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.13 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50012"','versionCode = "50013"')
patch("gradle/libs.versions.toml",'versionName = "5.0.12"','versionName = "5.0.13"')

p="app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt"
# "Update" previously started a background download that only showed a notification.
source=(ROOT/p).read_text(encoding="utf8")
start_anchor="                        showToast(R.string.download_started, Toast.LENGTH_LONG)\n"
end_anchor="                    setNegativeButton(R.string.cancel) { _, _ -> }\n"
if source.count(start_anchor)!=1 or source.count(end_anchor)!=1: sys.exit("FAIL: V5.0.13 update button anchors")
start=source.index(start_anchor); end=source.index(end_anchor)
if end<start: sys.exit("FAIL: V5.0.13 update button order")
files[p]=source[:start]+"""                        cxDownloadAndInstall(update.updateURL)
                    }

"""+source[end:]
patch(p,"    fun Activity.installPreReleaseIfNeeded() = ioSafe {\n",
'''    // CloudXtream: download inside the app with a progress dialog, then open Android's installer,
    // which replaces the current app after one tap (only an APK with the same certificate can).
    private fun Activity.cxDownloadAndInstall(url: String) {
        val builder = AlertDialog.Builder(this, R.style.AlertDialogCustom)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val progressBar = android.widget.ProgressBar(
            builder.context, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
            max = 100
        }
        val status = android.widget.TextView(builder.context).apply {
            text = "Connecting..."
            setPadding(0, padding / 2, 0, 0)
        }
        val layout = android.widget.LinearLayout(builder.context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(progressBar)
            addView(status)
        }
        var job: kotlinx.coroutines.Job? = null
        val dialog = builder
            .setTitle("Downloading update")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> job?.cancel() }
            .show()
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val file = File(cacheDir, "CloudStream-update.apk")
        job = ioSafe {
            try {
                file.delete()
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("Accept-Encoding", "identity")
                try {
                    if (connection.responseCode != 200) throw IOException("HTTP " + connection.responseCode)
                    val total = connection.contentLengthLong
                    var done = 0L
                    var shownAt = 0L
                    connection.inputStream.use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                val now = System.currentTimeMillis()
                                if (now - shownAt >= 250 || (total > 0 && done == total)) {
                                    shownAt = now
                                    val bytes = done
                                    runOnUiThread {
                                        val mb = bytes / 1_048_576.0
                                        if (total > 0) {
                                            val percent = (bytes * 100 / total).toInt()
                                            progressBar.isIndeterminate = false
                                            progressBar.progress = percent
                                            status.text = "%.1f MB / %.1f MB (%d%%)".format(
                                                mb, total / 1_048_576.0, percent
                                            )
                                        } else {
                                            status.text = "%.1f MB".format(mb)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (total > 0 && done != total) throw IOException("Incomplete download")
                } finally {
                    connection.disconnect()
                }
                runOnUiThread {
                    dialog.dismiss()
                    openApk(this@cxDownloadAndInstall, Uri.fromFile(file))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                logError(e)
                file.delete()
                runOnUiThread {
                    dialog.dismiss()
                    showToast(R.string.download_failed, Toast.LENGTH_LONG)
                }
            }
        }
    }

    fun Activity.installPreReleaseIfNeeded() = ioSafe {
''')
patch(p,"import kotlinx.coroutines.sync.Mutex\n","import kotlinx.coroutines.ensureActive\nimport kotlinx.coroutines.sync.Mutex\n")

for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.13 in-app update download progress",len(files),"files")
