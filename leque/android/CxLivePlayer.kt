package com.lagradost.cloudstream3.ui.live

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live TV player, like an IPTV app: the channel bar (OK / tap), the channel list (Up), the
 * schedule (Down), previous / next channel (Left / Right, CH- / CH+), channel numbers on the
 * remote, audio and subtitles, favourites, picture size and "report a problem".
 */
@OptIn(UnstableApi::class)
class CxLivePlayerActivity : AppCompatActivity() {
    companion object {
        /** Plays [list] from [index]; [title] is the list's name (category or source), [guide] its XMLTV guide. */
        fun start(context: Context, list: List<LiveChannel>, index: Int, title: String, guide: String?) {
            CxLive.playing = list
            context.startActivity(Intent(context, CxLivePlayerActivity::class.java)
                .putExtra("index", index).putExtra("title", title).putExtra("guide", guide))
        }
    }

    private val accent = Color.rgb(56, 189, 248)
    private val fg = Color.WHITE
    private val muted = Color.rgb(170, 184, 200)
    private val panelBg = Color.argb(235, 12, 20, 33)

    private lateinit var player: ExoPlayer
    private lateinit var video: PlayerView
    private lateinit var bar: LinearLayout
    private lateinit var barLogo: ImageView
    private lateinit var barName: TextView
    private lateinit var barNow: TextView
    private lateinit var barNext: TextView
    private lateinit var barProgress: ProgressBar
    private lateinit var barClock: TextView
    private lateinit var badge: TextView
    private lateinit var favButton: TextView
    private lateinit var prevButton: View
    private lateinit var nextButton: View
    private lateinit var spinner: ProgressBar
    private lateinit var message: TextView
    private lateinit var numberView: TextView
    private lateinit var channelPanel: LinearLayout
    private lateinit var channelTitle: TextView
    private lateinit var channelList: RecyclerView
    private lateinit var categoryList: RecyclerView
    /** The channel list panel: every category of the source on the left, its channels on the right. */
    private var allChannels: List<LiveChannel> = emptyList()
    private var categories: List<String> = emptyList()
    private var panelCategory = 0
    private var panelChannels: List<LiveChannel> = emptyList()
    private lateinit var epgPanel: LinearLayout
    private lateinit var epgTitle: TextView
    private lateinit var epgList: LinearLayout

    private var list: List<LiveChannel> = emptyList()
    private var index = 0
    private var title = ""
    private var guide: Map<String, List<CxEpg.Prog>> = emptyMap()
    private var retried = false
    private var typed = ""
    private val handler = Handler(Looper.getMainLooper())
    private val hideBar = Runnable { bar.visibility = View.GONE; prevButton.visibility = View.GONE; nextButton.visibility = View.GONE }
    private val clockTick = object : Runnable {
        override fun run() { updateClockAndEpg(); handler.postDelayed(this, 30_000) }
    }

    private fun t(ms: String, en: String) = CxLiveManage.t(this, ms, en)
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    // ---------------- set-up ----------------
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = CxLive.playing.filter { !it.isYoutube }
        if (list.isEmpty()) { finish(); return }
        index = intent.getIntExtra("index", 0).coerceIn(0, list.lastIndex)
        title = intent.getStringExtra("title").orEmpty()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = PlayerView(this).apply { useController = false; setShutterBackgroundColor(Color.BLACK); keepScreenOn = true }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        // Tap anywhere: show or hide the channel bar.
        root.addView(View(this).apply { setOnClickListener { if (panelsOpen()) closePanels() else toggleBar() } }, FrameLayout.LayoutParams(-1, -1))

        spinner = ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(accent) }
        root.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        message = TextView(this).apply {
            setTextColor(fg); textSize = 16f; gravity = Gravity.CENTER; background = rounded(Color.argb(200, 0, 0, 0), 14)
            setPadding(dp(20), dp(14), dp(20), dp(14)); visibility = View.GONE
        }
        root.addView(message, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        numberView = TextView(this).apply {
            setTextColor(fg); textSize = 40f; setTypeface(typeface, Typeface.BOLD); background = rounded(Color.argb(190, 0, 0, 0), 14)
            setPadding(dp(18), dp(6), dp(18), dp(6)); visibility = View.GONE
        }
        root.addView(numberView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(24), dp(32), 0) })
        badge = TextView(this).apply { setTextColor(muted); textSize = 11f; background = rounded(Color.argb(150, 0, 0, 0), 6); setPadding(dp(6), dp(2), dp(6), dp(2)) }

        // Previous / next channel (touch).
        prevButton = roundButton(android.R.drawable.ic_media_previous, t("Saluran sebelum", "Previous channel")) { step(-1) }
        nextButton = roundButton(android.R.drawable.ic_media_next, t("Saluran seterusnya", "Next channel")) { step(1) }
        root.addView(prevButton, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER).apply { marginEnd = dp(260) })
        root.addView(nextButton, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER).apply { marginStart = dp(260) })

        root.addView(buildBar(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(buildChannelPanel(), FrameLayout.LayoutParams(dp(680).coerceAtMost(resources.displayMetrics.widthPixels), -1, Gravity.START))
        root.addView(buildEpgPanel(), FrameLayout.LayoutParams(-1, dp(260), Gravity.BOTTOM))
        setContentView(root)

        player = buildPlayer()
        video.player = player
        intent.getStringExtra("guide")?.let { url ->
            lifecycleScope.launch { guide = CxEpg.load(this@CxLivePlayerActivity, url); updateClockAndEpg() }
        }
        play(index)
        handler.post(clockTick)
    }

    private fun roundButton(icon: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(fg); contentDescription = label
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(150, 30, 41, 59)) }
        setPadding(dp(16), dp(16), dp(16), dp(16)); isFocusable = true; isClickable = true; visibility = View.GONE
        setOnClickListener { onClick() }
    }

    private fun barButton(icon: Int?, label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(fg); textSize = 14f; gravity = Gravity.CENTER_VERTICAL
        if (icon != null) { setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0); compoundDrawableTintList = ColorStateList.valueOf(fg); compoundDrawablePadding = dp(8) }
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(Color.argb(120, 56, 189, 248), 18))
            addState(intArrayOf(), rounded(Color.TRANSPARENT, 18))
        }
        setPadding(dp(14), dp(8), dp(14), dp(8)); isFocusable = true; isClickable = true
        setOnClickListener { onClick(); scheduleHide() }
    }

    private fun buildBar(): View {
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(36), dp(28), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(235, 0, 0, 0), Color.argb(170, 0, 0, 0), Color.TRANSPARENT))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        barLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; background = rounded(Color.argb(90, 255, 255, 255), 8); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        top.addView(barLogo, LinearLayout.LayoutParams(dp(84), dp(52)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), 0) }
        barName = TextView(this).apply { setTextColor(fg); textSize = 19f; setTypeface(typeface, Typeface.BOLD); setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }
        barNow = TextView(this).apply { setTextColor(fg); textSize = 14f; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }
        barProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = ColorStateList.valueOf(accent) }
        barNext = TextView(this).apply { setTextColor(muted); textSize = 12f; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }
        names.addView(barName); names.addView(barNow); names.addView(barProgress, LinearLayout.LayoutParams(dp(280), dp(6)).apply { topMargin = dp(4); bottomMargin = dp(2) }); names.addView(barNext)
        top.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        barClock = TextView(this).apply { setTextColor(fg); textSize = 18f; setTypeface(typeface, Typeface.BOLD) }
        right.addView(barClock); right.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        top.addView(right)
        bar.addView(top)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        buttons.addView(barButton(R.drawable.cx_cat_all, t("Saluran", "Channels")) { openChannels() })
        buttons.addView(barButton(R.drawable.cx_cat_news, t("Jadual", "Schedule")) { openEpg() })
        buttons.addView(barButton(R.drawable.ic_outline_subtitles_24, t("Audio & sari kata", "Audio & subtitles")) { tracksDialog() })
        favButton = barButton(R.drawable.cx_ic_star, t("Kegemaran", "Favourite")) { toggleFavourite() }
        buttons.addView(favButton)
        buttons.addView(barButton(R.drawable.cx_more, t("Lagi", "More")) { moreDialog() })
        bar.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(buttons) })
        bar.visibility = View.GONE
        return bar
    }

    private fun buildChannelPanel(): View {
        channelPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(panelBg); setPadding(dp(12), dp(20), dp(12), dp(12)); visibility = View.GONE }
        channelTitle = TextView(this).apply { setTextColor(fg); textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), 0, dp(8), dp(10)) }
        channelPanel.addView(channelTitle)
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        categoryList = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@CxLivePlayerActivity); adapter = CategoryAdapter() }
        columns.addView(categoryList, LinearLayout.LayoutParams(dp(210), -1))
        columns.addView(View(this).apply { setBackgroundColor(Color.argb(60, 255, 255, 255)) }, LinearLayout.LayoutParams(dp(1), -1).apply { setMargins(dp(6), 0, dp(6), 0) })
        channelList = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@CxLivePlayerActivity); adapter = ChannelAdapter() }
        columns.addView(channelList, LinearLayout.LayoutParams(0, -1, 1f))
        channelPanel.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        return channelPanel
    }

    private fun buildEpgPanel(): View {
        epgPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(panelBg); setPadding(dp(28), dp(16), dp(28), dp(12)); visibility = View.GONE }
        epgTitle = TextView(this).apply { setTextColor(fg); textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(8)) }
        epgPanel.addView(epgTitle)
        epgList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        epgPanel.addView(android.widget.ScrollView(this).apply { addView(epgList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return epgPanel
    }

    // ---------------- playback ----------------
    private fun buildPlayer(): ExoPlayer {
        val renderers = runCatching {
            com.lagradost.cloudstream3.ui.player.FixedNextRenderersFactory(this).apply {
                setEnableDecoderFallback(true)
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            }
        }.getOrElse { DefaultRenderersFactory(this).setEnableDecoderFallback(true) }
        return ExoPlayer.Builder(this, renderers).build().apply {
            // Subtitles in the language chosen in Settings > Live TV.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredTextLanguages(*CxLive.subtitleLanguages(this@CxLivePlayerActivity, "en").toTypedArray())
                .build()
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_READY) { message.visibility = View.GONE; retried = false; updateBadge() }
                }
                override fun onTracksChanged(tracks: Tracks) = updateBadge()
                override fun onPlayerError(error: PlaybackException) {
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { seekToDefaultPosition(); prepare(); return }
                    if (!retried) { retried = true; handler.postDelayed({ play(index, keepRetry = true) }, 1500); return }
                    spinner.visibility = View.GONE
                    message.text = t("Saluran ini tidak dapat dimainkan sekarang.", "This channel cannot be played right now.") +
                        "\n(" + error.errorCodeName + ")\n" + t("◀ ▶ untuk tukar saluran", "◀ ▶ to change channel")
                    message.visibility = View.VISIBLE
                    showBar()
                }
            })
        }
    }

    private fun play(i: Int, keepRetry: Boolean = false) {
        if (list.isEmpty()) return
        index = (i + list.size) % list.size
        if (!keepRetry) retried = false
        val c = list[index]
        CxLive.addRecent(this, c, "")
        message.visibility = View.GONE
        spinner.visibility = View.VISIBLE

        val headers = HashMap<String, String>()
        c.referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        c.headersJson?.let { json -> runCatching { val o = org.json.JSONObject(json); o.keys().forEach { k -> headers[k] = o.getString(k) } } }
        val ua = c.userAgent?.takeIf { it.isNotBlank() } ?: headers.remove("User-Agent") ?: headers.remove("user-agent")
            ?: "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        val data = OkHttpDataSource.Factory(app.baseClient).setUserAgent(ua).setDefaultRequestProperties(headers)
        val factory = DefaultMediaSourceFactory(this).setDataSourceFactory(data)
        val lower = c.url.lowercase()
        val item = MediaItem.Builder().setUri(c.url).apply {
            when {
                lower.contains(".m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                lower.contains(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
            val drm = c.drm?.let { CxLive.drmParts(it) }
            if (drm != null) {
                val uuid = if (drm[0] == "widevine") C.WIDEVINE_UUID else C.CLEARKEY_UUID
                if (drm[1] != null && drm[2] != null) {
                    // ClearKey keys from the playlist (#KODIPROP): no licence server.
                    val keys = """{"keys":[{"kty":"oct","k":"${drm[2]}","kid":"${drm[1]}"}],"type":"temporary"}"""
                    val manager = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .build(LocalMediaDrmCallback(keys.toByteArray()))
                    factory.setDrmSessionManagerProvider { manager }
                } else if (drm[3] != null) {
                    setDrmConfiguration(MediaItem.DrmConfiguration.Builder(uuid).setLicenseUri(drm[3]).setLicenseRequestHeaders(headers).build())
                }
            }
        }.build()
        player.setMediaSource(factory.createMediaSource(item))
        player.prepare()
        player.playWhenReady = true
        updateChannelInfo()
        showBar()
    }

    private fun step(by: Int) { if (list.size > 1) play(index + by) }

    override fun onStop() { super.onStop(); if (::player.isInitialized) player.pause() }
    override fun onStart() { super.onStart(); if (::player.isInitialized && player.playbackState != Player.STATE_IDLE) player.play() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    // ---------------- channel bar ----------------
    private fun updateChannelInfo() {
        val c = list[index]
        barName.text = (c.number?.let { "$it  " } ?: "") + c.name
        barLogo.loadImage(null as String?)
        barLogo.setImageDrawable(null)
        (c.logo ?: CxLogos.find(c))?.let { barLogo.loadImage(it) }
        favButton.text = if (c.key in CxLive.favouriteKeys(this, "")) t("★ Kegemaran", "★ Favourite") else t("Kegemaran", "Favourite")
        updateClockAndEpg()
        if (channelPanel.visibility == View.VISIBLE) channelList.adapter?.notifyDataSetChanged()
    }

    private val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    private fun updateClockAndEpg() {
        barClock.text = time.format(Date())
        val c = list.getOrNull(index) ?: return
        val (now, next) = CxEpg.nowNext(guide, c)
        if (now != null) {
            barNow.text = now.title + "   " + time.format(Date(now.start)) + " – " + time.format(Date(now.stop))
            barProgress.visibility = View.VISIBLE
            barProgress.progress = (((System.currentTimeMillis() - now.start) * 1000) / (now.stop - now.start).coerceAtLeast(1)).toInt().coerceIn(0, 1000)
        } else {
            barNow.text = if (guide.isEmpty()) t("Tiada jadual (EPG)", "No schedule (EPG)") else title
            barProgress.visibility = View.GONE
        }
        barNext.text = next?.let { t("Seterusnya: ", "Next: ") + it.title + "  " + time.format(Date(it.start)) } ?: title
    }

    private fun updateBadge() {
        val f = player.videoFormat ?: run { badge.visibility = View.GONE; return }
        val q = when { f.height >= 2160 -> "4K"; f.height >= 1080 -> "FHD"; f.height >= 720 -> "HD"; f.height > 0 -> "SD"; else -> null }
        val fps = f.frameRate.takeIf { it > 0 }?.let { Math.round(it).toString() }
        val rate = (f.bitrate.takeIf { it > 0 } ?: f.averageBitrate.takeIf { it > 0 })?.let { String.format(Locale.US, "%.1fMbps", it / 1_000_000.0) }
        badge.text = listOfNotNull(q, fps, rate).joinToString("  ")
        badge.visibility = if (badge.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun showBar(focus: Boolean = false) {
        bar.visibility = View.VISIBLE; prevButton.visibility = View.VISIBLE; nextButton.visibility = View.VISIBLE
        if (focus) bar.post { (((bar.getChildAt(1) as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0))?.requestFocus() }
        scheduleHide()
    }
    private fun scheduleHide() { handler.removeCallbacks(hideBar); handler.postDelayed(hideBar, 6000) }
    private fun toggleBar() { if (bar.visibility == View.VISIBLE) hideBar.run() else showBar() }

    // ---------------- channel list (Up) ----------------
    private fun rowBackground() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Color.argb(140, 56, 189, 248), 10))
        addState(intArrayOf(android.R.attr.state_selected), rounded(Color.argb(70, 56, 189, 248), 10))
        addState(intArrayOf(), rounded(Color.TRANSPARENT, 10))
    }

    /** Left column: "All" and every category of the source; moving onto one shows its channels. */
    private inner class CategoryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = categories.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context).apply {
                setTextColor(fg); textSize = 15f; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), dp(11), dp(10), dp(11)); isFocusable = true; isClickable = true; background = rowBackground()
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val tv = holder.itemView as TextView
            val name = categories[position]
            tv.text = name + "  (" + channelsOf(position).size + ")"
            tv.isSelected = position == panelCategory
            tv.setTypeface(null, if (position == panelCategory) Typeface.BOLD else Typeface.NORMAL)
            tv.setOnClickListener { showCategory(holder.bindingAdapterPosition, focusChannels = true) }
            tv.setOnFocusChangeListener { _, has -> if (has && holder.bindingAdapterPosition != panelCategory) showCategory(holder.bindingAdapterPosition, focusChannels = false) }
        }
    }

    private fun channelsOf(category: Int): List<LiveChannel> {
        if (category <= 0 || categories.size <= 1) return allChannels
        val key = CxLive.groupKey(categories[category])
        return allChannels.filter { c -> CxLive.groupParts(c.group).any { CxLive.groupKey(it) == key } }
    }

    private fun showCategory(position: Int, focusChannels: Boolean) {
        if (position !in categories.indices) return
        val old = panelCategory
        panelCategory = position
        panelChannels = channelsOf(position)
        categoryList.adapter?.notifyItemChanged(old)
        categoryList.adapter?.notifyItemChanged(position)
        channelList.adapter?.notifyDataSetChanged()
        val playing = panelChannels.indexOfFirst { it.key == list.getOrNull(index)?.key }.coerceAtLeast(0)
        (channelList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(playing, dp(120))
        if (focusChannels) channelList.post { channelList.findViewHolderForAdapterPosition(playing)?.itemView?.requestFocus() ?: channelList.requestFocus() }
    }

    /** Right column: the channels of the chosen category, with what is on now (EPG). */
    private inner class ChannelAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = panelChannels.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
                isFocusable = true; isClickable = true; background = rowBackground()
            }
            val num = TextView(parent.context).apply { setTextColor(muted); textSize = 13f; minWidth = dp(40) }
            val logo = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val texts = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            val name = TextView(parent.context).apply { setTextColor(fg); textSize = 15f; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }
            val sub = TextView(parent.context).apply { setTextColor(muted); textSize = 12f; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }
            texts.addView(name); texts.addView(sub)
            row.addView(num); row.addView(logo, LinearLayout.LayoutParams(dp(48), dp(30))); row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.tag = arrayOf(num, logo, name, sub)
            return object : RecyclerView.ViewHolder(row) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val c = panelChannels[position]
            @Suppress("UNCHECKED_CAST") val v = holder.itemView.tag as Array<View>
            (v[0] as TextView).text = c.number?.toString() ?: (position + 1).toString()
            val logo = v[1] as ImageView
            logo.loadImage(null as String?); logo.setImageDrawable(null)
            (c.logo ?: CxLogos.find(c))?.let { logo.loadImage(it) }
            (v[2] as TextView).text = c.name
            val (now, next) = CxEpg.nowNext(guide, c)
            (v[3] as TextView).text = when {
                now != null -> "▶ " + now.title + "  " + time.format(Date(now.start)) + "–" + time.format(Date(now.stop)) +
                    (next?.let { "   ·   " + time.format(Date(it.start)) + " " + it.title } ?: "")
                guide.isEmpty() -> ""
                else -> t("Tiada jadual", "No schedule")
            }
            holder.itemView.isSelected = c.key == list.getOrNull(index)?.key
            holder.itemView.setOnClickListener {
                // The chosen category becomes the list: CH+ / CH- and Left / Right move through it.
                val pos = holder.bindingAdapterPosition
                if (pos !in panelChannels.indices) return@setOnClickListener
                list = panelChannels
                title = categories.getOrNull(panelCategory)?.takeIf { panelCategory > 0 } ?: title
                CxLive.playing = list
                play(pos); closePanels()
            }
        }
    }

    private fun openChannels() {
        hideBar.run(); epgPanel.visibility = View.GONE
        val current = list.getOrNull(index)
        // The whole source when the list came from it (else just this list, e.g. Favourites).
        allChannels = CxLive.numbered.takeIf { all -> all.isNotEmpty() && list.all { c -> all.any { it.key == c.key } } }
            ?.filter { !it.isYoutube } ?: list
        val names = if (allChannels === list) emptyList() else CxLive.categories.ifEmpty {
            allChannels.flatMap { CxLive.groupParts(it.group) }.distinctBy { CxLive.groupKey(it) }
        }
        categories = listOf(t("Semua", "All")) + names
        // Start on the category of the channel playing.
        panelCategory = current?.let { c -> CxLive.groupParts(c.group).firstOrNull()?.let { g -> categories.indexOfFirst { CxLive.groupKey(it) == CxLive.groupKey(g) } } }
            ?.takeIf { it > 0 && names.isNotEmpty() } ?: 0
        channelTitle.text = t("Saluran", "Channels") + "   ·   " + t("◀ kategori   ▶ saluran", "◀ categories   ▶ channels")
        channelPanel.visibility = View.VISIBLE
        categoryList.adapter?.notifyDataSetChanged()
        categoryList.scrollToPosition(panelCategory)
        showCategory(panelCategory, focusChannels = true)
    }

    // ---------------- schedule (Down) ----------------
    private fun openEpg() {
        hideBar.run(); channelPanel.visibility = View.GONE
        val c = list[index]
        epgTitle.text = t("Jadual: ", "Schedule: ") + c.name
        epgList.removeAllViews()
        val progs = CxEpg.programmes(guide, c)
        val now = System.currentTimeMillis()
        if (progs.isEmpty()) epgList.addView(TextView(this).apply {
            text = t("Tiada jadual (EPG) untuk saluran ini. Playlist perlu menyediakan link EPG (url-tvg).", "No schedule (EPG) for this channel. The playlist needs an EPG link (url-tvg).")
            setTextColor(muted); textSize = 14f
        })
        for (p in progs) {
            val on = p.start <= now && now < p.stop
            epgList.addView(TextView(this).apply {
                text = time.format(Date(p.start)) + " – " + time.format(Date(p.stop)) + "    " + p.title + if (on) t("   ● Sekarang", "   ● Now") else ""
                setTextColor(if (on) accent else fg); textSize = 15f; setPadding(0, dp(6), 0, dp(6))
                if (on) setTypeface(typeface, Typeface.BOLD)
                isFocusable = true
            })
        }
        epgPanel.visibility = View.VISIBLE
        epgPanel.post { epgList.getChildAt(0)?.requestFocus() }
    }

    private fun panelsOpen() = channelPanel.visibility == View.VISIBLE || epgPanel.visibility == View.VISIBLE
    private fun closePanels() { channelPanel.visibility = View.GONE; epgPanel.visibility = View.GONE; video.requestFocus() }

    // ---------------- audio and subtitles ----------------
    private fun tracksDialog() {
        data class Choice(val label: String, val apply: () -> Unit)
        val items = ArrayList<Choice>()
        for (group in player.currentTracks.groups) {
            val type = group.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val lang = f.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } }
                val name = listOfNotNull(f.label, lang, if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) f.channelCount.toString() + "ch" else null)
                    .distinct().joinToString(" · ").ifEmpty { t("Trek ", "Track ") + (i + 1) }
                val selected = group.isTrackSelected(i)
                items += Choice((if (type == C.TRACK_TYPE_AUDIO) "🔊 " else "💬 ") + name + if (selected) "  ✓" else "") {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
                }
            }
        }
        val subsOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        items += Choice("💬 " + t("Tutup sari kata", "Subtitles off") + if (subsOff) "  ✓" else "") {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        AlertDialog.Builder(this).setTitle(t("Audio & sari kata", "Audio & subtitles"))
            .setItems(items.map { it.label }.toTypedArray()) { _, which -> items[which].apply() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ---------------- favourite, more ----------------
    private fun toggleFavourite() {
        val now = CxLive.toggleFavourite(this, list[index], "")
        Toast.makeText(this, if (now) t("Ditambah ke Kegemaran", "Added to Favourites") else t("Dibuang daripada Kegemaran", "Removed from Favourites"), Toast.LENGTH_SHORT).show()
        updateChannelInfo()
    }

    private fun moreDialog() {
        val modes = listOf(
            t("Saiz gambar: Muat", "Picture: Fit") to AspectRatioFrameLayout.RESIZE_MODE_FIT,
            t("Saiz gambar: Penuh skrin", "Picture: Fill") to AspectRatioFrameLayout.RESIZE_MODE_FILL,
            t("Saiz gambar: Zum", "Picture: Zoom") to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        )
        val items = modes.map { it.first } + t("Laporkan masalah saluran ini", "Report a problem with this channel")
        AlertDialog.Builder(this).setTitle(t("Lagi", "More"))
            .setItems(items.toTypedArray()) { _, which ->
                if (which < modes.size) video.resizeMode = modes[which].second else report()
            }.show()
    }

    private fun report() {
        val reasons = arrayOf(t("Saluran tidak boleh dimainkan", "Channel does not play"), t("Gambar tersekat-sekat", "Picture keeps buffering"),
            t("Tiada bunyi", "No sound"), t("Saluran salah / bukan seperti nama", "Wrong channel"))
        AlertDialog.Builder(this).setTitle(t("Laporkan masalah", "Report a problem")).setItems(reasons) { _, which ->
            val c = list[index]
            val f = player.videoFormat
            val a = player.audioFormat
            val playerText = "ExoPlayer (Live TV)" + (f?.let { " | Video: " + (it.sampleMimeType?.substringAfter('/') ?: "?") + " (" + it.width + "x" + it.height + ")" } ?: "") +
                (a?.let { " | Audio: " + (it.sampleMimeType?.substringAfter('/') ?: "?") + " " + it.channelCount + "ch" } ?: "")
            val app = applicationContext
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val diag = com.lagradost.cloudstream3.activation.CxDiag.base(app).apply {
                    put("extension", "Live TV · $title"); put("media", c.name); put("link", c.url.take(300))
                    put("player", playerText); put("category", "Live TV channel problem")
                    com.lagradost.cloudstream3.activation.CxDiag.ping(c.url)?.let { put("ping", it) }
                }
                val sent = com.lagradost.cloudstream3.activation.CxWorkerActivation.reportProblem(app, c.name, "Live TV · $title", reasons[which],
                    com.lagradost.cloudstream3.BuildConfig.VERSION_NAME, diag = diag)
                runOnUiThread { Toast.makeText(app, if (sent) t("Laporan dihantar. Terima kasih.", "Report sent. Thank you.") else t("Laporan tidak dapat dihantar.", "The report could not be sent."), Toast.LENGTH_LONG).show() }
            }
        }.show()
    }

    // ---------------- channel numbers ----------------
    private fun typeDigit(d: Int) {
        typed = (typed + d).takeLast(4)
        numberView.text = typed; numberView.visibility = View.VISIBLE
        handler.removeCallbacks(goToTyped)
        handler.postDelayed(goToTyped, 1500)
    }
    private val goToTyped = Runnable {
        val n = typed.toIntOrNull() ?: 0
        typed = ""; numberView.visibility = View.GONE
        val inList = list.indexOfFirst { it.number == n }
        when {
            inList >= 0 -> play(inList)
            CxLive.byNumber(n) != null -> {
                // Another category of the same source: its channels become the list.
                val target = CxLive.byNumber(n)!!
                val key = CxLive.groupParts(target.group).firstOrNull()?.let { CxLive.groupKey(it) }
                list = CxLive.numbered.filter { c -> !c.isYoutube && (key == null || CxLive.groupParts(c.group).any { CxLive.groupKey(it) == key }) }.ifEmpty { listOf(target) }
                title = CxLive.groupParts(target.group).firstOrNull() ?: title
                CxLive.playing = list
                play(list.indexOfFirst { it.key == target.key }.coerceAtLeast(0))
            }
            list.numberless() && n in 1..list.size -> play(n - 1)
            else -> Toast.makeText(this, t("Tiada saluran ", "No channel ") + n, Toast.LENGTH_SHORT).show()
        }
    }
    private fun List<LiveChannel>.numberless() = all { it.number == null }

    // ---------------- remote, keyboard ----------------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0) { typeDigit(digit); return true }
        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { step(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { step(-1); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { if (player.isPlaying) player.pause() else player.play(); return true }
            KeyEvent.KEYCODE_GUIDE -> { openEpg(); return true }
        }
        if (panelsOpen()) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) { closePanels(); return true }
            // Between the two columns: back onto the chosen category, or onto the channel playing.
            if (channelPanel.visibility == View.VISIBLE) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && channelList.hasFocus()) {
                    categoryList.findViewHolderForAdapterPosition(panelCategory)?.itemView?.requestFocus() ?: categoryList.requestFocus(); return true
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && categoryList.hasFocus()) {
                    val playing = panelChannels.indexOfFirst { it.key == list.getOrNull(index)?.key }.coerceAtLeast(0)
                    val lm = channelList.layoutManager as LinearLayoutManager
                    val target = if (playing in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()) playing else lm.findFirstVisibleItemPosition()
                    channelList.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() ?: channelList.requestFocus(); return true
                }
            }
            return super.dispatchKeyEvent(event)
        }
        if (bar.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) { hideBar.run(); return true }
            // Arrows move between the bar's buttons while it has focus; otherwise they act as below.
            if (bar.hasFocus() || prevButton.hasFocus() || nextButton.hasFocus()) { scheduleHide(); return super.dispatchKeyEvent(event) }
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { showBar(focus = true); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { openChannels(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { openEpg(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { showBar(focus = true); return true }
        }
        return super.dispatchKeyEvent(event)
    }
}
