#!/usr/bin/env python3
"""V5.0.4 activated CloudXtream side-by-side debug preview."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
HERE=Path(__file__).resolve().parent
change={}
def patch(path,before,after):
    content=change.get(path)
    if content is None:content=(ROOT/path).read_text(encoding="utf-8")
    n=content.count(before)
    if n!=1:sys.exit(f"FAIL: V5.0.4 anchor mismatch {path}: found {n}: {before[:70]}")
    change[path]=content.replace(before,after,1)
patch("gradle/libs.versions.toml",'versionCode = "50003"','versionCode = "50004"')
patch("gradle/libs.versions.toml",'versionName = "5.0.3"','versionName = "5.0.4"')
patch("app/build.gradle.kts",'applicationIdSuffix = ".debug"',
      'applicationIdSuffix = ".debug.activationpreview"')
patch("app/src/main/AndroidManifest.xml",
'''        <activity
            android:name=".MainActivity"''',
'''        <!-- Non-exported online license entry gate. -->
        <activity android:name=".activation.CxActivationActivity"
            android:exported="false" />
        <activity
            android:name=".MainActivity"''')
p="app/src/main/java/com/lagradost/cloudstream3/ui/account/AccountSelectActivity2.kt"
patch(p,'import com.lagradost.cloudstream3.MainActivity',
      'import com.lagradost.cloudstream3.MainActivity\nimport com.lagradost.cloudstream3.activation.CxActivationGuard')
patch(p,'''        super.onCreate(savedInstanceState)

        // Are we editing''',
'''        super.onCreate(savedInstanceState)
        if (!CxActivationGuard.allow(this)) return

        // Are we editing''')
p="app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt"
patch(p,'import android.content.Intent','import android.content.Intent\nimport android.os.Handler\nimport android.os.Looper')
patch(p,'import androidx.lifecycle.ViewModelProvider',
      'import androidx.lifecycle.ViewModelProvider\nimport androidx.lifecycle.lifecycleScope')
patch(p,'import com.lagradost.cloudstream3.APIHolder.allProviders',
      'import com.lagradost.cloudstream3.activation.CxActivationGuard\nimport com.lagradost.cloudstream3.activation.CxWorkerActivation\nimport com.lagradost.cloudstream3.APIHolder.allProviders')
patch(p,'import kotlinx.coroutines.Job','import kotlinx.coroutines.Job\nimport kotlinx.coroutines.launch')
patch(p,
'''class MainActivity : AppCompatActivity(), ColorPickerDialogListener, BiometricCallback {
    companion object {''',
'''class MainActivity : AppCompatActivity(), ColorPickerDialogListener, BiometricCallback {
    private val cxCheckHandler=Handler(Looper.getMainLooper())
    private fun repeatCxOnlineCheck(){
        cxCheckHandler.removeCallbacksAndMessages(null)
        cxCheckHandler.postDelayed({
            lifecycleScope.launch {
                if (!CxWorkerActivation.validateOnline(this@MainActivity))
                    CxActivationGuard.lock(this@MainActivity)
                else if(!isFinishing)repeatCxOnlineCheck()
            }
        },45_000L)
    }
    companion object {''')
patch(p,
'''        super.onCreate(savedInstanceState)
        try {
            if (isCastApiAvailable()) {''',
'''        super.onCreate(savedInstanceState)
        if (!CxActivationGuard.allow(this)) return
        try {
            if (isCastApiAvailable()) {''')
patch(p,
'''    override fun onResume() {
        super.onResume()
        afterPluginsLoadedEvent += ::onAllPluginsLoaded''',
'''    override fun onResume() {
        super.onResume()
        if (!CxActivationGuard.allow(this)) return
        repeatCxOnlineCheck()
        afterPluginsLoadedEvent += ::onAllPluginsLoaded''')
patch(p,
'''    override fun onPause() {
        super.onPause()

        // Start any delayed updates''',
'''    override fun onPause() {
        cxCheckHandler.removeCallbacksAndMessages(null)
        super.onPause()

        // Start any delayed updates''')
p="app/src/main/java/com/lagradost/cloudstream3/ui/player/DownloadedPlayerActivity.kt"
patch(p,'import android.os.Bundle','import android.os.Bundle\nimport android.os.Handler\nimport android.os.Looper')
patch(p,'import androidx.appcompat.app.AppCompatActivity',
      'import androidx.appcompat.app.AppCompatActivity\nimport androidx.lifecycle.lifecycleScope')
patch(p,'import com.lagradost.cloudstream3.CommonActivity',
      'import com.lagradost.cloudstream3.CommonActivity\nimport com.lagradost.cloudstream3.activation.CxActivationGuard\nimport com.lagradost.cloudstream3.activation.CxWorkerActivation')
patch(p,'import com.lagradost.cloudstream3.utils.UIHelper.enableEdgeToEdgeCompat',
      'import com.lagradost.cloudstream3.utils.UIHelper.enableEdgeToEdgeCompat\nimport kotlinx.coroutines.launch')
patch(p,
'''class DownloadedPlayerActivity : AppCompatActivity() {
    companion object {''',
'''class DownloadedPlayerActivity : AppCompatActivity() {
    private val cxCheckHandler=Handler(Looper.getMainLooper())
    private fun repeatCxOnlineCheck(){
        cxCheckHandler.removeCallbacksAndMessages(null)
        cxCheckHandler.postDelayed({
            lifecycleScope.launch {
                if (!CxWorkerActivation.validateOnline(this@DownloadedPlayerActivity))
                    CxActivationGuard.lock(this@DownloadedPlayerActivity)
                else if(!isFinishing)repeatCxOnlineCheck()
            }
        },45_000L)
    }
    companion object {''')
patch(p,'''    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Ignore same intent''',
'''    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!CxActivationGuard.allow(this)) return
        // Ignore same intent''')
patch(p,'''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CommonActivity.loadThemes(this)''',
'''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CxActivationGuard.allow(this)) return
        CommonActivity.loadThemes(this)''')
patch(p,'''    override fun onResume() {
        super.onResume()
        CommonActivity.setActivityInstance(this)
    }''',
'''    override fun onResume() {
        super.onResume()
        if (!CxActivationGuard.allow(this)) return
        repeatCxOnlineCheck()
        CommonActivity.setActivityInstance(this)
    }
    override fun onPause(){
        cxCheckHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }''')
for name in ("CxWorkerActivation.kt","CxActivationActivity.kt"):
    p="app/src/main/java/com/lagradost/cloudstream3/activation/"+name
    if (ROOT/p).exists():sys.exit("FAIL: duplicate class "+name)
    change[p]=(HERE/"android"/name).read_text(encoding="utf-8")
for path,content in change.items():
    target=ROOT/path;target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(content,encoding="utf-8")
print("PASS: Applied V5.0.4 license gate to",len(change),"files")
