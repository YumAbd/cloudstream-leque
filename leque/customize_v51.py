#!/usr/bin/env python3
"""Leque v5.0.1 onboarding, automatic preferred-provider sync and settings UI."""
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
 * Only one selected, checksum-pinned provider is installed without a setup wizard.
 * Other catalog entries are never silently executed.
 */
object LequePluginSync {
    private const val TAG = "LequePluginSync"
    private const val DEFAULT_PROVIDER = "Pencurimovie"
    private const val INTERVAL_MS = 15 * 60 * 1000L
    private const val MAX_PROVIDER_BYTES = 5_000_000L
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
                val matches = getRepoPlugins(repo)?.filter {
                    it.plugin.internalName == DEFAULT_PROVIDER
                } ?: return@withLock
                if (matches.size != 1) {
                    Log.w(TAG, "Preferred provider missing or duplicated in Leque index")
                    return@withLock
                }
                val provider = matches.single().plugin
                val expectedHash = provider.fileHash
                if (provider.status != 1 ||
                    expectedHash == null || !Regex("sha256-[0-9a-f]{64}").matches(expectedHash) ||
                    provider.fileSize == null || provider.fileSize <= 0L ||
                    provider.fileSize > MAX_PROVIDER_BYTES || !provider.url.startsWith("https://")
                ) {
                    Log.w(TAG, "Selected provider metadata fails integrity checks")
                    return@withLock
                }

                val target = PluginManager.getPluginPath(activity, DEFAULT_PROVIDER, repo.url)
                val installed = target.isFile && PluginManager.getPluginsOnline().any {
                    it.internalName == DEFAULT_PROVIDER && it.filePath == target.absolutePath
                }
                if (!installed) {
                    val ok = PluginManager.downloadPlugin(
                        activity = activity,
                        pluginUrl = provider.url,
                        pluginHash = expectedHash,
                        internalName = DEFAULT_PROVIDER,
                        repositoryUrl = repo.url,
                        loadPlugin = true
                    )
                    if (!ok) {
                        Log.w(TAG, "Unable to download or load preferred provider")
                        return@withLock
                    }
                }
                val loaded = getApiFromNameNull(DEFAULT_PROVIDER)
                if (loaded == null) {
                    Log.w(TAG, "Preferred provider file is installed but its API failed to register")
                    return@withLock
                }
                if (DataStoreHelper.currentHomePage.isNullOrBlank()) {
                    DataStoreHelper.currentHomePage = loaded.name
                }
                MainActivity.afterPluginsLoadedEvent.invoke(true)
                lastSuccessfulRefresh = SystemClock.elapsedRealtime()
            } catch (ex: Exception) {
                Log.e(TAG, "Recoverable plugin sync failure", ex)
            }
        }
    }
}
'''
for path, content in changes.items():
    target=ROOT/path
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(content, encoding="utf-8")
print("PASS: applied Leque v5.0.1 onboarding/UI/provider sync patch to",len(changes),"files")
