package com.lagradost.cloudstream3.ui.live

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.activation.CxWorkerActivation
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.VideoGenerator
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Channels of a list for the CloudStream player: CH+/CH- (and N/B) move to the next/previous one.
 */
class CxLiveGenerator(private val channels: List<LiveChannel>, title: String, type: TvType = TvType.Live) : VideoGenerator<ExtractorUri>(
    channels.map { ExtractorUri(uri = Uri.parse(it.url), name = it.name, headerName = title, tvType = type) }
) {
    override val hasCache = false
    override val canSkipLoading = false
    override fun getId(index: Int): Int? = channels.getOrNull(index)?.url?.hashCode()

    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, com.lagradost.cloudstream3.ui.player.ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean
    ): Boolean {
        val c = channels.getOrNull(offset) ?: return false
        val path = Uri.parse(c.url).path.orEmpty().lowercase()
        val type = when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") || c.url.contains(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        val extra = HashMap<String, String>()
        c.userAgent?.let { extra["User-Agent"] = it }
        c.headersJson?.let { json -> runCatching { val o = org.json.JSONObject(json); o.keys().forEach { k -> extra[k] = o.getString(k) } } }
        val drm = c.drm?.let { CxLive.drmParts(it) }
        val link = if (drm != null) {
            // Encrypted DASH/HLS (#KODIPROP): ClearKey keys or a Widevine/ClearKey license server.
            newDrmExtractorLink("Live TV", c.name, c.url, if (type == ExtractorLinkType.VIDEO) ExtractorLinkType.DASH else type,
                if (drm[0] == "widevine") com.lagradost.cloudstream3.utils.WIDEVINE_UUID else com.lagradost.cloudstream3.utils.CLEARKEY_UUID) {
                referer = c.referer.orEmpty(); headers = extra
                kid = drm[1]; key = drm[2]; licenseUrl = drm[3]
                if (drm[1] != null) kty = "oct"
            }
        } else newExtractorLink("Live TV", c.name, c.url, type) {
            referer = c.referer.orEmpty(); headers = extra
        }
        if (link.type in sourceTypes) callback(link to null)
        return true
    }
}


/**
 * Live TV and Radio menus (V5.0.24). Live TV: the admin's playlists (with the official free
 * channels) and the user's own playlists where allowed. Radio: Malaysian stations (Radio Browser)
 * and the admin's radio playlists. Groups, search, favourites (long press) and recently played.
 * Works with touch, the remote (D-pad), a mouse and a keyboard; respects the status bar and notches.
 */
class CxLiveFragment : Fragment() {
    private val bg = Color.rgb(10, 19, 32)
    private val surface = Color.rgb(20, 34, 53)
    private val surfaceHigh = Color.rgb(28, 46, 69)
    private val accent = Color.rgb(0, 204, 239)
    private val fg = Color.rgb(232, 241, 248)
    private val muted = Color.rgb(143, 166, 186)
    private val onAccent = Color.rgb(4, 20, 30)

    private val radio get() = arguments?.getString("mode") == "radio"
    private val scope get() = if (radio) "radio" else ""
    private val tv get() = isLayout(TV)

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var tabs: LinearLayout
    private lateinit var search: EditText
    private lateinit var crumb: TextView
    private lateinit var resumeBar: TextView
    /** Now / next programmes of the current list (from its XMLTV guide), empty when it has none. */
    private var guide: Map<String, List<CxEpg.Prog>> = emptyMap()
    private var guideFor: String? = null
    private lateinit var grid: RecyclerView
    private lateinit var gridLayout: GridLayoutManager
    private lateinit var empty: TextView
    private lateinit var addButton: View
    private lateinit var phoneButton: View
    private var miniBar: LinearLayout? = null

    private var sources: List<LiveSource> = emptyList()
    private var allowOwn = false
    private var current = ""
    private var channels: List<LiveChannel> = emptyList()
    // Films and series of the source ("Movie Lists" / "Series Lists" tiles among the categories).
    private var vodMovies: List<LiveChannel> = emptyList()
    private var vodSeries: List<LiveChannel> = emptyList()
    private var vodXtream: CxLive.XtreamLogin? = null
    /** null = live channels; "movie" / "series" = browsing films / series; [vodShow] = a series' episodes. */
    private var vod: String? = null
    private var vodShow: LiveChannel? = null
    private var liveSaved: List<LiveChannel> = emptyList()
    private var liveGroupSaved: String? = null
    private var vodGroupSaved: String? = null
    private var groups: List<Pair<String, Int>> = emptyList()
    /** Live TV shows categories first (OTT Navigator style); null = the category list. */
    private var group: String? = null
    private var showingCategories = false
    private var shown: List<LiveChannel> = emptyList()
    private var favourites: Set<String> = emptySet()
    private var loadJob: Job? = null
    /** Kept while the fragment lives, so the search is still there after the player. */
    private var lastQuery = ""
    private var originals: Map<String, LiveSource> = emptyMap()
    /** Re-reads the current list after the user changed its arrangement (same category and place). */
    private fun refreshArrangement() { if (isAdded && view != null) { restorePos = gridLayout.findFirstVisibleItemPosition(); select(current, false, keepGroup = true) } }
    // Coming back from the player: the same list, category and scroll position, without reloading.
    private var loadedOnce = false
    private var restorePos = -1

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importFile(it) } }

    private val back = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { goBack() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(color); stroke?.let { setStroke(dp(2), it) }
    }

    /** Background with a clear focus ring (remote, keyboard, mouse) and a selected state. */
    private fun states(radius: Int, normal: Int = surface, selected: Int = accent) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(surfaceHigh, radius, accent))
        addState(intArrayOf(android.R.attr.state_hovered), shape(surfaceHigh, radius))
        addState(intArrayOf(android.R.attr.state_pressed), shape(surfaceHigh, radius))
        addState(intArrayOf(android.R.attr.state_selected), shape(selected, radius))
        addState(intArrayOf(), shape(normal, radius))
    }

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageButton(requireContext()).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(fg)
        background = states(22, Color.TRANSPARENT); contentDescription = label
        TooltipCompat.setTooltipText(this, label)
        setPadding(dp(10), dp(10), dp(10), dp(10)); scaleType = ImageView.ScaleType.FIT_CENTER
        isFocusable = true; setOnClickListener { onClick() }
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(requireContext()).apply {
        text = label; textSize = 14f; setSingleLine(true)
        setTextColor(if (selected) onAccent else fg)
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        background = states(20); isSelected = selected
        setPadding(dp(16), dp(9), dp(16), dp(9)); isFocusable = true; isClickable = true
        setOnClickListener { onClick() }
    }

    private fun categoryIcon(name: String): Int {
        val n = name.lowercase()
        fun has(vararg words: String) = words.any { n.contains(it) }
        return when {
            has("sport", "sukan", "bola", "football", "soccer", "liga", "league", "espn", "bein", "golf", "tennis", "racing", "wwe", "ufc") -> R.drawable.cx_cat_sport
            has("movie", "filem", "film", "cinema", "hbo", "action", "drama", "series", "siri") -> R.drawable.cx_movie
            has("news", "berita", "cnn", "bbc") -> R.drawable.cx_cat_news
            has("kid", "kanak", "anak", "cartoon", "kartun", "anime", "children", "junior", "family") -> R.drawable.cx_cat_kids
            has("music", "muzik", "musik", "lagu", "song", "radio", "mtv") -> R.drawable.cx_cat_music
            has("relig", "agama", "islam", "quran", "christian", "dakwah", "ramadan") -> R.drawable.cx_cat_religion
            has("docu", "dokumentari", "education", "pendidikan", "knowledge", "science", "discovery", "nature", "travel", "history") -> R.drawable.cx_cat_docu
            else -> R.drawable.cx_cat_player
        }
    }

    private fun initials(name: String) = name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "TV" }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val pad = dp(if (tv) 20 else 14)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        // Below the status bar and away from notches, on every phone and in both orientations.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(pad + bars.left, dp(10) + bars.top, pad + bars.right, 0)
            insets
        }

        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(ctx).apply { textSize = if (tv) 26f else 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(fg) }
        status = TextView(ctx).apply { textSize = 13f; setTextColor(muted); setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }
        titles.addView(title); titles.addView(status)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        progress = ProgressBar(ctx).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(accent); visibility = View.GONE }
        header.addView(progress, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
        addButton = iconButton(R.drawable.cx_ic_add, "Tambah playlist") { addPlaylistDialog() }
        phoneButton = iconButton(R.drawable.cx_ic_phone, "Hantar dari telefon") { phoneDialog() }
        header.addView(iconButton(R.drawable.cx_ic_refresh, "Muat semula") { reload(fresh = true) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(addButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        header.addView(phoneButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        root.addView(header)

        tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(10)) }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })

        search = EditText(ctx).apply {
            hint = if (radio) "Cari stesen" else "Cari saluran"; setSingleLine(true); textSize = 15f
            setTextColor(fg); setHintTextColor(muted); background = states(24)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.cx_ic_search, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(muted); compoundDrawablePadding = dp(10)
            setPadding(dp(16), 0, dp(16), 0); inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { lastQuery = s?.toString().orEmpty(); render() }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(8) })

        // "‹ Kategori · Sukan": back to the categories (also the Back key).
        crumb = TextView(ctx).apply {
            textSize = 15f; setTextColor(accent); setTypeface(typeface, Typeface.BOLD); background = states(16, Color.TRANSPARENT)
            setPadding(dp(10), dp(8), dp(12), dp(8)); isFocusable = true; isClickable = true; visibility = View.GONE
            setOnClickListener { openCategories() }
        }
        root.addView(crumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })

        // "▶ Sambung: TV3": the last channel watched, one tap away.
        resumeBar = TextView(ctx).apply {
            textSize = 15f; setTextColor(fg); setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD); background = states(16)
            setPadding(dp(16), dp(10), dp(16), dp(10)); isFocusable = true; isClickable = true; visibility = View.GONE
            setOnClickListener { resumeLast() }
        }
        root.addView(resumeBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        gridLayout = GridLayoutManager(ctx, 3)
        grid = RecyclerView(ctx).apply {
            layoutManager = gridLayout; adapter = GridAdapter(); clipToPadding = false; setPadding(0, 0, 0, dp(24))
            // Columns follow the real width: correct after rotating, after the player and on TV.
            // (posted: a column change inside a layout pass is not always applied, e.g. back from a landscape player)
            addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) v.post { updateSpan(v.width) } }
        }
        empty = TextView(ctx).apply { setTextColor(muted); textSize = 15f; gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), dp(24)); visibility = View.GONE }
        root.addView(FrameLayout(ctx).apply {
            addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (radio) root.addView(buildMiniBar(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(8)) })
        title.text = if (radio) "Radio" else "Live TV"
        return root
    }

    private fun updateSpan(widthPx: Int) {
        if (widthPx <= 0 || !isAdded) return
        val cell = dp(if (showingCategories) 170 else if (tv) 150 else 112)
        val span = maxOf(if (showingCategories) 2 else 3, widthPx / cell)
        if (gridLayout.spanCount != span) { gridLayout.spanCount = span; grid.adapter?.notifyDataSetChanged(); grid.requestLayout() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        grid.post { updateSpan(grid.width) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.requestApplyInsets(view)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        if (radio) CxRadio.listener = { updateMiniBar() }
        updateMiniBar()
        // The last search survives the player (the view is created again when coming back).
        if (lastQuery.isNotEmpty()) search.setText(lastQuery)
        reload(fresh = !loadedOnce)
        loadedOnce = true
    }

    override fun onDestroyView() {
        if (radio) CxRadio.listener = null
        miniBar = null
        super.onDestroyView()
    }

    private fun loading(on: Boolean) { progress.visibility = if (on) View.VISIBLE else View.GONE }

    // ---------------- sources ----------------
    private fun reload(fresh: Boolean) {
        val ctx = context ?: return
        loading(true); status.text = "Memuatkan…"
        viewLifecycleOwner.lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) { CxLive.config(ctx, fresh) }
            allowOwn = config.allowOwn && !radio
            val admin = config.admin.filter { (it.media == "radio") == radio }
            val all = if (radio) admin + CxLive.RADIO_MALAYSIA else admin + if (allowOwn) CxLive.ownSources(ctx) else emptyList()
            // The user's arrangement: hidden sources left out, renamed ones under their new name.
            originals = all.associateBy { it.id }
            val hidden = if (radio) emptySet() else CxEdits.hiddenSources(ctx)
            sources = all.filter { it.id !in hidden }.map { it.copy(name = CxEdits.nameOf(ctx, it)) }
            addButton.visibility = if (allowOwn) View.VISIBLE else View.GONE
            phoneButton.visibility = if (allowOwn) View.VISIBLE else View.GONE
            val keep = current.takeIf { c -> c == FAV || c == RECENT || sources.any { it.id == c } }
            val first = if (radio) sources.firstOrNull()?.id else CxLive.defaultSourceId(ctx, sources.map { it.id })
            select(keep ?: first ?: FAV, fresh, keepGroup = keep != null)
        }
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        val items = sources.map { it.id to it.name } + listOf(FAV to "★ Kegemaran", RECENT to "Baru dimainkan")
        for ((id, name) in items) {
            tabs.addView(chip(name, id == current) { if (id != current) select(id, false) }.apply {
                setOnLongClickListener { sourceActions(id); true }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
    }

    private fun select(id: String, fresh: Boolean, keepGroup: Boolean = false) {
        val ctx = context ?: return
        val sameSource = id == current
        current = id
        if (!(keepGroup && sameSource)) group = null
        // Back from the film player: the same films / series view, where the user was.
        if (keepGroup && sameSource && vod != null && channels.isNotEmpty()) {
            renderTabs(); loading(false)
            val g = group
            show(channels, null); group = g; render(); restoreScroll()
            return
        }
        if (!(keepGroup && sameSource) || vod != null) { vod = null; vodShow = null; if (keepGroup && sameSource) group = null }
        if (!sameSource) { vodMovies = emptyList(); vodSeries = emptyList(); vodXtream = null }
        renderTabs()
        loadJob?.cancel()
        favourites = CxLive.favouriteKeys(ctx, scope)
        when (id) {
            FAV -> { loading(false); show(CxLive.favourites(ctx, scope), "Tiada kegemaran lagi.\nTekan lama pada " + (if (radio) "stesen" else "saluran") + " untuk menambahnya."); restoreScroll() }
            RECENT -> { loading(false); show(CxLive.recent(ctx, scope), "Belum ada yang dimainkan."); restoreScroll() }
            else -> {
                val source = sources.firstOrNull { it.id == id } ?: return
                loading(true); status.text = "Memuatkan " + source.name + "…"
                show(emptyList(), null)
                loadJob = viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val list = withContext(Dispatchers.IO) { CxLive.channels(ctx, source, fresh) }
                        // Films and series: under "Movie Lists" / "Series Lists" (and in the Films menu).
                        val films = if (radio) 0 else list.count { CxLive.isVod(it) }
                        if (!radio && current == id) {
                            val vodAll = list.filter { CxLive.isVod(it) }
                            vodMovies = vodAll.filter { !CxLive.isSeries(it) }
                            vodSeries = vodAll.filter { CxLive.isSeries(it) }
                            vodXtream = if (source.kind == "xtream") CxLive.XtreamLogin(source.server.orEmpty(), source.username.orEmpty(), source.password.orEmpty())
                                else source.url?.let { CxLive.xtreamFromLink(it) }
                        }
                        if (!radio) {
                            val before = CxLive.vodCount(ctx, id)
                            CxLive.setVodCount(ctx, id, films)
                            if ((before > 0) != (films > 0)) CxXtream.register(ctx)
                        }
                        val live = if (films > 0) list.filter { !CxLive.isVod(it) } else list
                        if (current == id) {
                            show(if (radio) live else CxEdits.apply(ctx, id, live), "Tiada saluran dalam senarai ini.")
                            restoreScroll(); loadGuide(source)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (current == id) show(emptyList(), (e as? LiveProblem)?.message ?: ("Gagal memuatkan (" + e.javaClass.simpleName + ")."))
                    } finally {
                        if (current == id && isAdded && this@CxLiveFragment.view != null) loading(false)
                    }
                }
            }
        }
    }

    private fun loadGuide(source: LiveSource) {
        if (radio) return
        val ctx = context ?: return
        val url = CxLive.guideUrl(ctx, source) ?: run { guide = emptyMap(); guideFor = null; return }
        if (guideFor == url && guide.isNotEmpty()) return
        val id = current
        viewLifecycleOwner.lifecycleScope.launch {
            val g = CxEpg.load(ctx, url)
            if (current != id || !isAdded || this@CxLiveFragment.view == null) return@launch
            guide = g; guideFor = url
            if (g.isNotEmpty() && !showingCategories) grid.adapter?.notifyDataSetChanged()
        }
    }

    private fun restoreScroll() {
        if (restorePos < 0 || !isAdded || this.view == null) return
        if (shown.isNotEmpty()) grid.scrollToPosition(restorePos.coerceAtMost(shown.size - 1))
        restorePos = -1
    }

    private val spaces = Regex("[\\s\\u00A0\\u200B\\uFEFF]+")
    private fun groupKey(g: String) = CxLive.groupKey(g)

    /** (key, display name) of each category of a channel; ";" or "|" separates several. */
    private fun groupsOf(c: LiveChannel): List<Pair<String, String>> {
        val parts = c.group.split(';', '|').map { it.trim().replace(spaces, " ") }.filter { it.isNotEmpty() }
        return (parts.ifEmpty { listOf(c.group.trim()) }).map { groupKey(it) to it }.distinctBy { it.first }
    }

    private var channelKeys: List<Set<String>> = emptyList()

    private fun show(listIn: List<LiveChannel>, emptyText: String?) {
        if (!isAdded || this.view == null) return
        // Adult (18+) categories: hidden unless shown in Settings; never in a Kids profile.
        val list = if (radio || CxLive.adultAllowed(requireContext())) listIn else listIn.filter { !CxLive.isAdult(it) }
        channels = list
        // Categories: "MOVIES;HOT CHANNEL" is in both; "Malaysia", "MALAYSIA " and "MALAYSIA" are one.
        val names = LinkedHashMap<String, String>()
        val counts = HashMap<String, Int>()
        val keys = ArrayList<Set<String>>(list.size)
        for (c in list) {
            val of = groupsOf(c)
            keys.add(of.map { it.first }.toSet())
            for ((key, name) in of) { names.getOrPut(key) { name }; counts[key] = (counts[key] ?: 0) + 1 }
        }
        channelKeys = keys
        val isSource = current != FAV && current != RECENT
        val orderedNames = if (isSource && !radio && vod == null) CxEdits.sortCategories(requireContext(), current, names.values.toList()) else names.values.toList()
        groups = orderedNames.map { name -> name to (counts[groupKey(name)] ?: 0) }
        if (isSource && !radio && vod == null) { CxLive.numbered = list; CxLive.categories = orderedNames }
        if (group != null && group != ALL && list.isNotEmpty() && groups.none { groupKey(it.first) == groupKey(group!!) }) group = null
        // Channels without a logo: look them up once (TV only; radio stations keep their own icons).
        if (!radio && !CxLogos.loaded && list.any { it.logo == null }) {
            val ctx = context
            if (ctx != null) viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { runCatching { CxLogos.load(ctx) } }
                grid.adapter?.notifyDataSetChanged()
            }
        }
        render(emptyText)
    }

    /**
     * Live TV (with more than one category, outside Favourites/Recent and without a search):
     * the categories first, then the channels of the chosen one. Radio: the stations directly.
     */
    private fun render(emptyText: String? = null) {
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        val byCategory = !radio && current != FAV && current != RECENT && (groups.size > 1 || (vod == null && vodTiles().isNotEmpty()))
        showingCategories = byCategory && group == null && q.isEmpty()
        val key = group?.takeIf { it != ALL }?.let { groupKey(it) }
        shown = if (showingCategories) emptyList() else channels.filterIndexed { i, it ->
            (key == null || q.isNotEmpty() || channelKeys.getOrNull(i)?.contains(key) == true) && (q.isEmpty() || it.name.lowercase().contains(q))
        }
        back.isEnabled = (byCategory && group != null) || vod != null
        crumb.visibility = if ((byCategory && group != null && q.isEmpty()) || vod != null) View.VISIBLE else View.GONE
        val last = if (radio || current == RECENT || q.isNotEmpty() || (byCategory && group != null)) null else context?.let { CxLive.recent(it, scope).firstOrNull() }
        resumeBar.text = last?.let { "▶  Sambung tonton: " + it.name } ?: ""
        resumeBar.visibility = if (last != null) View.VISIBLE else View.GONE
        crumb.text = "‹  " + when {
            vodShow != null -> vodShow!!.name
            vod != null -> (if (vod == "series") "Series Lists" else "Movie Lists") + (group?.let { "  ·  " + (if (it == ALL) "Semua" else it) } ?: "")
            else -> "Kategori  ·  " + (if (group == ALL) "Semua" else group ?: "")
        }
        updateSpan(grid.width)
        grid.adapter?.notifyDataSetChanged()
        grid.scrollToPosition(0)
        val noun = if (radio) "stesen" else "saluran"
        status.text = when {
            channels.isEmpty() -> ""
            showingCategories -> groups.size.toString() + " kategori · " + channels.size + " " + (if (vod == "movie") "filem" else if (vod == "series") "siri" else noun)
            else -> shown.size.toString() + " " + noun + (group?.takeIf { q.isEmpty() && it != ALL }?.let { " · $it" } ?: "")
        }
        val nothing = if (showingCategories) groups.isEmpty() else shown.isEmpty()
        when {
            channels.isEmpty() && emptyText != null -> { empty.text = emptyText; empty.visibility = View.VISIBLE }
            channels.isNotEmpty() && nothing -> { empty.text = "Tiada padanan."; empty.visibility = View.VISIBLE }
            channels.isNotEmpty() -> empty.visibility = View.GONE
        }
    }

    private fun openCategory(name: String?) { group = name ?: ALL; render(); grid.requestFocus() }

    /** "Movie Lists" / "Series Lists" tiles: shown when the source has films / series (Xtream: always). */
    private fun vodTiles(): List<String> = if (radio || current == FAV || current == RECENT) emptyList() else listOfNotNull(
        "movie".takeIf { vodMovies.isNotEmpty() || vodXtream != null },
        "series".takeIf { vodSeries.isNotEmpty() || vodXtream != null })

    /** Category tiles: "All" (when there are channels), the film / series tiles, then the categories. */
    private fun catTiles(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (channels.isNotEmpty()) out += "all" to "Semua"
        if (vod == null) for (k in vodTiles()) out += k to (if (k == "movie") "Movie Lists" else "Series Lists")
        groups.forEach { out += "group" to it.first }
        return out
    }

    private fun openVod(kind: String) {
        val ctx = context ?: return
        liveSaved = channels; liveGroupSaved = group
        vod = kind; vodShow = null; group = null
        val ready = if (kind == "movie") vodMovies else vodSeries
        val login = vodXtream
        if (ready.isNotEmpty() || login == null) { show(ready, if (kind == "movie") "Tiada filem dalam sumber ini." else "Tiada siri dalam sumber ini."); return }
        loading(true); status.text = "Memuatkan " + (if (kind == "movie") "filem" else "siri") + "…"
        show(emptyList(), null)
        val id = current
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { if (kind == "movie") CxLive.xtreamMovies(login) else CxLive.xtreamSeries(login) }
                if (current == id && vod == kind) {
                    if (kind == "movie") vodMovies = list else vodSeries = list
                    show(list, if (kind == "movie") "Akaun ini tiada filem." else "Akaun ini tiada siri.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                if (current == id && vod == kind) show(emptyList(), "Senarai tidak dapat dimuatkan (" + e.javaClass.simpleName + ").")
            } finally { if (isAdded && this@CxLiveFragment.view != null) loading(false) }
        }
    }

    private fun openSeries(show: LiveChannel) {
        val login = vodXtream ?: return
        vodGroupSaved = group
        vodShow = show
        loading(true); status.text = "Memuatkan episod…"
        val id = current
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val eps = withContext(Dispatchers.IO) { CxLive.xtreamEpisodes(login, show) }
                if (current == id && vodShow == show) { show(eps, "Siri ini belum ada episod."); group = ALL; render() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                if (current == id && vodShow == show) { vodShow = null; Toast.makeText(context, "Episod tidak dapat dimuatkan.", Toast.LENGTH_SHORT).show() }
            } finally { if (isAdded && this@CxLiveFragment.view != null) loading(false) }
        }
    }

    /** Back: episodes → series, a category → its list, films / series → the live categories. */
    private fun goBack() {
        when {
            vodShow != null -> { vodShow = null; show(vodSeries, null); group = vodGroupSaved; render() }
            group != null && (groups.size > 1 || (vod == null && vodTiles().isNotEmpty())) -> openCategories()
            vod != null -> { vod = null; show(liveSaved, null); group = null; render() }
            else -> openCategories()
        }
    }
    private fun openCategories() { group = null; render() }

    // ---------------- play ----------------
    private fun play(index: Int) {
        val c = shown.getOrNull(index) ?: return
        if (vod != null) { playVod(c); return }
        playChannel(c, shown)
    }

    /** A film or an episode: the app's film player (seek, subtitles, next episode). */
    private fun playVod(c: LiveChannel) {
        if (c.url.startsWith("xtream-series:")) { openSeries(c); return }
        val list = if (vodShow != null || vod == "series") shown else listOf(c)
        val at = list.indexOf(c).coerceAtLeast(0)
        restorePos = gridLayout.findFirstVisibleItemPosition()
        CxRadio.stop()
        activity.navigate(R.id.global_to_navigation_player, GeneratorPlayer.newInstance(
            CxLiveGenerator(list, vodShow?.name ?: group?.takeIf { it != ALL } ?: c.name, if (vod == "series") TvType.TvSeries else TvType.Movie), at))
    }

    /** The last channel watched, inside its category (CH+/CH- move through it) when it is in this list. */
    private fun resumeLast() {
        val ctx = context ?: return
        val c = CxLive.recent(ctx, scope).firstOrNull() ?: return
        val same = channels.filter { it.group == c.group }
        playChannel(c, if (same.any { it.key == c.key }) same else listOf(c))
    }

    private fun playChannel(c: LiveChannel, list: List<LiveChannel>) {
        val ctx = context ?: return
        CxLive.addRecent(ctx, c, scope)
        if (radio) { CxRadio.play(ctx, c); return }
        CxRadio.stop()
        if (c.isYoutube) {
            startActivity(Intent(ctx, CxYoutubeActivity::class.java).putExtra("channel", c.url.removePrefix("youtube:")).putExtra("title", c.name))
            return
        }
        // The player moves through the channels of this list (CH+/CH-).
        val playable = list.filter { !it.isYoutube }.ifEmpty { listOf(c) }
        CxLive.playing = playable
        val at = playable.indexOf(c).coerceAtLeast(0)
        val listName = group?.takeIf { it != ALL } ?: sources.firstOrNull { it.id == current }?.name ?: title.text.toString()
        restorePos = gridLayout.findFirstVisibleItemPosition()
        if (CxLive.classicPlayer(ctx)) activity.navigate(R.id.global_to_navigation_player, GeneratorPlayer.newInstance(CxLiveGenerator(playable, listName), at))
        else CxLivePlayerActivity.start(ctx, playable, at, listName, guideFor.takeIf { current != FAV && current != RECENT })
    }

    private fun toggleFavourite(c: LiveChannel, position: Int) {
        val ctx = context ?: return
        val now = CxLive.toggleFavourite(ctx, c, scope)
        favourites = CxLive.favouriteKeys(ctx, scope)
        Toast.makeText(ctx, if (now) "Ditambah ke Kegemaran" else "Dibuang daripada Kegemaran", Toast.LENGTH_SHORT).show()
        if (current == FAV) select(FAV, false) else grid.adapter?.notifyItemChanged(position)
    }

    // ---------------- grid ----------------
    private class Holder(val tile: FrameLayout, val initials: TextView, val logo: ImageView, val name: TextView, val sub: TextView, val star: ImageView)

    private inner class GridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = if (showingCategories) catTiles().size else shown.size
        override fun getItemViewType(position: Int) = if (showingCategories) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val tile = FrameLayout(ctx).apply { background = shape(surfaceHigh, 12) }
            val initials = TextView(ctx).apply { textSize = if (viewType == 1) 26f else 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(muted); gravity = Gravity.CENTER }
            val logo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(10), dp(10), dp(10), dp(10)) }
            tile.addView(initials, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            tile.addView(logo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val star = ImageView(ctx).apply { setImageResource(R.drawable.cx_ic_star); imageTintList = ColorStateList.valueOf(Color.rgb(255, 196, 0)) }
            tile.addView(star, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
            val name = TextView(ctx).apply { setTextColor(fg); textSize = 13.5f; setTypeface(typeface, Typeface.BOLD); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            val sub = TextView(ctx).apply { setTextColor(muted); textSize = 12f; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_HORIZONTAL }
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
                setPadding(dp(8), dp(8), dp(8), dp(10))
                addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (viewType == 1) 92 else if (tv) 88 else 76)))
                addView(name); addView(sub)
                background = states(16); isFocusable = true; isClickable = true; isLongClickable = true
            }
            card.tag = Holder(tile, initials, logo, name, sub, star)
            return object : RecyclerView.ViewHolder(card) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val h = holder.itemView.tag as Holder
            if (showingCategories) {
                val (kind, name) = catTiles().getOrNull(position) ?: return
                val noun = when { radio -> " stesen"; vod == "movie" -> " filem"; vod == "series" -> " siri"; else -> " saluran" }
                h.name.text = name
                h.sub.text = when (kind) {
                    "all" -> channels.size.toString() + noun
                    "movie" -> if (vodMovies.isNotEmpty()) vodMovies.size.toString() + " filem" else "Filem"
                    "series" -> if (vodSeries.isNotEmpty()) vodSeries.size.toString() + " siri" else "Siri"
                    else -> (groups.firstOrNull { it.first == name }?.second ?: 0).toString() + noun
                }
                h.star.visibility = View.GONE
                // A category has no logo of its own: an icon for its kind (sports, movies, news...).
                h.initials.visibility = View.INVISIBLE
                h.logo.setImageResource(when (kind) { "all" -> R.drawable.cx_cat_all; "movie" -> R.drawable.cx_movie; "series" -> R.drawable.cx_cat_player; else -> categoryIcon(name) })
                h.logo.imageTintList = ColorStateList.valueOf(accent)
                h.logo.setPadding(dp(22), dp(22), dp(22), dp(22))
                holder.itemView.setOnClickListener {
                    when (kind) { "all" -> openCategory(null); "movie", "series" -> openVod(kind); else -> openCategory(name) }
                }
                holder.itemView.setOnLongClickListener {
                    val ctx = context
                    if (kind != "group" || vod != null || ctx == null || current == FAV || current == RECENT || radio) return@setOnLongClickListener false
                    val t = { ms: String, en: String -> CxLiveManage.t(ctx, ms, en) }
                    AlertDialog.Builder(ctx).setTitle(name).setItems(arrayOf(t("Tukar nama", "Rename"), t("Sembunyikan kategori", "Hide category"))) { _, which ->
                        if (which == 0) CxLiveManage.renameCategory(ctx, current, name) { refreshArrangement() }
                        else CxLiveManage.hideCategory(ctx, current, name) { refreshArrangement() }
                    }.show()
                    true
                }
                return
            }
            val c = shown[position]
            h.name.text = c.name
            val onNow = CxEpg.nowNext(guide, c).first
            h.sub.text = when {
                c.isYoutube -> "YouTube"
                onNow != null -> "▶ " + onNow.title
                else -> group?.takeIf { it != ALL } ?: groupsOf(c).firstOrNull()?.second ?: c.group
            }.let { sub -> if (!radio && c.number != null && current != FAV && current != RECENT) "${c.number} · $sub" else sub }
            h.star.visibility = if (c.key in favourites) View.VISIBLE else View.GONE
            h.initials.text = initials(c.name)
            h.logo.imageTintList = null
            h.logo.setPadding(dp(10), dp(10), dp(10), dp(10))
            // Radio: the station's own icon only (the TV logo database matched wrong stations by name).
            val logoUrl = c.logo ?: if (radio) null else CxLogos.find(c)
            // A recycled tile is cleared first, so it never keeps another channel's picture;
            // a picture that cannot be loaded shows the initials.
            h.logo.loadImage(null as String?)
            h.logo.setImageDrawable(null)
            if (logoUrl != null) {
                h.initials.visibility = View.INVISIBLE
                val initials = h.initials
                h.logo.loadImage(logoUrl) { listener(onError = { _, _ -> initials.visibility = View.VISIBLE }) }
            } else h.initials.visibility = View.VISIBLE
            holder.itemView.setOnClickListener { play(holder.bindingAdapterPosition) }
            holder.itemView.setOnLongClickListener {
                val ctx = context ?: return@setOnLongClickListener true
                val pos = holder.bindingAdapterPosition
                val sourceId = current.takeIf { it != FAV && it != RECENT && !radio }
                CxLiveManage.channelActions(ctx, sourceId, c, c.key in favourites, groups.map { it.first },
                    toggleFavourite = { toggleFavourite(c, pos) }, done = { refreshArrangement() })
                true
            }
        }
    }

    // ---------------- radio mini player ----------------
    private fun buildMiniBar(ctx: android.content.Context): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = shape(surfaceHigh, 16); setPadding(dp(10), dp(8), dp(8), dp(8)); visibility = View.GONE
        }
        val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; background = shape(surface, 10); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        val name = TextView(ctx).apply { setTextColor(fg); textSize = 15f; setTypeface(typeface, Typeface.BOLD); setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }
        val state = TextView(ctx).apply { setTextColor(muted); textSize = 12f; setSingleLine(true) }
        texts.addView(name); texts.addView(state)
        val toggle = ImageButton(ctx).apply {
            imageTintList = ColorStateList.valueOf(onAccent); background = states(24, accent, accent); isFocusable = true
            setPadding(dp(10), dp(10), dp(10), dp(10)); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Main / jeda"
            setOnClickListener { CxRadio.toggle() }
        }
        val stop = ImageButton(ctx).apply {
            setImageResource(R.drawable.cx_ic_close); imageTintList = ColorStateList.valueOf(fg); background = states(22, Color.TRANSPARENT)
            setPadding(dp(10), dp(10), dp(10), dp(10)); scaleType = ImageView.ScaleType.FIT_CENTER; isFocusable = true; contentDescription = "Henti"
            setOnClickListener { CxRadio.stop() }
        }
        bar.addView(art, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(toggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(stop, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
        bar.tag = listOf(art, name, state, toggle)
        miniBar = bar
        return bar
    }

    private fun updateMiniBar() {
        val bar = miniBar ?: return
        val c = CxRadio.current
        if (c == null) { bar.visibility = View.GONE; return }
        @Suppress("UNCHECKED_CAST") val parts = bar.tag as List<View>
        val art = parts[0] as ImageView; val name = parts[1] as TextView; val state = parts[2] as TextView; val toggle = parts[3] as ImageButton
        bar.visibility = View.VISIBLE
        name.text = c.name
        state.text = when (CxRadio.state) {
            CxRadio.State.LOADING -> "Menyambung…"
            CxRadio.State.PLAYING -> "Sedang dimainkan"
            CxRadio.State.PAUSED -> "Dijeda"
            CxRadio.State.ERROR -> "Stesen tidak dapat dimainkan"
        }
        toggle.setImageResource(if (CxRadio.state == CxRadio.State.PLAYING || CxRadio.state == CxRadio.State.LOADING) R.drawable.cx_ic_pause else R.drawable.cx_ic_play)
        if (c.logo != null) art.loadImage(c.logo) else art.setImageResource(R.drawable.cx_radio)
    }

    // ---------------- own playlists ----------------
    private fun field(hint: String, password: Boolean = false) = EditText(requireContext()).apply {
        this.hint = hint; setSingleLine(true)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun addPlaylistDialog() {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = field("Nama playlist (pilihan)").apply { inputType = InputType.TYPE_CLASS_TEXT }
        val modes = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val link = RadioButton(ctx).apply { text = "Link"; id = View.generateViewId(); isChecked = true }
        val xtream = RadioButton(ctx).apply { text = "Xtream"; id = View.generateViewId() }
        val file = RadioButton(ctx).apply { text = "Fail"; id = View.generateViewId() }
        modes.addView(link); modes.addView(xtream); modes.addView(file)
        val url = field("Link playlist (M3U, shortlink, link Xtream penuh)")
        val server = field("Server Xtream (http://host:port)"); val user = field("Username").apply { inputType = InputType.TYPE_CLASS_TEXT }
        val pass = field("Password", password = true)
        val note = TextView(ctx).apply { text = "Anda bertanggungjawab atas kandungan playlist sendiri. Playlist hanya disimpan pada peranti ini."; textSize = 12f; setPadding(0, dp(8), 0, 0) }
        listOf(name, modes, url, server, user, pass, note).forEach { box.addView(it) }
        val dialog = AlertDialog.Builder(ctx).setTitle("Tambah playlist").setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Tambah") { _, _ ->
                when {
                    file.isChecked -> pickFile.launch("*/*")
                    xtream.isChecked -> saveOwn(LiveSource(CxLive.newId(), name.text.toString().trim().ifEmpty { "Xtream" }, "xtream",
                        server = server.text.toString().trim(), username = user.text.toString().trim(), password = pass.text.toString(), own = true))
                    else -> saveOwn(ownFromLink(url.text.toString().trim(), name.text.toString().trim()))
                }
            }
            .setNegativeButton("Batal", null).show()
        fun refresh() {
            url.visibility = if (link.isChecked) View.VISIBLE else View.GONE
            listOf(server, user, pass).forEach { it.visibility = if (xtream.isChecked) View.VISIBLE else View.GONE }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text = if (file.isChecked) "Pilih fail" else "Tambah"
        }
        modes.setOnCheckedChangeListener { _, _ -> refresh() }
        refresh()
    }

    private fun ownFromLink(l: String, name: String?): LiveSource {
        val x = CxLive.xtreamFromLink(l)
        return if (x != null) LiveSource(CxLive.newId(), name?.takeIf { it.isNotBlank() } ?: "Xtream", "xtream",
            server = x.server, username = x.username, password = x.password, own = true)
        else LiveSource(CxLive.newId(), name?.takeIf { it.isNotBlank() } ?: "Playlist", "m3u", url = l, own = true)
    }

    /** Checks a new playlist (downloads it once) before keeping it. */
    private fun saveOwn(source: LiveSource) {
        val ctx = context ?: return
        loading(true); status.text = "Menyemak playlist…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { CxLive.channels(ctx, source, true) }
                CxLive.addOwn(ctx, source)
                Toast.makeText(ctx, source.name + ": " + list.size + " saluran", Toast.LENGTH_LONG).show()
                current = source.id
                reload(fresh = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                loading(false)
                val why = (e as? LiveProblem)?.message ?: ("Playlist tidak dapat ditambah (" + e.javaClass.simpleName + ").")
                status.text = why
                AlertDialog.Builder(ctx).setTitle("Playlist tidak ditambah").setMessage(why).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun importFile(uri: Uri) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val id = CxLive.newId()
                val target = File(File(ctx.filesDir, "cx_live").apply { mkdirs() }, "$id.m3u")
                withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } }
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.take(40) ?: "Fail playlist"
                if (CxLive.parseM3u(target.readText()).isEmpty()) { target.delete(); throw LiveProblem("Fail ini bukan playlist M3U.") }
                saveOwn(LiveSource(id, name, "file", path = target.absolutePath, own = true))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = (e as? LiveProblem)?.message ?: "Fail tidak dapat dibaca."
            }
        }
    }

    private fun sourceActions(id: String) {
        val ctx = context ?: return
        val s = originals[id] ?: return
        if (radio) { if (s.own) confirmRemove(s); return }
        CxLiveManage.sourceActions(ctx, s,
            edit = { CxLiveManage.editSource(ctx, s, { updated -> saveOwn(updated) }) { reload(fresh = false) } },
            remove = if (s.own) ({ confirmRemove(s) }) else null,
            done = { reload(fresh = false) })
    }

    private fun confirmRemove(source: LiveSource) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx).setTitle("Buang " + source.name + "?").setMessage("Playlist ini akan dibuang daripada peranti ini.")
            .setPositiveButton("Buang") { _, _ -> CxLive.removeOwn(ctx, source.id); if (current == source.id) current = ""; reload(fresh = false) }
            .setNegativeButton("Batal", null).show()
    }

    // ---------------- phone to TV ----------------
    private fun phoneDialog() {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(12), dp(20), dp(4)) }
        val info = TextView(ctx).apply { textSize = 15f; gravity = Gravity.CENTER; text = "Meminta kod…" }
        val code = TextView(ctx).apply { textSize = 34f; setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.2f; gravity = Gravity.CENTER }
        val qr = ImageView(ctx)
        box.addView(info); box.addView(code); box.addView(qr, LinearLayout.LayoutParams(dp(200), dp(200)).apply { topMargin = dp(8) })
        var job: Job? = null
        val dialog = AlertDialog.Builder(ctx).setTitle("Hantar playlist dari telefon").setView(ScrollView(ctx).apply { addView(box) })
            .setNegativeButton("Tutup", null).setOnDismissListener { job?.cancel() }.show()
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val c = withContext(Dispatchers.IO) { CxLive.startPairing(ctx) }
                val link = CxLive.TV_PAGE + "?code=" + c
                info.text = "Di telefon, buka:\n" + CxLive.TV_PAGE + "\ndan masukkan kod ini (atau imbas QR):"
                code.text = c
                withContext(Dispatchers.Default) {
                    runCatching { qrcode.QRCode.ofRoundedSquares().withColor(Color.BLACK).withBackgroundColor(Color.WHITE).build(link).render().nativeImage() as Bitmap }.getOrNull()
                }?.let { qr.setImageBitmap(it) }
                val until = System.currentTimeMillis() + 10 * 60_000L
                while (isActive && System.currentTimeMillis() < until) {
                    delay(4_000)
                    val (state, playlist) = withContext(Dispatchers.IO) { runCatching { CxLive.pollPairing(ctx, c) }.getOrDefault("waiting" to null) }
                    if (state == "received" && playlist != null) {
                        dialog.dismiss()
                        val n = playlist.optString("name").takeIf { it.isNotBlank() && it != "null" }
                        if (playlist.optString("kind") == "xtream") saveOwn(LiveSource(CxLive.newId(), n ?: "Xtream", "xtream",
                            server = playlist.optString("server"), username = playlist.optString("username"), password = playlist.optString("password"), own = true))
                        else saveOwn(ownFromLink(playlist.optString("url"), n))
                        return@launch
                    }
                    if (state == "expired") { info.text = "Kod tamat tempoh. Tutup dan cuba lagi."; code.text = ""; qr.setImageDrawable(null); return@launch }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                info.text = if (CxWorkerActivation.serverCode(e) == "user_playlists_not_allowed") "Playlist sendiri tidak dibenarkan untuk lesen ini."
                else "Kod tidak dapat diminta. Semak Internet dan cuba lagi."
            }
        }
    }

    companion object {
        private const val FAV = "@fav"
        private const val RECENT = "@recent"
        /** The "Semua" category (all channels of the list). */
        private const val ALL = "\u0000all"
    }
}

/** Official YouTube live channels, played inside the app with YouTube's own embedded player. */
class CxYoutubeActivity : androidx.appcompat.app.AppCompatActivity() {
    private lateinit var web: android.webkit.WebView
    private var playing = true

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = android.webkit.WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            webChromeClient = android.webkit.WebChromeClient()
        }
        setContentView(web)
        val channel = intent.getStringExtra("channel").orEmpty()
        lifecycleScope.launch {
            val id = if (channel.startsWith("UC")) channel else withContext(Dispatchers.IO) { channelId(channel) }
            if (id == null) {
                // Not resolved: YouTube's mobile page of the channel's live stream, still inside the app.
                web.loadUrl("https://m.youtube.com/" + channel.trimStart('/') + "/live")
                return@launch
            }
            val src = "https://www.youtube.com/embed/live_stream?channel=$id&autoplay=1&playsinline=1&enablejsapi=1&rel=0"
            val html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>" +
                "<style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head><body>" +
                "<iframe id=p src='$src' allow='autoplay; encrypted-media; picture-in-picture' allowfullscreen></iframe></body></html>"
            // An https origin is required by YouTube's embedded player.
            web.loadDataWithBaseURL("https://cloudxpanel.infopc.xyz/", html, "text/html", "utf-8", null)
        }
    }

    /** The channel id (UC…) of a YouTube handle such as @astroawani, read from its public page. */
    private suspend fun channelId(handle: String): String? = try {
        val page = com.lagradost.cloudstream3.app.get("https://www.youtube.com/" + handle.trimStart('/'), timeout = 30L).text
        Regex(""""(?:externalId|channelId)":"(UC[A-Za-z0-9_-]{22})"""").find(page)?.groupValues?.get(1)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) { null }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val toggle = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == android.view.KeyEvent.KEYCODE_SPACE
        if (toggle) {
            playing = !playing
            web.evaluateJavascript("document.getElementById('p')&&document.getElementById('p').contentWindow.postMessage(JSON.stringify({event:'command',func:'" +
                (if (playing) "playVideo" else "pauseVideo") + "',args:[]}),'*')", null)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}

/** From the player: the channel typed on the remote, inside its own category (CH+/CH- move through it). */
fun cxOpenNumbered(activity: android.app.Activity, target: LiveChannel) {
    if (target.isYoutube) {
        activity.startActivity(Intent(activity, CxYoutubeActivity::class.java).putExtra("channel", target.url.removePrefix("youtube:")).putExtra("title", target.name))
        return
    }
    val first = CxLive.groupParts(target.group).firstOrNull()
    val key = first?.let { CxLive.groupKey(it) }
    val list = CxLive.numbered.filter { c -> !c.isYoutube && (key == null || CxLive.groupParts(c.group).any { CxLive.groupKey(it) == key }) }
        .ifEmpty { listOf(target) }
    val at = list.indexOfFirst { it.key == target.key }.coerceAtLeast(0)
    CxLive.playing = list
    runCatching { androidx.navigation.Navigation.findNavController(activity, R.id.nav_host_fragment).popBackStack() }
    activity.navigate(R.id.global_to_navigation_player, GeneratorPlayer.newInstance(CxLiveGenerator(list, first ?: target.name), at))
}
