#!/usr/bin/env python3
"""Leque v5.0.2 onboarding, full catalog installation and Google DoH and settings UI."""
from pathlib import Path
import sys

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
changes={}
def patch(path, old, new):
    src=changes.get(path)
    if src is None:
        src=(ROOT/path).read_text(encoding="utf-8")
    count=src.count(old)
    if count!=1:
        sys.exit("FAIL: Leque v5.0.1 patch mismatch: "+path+" expected one anchor, got "+str(count)+" for "+old[:75])
    changes[path]=src.replace(old,new,1)

p="app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt"
patch(p,'''import com.lagradost.cloudstream3.plugins.PluginManager
''','''import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.LequePluginSync
''')
patch(p,'''        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)

        setLastError(this)''','''        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)

        // Set English on a clean install without overriding existing user settings.
        val langPreferenceKey = getString(R.string.locale_key)
        if (!settingsManager.contains(langPreferenceKey)) {
            settingsManager.edit { putString(langPreferenceKey, "en") }
        }
        // First launch opens Home directly, with the Leque repo preconfigured.
        if (getKey<Boolean>(HAS_DONE_SETUP_KEY, false) != true) {
            setKey(HAS_DONE_SETUP_KEY, true)
        }
        if (DataStoreHelper.currentHomePage.isNullOrBlank()) {
            DataStoreHelper.currentHomePage = "Pencurimovie"
        }
        val existingAccounts = DataStoreHelper.accounts
        if (existingAccounts.any { it.keyIndex == 0 && it.name == "Mod v5 by Leque" }) {
            DataStoreHelper.accounts = existingAccounts.map {
                if (it.keyIndex == 0 && it.name == "Mod v5 by Leque")
                    it.copy(name = "AutoPlaylist by Leque")
                else it
            }.toTypedArray()
        }

        setLastError(this)''')
patch(p,'''        afterPluginsLoadedEvent += ::onAllPluginsLoaded''','''        afterPluginsLoadedEvent += ::onAllPluginsLoaded
        // Recheck on returning to the app, throttled to once in 15 minutes.
        if (!PluginManager.isSafeMode() && lastError == null) {
            ioSafe { LequePluginSync.refreshIfDue(this@MainActivity) }
        }''')
patch(p,'''                ioSafe {
                    if (settingsManager.getBoolean(
                            getString(R.string.auto_update_plugins_key),
                            true
                        )
                    ) {
                        PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_updateAllOnlinePluginsAndLoadThem(
                            this@MainActivity
                        )
                    } else {
                        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(this@MainActivity)
                    }

                    //Automatically download not existing plugins, using mode specified.
                    /*val autoDownloadPlugin = AutoDownloadMode.getEnum(
                        settingsManager.getInt(
                            getString(R.string.auto_download_plugins_key),
                            0
                        )
                    ) ?: AutoDownloadMode.Disable
                    if (autoDownloadPlugin != AutoDownloadMode.Disable) {
                        PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_downloadNotExistingPluginsAndLoad(
                            this@MainActivity,
                            autoDownloadPlugin
                        )
                    }*/
                }''','''                ioSafe {
                    // Only the approved default provider is bootstrapped automatically.
                    LequePluginSync.refreshIfDue(this@MainActivity)
                }''')
patch(p,'''        try {
            if (getKey<Boolean>(HAS_DONE_SETUP_KEY, false) != true) {
                navController.navigate(R.id.navigation_setup_language)
                // If no plugins bring up extensions screen
            } else if (PluginManager.getPluginsOnline().isEmpty()
                && PluginManager.getPluginsLocal().isEmpty()
//                && PREBUILT_REPOSITORIES.isNotEmpty()
            ) {
                navController.navigate(
                    R.id.navigation_setup_extensions,
                    SetupFragmentExtensions.newInstance(false)
                )
            }
        } catch (e: Exception) {
            logError(e)
        }''','''        // Hide the legacy setup wizard; extension bootstrap runs in background.''')

# Hide main Settings menu categories in Compose, including search indexing.
p="app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsFragmentScreen.kt"
patch(p,'''        ),
    )

    @Immutable''','''        ),
    ).filterNot {
        it.title == R.string.category_account || it.title == R.string.pref_category_extensions
    }.toPersistentList()

    @Immutable''')

# Keep the legacy Settings UI consistent (hide entries only, never disable security).
p="app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsFragment.kt"
patch(p,'''        binding.apply {
            listOf(''','''        binding.apply {
            settingsCredits.visibility = View.GONE
            settingsExtensions.visibility = View.GONE
            listOf(''')

plugin_path="app/src/main/java/com/lagradost/cloudstream3/plugins/LequePluginSync.kt"
if (ROOT/plugin_path).exists():
    sys.exit("FAIL: unexpected existing LequePluginSync.kt")
changes[plugin_path] = r'''package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.InternalAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.plugins.RepositoryManager.getRepoPlugins
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Host-app bootstrap, NOT a CloudStream extension.
 * All status=1 Leque catalog entries are installed automatically on fresh installs.
 * Third-party extension code is loaded from the user-managed Leque index.
 */
object LequePluginSync {
    private const val TAG = "LequePluginSync"
    private const val DEFAULT_PROVIDER = "Pencurimovie"
    private const val INTERVAL_MS = 15 * 60 * 1000L
    private const val MAX_PROVIDER_BYTES = 5_000_000L
    private const val MAX_CATALOG_ENTRIES = 100
    private val lock = Mutex()
    private var lastSuccessfulRefresh = 0L

    @OptIn(InternalAPI::class)
    suspend fun refreshIfDue(activity: Activity) {
        lock.withLock {
            val now = SystemClock.elapsedRealtime()
            if (lastSuccessfulRefresh > 0L && now >= lastSuccessfulRefresh &&
                now - lastSuccessfulRefresh < INTERVAL_MS
            ) return@withLock
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
                if (prefs.getBoolean(activity.getString(R.string.auto_update_plugins_key), true)) {
                    PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_updateAllOnlinePluginsAndLoadThem(activity)
                } else {
                    PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(activity)
                }
                val repo = PREBUILT_REPOSITORIES.firstOrNull() ?: return@withLock
                val all = getRepoPlugins(repo)?.map { it.plugin } ?: return@withLock
                if (all.isEmpty() || all.size > MAX_CATALOG_ENTRIES) {
                    Log.w(TAG, "Catalog is empty or over configured limit")
                    return@withLock
                }
                val duplicates = all.groupBy { it.internalName }
                    .filterValues { it.size > 1 }.keys
                val hashFormat = Regex("sha256-[0-9a-fA-F]{64}")
                val toInstall = all.filter { it.status == 1 }
                    .sortedBy { if (it.internalName == DEFAULT_PROVIDER) 0 else 1 }
                var successes = 0
                var failed = 0
                for (item in toInstall) {
                    val name = item.internalName
                    val hash = item.fileHash
                    if (name.isBlank() || name in duplicates ||
                        !item.url.startsWith("https://") ||
                        item.fileSize == null || item.fileSize <= 0L ||
                        item.fileSize > MAX_PROVIDER_BYTES ||
                        (hash != null && !hashFormat.matches(hash))) {
                        failed++
                        Log.w(TAG, "Rejected invalid catalog entry: $name")
                        continue
                    }
                    if (hash == null) {
                        Log.w(TAG, "No pinned SHA256 for third-party plugin: $name")
                    }
                    val local = PluginManager.getPluginPath(activity, name, repo.url)
                    val alreadyInstalled = local.isFile &&
                        PluginManager.getPluginsOnline().any {
                            it.internalName == name && it.filePath == local.absolutePath
                        }
                    if (alreadyInstalled) continue
                    try {
                        if (PluginManager.downloadPlugin(
                                activity = activity,
                                pluginUrl = item.url,
                                pluginHash = hash,
                                internalName = name,
                                repositoryUrl = repo.url,
                                loadPlugin = true)) {
                            successes++
                        } else {
                            failed++
                            Log.w(TAG, "Download or load failed: $name")
                        }
                    } catch (ex: Exception) {
                        failed++
                        Log.e(TAG, "Catalog installation error: $name", ex)
                    }
                }
                val preferred = getApiFromNameNull(DEFAULT_PROVIDER)
                if (preferred != null && DataStoreHelper.currentHomePage.isNullOrBlank()) {
                    DataStoreHelper.currentHomePage = preferred.name
                }
                if (preferred == null) Log.w(TAG, "Pencurimovie not registered")
                MainActivity.afterPluginsLoadedEvent.invoke(true)
                lastSuccessfulRefresh = SystemClock.elapsedRealtime()
                Log.i(TAG, "Leque catalog installed: $successes; failures: $failed")
            } catch (ex: Exception) {
                Log.e(TAG, "Recoverable plugin sync failure", ex)
            }
        }
    }
}
'''
# V5.0.2 default resolver and version. Preserve users' explicit DNS choices.
p="gradle/libs.versions.toml"
patch(p,'versionCode = "50001"','versionCode = "50002"')
patch(p,'versionName = "5.0.1"','versionName = "5.0.2"')
p="app/src/main/java/com/lagradost/cloudstream3/network/RequestsHelper.kt"
patch(p,'val dns = settingsManager.getInt(context.getString(R.string.dns_key), 0)','val dns = settingsManager.getInt(context.getString(R.string.dns_key), 1)')
p="shared/src/commonMain/kotlin/com/lagradost/cloudstream4/DevicePreferenceStore.kt"
patch(p,'val dns = preferences.getInt("dns_key", 0)','val dns = preferences.getInt("dns_key", 1)')
p="app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsGeneral.kt"
patch(p,'settingsManager.getInt(getString(R.string.dns_key), 0)','settingsManager.getInt(getString(R.string.dns_key), 1)')

for path, content in changes.items():
    target=ROOT/path
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(content, encoding="utf-8")
print("PASS: Leque V5.0.2 active catalog and Google DoH patch",len(changes),"files")
