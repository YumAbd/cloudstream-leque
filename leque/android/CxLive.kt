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
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("url", url)
        .put("server", server).put("username", username).put("password", password).put("text", text)
        .put("path", path).put("own", own)

    companion object {
        fun fromJson(o: JSONObject, own: Boolean) = LiveSource(
            id = o.optString("id"), name = o.optString("name"), kind = o.optString("kind"),
            url = o.str("url"), server = o.str("server"), username = o.str("username"), password = o.str("password"),
            text = o.str("text"), path = o.str("path"), own = own,
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
        .put("group", c.group).put("ua", c.userAgent).put("ref", c.referer)
    private fun channelFrom(o: JSONObject) = LiveChannel(o.optString("name"), o.optString("url"),
        o.optString("logo").takeIf { it.isNotEmpty() && it != "null" }, o.optString("group"),
        o.optString("ua").takeIf { it.isNotEmpty() && it != "null" }, o.optString("ref").takeIf { it.isNotEmpty() && it != "null" })
    private fun readList(context: Context, key: String): List<LiveChannel> {
        val a = prefs(context).getString(key, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(::channelFrom)
    }
    private fun writeList(context: Context, key: String, list: List<LiveChannel>) =
        prefs(context).edit().putString(key, JSONArray(list.map(::channelJson)).toString()).apply()

    fun favourites(context: Context) = readList(context, "favourites")
    fun isFavourite(context: Context, c: LiveChannel) = favourites(context).any { it.key == c.key }
    /** Adds or removes; returns true when it is now a favourite. */
    fun toggleFavourite(context: Context, c: LiveChannel): Boolean {
        val list = favourites(context)
        val now = list.none { it.key == c.key }
        writeList(context, "favourites", if (now) list + c else list.filter { it.key != c.key })
        return now
    }
    fun recent(context: Context) = readList(context, "recent")
    fun addRecent(context: Context, c: LiveChannel) =
        writeList(context, "recent", (listOf(c) + recent(context).filter { it.key != c.key }).take(30))

    // ---------------- channels ----------------
    private fun cacheFile(context: Context, id: String) =
        File(File(context.filesDir, "cx_live").apply { mkdirs() }, sha(id) + ".json")
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** Channels of a source; cached for 12 hours (playlist links and Xtream are downloaded again after that). */
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
        val response = try { app.get(url, timeout = 90L) } catch (e: Exception) {
            throw LiveProblem("Playlist tidak dapat dibuka (" + e.javaClass.simpleName + "). Semak link dan Internet.")
        }
        val finalUrl = response.okhttpResponse.request.url.toString()
        if (finalUrl != url) xtreamFromLink(finalUrl)?.let { return xtream(it.server, it.username, it.password) }
        if (response.code !in 200..299) throw LiveProblem("Playlist tidak dapat dibuka (HTTP " + response.code + ").")
        val body = response.text
        if (!body.trimStart().startsWith("#EXTM3U") && !body.contains("#EXTINF"))
            throw LiveProblem("Link ini bukan playlist (tiada saluran ditemui).")
        return parseM3u(body).also { if (it.isEmpty()) throw LiveProblem("Playlist ini kosong.") }
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
            )
        }.also { if (it.isEmpty()) throw LiveProblem("Akaun Xtream ini tiada saluran live.") }
    }

    private val attr = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /** M3U / M3U8 playlist (IPTV format): #EXTINF attributes, #EXTGRP, #EXTVLCOPT and "url|Header=value". */
    fun parseM3u(text: String): List<LiveChannel> {
        val out = ArrayList<LiveChannel>()
        var name: String? = null; var logo: String? = null; var group: String? = null
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
                }
                line.startsWith("#EXTGRP:", true) -> grp = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
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
                            group ?: grp ?: "Lain-lain", ua, ref)
                        if (out.size >= MAX_CHANNELS) break
                    }
                    name = null; logo = null; group = null; ua = null; ref = null
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
