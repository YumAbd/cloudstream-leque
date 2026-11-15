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
class CxLiveGenerator(private val channels: List<LiveChannel>, title: String) : VideoGenerator<ExtractorUri>(
    channels.map { ExtractorUri(uri = Uri.parse(it.url), name = it.name, headerName = title, tvType = TvType.Live) }
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
        val link = newExtractorLink("Live TV", c.name, c.url, type) {
            referer = c.referer.orEmpty()
            c.userAgent?.let { headers = mapOf("User-Agent" to it) }
        }
        if (type in sourceTypes) callback(link to null)
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

    private val radio get() = arguments?.getString("mode") == "radio"
    private val scope get() = if (radio) "radio" else ""
    private val tv get() = isLayout(TV)
    private val wide get() = tv || resources.configuration.screenWidthDp >= 600

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var tabs: LinearLayout
    private lateinit var search: EditText
    private lateinit var groupList: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var empty: TextView
    private lateinit var addButton: View
    private lateinit var phoneButton: View

    private var sources: List<LiveSource> = emptyList()
    private var allowOwn = false
    private var current = ""
    private var channels: List<LiveChannel> = emptyList()
    private var groups: List<Pair<String, Int>> = emptyList()
    private var group: String? = null
    private var shown: List<LiveChannel> = emptyList()
    private var favourites: Set<String> = emptySet()
    private var loadJob: Job? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importFile(it) } }

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
        setTextColor(if (selected) Color.rgb(4, 20, 30) else fg)
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        background = states(20); isSelected = selected
        setPadding(dp(16), dp(9), dp(16), dp(9)); isFocusable = true; isClickable = true
        setOnClickListener { onClick() }
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
        val iconSize = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) }
        addButton = iconButton(R.drawable.cx_ic_add, "Tambah playlist") { addPlaylistDialog() }
        phoneButton = iconButton(R.drawable.cx_ic_phone, "Hantar dari telefon") { phoneDialog() }
        header.addView(iconButton(R.drawable.cx_ic_refresh, "Muat semula") { reload(fresh = true) }, iconSize)
        header.addView(addButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        header.addView(phoneButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        root.addView(header)

        tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(10)) }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(tabs) })

        search = EditText(ctx).apply {
            hint = if (radio) "Cari stesen" else "Cari saluran"; setSingleLine(true); textSize = 15f
            setTextColor(fg); setHintTextColor(muted); background = states(24)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.cx_ic_search, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(muted); compoundDrawablePadding = dp(10)
            setPadding(dp(16), 0, dp(16), 0); inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { applyFilter() }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(10) })

        groupList = RecyclerView(ctx).apply {
            adapter = GroupAdapter(); clipToPadding = false
            layoutManager = LinearLayoutManager(ctx, if (wide) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL, false)
        }
        channelList = RecyclerView(ctx).apply {
            adapter = ChannelAdapter(); clipToPadding = false; setPadding(0, 0, 0, dp(96))
            val span = if (wide) maxOf(2, (resources.configuration.screenWidthDp - (if (tv) 330 else 280)) / 180) else 1
            layoutManager = GridLayoutManager(ctx, span)
        }
        empty = TextView(ctx).apply { setTextColor(muted); textSize = 15f; gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), dp(24)); visibility = View.GONE }
        val channelsBox = FrameLayout(ctx).apply {
            addView(channelList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (wide) {
            val body = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            body.addView(groupList, LinearLayout.LayoutParams(dp(if (tv) 230 else 200), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(12) })
            body.addView(channelsBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            root.addView(groupList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            root.addView(channelsBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        title.text = if (radio) "Radio" else "Live TV"
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.requestApplyInsets(view)
        reload(fresh = true)
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
            sources = if (radio) admin + CxLive.RADIO_MALAYSIA else admin + if (allowOwn) CxLive.ownSources(ctx) else emptyList()
            addButton.visibility = if (allowOwn) View.VISIBLE else View.GONE
            phoneButton.visibility = if (allowOwn) View.VISIBLE else View.GONE
            val keep = current.takeIf { c -> c == FAV || c == RECENT || sources.any { it.id == c } }
            select(keep ?: sources.firstOrNull()?.id ?: FAV, fresh)
        }
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        val items = sources.map { it.id to it.name } + listOf(FAV to "★ Kegemaran", RECENT to "Baru dimainkan")
        for ((id, name) in items) {
            tabs.addView(chip(name, id == current) { if (id != current) select(id, false) }.apply {
                setOnLongClickListener { sources.firstOrNull { it.id == id && it.own }?.let { confirmRemove(it) }; true }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
    }

    private fun select(id: String, fresh: Boolean) {
        val ctx = context ?: return
        current = id; group = null
        renderTabs()
        loadJob?.cancel()
        favourites = CxLive.favouriteKeys(ctx, scope)
        when (id) {
            FAV -> { loading(false); show(CxLive.favourites(ctx, scope), "Tiada kegemaran lagi.\nTekan lama pada " + (if (radio) "stesen" else "saluran") + " untuk menambahnya.") }
            RECENT -> { loading(false); show(CxLive.recent(ctx, scope), "Belum ada yang dimainkan.") }
            else -> {
                val source = sources.firstOrNull { it.id == id } ?: return
                loading(true); status.text = "Memuatkan " + source.name + "…"
                show(emptyList(), null)
                loadJob = viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val list = withContext(Dispatchers.IO) { CxLive.channels(ctx, source, fresh) }
                        if (current == id) show(list, "Tiada saluran dalam senarai ini.")
                    } catch (e: Exception) {
                        if (current == id) show(emptyList(), (e as? LiveProblem)?.message ?: ("Gagal memuatkan (" + e.javaClass.simpleName + ")."))
                    } finally {
                        if (current == id) loading(false)
                    }
                }
            }
        }
    }

    private fun show(list: List<LiveChannel>, emptyText: String?) {
        channels = list
        // Channels without a logo: look them up once (TV only; radio stations keep their own icons).
        if (!radio && !CxLogos.loaded && list.any { it.logo == null }) {
            val ctx = context
            if (ctx != null) viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { runCatching { CxLogos.load(ctx) } }
                channelList.adapter?.notifyDataSetChanged()
            }
        }
        groups = list.groupingBy { it.group }.eachCount().toList()
        groupList.visibility = if (groups.size > 1) View.VISIBLE else View.GONE
        groupList.adapter?.notifyDataSetChanged()
        applyFilter()
        empty.text = emptyText.orEmpty()
        empty.visibility = if (list.isEmpty() && emptyText != null) View.VISIBLE else View.GONE
    }

    private fun applyFilter() {
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        shown = channels.filter { (group == null || it.group == group) && (q.isEmpty() || it.name.lowercase().contains(q)) }
        channelList.adapter?.notifyDataSetChanged()
        val noun = if (radio) "stesen" else "saluran"
        if (channels.isNotEmpty()) status.text = shown.size.toString() + " " + noun + (group?.let { " · $it" } ?: "")
        else if (empty.visibility != View.VISIBLE) status.text = ""
        if (channels.isNotEmpty()) empty.visibility = if (shown.isEmpty()) View.VISIBLE.also { empty.text = "Tiada padanan." } else View.GONE
    }

    // ---------------- play ----------------
    private fun play(index: Int) {
        val c = shown.getOrNull(index) ?: return
        val ctx = context ?: return
        CxLive.addRecent(ctx, c, scope)
        if (c.isYoutube) {
            startActivity(Intent(ctx, CxYoutubeActivity::class.java).putExtra("channel", c.url.removePrefix("youtube:")).putExtra("title", c.name))
            return
        }
        // The player moves through the channels of this list (CH+/CH-).
        val playable = shown.filter { !it.isYoutube }
        val at = playable.indexOf(c).coerceAtLeast(0)
        val listName = sources.firstOrNull { it.id == current }?.name ?: title.text.toString()
        activity.navigate(R.id.global_to_navigation_player, GeneratorPlayer.newInstance(CxLiveGenerator(playable, listName), at))
    }

    private fun toggleFavourite(c: LiveChannel, position: Int) {
        val ctx = context ?: return
        val now = CxLive.toggleFavourite(ctx, c, scope)
        favourites = CxLive.favouriteKeys(ctx, scope)
        Toast.makeText(ctx, if (now) "Ditambah ke Kegemaran" else "Dibuang daripada Kegemaran", Toast.LENGTH_SHORT).show()
        if (current == FAV) select(FAV, false) else channelList.adapter?.notifyItemChanged(position)
    }

    // ---------------- adapters ----------------
    private inner class GroupAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = if (groups.size > 1) groups.size + 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = TextView(parent.context).apply {
                textSize = 14f; isFocusable = true; isClickable = true; background = states(if (wide) 12 else 20)
                setPadding(dp(14), dp(if (wide) 12 else 8), dp(14), dp(if (wide) 12 else 8))
                if (wide) maxLines = 2 else setSingleLine(true)
                layoutParams = if (wide) RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                else RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView as TextView
            val (name, count) = if (position == 0) "Semua" to channels.size else groups[position - 1]
            val value = if (position == 0) null else name
            val selected = group == value
            v.text = "$name  $count"
            v.isSelected = selected
            v.setTextColor(if (selected) Color.rgb(4, 20, 30) else fg)
            v.setOnClickListener { group = value; notifyDataSetChanged(); applyFilter() }
        }
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = shown.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val tile = FrameLayout(ctx).apply { background = shape(surfaceHigh, 10) }
            tile.addView(TextView(ctx).apply { textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(muted); gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            tile.addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val name = TextView(ctx).apply { setTextColor(fg); textSize = 14.5f; setTypeface(typeface, Typeface.BOLD); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            val sub = TextView(ctx).apply { setTextColor(muted); textSize = 12f; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }
            val star = ImageView(ctx).apply { setImageResource(R.drawable.cx_ic_star); imageTintList = ColorStateList.valueOf(Color.rgb(255, 196, 0)) }
            val card = if (wide) {
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
                    setPadding(dp(8), dp(8), dp(8), dp(10))
                    addView(FrameLayout(ctx).apply {
                        addView(tile, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (tv) 96 else 84)))
                        addView(star, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
                    })
                    addView(name.apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(8), 0, 0) })
                    addView(sub.apply { gravity = Gravity.CENTER_HORIZONTAL })
                }
            } else {
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) }
                    setPadding(dp(10), dp(10), dp(12), dp(10))
                    addView(tile, LinearLayout.LayoutParams(dp(72), dp(52)).apply { marginEnd = dp(14) })
                    addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(name); addView(sub) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(star, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
                }
            }
            card.background = states(14); card.isFocusable = true; card.isClickable = true; card.isLongClickable = true
            card.tag = Holder(tile, name, sub, star)
            return object : RecyclerView.ViewHolder(card) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val c = shown[position]
            val h = holder.itemView.tag as Holder
            h.name.text = c.name
            h.sub.text = if (c.isYoutube) "YouTube · " + c.group else c.group
            h.star.visibility = if (c.key in favourites) View.VISIBLE else View.GONE
            val initials = h.tile.getChildAt(0) as TextView
            val logo = h.tile.getChildAt(1) as ImageView
            initials.text = initials(c.name)
            val logoUrl = c.logo ?: CxLogos.find(c)
            if (logoUrl != null) { initials.visibility = View.INVISIBLE; logo.loadImage(logoUrl) } else { initials.visibility = View.VISIBLE; logo.setImageDrawable(null) }
            holder.itemView.setOnClickListener { play(holder.bindingAdapterPosition) }
            holder.itemView.setOnLongClickListener { toggleFavourite(c, holder.bindingAdapterPosition); true }
        }
    }
    private class Holder(val tile: FrameLayout, val name: TextView, val sub: TextView, val star: ImageView)

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
            } catch (e: Exception) {
                status.text = (e as? LiveProblem)?.message ?: "Fail tidak dapat dibaca."
            }
        }
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
            } catch (e: Exception) {
                info.text = if (CxWorkerActivation.serverCode(e) == "user_playlists_not_allowed") "Playlist sendiri tidak dibenarkan untuk lesen ini."
                else "Kod tidak dapat diminta. Semak Internet dan cuba lagi."
            }
        }
    }

    companion object {
        private const val FAV = "@fav"
        private const val RECENT = "@recent"
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
