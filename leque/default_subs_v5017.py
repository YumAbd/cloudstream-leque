#!/usr/bin/env python3
"""CloudXtream V5.0.17: Home default provider chosen in CloudXtream Control, automatic subtitles."""
from pathlib import Path
from xml.sax.saxutils import escape
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.17 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50016"','versionCode = "50017"')
patch("gradle/libs.versions.toml",'versionName = "5.0.16"','versionName = "5.0.17"')

STRINGS={  # name: (English, Bahasa Melayu)
 "cx_auto_subs_on":("Automatic subtitles: On","Sari kata automatik: Hidup"),
 "cx_auto_subs_off":("Automatic subtitles: Off","Sari kata automatik: Tutup"),
 "cx_auto_subs_loaded":("Subtitles found online and turned on (%s)","Sari kata dijumpai dalam talian dan dihidupkan (%s)"),
}
for path,col in (("app/src/main/res/values/strings.xml",0),("app/src/main/res/values-b+ms/strings.xml",1)):
    lines="".join('    <string name="'+k+'">'+escape(v[col])+'</string>\n' for k,v in STRINGS.items())
    patch(path,"</resources>","    <!-- CloudXtream V5.0.17 -->\n"+lines+"</resources>")

# ---------------------------------------------------------------- Home default provider
p="app/src/main/java/com/lagradost/cloudstream3/plugins/LequePluginSync.kt"
patch(p,"""                val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
""","""                val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
                val chosen = cxChosenDefault()
""")
patch(p,"""                    .sortedBy { if (it.internalName == DEFAULT_PROVIDER) 0 else 1 }""",
      """                    .sortedBy { if (it.internalName == (chosen?.first ?: DEFAULT_PROVIDER)) 0 else 1 }""")
patch(p,"""                val preferred = getApiFromNameNull(DEFAULT_PROVIDER)
                if (preferred != null && DataStoreHelper.currentHomePage.isNullOrBlank()) {
                    DataStoreHelper.currentHomePage = preferred.name
                }
                if (preferred == null) Log.w(TAG, "Pencurimovie not registered")""","""                cxApplyDefaultHome(activity, chosen)""")
patch(p,"""    @OptIn(InternalAPI::class)
    suspend fun refreshIfDue(activity: Activity) {""","""    private const val CONFIG_URL = "https://cloudxpanel.infopc.xyz/api/app/config"
    private const val APPLIED_DEFAULT = "cx_default_home_applied"

    /** (internalName, name) of the Home default chosen in CloudXtream Control, if any. */
    private fun cxChosenDefault(): Pair<String, String>? = try {
        val connection = java.net.URL(CONFIG_URL).openConnection() as javax.net.ssl.HttpsURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        try {
            if (connection.responseCode != 200) null
            else org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONObject("default_provider")
                ?.let { it.optString("internal_name") to it.optString("name") }
                ?.takeIf { it.first.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Default provider not read: " + e.javaClass.simpleName)
        null
    }

    /**
     * Home opens the chosen default (Pencurimovie until one is chosen). The Home provider is only
     * replaced while it is empty or still the previous default, so a provider the user picked is kept.
     */
    private fun cxApplyDefaultHome(activity: Activity, chosen: Pair<String, String>?) {
        val api = chosen?.let { (internal, name) ->
            getApiFromNameNull(name) ?: getApiFromNameNull(internal)
                ?: com.lagradost.cloudstream3.APIHolder.apis.firstOrNull { it.sourcePlugin?.contains(internal) == true }
        } ?: getApiFromNameNull(DEFAULT_PROVIDER)
        if (api == null) {
            Log.w(TAG, "Default provider not registered")
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val current = DataStoreHelper.currentHomePage
        val previous = prefs.getString(APPLIED_DEFAULT, DEFAULT_PROVIDER)
        if (current.isNullOrBlank() || current == previous || current == api.name) {
            DataStoreHelper.currentHomePage = api.name
            prefs.edit().putString(APPLIED_DEFAULT, api.name).apply()
        }
    }

    @OptIn(InternalAPI::class)
    suspend fun refreshIfDue(activity: Activity) {""")

# ---------------------------------------------------------------- automatic subtitles
p="app/src/main/java/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt"
patch(p,"""            .setItems(arrayOf(sleepLabel, ctx.getString(R.string.cx_report))) { _, which ->
                if (which == 0) cxShowSleepTimer(ctx) else cxShowReport(ctx)
            }""","""            .setItems(arrayOf(
                sleepLabel,
                ctx.getString(R.string.cx_report),
                ctx.getString(if (cxAutoSubtitlesOn(ctx)) R.string.cx_auto_subs_on else R.string.cx_auto_subs_off)
            )) { _, which ->
                when (which) {
                    0 -> cxShowSleepTimer(ctx)
                    1 -> cxShowReport(ctx)
                    else -> {
                        val on = !cxAutoSubtitlesOn(ctx)
                        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(CX_AUTO_SUBS, on).apply()
                        showToast(if (on) R.string.cx_auto_subs_on else R.string.cx_auto_subs_off, Toast.LENGTH_SHORT)
                        if (on) {
                            cxAutoSubsFor = null
                            cxMaybeAutoSubtitles()
                        }
                    }
                }
            }""")
patch(p,"""    private fun loadLink(link: VideoLink?, sameEpisode: Boolean) {""","""    // CloudXtream: when a video has no subtitles in the user's languages, search the online
    // subtitle providers that need no account (plus any the user signed in to), once per video,
    // and turn on the first match. Languages: the auto-select setting, then Malay, Indonesian, English.
    private val CX_AUTO_SUBS = "cx_auto_subtitles"
    private var cxAutoSubsFor: Int? = null
    private fun cxAutoSubtitlesOn(ctx: android.content.Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(CX_AUTO_SUBS, true)
    private fun cxMaybeAutoSubtitles() {
        val ctx = context ?: return
        if (!cxAutoSubtitlesOn(ctx)) return
        val key = viewModel.state.generatorState?.id ?: return
        if (cxAutoSubsFor == key) return
        cxAutoSubsFor = key
        val languages = listOf(getAutoSelectLanguageTagIETF(), "ms", "id", "en").filter { it.isNotBlank() }.distinct()
        if (viewModel.state.subtitles.any { sub -> languages.any { sub.matchesLanguageCode(it) } }) return
        val response = viewModel.state.generatorState?.response
        val metadata = getMetaData()
        val name = metadata.name ?: response?.name ?: return
        val providers = subsProviders.filter { it.isAvailable }
        if (providers.isEmpty()) return
        viewModel.viewModelScope.launch {
            for (language in languages) {
                val query = SubtitleSearch(
                    query = name,
                    imdbId = response?.getImdbId(),
                    tmdbId = response?.getTMDbId()?.toIntOrNull(),
                    malId = response?.getMalId()?.toIntOrNull(),
                    aniListId = response?.getAniListId()?.toIntOrNull(),
                    epNumber = metadata.episode,
                    seasonNumber = metadata.season,
                    lang = language,
                    year = viewModel.currentSubtitleYear.value
                )
                for (provider in providers) {
                    if (!isActive || cxAutoSubsFor != key) return@launch
                    val entries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        provider.search(query).getOrNull()
                    } ?: continue
                    for (entry in entries.take(3)) {
                        val resources = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            provider.resource(entry).getOrNull()
                        } ?: continue
                        val subtitles = resources.getSubtitles().map { resource ->
                            SubtitleData(
                                originalName = resource.name ?: getName(entry, true),
                                nameSuffix = "",
                                url = resource.url,
                                origin = resource.origin,
                                mimeType = resource.url.toSubtitleMimeType(),
                                headers = entry.headers,
                                languageCode = entry.lang,
                            )
                        }
                        if (subtitles.isEmpty() || viewModel.state.subtitles.containsAll(subtitles)) continue
                        if (!isActive || cxAutoSubsFor != key) return@launch
                        addAndSelectSubtitles(*subtitles.toTypedArray())
                        showToast(ctx.getString(R.string.cx_auto_subs_loaded, entry.lang ?: language), Toast.LENGTH_SHORT)
                        return@launch
                    }
                }
            }
        }
    }

    private fun loadLink(link: VideoLink?, sameEpisode: Boolean) {""")
patch(p,"""            viewModel.forceClearCache = false
            cxStallHandler.removeCallbacksAndMessages(null)
""","""            viewModel.forceClearCache = false
            cxStallHandler.removeCallbacksAndMessages(null)
            cxMaybeAutoSubtitles()
""")

for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.17 Home default from CloudXtream Control, automatic subtitles",len(files),"files")
