package com.lagradost.cloudstream3.ui.live

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
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
 * Live TV menu (V5.0.24): the admin's playlists (and the official free channels), the user's own
 * playlists where allowed, groups, search, favourites and recently watched channels.
 * Works with the remote (D-pad), touch, a mouse and a keyboard.
 */
class CxLiveFragment : Fragment() {
    private val accent = Color.rgb(0, 204, 239)
    private val surface = Color.rgb(20, 30, 42)
    private val fg = Color.rgb(232, 241, 248)
    private val muted = Color.rgb(155, 179, 198)

    private lateinit var status: TextView
    private lateinit var sourceRow: LinearLayout
    private lateinit var search: EditText
    private lateinit var groupList: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var addButton: Button
    private lateinit var phoneButton: Button

    private var sources: List<LiveSource> = emptyList()
    private var allowOwn = false
    private var current: String = "" // source id, or FAV / RECENT
    private var channels: List<LiveChannel> = emptyList()
    private var groups: List<Pair<String, Int>> = emptyList()
    private var group: String? = null
    private var shown: List<LiveChannel> = emptyList()
    private var loadJob: Job? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importFile(it) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private val tv get() = isLayout(TV)

    private fun focusBackground(radius: Int = 10): StateListDrawable = StateListDrawable().apply {
        val focused = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(Color.rgb(28, 52, 70)); setStroke(dp(3), accent) }
        val normal = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(surface) }
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), focused)
        addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(Color.rgb(0, 96, 116)) })
        addState(intArrayOf(), normal)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(requireContext()).apply {
        text = label; isAllCaps = false; setTextColor(fg); textSize = 14f
        background = focusBackground(18); setPadding(dp(14), dp(6), dp(14), dp(6)); minHeight = dp(40); minimumHeight = dp(40)
        isFocusable = true; setOnClickListener { onClick() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 12, 21))
            setPadding(dp(if (tv) 16 else 10), dp(if (tv) 12 else 8), dp(if (tv) 16 else 10), 0)
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply { text = "Live TV"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(accent) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addButton = button("+ Playlist") { addPlaylistDialog() }
        phoneButton = button("Dari telefon") { phoneDialog() }
        header.addView(button("Muat semula") { reload(fresh = true) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        header.addView(addButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        header.addView(phoneButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        root.addView(header)

        sourceRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(4)) }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(sourceRow) })

        search = EditText(ctx).apply {
            hint = "Cari saluran…"; setSingleLine(true); setTextColor(fg); setHintTextColor(muted)
            background = focusBackground(10); setPadding(dp(12), dp(8), dp(12), dp(8)); inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { applyFilter() }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(6) })
        status = TextView(ctx).apply { setTextColor(muted); textSize = 13f; setPadding(dp(2), 0, 0, dp(4)) }
        root.addView(status)

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        groupList = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx); adapter = GroupAdapter(); isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        channelList = RecyclerView(ctx).apply {
            val span = if (tv) 4 else if (isLayout(PHONE) && resources.configuration.screenWidthDp < 600) 1 else 3
            layoutManager = GridLayoutManager(ctx, span); adapter = ChannelAdapter(); descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            clipToPadding = false; setPadding(0, 0, 0, dp(80))
        }
        val groupWidth = minOf(dp(240), (resources.displayMetrics.widthPixels * 0.34f).toInt())
        body.addView(groupList, LinearLayout.LayoutParams(groupWidth, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(8) })
        body.addView(channelList, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reload(fresh = true)
    }

    // ---------------- sources ----------------
    private fun reload(fresh: Boolean) {
        val ctx = context ?: return
        status.text = "Memuatkan senarai…"
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) { CxLive.config(ctx, fresh) }
            allowOwn = config.allowOwn
            sources = config.admin + if (allowOwn) CxLive.ownSources(ctx) else emptyList()
            addButton.visibility = if (allowOwn) View.VISIBLE else View.GONE
            phoneButton.visibility = if (allowOwn) View.VISIBLE else View.GONE
            renderSources()
            val keep = current.takeIf { c -> c == FAV || c == RECENT || sources.any { it.id == c } }
            select(keep ?: sources.firstOrNull()?.id ?: FAV, fresh)
        }
    }

    private fun renderSources() {
        sourceRow.removeAllViews()
        val items = sources.map { it.id to it.name } + listOf(FAV to "★ Kegemaran", RECENT to "Baru ditonton")
        for ((id, name) in items) {
            sourceRow.addView(button(name) { select(id, false) }.apply {
                isSelected = id == current
                setOnLongClickListener {
                    sources.firstOrNull { it.id == id && it.own }?.let { confirmRemove(it) }; true
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
    }

    private fun select(id: String, fresh: Boolean) {
        val ctx = context ?: return
        current = id; group = null
        for (i in 0 until sourceRow.childCount) sourceRow.getChildAt(i).isSelected = false
        renderSources()
        loadJob?.cancel()
        when (id) {
            FAV -> show(CxLive.favourites(ctx), "Tiada kegemaran lagi. Tekan lama pada saluran untuk menambahnya.")
            RECENT -> show(CxLive.recent(ctx), "Tiada saluran yang baru ditonton.")
            else -> {
                val source = sources.firstOrNull { it.id == id } ?: return
                status.text = "Memuatkan " + source.name + "…"
                show(emptyList(), null)
                loadJob = lifecycleScope.launch {
                    try {
                        val list = withContext(Dispatchers.IO) { CxLive.channels(ctx, source, fresh) }
                        if (current == id) show(list, "Tiada saluran.")
                    } catch (e: Exception) {
                        if (current == id) {
                            show(emptyList(), null)
                            status.text = (e as? LiveProblem)?.message ?: ("Gagal memuatkan (" + e.javaClass.simpleName + ").")
                        }
                    }
                }
            }
        }
    }

    private fun show(list: List<LiveChannel>, emptyText: String?) {
        channels = list
        groups = list.groupingBy { it.group }.eachCount().toList()
        groupList.adapter?.notifyDataSetChanged()
        applyFilter()
        if (list.isEmpty() && emptyText != null) status.text = emptyText
    }

    private fun applyFilter() {
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        shown = channels.filter { (group == null || it.group == group) && (q.isEmpty() || it.name.lowercase().contains(q)) }
        channelList.adapter?.notifyDataSetChanged()
        if (channels.isNotEmpty()) status.text = shown.size.toString() + " saluran" + (group?.let { " · $it" } ?: "")
    }

    // ---------------- play ----------------
    private fun play(index: Int) {
        val c = shown.getOrNull(index) ?: return
        val ctx = context ?: return
        CxLive.addRecent(ctx, c)
        if (c.isYoutube) {
            startActivity(Intent(ctx, CxYoutubeActivity::class.java).putExtra("channel", c.url.removePrefix("youtube:")).putExtra("title", c.name))
            return
        }
        // The player moves through the channels of this list (YouTube ones are opened separately).
        val playable = shown.filter { !it.isYoutube }
        val at = playable.indexOf(c).coerceAtLeast(0)
        val title = sources.firstOrNull { it.id == current }?.name ?: "Live TV"
        activity.navigate(R.id.global_to_navigation_player, GeneratorPlayer.newInstance(CxLiveGenerator(playable, title), at))
    }

    // ---------------- adapters ----------------
    private inner class GroupAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = if (groups.isEmpty()) 0 else groups.size + 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
            setTextColor(fg); textSize = 14f; setPadding(dp(12), dp(10), dp(12), dp(10)); background = focusBackground(8)
            isFocusable = true; isClickable = true; maxLines = 2
        }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView as TextView
            val (name, count) = if (position == 0) "Semua" to channels.size else groups[position - 1]
            val value = if (position == 0) null else name
            v.text = "$name ($count)"
            v.isSelected = group == value
            v.setOnClickListener { group = value; notifyDataSetChanged(); applyFilter() }
        }
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = shown.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                background = focusBackground(10); setPadding(dp(8), dp(6), dp(8), dp(6)); isFocusable = true; isClickable = true; isLongClickable = true
            }
            row.addView(ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(56), dp(44)).apply { marginEnd = dp(8) })
            row.addView(TextView(parent.context).apply { setTextColor(fg); textSize = 14f; maxLines = 2 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return object : RecyclerView.ViewHolder(row) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val c = shown[position]
            val row = holder.itemView as LinearLayout
            val logo = row.getChildAt(0) as ImageView
            val name = row.getChildAt(1) as TextView
            val fav = context?.let { CxLive.isFavourite(it, c) } == true
            name.text = (if (fav) "★ " else "") + c.name + if (c.isYoutube) "  ▶ YouTube" else ""
            if (c.logo != null) logo.loadImage(c.logo) else logo.setImageDrawable(null)
            row.setOnClickListener { play(holder.bindingAdapterPosition) }
            row.setOnLongClickListener {
                val ctx = context ?: return@setOnLongClickListener true
                val now = CxLive.toggleFavourite(ctx, c)
                Toast.makeText(ctx, if (now) "Ditambah ke Kegemaran" else "Dibuang daripada Kegemaran", Toast.LENGTH_SHORT).show()
                if (current == FAV) select(FAV, false) else notifyItemChanged(holder.bindingAdapterPosition)
                true
            }
        }
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
        fun refresh() {
            url.visibility = if (link.isChecked) View.VISIBLE else View.GONE
            listOf(server, user, pass).forEach { it.visibility = if (xtream.isChecked) View.VISIBLE else View.GONE }
        }
        modes.setOnCheckedChangeListener { _, _ -> refresh() }
        listOf(name, modes, url, server, user, pass, note).forEach { box.addView(it) }
        refresh()
        AlertDialog.Builder(ctx).setTitle("Tambah playlist").setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(if (file.isChecked) "Pilih fail" else "Tambah") { _, _ ->
                when {
                    file.isChecked -> pickFile.launch("*/*")
                    xtream.isChecked -> saveOwn(LiveSource(CxLive.newId(), name.text.toString().trim().ifEmpty { "Xtream" }, "xtream",
                        server = server.text.toString().trim(), username = user.text.toString().trim(), password = pass.text.toString(), own = true))
                    else -> {
                        val l = url.text.toString().trim()
                        val x = CxLive.xtreamFromLink(l)
                        saveOwn(if (x != null) LiveSource(CxLive.newId(), name.text.toString().trim().ifEmpty { "Xtream" }, "xtream",
                            server = x.server, username = x.username, password = x.password, own = true)
                        else LiveSource(CxLive.newId(), name.text.toString().trim().ifEmpty { "Playlist" }, "m3u", url = l, own = true))
                    }
                }
            }
            .setNegativeButton("Batal", null).show()
            .also { dialog -> modes.setOnCheckedChangeListener { _, _ -> refresh(); dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text = if (file.isChecked) "Pilih fail" else "Tambah" } }
    }

    /** Checks a new playlist (downloads it once) before keeping it. */
    private fun saveOwn(source: LiveSource) {
        val ctx = context ?: return
        status.text = "Menyemak playlist…"
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { CxLive.channels(ctx, source, true) }
                CxLive.addOwn(ctx, source)
                Toast.makeText(ctx, source.name + ": " + list.size + " saluran", Toast.LENGTH_LONG).show()
                current = source.id
                reload(fresh = false)
            } catch (e: Exception) {
                status.text = (e as? LiveProblem)?.message ?: ("Playlist tidak dapat ditambah (" + e.javaClass.simpleName + ").")
                AlertDialog.Builder(ctx).setTitle("Playlist tidak ditambah").setMessage(status.text).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun importFile(uri: Uri) {
        val ctx = context ?: return
        lifecycleScope.launch {
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
        val dialog = AlertDialog.Builder(ctx).setTitle("Hantar playlist dari telefon").setView(box)
            .setNegativeButton("Tutup", null).setOnDismissListener { job?.cancel() }.show()
        job = lifecycleScope.launch {
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
                        else {
                            val l = playlist.optString("url"); val x = CxLive.xtreamFromLink(l)
                            saveOwn(if (x != null) LiveSource(CxLive.newId(), n ?: "Xtream", "xtream", server = x.server, username = x.username, password = x.password, own = true)
                            else LiveSource(CxLive.newId(), n ?: "Playlist", "m3u", url = l, own = true))
                        }
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
