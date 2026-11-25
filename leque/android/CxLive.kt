package com.lagradost.cloudstream3.ui.live

import android.content.Context
import android.net.Uri
import com.lagradost.cloudstream3.activation.CxWorkerActivation
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** One channel of a playlist. [url] "youtube:@handle" is an official YouTube channel. */
data class LiveChannel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val userAgent: String? = null,
    val referer: String? = null,
    /** tvg-id (e.g. TV1.my), used to find a logo when the playlist has none. */
    val tvgId: String? = null,
    /** DRM from #KODIPROP: "clearkey|<kid hex>|<key hex>", "clearkey-url|<url>" or "widevine|<license url>". */
    val drm: String? = null,
    /** Extra request headers (#EXTHTTP), as JSON. */
    val headersJson: String? = null,
    /** Channel number for the remote: tvg-chno / Xtream "num", the user's own number, else its position. */
    val number: Int? = null,
) {
    val isYoutube get() = url.startsWith("youtube:")
    val key get() = url
}

/**
 * A playlist: from the admin (via the server) or added by the user on this device.
 * kind: m3u (a link in any form), xtream (server + username + password), m3u_text (channels as
 * text, e.g. the official free channels) or file (a playlist file copied into the app).
 */
data class LiveSource(
    val id: String,
    val name: String,
    val kind: String,
    val url: String? = null,
    val server: String? = null,
    val username: String? = null,
    val password: String? = null,
    val text: String? = null,
    val path: String? = null,
    val own: Boolean = false,
    /** "tv" (Live TV menu) or "radio" (Radio menu). */
    val media: String = "tv",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("media", media).put("url", url)
        .put("server", server).put("username", username).put("password", password).put("text", text)
        .put("path", path).put("own", own)

    companion object {
        fun fromJson(o: JSONObject, own: Boolean) = LiveSource(
            id = o.optString("id"), name = o.optString("name"), kind = o.optString("kind"),
            url = o.str("url"), server = o.str("server"), username = o.str("username"), password = o.str("password"),
            text = o.str("text"), path = o.str("path"), own = own, media = if (o.optString("media") == "radio") "radio" else "tv",
        )
        private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
    }
}

class LiveProblem(message: String) : Exception(message)

/** Live TV data: sources from the server, the user's own playlists, favourites and recent channels. */
object CxLive {
    private const val PREFS = "cx_live_tv_v1"
    private const val MAX_CHANNELS = 200_000
    private const val REFRESH_MS = 12 * 3600_000L
    const val TV_PAGE = "https://cloudxpanel.infopc.xyz/tv"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- sources ----------------
    data class Config(val allowOwn: Boolean, val admin: List<LiveSource>)

    /** Sources and the "own playlists" permission from the server; the last answer when offline. */
    fun config(context: Context, fresh: Boolean): Config {
        val p = prefs(context)
        if (fresh) {
            try {
                val r = CxWorkerActivation.signedPost(context, "/api/device/live")
                p.edit().putString("config", r.toString()).apply()
            } catch (e: Exception) {
                android.util.Log.w("CxLive", "Live TV sources not refreshed (" + e.javaClass.simpleName + ")")
            }
        }
        val o = p.getString("config", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Config(false, emptyList())
        val list = o.optJSONArray("sources") ?: JSONArray()
        return Config(o.optBoolean("allow_user_playlists", false),
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { LiveSource.fromJson(it, false) })
    }

    // ---------------- first screen ----------------
    val START_SCREENS = listOf("movie", "live", "radio")

    /** The menu the app opens on: the user's choice, else the admin's for the license ("movie" or "live"). */
    fun startScreen(context: Context): String {
        val p = prefs(context)
        p.getString("start_screen", null)?.let { if (it in START_SCREENS) return it }
        val admin = p.getString("config", null)?.let { runCatching { JSONObject(it).optString("start_screen") }.getOrNull() }
        return if (admin == "live") "live" else "movie"
    }

    fun setStartScreen(context: Context, screen: String) {
        if (screen in START_SCREENS) prefs(context).edit().putString("start_screen", screen).apply()
    }

    private fun startNames(context: Context) = listOf(context.getString(com.lagradost.cloudstream3.R.string.cx_films), context.getString(com.lagradost.cloudstream3.R.string.cx_live_tv), context.getString(com.lagradost.cloudstream3.R.string.cx_radio))
    fun startScreenName(context: Context) = startNames(context)[START_SCREENS.indexOf(startScreen(context)).coerceAtLeast(0)]

    /** Settings: choose the menu the app opens on. */
    fun chooseStartScreen(context: Context, done: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(com.lagradost.cloudstream3.R.string.cx_main_menu)
            .setSingleChoiceItems(startNames(context).toTypedArray(), START_SCREENS.indexOf(startScreen(context))) { dialog, which ->
                setStartScreen(context, START_SCREENS[which])
                dialog.dismiss(); done()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- Live TV subtitles ----------------
    private val SUBTITLE_CHOICES = listOf("auto", "en", "ms")
    private fun subtitleNames(context: Context) = listOf(context.getString(com.lagradost.cloudstream3.R.string.cx_auto), "English", context.getString(com.lagradost.cloudstream3.R.string.cx_malay))

    /** "auto" (Malay, else English, else the app's subtitle language), "en" or "ms". */
    fun subtitleChoice(context: Context): String =
        prefs(context).getString("subtitles", null)?.takeIf { it in SUBTITLE_CHOICES } ?: "auto"
    fun subtitleName(context: Context) = subtitleNames(context)[SUBTITLE_CHOICES.indexOf(subtitleChoice(context)).coerceAtLeast(0)]

    /** Language codes tried in turn for a Live TV channel's subtitles. */
    fun subtitleLanguages(context: Context, appLanguage: String): List<String> = when (subtitleChoice(context)) {
        "en" -> listOf("en", "eng")
        "ms" -> listOf("ms", "may", "msa")
        else -> listOf("ms", "may", "msa", "en", "eng", appLanguage)
    }.filter { it.isNotBlank() }.distinct()

    /** Settings: choose the default subtitles of Live TV. */
    fun chooseSubtitles(context: Context, done: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(com.lagradost.cloudstream3.R.string.cx_live_subs)
            .setSingleChoiceItems(subtitleNames(context).toTypedArray(), SUBTITLE_CHOICES.indexOf(subtitleChoice(context))) { dialog, which ->
                prefs(context).edit().putString("subtitles", SUBTITLE_CHOICES[which]).apply()
                dialog.dismiss(); done()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Refreshes the server's answer (sources, first screen) in the background, at most every 6 hours. */
    fun refreshConfigIfOld(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val now = System.currentTimeMillis()
        if (now - p.getLong("config_ms", 0L) < 6 * 3600_000L) return
        p.edit().putLong("config_ms", now).apply()
        Thread { try { config(app, true); CxXtream.register(app) } catch (_: Exception) {} }.start()
    }

    fun ownSources(context: Context): List<LiveSource> {
        val a = prefs(context).getString("own", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { LiveSource.fromJson(it, true) }
    }

    private fun saveOwn(context: Context, list: List<LiveSource>) {
        prefs(context).edit().putString("own", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun addOwn(context: Context, source: LiveSource) = saveOwn(context, ownSources(context).filter { it.id != source.id } + source)

    fun removeOwn(context: Context, id: String) {
        val removed = ownSources(context).firstOrNull { it.id == id }
        removed?.path?.let { File(it).delete() }
        cacheFile(context, id).delete()
        saveOwn(context, ownSources(context).filter { it.id != id })
    }

    // ---------------- favourites and recent ----------------
    private fun channelJson(c: LiveChannel) = JSONObject().put("name", c.name).put("url", c.url).put("logo", c.logo)
        .put("group", c.group).put("ua", c.userAgent).put("ref", c.referer).put("tid", c.tvgId).put("drm", c.drm).put("hdr", c.headersJson).put("num", c.number)
    private fun channelFrom(o: JSONObject) = LiveChannel(o.optString("name"), o.optString("url"),
        o.optString("logo").takeIf { it.isNotEmpty() && it != "null" }, o.optString("group"),
        o.optString("ua").takeIf { it.isNotEmpty() && it != "null" }, o.optString("ref").takeIf { it.isNotEmpty() && it != "null" },
        o.optString("tid").takeIf { it.isNotEmpty() && it != "null" },
        o.optString("drm").takeIf { it.isNotEmpty() && it != "null" }, o.optString("hdr").takeIf { it.isNotEmpty() && it != "null" },
        if (o.has("num") && !o.isNull("num")) o.optInt("num").takeIf { it > 0 } else null)
    private fun readList(context: Context, key: String): List<LiveChannel> {
        val a = prefs(context).getString(key, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(::channelFrom)
    }
    private fun writeList(context: Context, key: String, list: List<LiveChannel>) =
        prefs(context).edit().putString(key, JSONArray(list.map(::channelJson)).toString()).apply()

    // [scope]: "" for Live TV, "radio" for Radio (kept apart).
    fun favourites(context: Context, scope: String = "") = readList(context, "favourites$scope")
    fun favouriteKeys(context: Context, scope: String = "") = favourites(context, scope).map { it.key }.toHashSet()
    /** Adds or removes; returns true when it is now a favourite. */
    fun toggleFavourite(context: Context, c: LiveChannel, scope: String = ""): Boolean {
        val list = favourites(context, scope)
        val now = list.none { it.key == c.key }
        writeList(context, "favourites$scope", if (now) list + c else list.filter { it.key != c.key })
        return now
    }
    fun recent(context: Context, scope: String = "") = readList(context, "recent$scope")
    fun addRecent(context: Context, c: LiveChannel, scope: String = "") =
        writeList(context, "recent$scope", (listOf(c) + recent(context, scope).filter { it.key != c.key }).take(30))

    // ---------------- channels ----------------
    private fun cacheFile(context: Context, id: String) =
        File(File(context.filesDir, "cx_live").apply { mkdirs() }, sha(id) + ".json")
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** Channels of a source; cached for 12 hours (playlist links and Xtream are downloaded again after that). */
    /** Radio stations of Malaysia from Radio Browser (a public directory of stations' own streams). */
    val RADIO_MALAYSIA = LiveSource("radio-browser-my", "Radio Malaysia", "radio_browser", media = "radio")

    // ---------------- guide (EPG) ----------------
    /** The XMLTV guide of a playlist: #EXTM3U url-tvg / x-tvg-url (the first one), or Xtream's xmltv.php. */
    fun guideOf(text: String): String? {
        val head = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (!head.startsWith("#EXTM3U", true)) return null
        val attrs = attr.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        return (attrs["url-tvg"] ?: attrs["x-tvg-url"])?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("http") }
    }
    private fun xtreamGuide(server: String, username: String, password: String) =
        server.trim().trimEnd('/') + "/xmltv.php?username=" + Uri.encode(username) + "&password=" + Uri.encode(password)

    fun guideUrl(context: Context, source: LiveSource): String? = when (source.kind) {
        "xtream" -> xtreamGuide(source.server.orEmpty(), source.username.orEmpty(), source.password.orEmpty())
        "m3u_text" -> guideOf(source.text.orEmpty())
        "radio_browser" -> null
        else -> source.url?.let { xtreamFromLink(it) }?.let { xtreamGuide(it.server, it.username, it.password) }
            ?: prefs(context).getString("guide_" + source.id, null)
    }

    // ---------------- categories ----------------
    private val spaces = Regex("[\\s\\u00A0\\u200B\\uFEFF]+")
    /** "Malaysia", "MALAYSIA " and "MALAYSIA" are one category. */
    fun groupKey(g: String) = g.replace(spaces, " ").trim().uppercase()
    /** The categories of a channel: "MOVIES;HOT CHANNEL" is in both. */
    fun groupParts(g: String): List<String> = g.split(';', '|').map { it.replace(spaces, " ").trim() }.filter { it.isNotEmpty() }

    /** Set when Live TV shows a list: channel numbers for the remote, and the list being played. */
    @Volatile var numbered: List<LiveChannel> = emptyList()
    @Volatile var playing: List<LiveChannel> = emptyList()
    /** The categories of [numbered], in the user's order (for the player's channel list). */
    @Volatile var categories: List<String> = emptyList()
    fun byNumber(n: Int): LiveChannel? = numbered.firstOrNull { it.number == n }

    // ---------------- adult categories ----------------
    private val ADULT = Regex("(?i)(\\bxxx\\b|\\badults?\\b|18\\s*\\+|\\+\\s*18\\b|\\bporn|\\bsex|\\berotic|\\bnsfw\\b|\\bdewasa\\b|playboy|hustler|brazzers|redlight|\\bblue\\s*movie)")
    fun isAdult(c: LiveChannel) = ADULT.containsMatchIn(c.group) || ADULT.containsMatchIn(c.name)
    fun isAdultName(name: String) = ADULT.containsMatchIn(name)

    // ---------------- films inside playlists ----------------
    private val VOD_EXT = Regex("(?i)\\.(mp4|mkv|avi|mov|webm|m4v|wmv|flv)(\\?|$)")
    /** A film (video file), not a live channel: shown in the Films menu instead of Live TV. */
    fun isVod(c: LiveChannel): Boolean {
        val path = runCatching { Uri.parse(c.url).path.orEmpty() }.getOrDefault(c.url)
        return VOD_EXT.containsMatchIn(path) || path.contains("/movie/") || path.contains("/series/")
    }
    private val EPISODE = Regex("(?i)\\bS\\d{1,2}\\s*E\\d{1,3}\\b|\\bEp(isode)?\\.?\\s*\\d{1,3}\\b")
    /** A series episode (Xtream "/series/" link or a name like "S01E02"); otherwise a film. */
    fun isSeries(c: LiveChannel): Boolean {
        val path = runCatching { Uri.parse(c.url).path.orEmpty() }.getOrDefault(c.url)
        return c.url.startsWith("xtream-series:") || path.contains("/series/") || EPISODE.containsMatchIn(c.name)
    }

    // ---------------- Xtream films and series (Live TV "Movie Lists" / "Series Lists") ----------------
    private suspend fun xtreamArray(login: XtreamLogin, action: String): JSONArray {
        val base = login.server.trimEnd('/') + "/player_api.php?username=" + Uri.encode(login.username) + "&password=" + Uri.encode(login.password)
        return CxXtream.cached("live|" + login.server + "|" + login.username + "|" + action) {
            JSONArray(app.get("$base&action=$action", headers = mapOf("User-Agent" to PLAYER_AGENTS[1]!!), timeout = 120L).text)
        }
    }
    private suspend fun xtreamCategoryNames(login: XtreamLogin, action: String): Map<String, String> {
        val a = runCatching { xtreamArray(login, action) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associate { it.optString("category_id") to it.optString("category_name").trim() }
    }
    private fun play(login: XtreamLogin, kind: String, id: String, ext: String) =
        login.server.trimEnd('/') + "/" + kind + "/" + Uri.encode(login.username) + "/" + Uri.encode(login.password) + "/" + id + "." + ext

    suspend fun xtreamMovies(login: XtreamLogin): List<LiveChannel> {
        val names = xtreamCategoryNames(login, "get_vod_categories")
        val a = xtreamArray(login, "get_vod_streams")
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            LiveChannel(o.optString("name").trim().ifEmpty { "Filem " + o.optString("stream_id") },
                play(login, "movie", o.optString("stream_id"), o.optString("container_extension").ifBlank { "mp4" }),
                o.optString("stream_icon").takeIf { it.startsWith("http") }, names[o.optString("category_id")]?.takeIf { it.isNotBlank() } ?: "Lain-lain")
        }
    }

    suspend fun xtreamSeries(login: XtreamLogin): List<LiveChannel> {
        val names = xtreamCategoryNames(login, "get_series_categories")
        val a = xtreamArray(login, "get_series")
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            LiveChannel(o.optString("name").trim().ifEmpty { "Siri " + o.optString("series_id") }, "xtream-series:" + o.optString("series_id"),
                o.optString("cover").takeIf { it.startsWith("http") }, names[o.optString("category_id")]?.takeIf { it.isNotBlank() } ?: "Lain-lain")
        }
    }

    /** The episodes of an Xtream series, in order ("S1 E2 · title"). */
    suspend fun xtreamEpisodes(login: XtreamLogin, series: LiveChannel): List<LiveChannel> {
        val id = series.url.removePrefix("xtream-series:")
        val base = login.server.trimEnd('/') + "/player_api.php?username=" + Uri.encode(login.username) + "&password=" + Uri.encode(login.password)
        val o = JSONObject(app.get("$base&action=get_series_info&series_id=" + Uri.encode(id), headers = mapOf("User-Agent" to PLAYER_AGENTS[1]!!), timeout = 60L).text)
        val seasons = o.optJSONObject("episodes") ?: return emptyList()
        val out = ArrayList<Pair<Int, LiveChannel>>()
        for (season in seasons.keys()) {
            val list = seasons.optJSONArray(season) ?: continue
            for (i in 0 until list.length()) {
                val e = list.optJSONObject(i) ?: continue
                val sn = e.optString("season").toIntOrNull() ?: season.toIntOrNull() ?: 0
                val en = e.optString("episode_num").toIntOrNull() ?: (i + 1)
                val title = e.optString("title").trim()
                val label = "S" + sn + " E" + en + if (title.isNotEmpty() && !title.contains(series.name, true)) " · $title" else ""
                out += (sn * 10_000 + en) to LiveChannel(label, play(login, "series", e.optString("id"), e.optString("container_extension").ifBlank { "mp4" }),
                    e.optJSONObject("info")?.optString("movie_image")?.takeIf { it.startsWith("http") } ?: series.logo, series.name)
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    fun vodCount(context: Context, sourceId: String) = prefs(context).getInt("vod_count_$sourceId", 0)
    fun setVodCount(context: Context, sourceId: String, n: Int) {
        if (vodCount(context, sourceId) != n) prefs(context).edit().putInt("vod_count_$sourceId", n).apply()
    }

    private fun kidsProfile(context: Context) = com.lagradost.cloudstream3.ui.account.CxKids.isKids(context,
        com.lagradost.cloudstream3.utils.DataStoreHelper.selectedKeyIndex)

    /** Adult categories are hidden unless the user shows them; always hidden in a Kids profile. */
    fun adultAllowed(context: Context) = prefs(context).getBoolean("show_adult", false) && !kidsProfile(context)

    /** Settings: show or hide adult categories (never in a Kids profile). */
    fun toggleAdult(context: Context, done: () -> Unit) {
        if (kidsProfile(context)) {
            android.widget.Toast.makeText(context, com.lagradost.cloudstream3.R.string.cx_live_adult_kids, android.widget.Toast.LENGTH_LONG).show(); return
        }
        if (prefs(context).getBoolean("show_adult", false)) { prefs(context).edit().putBoolean("show_adult", false).apply(); done(); return }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(com.lagradost.cloudstream3.R.string.cx_live_adult_confirm_title)
            .setMessage(com.lagradost.cloudstream3.R.string.cx_live_adult_confirm_text)
            .setPositiveButton(com.lagradost.cloudstream3.R.string.cx_show) { _, _ -> prefs(context).edit().putBoolean("show_adult", true).apply(); done() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    fun adultShown(context: Context) = prefs(context).getBoolean("show_adult", false)
    fun adultName(context: Context) = context.getString(if (prefs(context).getBoolean("show_adult", false)) com.lagradost.cloudstream3.R.string.cx_live_adult_shown else com.lagradost.cloudstream3.R.string.cx_live_adult_hidden)

    // ---------------- default source ----------------
    /** The Live TV sources of this user: the admin's and, when allowed, the user's own. */
    fun tvSources(context: Context): List<LiveSource> {
        val config = config(context, false)
        return config.admin.filter { it.media != "radio" } + if (config.allowOwn) ownSources(context) else emptyList()
    }

    /** The source Live TV opens on: the user's choice, else the official one (RTM Klik), else the first. */
    fun defaultSourceId(context: Context, ids: List<String>): String? =
        prefs(context).getString("default_source", null)?.takeIf { it in ids } ?: "official".takeIf { it in ids } ?: ids.firstOrNull()

    /** Live TV plays in the CloudXtream Live TV player, or in the app's movie player ("classic"). */
    fun classicPlayer(context: Context) = prefs(context).getBoolean("classic_player", false)
    fun setClassicPlayer(context: Context, on: Boolean) = prefs(context).edit().putBoolean("classic_player", on).apply()

    fun setDefaultSource(context: Context, id: String) = prefs(context).edit().putString("default_source", id).apply()

    private fun shownTvSources(context: Context): List<LiveSource> {
        val hidden = CxEdits.hiddenSources(context)
        return tvSources(context).filter { it.id !in hidden }
    }

    fun defaultSourceName(context: Context): String {
        val list = shownTvSources(context)
        val id = defaultSourceId(context, list.map { it.id }) ?: return "—"
        return list.firstOrNull { it.id == id }?.let { CxEdits.nameOf(context, it) } ?: "—"
    }

    /** Settings: choose the source Live TV opens on. */
    fun chooseDefaultSource(context: Context, done: () -> Unit) {
        val list = shownTvSources(context)
        if (list.isEmpty()) { android.widget.Toast.makeText(context, com.lagradost.cloudstream3.R.string.cx_live_no_sources, android.widget.Toast.LENGTH_LONG).show(); return }
        val current = list.indexOfFirst { it.id == defaultSourceId(context, list.map { s -> s.id }) }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(com.lagradost.cloudstream3.R.string.cx_live_source)
            .setSingleChoiceItems(list.map { CxEdits.nameOf(context, it) }.toTypedArray(), current) { dialog, which ->
                prefs(context).edit().putString("default_source", list[which].id).apply()
                dialog.dismiss(); done()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    suspend fun channels(context: Context, source: LiveSource, fresh: Boolean): List<LiveChannel> {
        if (source.kind == "m3u_text") return parseM3u(source.text.orEmpty())
        if (source.kind == "file") {
            val text = source.path?.let { File(it).takeIf(File::isFile)?.readText() }.orEmpty()
            guideOf(text).let { g -> prefs(context).edit().apply { if (g == null) remove("guide_" + source.id) else putString("guide_" + source.id, g) }.apply() }
            return parseM3u(text)
        }
        val stamp = (source.url ?: "") + "|" + (source.server ?: "") + "|" + (source.username ?: "") + "|" + (source.password ?: "")
        val cache = cacheFile(context, source.id + "|" + stamp)
        if (!fresh && cache.isFile && System.currentTimeMillis() - cache.lastModified() < REFRESH_MS) {
            runCatching { return fromCache(cache.readText()) }
        }
        return try {
            val list = when (source.kind) {
                "xtream" -> xtream(source.server.orEmpty(), source.username.orEmpty(), source.password.orEmpty())
                "radio_browser" -> radioBrowser("MY")
                else -> fromLink(source.url.orEmpty()) { g ->
                    prefs(context).edit().apply { if (g == null) remove("guide_" + source.id) else putString("guide_" + source.id, g) }.apply()
                }
            }
            cache.writeText(JSONArray(list.map(::channelJson)).toString())
            list
        } catch (e: Exception) {
            // Offline or the provider is down: the last copy, if any.
            if (cache.isFile) runCatching { return fromCache(cache.readText()) }
            throw e
        }
    }

    private fun fromCache(text: String): List<LiveChannel> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(::channelFrom)
    }

    data class XtreamLogin(val server: String, val username: String, val password: String)

    /** A full Xtream link (get.php / player_api.php with username and password), else null. */
    fun xtreamFromLink(link: String): XtreamLogin? {
        val uri = runCatching { Uri.parse(link.trim()) }.getOrNull() ?: return null
        val user = uri.getQueryParameter("username") ?: return null
        val pass = uri.getQueryParameter("password") ?: return null
        val path = uri.path.orEmpty()
        if (!path.endsWith("get.php") && !path.endsWith("player_api.php") && !path.endsWith("xmltv.php")) return null
        val port = if (uri.port > 0) ":" + uri.port else ""
        return XtreamLogin(uri.scheme + "://" + uri.host + port, user, pass)
    }

    /**
     * A playlist link in any form: .m3u/.m3u8, a link without an extension, a shortlink (redirects
     * are followed) or a full Xtream link (handled through the Xtream API, also after a redirect).
     */
    suspend fun fromLink(link: String, guide: ((String?) -> Unit)? = null): List<LiveChannel> {
        val url = link.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw LiveProblem("Link mesti bermula dengan http:// atau https://")
        xtreamFromLink(url)?.let { return xtream(it.server, it.username, it.password) }
        // Some playlist hosts only answer IPTV players (e.g. OTT Navigator): tried in turn.
        var lastCode = 0
        var lastError: Exception? = null
        for (agent in PLAYER_AGENTS) {
            val response = try {
                if (agent == null) app.get(url, timeout = 90L) else app.get(url, headers = mapOf("User-Agent" to agent), timeout = 90L)
            } catch (e: Exception) { lastError = e; continue }
            val finalUrl = response.okhttpResponse.request.url.toString()
            if (finalUrl != url) xtreamFromLink(finalUrl)?.let { return xtream(it.server, it.username, it.password) }
            lastCode = response.code
            if (response.code !in 200..299) continue
            val body = response.text
            if (!body.trimStart().startsWith("#EXTM3U") && !body.contains("#EXTINF")) continue
            val list = parseM3u(body)
            if (list.isNotEmpty()) { guide?.invoke(guideOf(body)); return list }
        }
        if (lastCode == 0 && lastError != null) throw LiveProblem("Playlist tidak dapat dibuka (" + lastError.javaClass.simpleName + "). Semak link dan Internet.")
        if (lastCode !in 200..299 && lastCode != 0) throw LiveProblem("Playlist tidak dapat dibuka (HTTP $lastCode).")
        throw LiveProblem("Link ini bukan playlist (tiada saluran ditemui).")
    }

    /** Live channels through the Xtream Codes API. */
    suspend fun xtream(serverIn: String, username: String, password: String): List<LiveChannel> {
        val server = serverIn.trim().trimEnd('/')
        if (!server.startsWith("http://") && !server.startsWith("https://")) throw LiveProblem("Server Xtream mesti bermula dengan http:// atau https://")
        val base = server + "/player_api.php?username=" + Uri.encode(username) + "&password=" + Uri.encode(password)
        val info = try { JSONObject(app.get(base, timeout = 60L).text) } catch (e: Exception) {
            throw LiveProblem("Server Xtream tidak dapat dihubungi atau jawapannya tidak sah.")
        }
        val user = info.optJSONObject("user_info") ?: throw LiveProblem("Jawapan server Xtream tidak sah.")
        if (user.optString("auth") != "1" && user.optInt("auth", 0) != 1) throw LiveProblem("Username atau password Xtream salah.")
        val status = user.optString("status")
        if (status.isNotEmpty() && !status.equals("Active", true)) throw LiveProblem("Akaun Xtream tidak aktif ($status).")
        val formats = user.optJSONArray("allowed_output_formats")
        val ext = if (formats == null || (0 until formats.length()).any { formats.optString(it) == "m3u8" }) "m3u8" else "ts"
        val categories = runCatching { JSONArray(app.get("$base&action=get_live_categories", timeout = 60L).text) }.getOrElse { JSONArray() }
        val names = (0 until categories.length()).mapNotNull { categories.optJSONObject(it) }
            .associate { it.optString("category_id") to it.optString("category_name") }
        val streams = try { JSONArray(app.get("$base&action=get_live_streams", timeout = 120L).text) } catch (e: Exception) {
            throw LiveProblem("Senarai saluran Xtream tidak dapat dimuat turun.")
        }
        val user64 = Uri.encode(username); val pass64 = Uri.encode(password)
        return (0 until minOf(streams.length(), MAX_CHANNELS)).mapNotNull { streams.optJSONObject(it) }.map { s ->
            LiveChannel(
                name = s.optString("name").trim().ifEmpty { "Saluran " + s.optString("stream_id") },
                url = "$server/live/$user64/$pass64/${s.optString("stream_id")}.$ext",
                logo = s.optString("stream_icon").takeIf { it.startsWith("http") },
                group = names[s.optString("category_id")]?.takeIf { it.isNotBlank() } ?: "Lain-lain",
                tvgId = s.optString("epg_channel_id").takeIf { it.isNotBlank() && it != "null" },
                number = s.optInt("num", 0).takeIf { it > 0 },
            )
        }.also { if (it.isEmpty()) throw LiveProblem("Akaun Xtream ini tiada saluran live.") }
    }

    /** Working stations of a country, most voted first; grouped by state (or "Malaysia"). */
    suspend fun radioBrowser(country: String): List<LiveChannel> {
        val text = try {
            app.get("https://de1.api.radio-browser.info/json/stations/search?countrycode=$country&hidebroken=true&order=votes&reverse=true&limit=400",
                headers = mapOf("User-Agent" to "CloudXtream/1.0"), timeout = 60L).text
        } catch (e: Exception) {
            app.get("https://all.api.radio-browser.info/json/stations/search?countrycode=$country&hidebroken=true&order=votes&reverse=true&limit=400",
                headers = mapOf("User-Agent" to "CloudXtream/1.0"), timeout = 60L).text
        }
        val a = JSONArray(text)
        val seen = HashSet<String>()
        val stations = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        // An icon shared by several stations is a streaming host's logo, not the station's: left out.
        val iconUse = stations.groupingBy { it.optString("favicon").trim() }.eachCount()
        return stations.mapNotNull { o ->
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            val name = o.optString("name").trim()
            if (!url.startsWith("http") || name.isEmpty() || !seen.add(name.lowercase())) return@mapNotNull null
            val icon = o.optString("favicon").trim().takeIf { it.startsWith("http") && (iconUse[it] ?: 0) <= 2 }
            LiveChannel(name, url, icon, o.optString("state").trim().ifEmpty { "Malaysia" })
        }.also { if (it.isEmpty()) throw LiveProblem("Tiada stesen radio ditemui.") }
    }

    /** null = the app's own browser identity. */
    private val PLAYER_AGENTS = listOf(null, "OTT Navigator/1.7.1.3 (Linux;Android 12; en; 1x7m7bt)", "Kodi/20.2 (Linux; Android 12) Android/12.0.0 Sys_CPU/aarch64 App_Bitness/64 Version/20.2", "TiviMate/4.7.0 (Android 12)")

    private fun hexToB64Url(hex: String): String? {
        val h = hex.trim().lowercase()
        if (h.length % 2 != 0 || !h.all { it in "0123456789abcdef" }) return null
        val bytes = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    /** #KODIPROP license_type + license_key -> the compact drm string of [LiveChannel]. */
    private fun drmOf(type: String?, key: String?): String? {
        val t = type?.lowercase() ?: return null
        val k = key?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            t.contains("widevine") -> if (k.startsWith("http")) "widevine|" + k.substringBefore('|') else null
            t.contains("clearkey") -> when {
                k.startsWith("http") -> "clearkey-url|" + k.substringBefore('|')
                k.startsWith("{") -> runCatching {
                    val first = JSONObject(k).getJSONArray("keys").getJSONObject(0)
                    "clearkey-b64|" + first.getString("kid") + "|" + first.getString("k")
                }.getOrNull()
                else -> {
                    // kid:key (hex), several separated by commas: the first pair.
                    val pair = k.split(',').first().split(':')
                    if (pair.size == 2) "clearkey|" + pair[0].trim() + "|" + pair[1].trim() else null
                }
            }
            else -> null
        }
    }

    /** The DRM part of a link for the player: (uuid name, kid b64, key b64, license url). */
    fun drmParts(drm: String): List<String?>? {
        val parts = drm.split('|')
        return when (parts[0]) {
            "clearkey" -> listOf("clearkey", hexToB64Url(parts.getOrNull(1).orEmpty()) ?: return null, hexToB64Url(parts.getOrNull(2).orEmpty()) ?: return null, null)
            "clearkey-b64" -> listOf("clearkey", parts.getOrNull(1), parts.getOrNull(2), null)
            "clearkey-url" -> listOf("clearkey", null, null, parts.getOrNull(1))
            "widevine" -> listOf("widevine", null, null, parts.getOrNull(1))
            else -> null
        }
    }

    private val attr = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /** M3U / M3U8 playlist (IPTV format): #EXTINF attributes, #EXTGRP, #EXTVLCOPT and "url|Header=value". */
    fun parseM3u(text: String): List<LiveChannel> {
        val out = ArrayList<LiveChannel>()
        var name: String? = null; var logo: String? = null; var group: String? = null; var tid: String? = null
        var licType: String? = null; var licKey: String? = null; var http: String? = null
        var ua: String? = null; var ref: String? = null; var grp: String? = null; var chno: Int? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", true) -> {
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    // The name follows the first comma outside quotes.
                    var quoted = false; var comma = -1
                    for (i in line.indices) { val ch = line[i]; if (ch == '"') quoted = !quoted else if (ch == ',' && !quoted) { comma = i; break } }
                    name = (if (comma >= 0) line.substring(comma + 1) else "").trim().ifEmpty { attrs["tvg-name"] }
                    logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") }
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    tid = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                    chno = (attrs["tvg-chno"] ?: attrs["channel-number"])?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                }
                line.startsWith("#EXTGRP:", true) -> grp = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
                line.startsWith("#KODIPROP:", true) -> {
                    val prop = line.substringAfter(':')
                    val key = prop.substringBefore('=').trim().lowercase(); val value = prop.substringAfter('=', "").trim()
                    if (key.endsWith("license_type")) licType = value
                    if (key.endsWith("license_key")) licKey = value
                }
                line.startsWith("#EXTHTTP:", true) -> http = line.substringAfter(':').trim().takeIf { it.startsWith("{") }
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase(); val value = opt.substringAfter('=', "").trim()
                    if (key == "http-user-agent") ua = value
                    if (key == "http-referrer" || key == "http-referer") ref = value
                }
                line.startsWith("#") -> {}
                else -> {
                    var url = line
                    if (url.contains('|')) {
                        // Kodi style: url|User-Agent=...&Referer=...
                        val opts = url.substringAfter('|'); url = url.substringBefore('|')
                        for (pair in opts.split('&')) {
                            val k = pair.substringBefore('=').trim().lowercase(); val v = Uri.decode(pair.substringAfter('=', "")).trim()
                            if (k == "user-agent") ua = v
                            if (k == "referer" || k == "referrer") ref = v
                        }
                    }
                    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("youtube:")) {
                        out += LiveChannel(name?.takeIf { it.isNotBlank() } ?: "Saluran ${out.size + 1}", url, logo,
                            group ?: grp ?: "Lain-lain", ua, ref, tid, drmOf(licType, licKey), http, chno)
                        if (out.size >= MAX_CHANNELS) break
                    }
                    name = null; logo = null; group = null; ua = null; ref = null; tid = null; licType = null; licKey = null; http = null; chno = null
                }
            }
        }
        return out
    }

    // ---------------- phone to TV ----------------
    /** Asks the server for a code; returns it (6 characters). */
    fun startPairing(context: Context): String =
        CxWorkerActivation.signedPost(context, "/api/device/live/pair").getString("code")

    /** "waiting", "expired", or the playlist sent from the phone. */
    fun pollPairing(context: Context, code: String): Pair<String, JSONObject?> {
        val r = CxWorkerActivation.signedPost(context, "/api/device/live/pair/poll", JSONObject().put("code", code))
        return r.optString("status") to r.optJSONObject("playlist")
    }

    fun newId() = "own-" + java.util.UUID.randomUUID().toString()
}

/**
 * Logos for channels whose playlist has none: the public iptv-org channel database (downloaded at
 * most once a week). Matched by tvg-id (e.g. TV1.my) or by the channel name (Malaysian first).
 */
/** Now and next programme of channels, from the playlist's XMLTV guide; kept 6 hours. */
object CxEpg {
    data class Prog(val start: Long, val stop: Long, val title: String)
    private const val KEEP_MS = 6 * 3600_000L
    private const val AHEAD_MS = 12 * 3600_000L
    private val memory = HashMap<String, Pair<Long, Map<String, List<Prog>>>>()

    fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    suspend fun load(context: Context, url: String): Map<String, List<Prog>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(memory) { memory[url]?.takeIf { now - it.first < KEEP_MS }?.let { return@withContext it.second } }
        val file = File(File(context.filesDir, "cx_live").apply { mkdirs() }, "epg_" + sha(url) + ".json")
        var map: Map<String, List<Prog>>? = null
        if (file.isFile && now - file.lastModified() < KEEP_MS) map = runCatching { fromJson(file.readText()) }.getOrNull()
        if (map == null) {
            map = try { download(url) } catch (e: Exception) {
                android.util.Log.w("CxEpg", "Guide not loaded (" + e.javaClass.simpleName + ")"); null
            }
            if (map != null) runCatching { file.writeText(toJson(map)) }
            else if (file.isFile) map = runCatching { fromJson(file.readText()) }.getOrNull()
        }
        val out = map ?: emptyMap()
        synchronized(memory) { memory[url] = now to out }
        out
    }

    /** Every programme of a channel in the guide (now and the next hours). */
    fun programmes(guide: Map<String, List<Prog>>, c: LiveChannel): List<Prog> =
        c.tvgId?.let { guide[key(it)] } ?: guide["n:" + key(c.name)] ?: emptyList()

    /** The programme on now and the next one. */
    fun nowNext(guide: Map<String, List<Prog>>, c: LiveChannel): Pair<Prog?, Prog?> {
        if (guide.isEmpty()) return null to null
        val list = c.tvgId?.let { guide[key(it)] } ?: guide["n:" + key(c.name)] ?: return null to null
        val now = System.currentTimeMillis()
        val i = list.indexOfFirst { it.start <= now && now < it.stop }
        return if (i >= 0) list[i] to list.getOrNull(i + 1) else null to list.firstOrNull { it.start > now }
    }

    private val TIME = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
    private fun time(v: String?): Long {
        val t = v?.trim() ?: return 0L
        return try {
            synchronized(TIME) { TIME.parse(if (t.length > 14) t.substring(0, 14) + " " + t.substring(14).trim().ifEmpty { "+0000" } else "$t +0000")?.time ?: 0L }
        } catch (e: Exception) { 0L }
    }

    private fun download(url: String): Map<String, List<Prog>> {
        val request = okhttp3.Request.Builder().url(url).header("User-Agent", "OTT Navigator/1.7.1.3 (Linux;Android 12; en; 1x7m7bt)").build()
        val client = app.baseClient.newBuilder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw LiveProblem("EPG HTTP " + r.code)
            val raw = java.io.BufferedInputStream(r.body?.byteStream() ?: throw LiveProblem("EPG kosong"))
            raw.mark(4)
            val gz = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            val input = if (gz) java.util.zip.GZIPInputStream(raw) else raw
            val parser = android.util.Xml.newPullParser()
            parser.setInput(input, null)
            val now = System.currentTimeMillis(); val until = now + AHEAD_MS
            val names = HashMap<String, MutableList<String>>()
            val progs = HashMap<String, MutableList<Prog>>()
            var channel: String? = null
            var pChannel: String? = null; var pStart = 0L; var pStop = 0L; var title: String? = null
            var count = 0
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && count < 300_000) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> channel = parser.getAttributeValue(null, "id")
                        "display-name" -> channel?.let { id -> names.getOrPut(id) { ArrayList() }.add(parser.nextText()) }
                        "programme" -> {
                            pChannel = parser.getAttributeValue(null, "channel"); title = null
                            pStart = time(parser.getAttributeValue(null, "start")); pStop = time(parser.getAttributeValue(null, "stop"))
                        }
                        "title" -> if (pChannel != null && title == null) title = parser.nextText().trim()
                    }
                } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG) {
                    when (parser.name) {
                        "channel" -> channel = null
                        "programme" -> {
                            val id = pChannel; val t = title
                            if (id != null && !t.isNullOrEmpty() && pStop > now && pStart < until) {
                                progs.getOrPut(id) { ArrayList() }.add(Prog(pStart, pStop, t)); count++
                            }
                            pChannel = null
                        }
                    }
                }
                event = parser.next()
            }
            val out = HashMap<String, List<Prog>>()
            for ((id, list) in progs) {
                val sorted = list.sortedBy { it.start }
                out[key(id)] = sorted
                names[id]?.forEach { n -> out.putIfAbsent("n:" + key(n), sorted) }
            }
            return out
        }
    }

    private fun toJson(map: Map<String, List<Prog>>): String {
        val o = JSONObject()
        for ((k, list) in map) o.put(k, JSONArray(list.map { JSONArray().put(it.start).put(it.stop).put(it.title) }))
        return o.toString()
    }
    private fun fromJson(text: String): Map<String, List<Prog>> {
        val o = JSONObject(text); val out = HashMap<String, List<Prog>>()
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            out[k] = (0 until a.length()).map { val p = a.getJSONArray(it); Prog(p.getLong(0), p.getLong(1), p.getString(2)) }
        }
        return out
    }
}

/** A crash that put the app in safe mode is sent once to CloudXtream Control (admin panel and Telegram). */
object CxCrash {
    fun report(context: Context, error: String?) {
        val text = error?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val app = context.applicationContext
        val p = app.getSharedPreferences("cx_crash_reports", Context.MODE_PRIVATE)
        val id = text.hashCode().toString()
        if (p.getString("last", null) == id) return
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val at = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("at com.lagradost") || it.startsWith("at com.leque") }.orEmpty()
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val sent = CxWorkerActivation.reportProblem(app, "App crash (safe mode)", first.take(200),
                (at + "\n" + text).take(3000), version, kind = "crash")
            if (sent) p.edit().putString("last", id).apply()
        }
    }
}

object CxLogos {
    private const val URL = "https://raw.githubusercontent.com/iptv-org/database/master/data/logos.csv"
    private const val WEEK_MS = 7 * 24 * 3600_000L
    @Volatile private var map: Map<String, String>? = null

    private val noise = Regex("""\(.*?\)|\[.*?\]|\b(fhd|uhd|hd|sd|4k|hevc|h265|backup|live)\b""")
    fun key(name: String) = name.lowercase().replace(noise, "").replace(Regex("[^a-z0-9]"), "")

    val loaded get() = map != null

    suspend fun load(context: Context) {
        if (map != null) return
        val file = File(File(context.filesDir, "cx_live").apply { mkdirs() }, "logos.csv")
        if (!file.isFile || System.currentTimeMillis() - file.lastModified() > WEEK_MS) {
            runCatching { app.get(URL, timeout = 90L).text }.getOrNull()?.takeIf { it.startsWith("channel,") }?.let { file.writeText(it) }
        }
        if (!file.isFile) return
        val out = HashMap<String, String>(80_000)
        val local = HashSet<String>()
        file.forEachLine { line ->
            if (line.startsWith("channel,")) return@forEachLine
            val id = line.substringBefore(',').trim('"')
            val rest = line.substringAfter(',')
            val feed = rest.substringBefore(',').trim('"')
            val url = line.substringAfterLast(',').trim('"')
            if (id.isEmpty() || !url.startsWith("https://") || line.contains("vertical")) return@forEachLine
            val full = id.lowercase()
            if (feed.isEmpty() || !out.containsKey(full)) out[full] = url
            val base = "n:" + key(id.substringBefore('.'))
            val my = full.endsWith(".my")
            if (!out.containsKey(base) || (my && base !in local)) { out[base] = url; if (my) local += base }
        }
        map = out
    }

    fun find(c: LiveChannel): String? {
        val m = map ?: return null
        c.tvgId?.lowercase()?.substringBefore('@')?.let { id -> m[id]?.let { return it } }
        return m["n:" + key(c.name)]
    }
}

/**
 * Radio player (V5.0.24): plays a station without opening the full-screen player, like a music app,
 * and keeps playing while the user moves to other menus. One station at a time.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object CxRadio {
    enum class State { LOADING, PLAYING, PAUSED, ERROR }

    @Volatile var current: LiveChannel? = null
        private set
    @Volatile var state = State.PAUSED
        private set
    /** Called on the main thread when the station or state changes (the Radio screen's mini player). */
    var listener: (() -> Unit)? = null

    private var player: androidx.media3.exoplayer.ExoPlayer? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun changed() { main.post { listener?.invoke() } }

    private fun build(context: Context) = androidx.media3.exoplayer.ExoPlayer.Builder(context)
        .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build().apply {
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) state = State.PLAYING
                    else if (state == State.PLAYING) state = State.PAUSED
                    changed()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == androidx.media3.common.Player.STATE_BUFFERING && playWhenReady) { state = State.LOADING; changed() }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    state = State.ERROR; changed()
                }
            })
        }

    /** Must be called on the main thread. */
    fun play(context: Context, c: LiveChannel) {
        val p = player ?: build(context.applicationContext).also { player = it }
        current = c; state = State.LOADING
        val headers = HashMap<String, String>()
        c.referer?.let { headers["Referer"] = it }
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(c.userAgent ?: "CloudXtream/1.0").setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000).setDefaultRequestProperties(headers)
        val item = androidx.media3.common.MediaItem.Builder().setUri(c.url)
            .apply { if (c.url.contains(".m3u8")) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8) }.build()
        p.setMediaSource(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http).createMediaSource(item))
        p.prepare()
        p.playWhenReady = true
        changed()
    }

    fun toggle() {
        val p = player ?: return
        when {
            state == State.ERROR -> { p.prepare(); p.playWhenReady = true; state = State.LOADING }
            p.isPlaying || p.playWhenReady -> { p.playWhenReady = false; state = State.PAUSED }
            else -> { p.playWhenReady = true; state = State.LOADING }
        }
        changed()
    }

    /** Stops and frees the player (also when a TV channel or video starts). */
    fun stop() {
        player?.let { it.stop(); it.release() }
        player = null
        current = null; state = State.PAUSED
        changed()
    }
}
