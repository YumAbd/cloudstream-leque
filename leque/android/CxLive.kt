package com.lagradost.cloudstream3.ui.live

import android.content.Context
import android.net.Uri
import com.lagradost.cloudstream3.activation.CxWorkerActivation
import com.lagradost.cloudstream3.app
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

    /** Refreshes the server's answer (sources, first screen) in the background, at most every 6 hours. */
    fun refreshConfigIfOld(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val now = System.currentTimeMillis()
        if (now - p.getLong("config_ms", 0L) < 6 * 3600_000L) return
        p.edit().putLong("config_ms", now).apply()
        Thread { try { config(app, true) } catch (_: Exception) {} }.start()
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
        .put("group", c.group).put("ua", c.userAgent).put("ref", c.referer).put("tid", c.tvgId).put("drm", c.drm).put("hdr", c.headersJson)
    private fun channelFrom(o: JSONObject) = LiveChannel(o.optString("name"), o.optString("url"),
        o.optString("logo").takeIf { it.isNotEmpty() && it != "null" }, o.optString("group"),
        o.optString("ua").takeIf { it.isNotEmpty() && it != "null" }, o.optString("ref").takeIf { it.isNotEmpty() && it != "null" },
        o.optString("tid").takeIf { it.isNotEmpty() && it != "null" },
        o.optString("drm").takeIf { it.isNotEmpty() && it != "null" }, o.optString("hdr").takeIf { it.isNotEmpty() && it != "null" })
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

    suspend fun channels(context: Context, source: LiveSource, fresh: Boolean): List<LiveChannel> {
        if (source.kind == "m3u_text") return parseM3u(source.text.orEmpty())
        if (source.kind == "file") return parseM3u(source.path?.let { File(it).takeIf(File::isFile)?.readText() }.orEmpty())
        val stamp = (source.url ?: "") + "|" + (source.server ?: "") + "|" + (source.username ?: "") + "|" + (source.password ?: "")
        val cache = cacheFile(context, source.id + "|" + stamp)
        if (!fresh && cache.isFile && System.currentTimeMillis() - cache.lastModified() < REFRESH_MS) {
            runCatching { return fromCache(cache.readText()) }
        }
        return try {
            val list = when (source.kind) {
                "xtream" -> xtream(source.server.orEmpty(), source.username.orEmpty(), source.password.orEmpty())
                "radio_browser" -> radioBrowser("MY")
                else -> fromLink(source.url.orEmpty())
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
    suspend fun fromLink(link: String): List<LiveChannel> {
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
            if (list.isNotEmpty()) return list
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
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            val name = o.optString("name").trim()
            if (!url.startsWith("http") || name.isEmpty() || !seen.add(name.lowercase())) return@mapNotNull null
            LiveChannel(name, url, o.optString("favicon").takeIf { it.startsWith("http") },
                o.optString("state").trim().ifEmpty { "Malaysia" })
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
        var ua: String? = null; var ref: String? = null; var grp: String? = null
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
                            group ?: grp ?: "Lain-lain", ua, ref, tid, drmOf(licType, licKey), http)
                        if (out.size >= MAX_CHANNELS) break
                    }
                    name = null; logo = null; group = null; ua = null; ref = null; tid = null; licType = null; licKey = null; http = null
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
