package com.lagradost.cloudstream3.ui.live

import android.content.Context
import android.net.Uri
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Movies and series of an Xtream Codes account (from the admin, or the user's own when allowed),
 * shown as a provider in the Films menu: "Xtream · <playlist name>".
 */
class CxXtreamProvider(private val label: String, server: String, private val user: String, private val pass: String) : MainAPI() {
    private val server = server.trim().trimEnd('/')
    override var name = "Xtream · $label"
    override var mainUrl = this.server
    override var lang = "ms"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val instantLinkLoading = true

    private val api get() = "$server/player_api.php?username=" + Uri.encode(user) + "&password=" + Uri.encode(pass)
    private fun play(kind: String, id: String, ext: String) = "$server/$kind/" + Uri.encode(user) + "/" + Uri.encode(pass) + "/$id.$ext"

    override val mainPage: List<MainPageData>
        get() = listOf(MainPageData("Filem terbaru", "vod:", false), MainPageData("Siri terbaru", "series:", false)) +
            CxXtream.cachedCategories(name).map { (data, title) -> MainPageData(title, data, false) }

    private val PAGE = 60

    private suspend fun array(action: String): JSONArray = CxXtream.cached("$name|$action") {
        JSONArray(app.get("$api&action=$action", timeout = 120L).text)
    }

    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun movieOf(o: JSONObject): SearchResponse = newMovieSearchResponse(o.optString("name").trim(),
        "xtream://vod/" + o.optString("stream_id") + "/" + o.optString("container_extension").ifEmpty { "mp4" }, TvType.Movie, fix = false) {
        posterUrl = o.optString("stream_icon").takeIf { it.startsWith("http") }
    }

    private fun seriesOf(o: JSONObject): SearchResponse = newTvSeriesSearchResponse(o.optString("name").trim(),
        "xtream://series/" + o.optString("series_id"), TvType.TvSeries, fix = false) {
        posterUrl = o.optString("cover").takeIf { it.startsWith("http") }
    }

    private fun allowed(context: Context?, categoryName: String?) =
        context == null || CxLive.adultAllowed(context) || !CxLive.isAdultName(categoryName.orEmpty())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context
        val kind = request.data.substringBefore(':'); val category = request.data.substringAfter(':')
        // Categories are fetched with the first page, then listed on the next opening.
        if (page == 1 && category.isEmpty() && kind == "vod") runCatching {
            val vod = array("get_vod_categories").objects().filter { allowed(ctx, it.optString("category_name")) }
                .take(15).map { "vod:" + it.optString("category_id") to it.optString("category_name").trim() }
            val ser = array("get_series_categories").objects().filter { allowed(ctx, it.optString("category_name")) }
                .take(15).map { "series:" + it.optString("category_id") to "Siri · " + it.optString("category_name").trim() }
            CxXtream.saveCategories(name, vod + ser)
        }
        val adultCats = if (ctx != null && CxLive.adultAllowed(ctx)) emptySet() else
            array(if (kind == "vod") "get_vod_categories" else "get_series_categories").objects()
                .filter { CxLive.isAdultName(it.optString("category_name")) }.map { it.optString("category_id") }.toSet()
        val action = (if (kind == "vod") "get_vod_streams" else "get_series") + if (category.isNotEmpty()) "&category_id=" + Uri.encode(category) else ""
        val items = array(action).objects().filter { it.optString("category_id") !in adultCats }
            .let { list -> if (category.isEmpty()) list.sortedByDescending { it.optString(if (kind == "vod") "added" else "last_modified").toLongOrNull() ?: 0L } else list }
        val slice = items.drop((page - 1) * PAGE).take(PAGE)
        return newHomePageResponse(HomePageList(request.name, slice.map { if (kind == "vod") movieOf(it) else seriesOf(it) }), hasNext = items.size > page * PAGE)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context
        val hidden = if (ctx != null && CxLive.adultAllowed(ctx)) emptySet() else
            (array("get_vod_categories").objects() + array("get_series_categories").objects())
                .filter { CxLive.isAdultName(it.optString("category_name")) }.map { it.optString("category_id") }.toSet()
        val movies = runCatching { array("get_vod_streams").objects() }.getOrDefault(emptyList())
            .filter { it.optString("category_id") !in hidden && it.optString("name").lowercase().contains(q) }.take(40).map(::movieOf)
        val series = runCatching { array("get_series").objects() }.getOrDefault(emptyList())
            .filter { it.optString("category_id") !in hidden && it.optString("name").lowercase().contains(q) }.take(40).map(::seriesOf)
        return movies + series
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.removePrefix("xtream://").split('/')
        return when (parts.getOrNull(0)) {
            "vod" -> {
                val id = parts.getOrNull(1) ?: return null
                val ext = parts.getOrNull(2) ?: "mp4"
                val o = runCatching { JSONObject(app.get("$api&action=get_vod_info&vod_id=" + Uri.encode(id), timeout = 60L).text) }.getOrNull()
                val info = o?.optJSONObject("info"); val data = o?.optJSONObject("movie_data")
                val title = data?.optString("name")?.takeIf { it.isNotBlank() } ?: info?.optString("name")?.takeIf { it.isNotBlank() } ?: "Filem"
                newMovieLoadResponse(title, url, TvType.Movie, play("movie", id, data?.optString("container_extension")?.takeIf { it.isNotBlank() } ?: ext)) {
                    posterUrl = info?.optString("movie_image")?.takeIf { it.startsWith("http") } ?: info?.optString("cover_big")?.takeIf { it.startsWith("http") }
                    plot = info?.optString("plot")?.takeIf { it.isNotBlank() && it != "null" }
                    year = info?.optString("releasedate")?.take(4)?.toIntOrNull()
                    tags = info?.optString("genre")?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    duration = info?.optString("duration_secs")?.toIntOrNull()?.let { it / 60 }
                }
            }
            "series" -> {
                val id = parts.getOrNull(1) ?: return null
                val o = JSONObject(app.get("$api&action=get_series_info&series_id=" + Uri.encode(id), timeout = 60L).text)
                val info = o.optJSONObject("info")
                val seasons = o.optJSONObject("episodes")
                val episodes = ArrayList<com.lagradost.cloudstream3.Episode>()
                seasons?.keys()?.forEach { season ->
                    val list = seasons.optJSONArray(season) ?: return@forEach
                    for (e in list.objects()) {
                        val epInfo = e.optJSONObject("info")
                        episodes += newEpisode(play("series", e.optString("id"), e.optString("container_extension").ifEmpty { "mp4" }), {
                            this.name = e.optString("title").takeIf { it.isNotBlank() }
                            this.season = e.optString("season").toIntOrNull() ?: season.toIntOrNull()
                            this.episode = e.optString("episode_num").toIntOrNull()
                            this.posterUrl = epInfo?.optString("movie_image")?.takeIf { it.startsWith("http") }
                            this.description = epInfo?.optString("plot")?.takeIf { it.isNotBlank() && it != "null" }
                        }, fix = false)
                    }
                }
                newTvSeriesLoadResponse(info?.optString("name")?.takeIf { it.isNotBlank() } ?: "Siri", url, TvType.TvSeries,
                    episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))) {
                    posterUrl = info?.optString("cover")?.takeIf { it.startsWith("http") }
                    plot = info?.optString("plot")?.takeIf { it.isNotBlank() && it != "null" }
                    year = info?.optString("releaseDate")?.take(4)?.toIntOrNull()
                    tags = info?.optString("genre")?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() }
                }
            }
            else -> null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (!data.startsWith("http")) return false
        callback(newExtractorLink(name, label, data))
        return true
    }
}

/** Registers one Films provider per Xtream account the user can see; caches the API answers. */
object CxXtream {
    private const val PREFS = "cx_xtream_v1"
    private const val KEEP_MS = 6 * 3600_000L
    private val registered = ArrayList<MainAPI>()
    private val memory = HashMap<String, Pair<Long, JSONArray>>()
    @Volatile private var appContext: Context? = null

    suspend fun cached(key: String, fetch: suspend () -> JSONArray): JSONArray {
        val now = System.currentTimeMillis()
        synchronized(memory) { memory[key]?.takeIf { now - it.first < KEEP_MS }?.let { return it.second } }
        val a = fetch()
        synchronized(memory) { memory[key] = now to a }
        return a
    }

    fun cachedCategories(provider: String): List<Pair<String, String>> {
        val text = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("cats|$provider", null) ?: return emptyList()
        return runCatching { JSONArray(text).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } } }.getOrDefault(emptyList())
    }

    fun saveCategories(provider: String, list: List<Pair<String, String>>) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("cats|$provider", JSONArray(list.map { JSONArray().put(it.first).put(it.second) }).toString())?.apply()
    }

    /** From the cached Live TV answer of the server (no network): admin Xtream sources, and the user's own when allowed. */
    @Synchronized
    fun register(context: Context) {
        appContext = context.applicationContext
        val config = runCatching { CxLive.config(context, false) }.getOrNull() ?: return
        val own = if (config.allowOwn) CxLive.ownSources(context) else emptyList()
        val logins = (config.admin.filter { it.media == "tv" } + own).mapNotNull { s ->
            when {
                s.kind == "xtream" && !s.server.isNullOrBlank() && !s.username.isNullOrBlank() -> Triple(s.name, CxLive.XtreamLogin(s.server, s.username, s.password.orEmpty()), s)
                s.kind == "m3u" && s.url != null -> CxLive.xtreamFromLink(s.url)?.let { Triple(s.name, it, s) }
                else -> null
            }
        }.distinctBy { it.second.server.trimEnd('/') + "|" + it.second.username }
        val wanted = logins.map { (label, login, _) -> CxXtreamProvider(label, login.server, login.username, login.password) }
        if (wanted.map { it.name + it.mainUrl } == registered.map { it.name + it.mainUrl }) return
        for (old in registered) { APIHolder.allProviders.remove(old); APIHolder.removePluginMapping(old) }
        registered.clear()
        for (api in wanted) { APIHolder.allProviders.add(api); APIHolder.addPluginMapping(api); registered += api }
    }
}
