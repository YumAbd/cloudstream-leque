package com.lagradost.cloudstream3.ui.live

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's own arrangement of Live TV, kept on this device: hidden sources, a source's name,
 * its categories (order, hidden, renamed), its channels (hidden, moved to another category,
 * their numbers for the remote).
 */
object CxEdits {
    private const val PREFS = "cx_live_edits_v1"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    class Edits(
        var alias: String? = null,
        val order: MutableList<String> = ArrayList(),
        val hiddenCats: MutableSet<String> = HashSet(),
        val rename: MutableMap<String, String> = HashMap(),
        val hiddenCh: MutableSet<String> = HashSet(),
        val move: MutableMap<String, String> = HashMap(),
        val num: MutableMap<String, Int> = HashMap(),
    )

    private fun JSONArray.strings() = (0 until length()).map { optString(it) }
    private fun JSONObject.strMap() = keys().asSequence().associateWith { optString(it) }

    fun load(c: Context, sourceId: String): Edits {
        val o = prefs(c).getString("src_$sourceId", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Edits()
        return Edits(
            alias = o.optString("alias").takeIf { it.isNotBlank() },
            order = o.optJSONArray("order")?.strings()?.toMutableList() ?: ArrayList(),
            hiddenCats = o.optJSONArray("hiddenCats")?.strings()?.toHashSet() ?: HashSet(),
            rename = o.optJSONObject("rename")?.strMap()?.toMutableMap() ?: HashMap(),
            hiddenCh = o.optJSONArray("hiddenCh")?.strings()?.toHashSet() ?: HashSet(),
            move = o.optJSONObject("move")?.strMap()?.toMutableMap() ?: HashMap(),
            num = o.optJSONObject("num")?.let { n -> n.keys().asSequence().associateWith { n.optInt(it) }.toMutableMap() } ?: HashMap(),
        )
    }

    fun save(c: Context, sourceId: String, e: Edits) {
        val o = JSONObject().put("alias", e.alias).put("order", JSONArray(e.order)).put("hiddenCats", JSONArray(e.hiddenCats.toList()))
            .put("rename", JSONObject(e.rename as Map<*, *>)).put("hiddenCh", JSONArray(e.hiddenCh.toList()))
            .put("move", JSONObject(e.move as Map<*, *>)).put("num", JSONObject(e.num as Map<*, *>))
        prefs(c).edit().putString("src_$sourceId", o.toString()).apply()
    }

    fun edit(c: Context, sourceId: String, change: (Edits) -> Unit) { val e = load(c, sourceId); change(e); save(c, sourceId, e) }

    // ---------------- sources ----------------
    fun hiddenSources(c: Context): Set<String> = prefs(c).getStringSet("hidden_sources", emptySet()) ?: emptySet()
    fun setSourceHidden(c: Context, id: String, hidden: Boolean) {
        val set = hiddenSources(c).toMutableSet(); if (hidden) set += id else set -= id
        prefs(c).edit().putStringSet("hidden_sources", set).apply()
    }
    fun nameOf(c: Context, s: LiveSource) = load(c, s.id).alias ?: s.name

    // ---------------- channels ----------------
    /** The source's channels as the user arranged them. Numbers: the user's, the playlist's, else the position. */
    fun apply(c: Context, sourceId: String, raw: List<LiveChannel>): List<LiveChannel> {
        val e = load(c, sourceId)
        return raw.mapIndexedNotNull { i, ch ->
            if (ch.key in e.hiddenCh) return@mapIndexedNotNull null
            val original = e.move[ch.key] ?: ch.group
            val parts = CxLive.groupParts(original).map { p -> e.rename[CxLive.groupKey(p)] ?: p }
            val kept = parts.filter { CxLive.groupKey(it) !in e.hiddenCats }
            if (parts.isNotEmpty() && kept.isEmpty()) return@mapIndexedNotNull null
            ch.copy(group = if (kept.isEmpty()) original else kept.joinToString(";"), number = e.num[ch.key] ?: ch.number ?: (i + 1))
        }
    }

    /** Category display names in the user's order (the rest keep their playlist order). */
    fun sortCategories(c: Context, sourceId: String, names: List<String>): List<String> {
        val order = load(c, sourceId).order
        return names.withIndex().sortedWith(compareBy({ order.indexOf(CxLive.groupKey(it.value)).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.index })).map { it.value }
    }

    fun renameCategory(c: Context, sourceId: String, shown: String, newName: String) = edit(c, sourceId) { e ->
        val oldKey = CxLive.groupKey(shown); val newKey = CxLive.groupKey(newName)
        val original = e.rename.entries.firstOrNull { CxLive.groupKey(it.value) == oldKey }?.key ?: oldKey
        e.rename[original] = newName
        e.order.replaceAll { if (it == oldKey) newKey else it }
        if (e.hiddenCats.remove(oldKey)) e.hiddenCats += newKey
        e.move.replaceAll { _, v -> if (CxLive.groupKey(v) == oldKey) newName else v }
    }
}

/** Dialogs to arrange Live TV (Settings > Live TV > Manage playlists, and long presses in Live TV). */
object CxLiveManage {
    fun t(c: Context, ms: String, en: String) =
        if (c.resources.configuration.locales[0].language == "ms") ms else en

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    private fun input(c: Context, value: String, hint: String, numeric: Boolean = false) = EditText(c).apply {
        setText(value); this.hint = hint; setSingleLine(true)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
    }

    private fun box(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 20), dp(c, 8), dp(c, 20), 0) }

    /** Rename a category (in this source). */
    fun renameCategory(c: Context, sourceId: String, shown: String, done: () -> Unit) {
        val field = input(c, shown, t(c, "Nama kategori", "Category name"))
        AlertDialog.Builder(c).setTitle(t(c, "Tukar nama kategori", "Rename category")).setView(box(c).apply { addView(field) })
            .setPositiveButton(t(c, "Simpan", "Save")) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) { CxEdits.renameCategory(c, sourceId, shown, name); done() }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    fun hideCategory(c: Context, sourceId: String, shown: String, done: () -> Unit) {
        CxEdits.edit(c, sourceId) { it.hiddenCats += CxLive.groupKey(shown) }
        Toast.makeText(c, t(c, "Kategori disembunyikan. Tunjuk semula di Settings › Live TV › Urus playlist.",
            "Category hidden. Show it again in Settings › Live TV › Manage playlists."), Toast.LENGTH_LONG).show()
        done()
    }

    /** Long press on a channel: favourite, hide, move to another category, number. */
    fun channelActions(c: Context, sourceId: String?, ch: LiveChannel, isFavourite: Boolean, categories: List<String>,
                       toggleFavourite: () -> Unit, done: () -> Unit) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += (if (isFavourite) t(c, "Buang daripada Kegemaran", "Remove from Favourites") else t(c, "Tambah ke Kegemaran", "Add to Favourites")) to toggleFavourite
        if (sourceId != null) {
            items += t(c, "Sembunyikan saluran", "Hide channel") to {
                CxEdits.edit(c, sourceId) { it.hiddenCh += ch.key }
                Toast.makeText(c, t(c, "Saluran disembunyikan. Tunjuk semula di Settings › Live TV › Urus playlist.",
                    "Channel hidden. Show it again in Settings › Live TV › Manage playlists."), Toast.LENGTH_LONG).show()
                done()
            }
            if (categories.size > 1) items += t(c, "Pindah ke kategori…", "Move to category…") to {
                AlertDialog.Builder(c).setTitle(t(c, "Pindah ke", "Move to"))
                    .setItems(categories.toTypedArray()) { _, which -> CxEdits.edit(c, sourceId) { it.move[ch.key] = categories[which] }; done() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            items += (t(c, "Nombor saluran", "Channel number") + (ch.number?.let { " (" + it + ")" } ?: "")) to { numberDialog(c, sourceId, ch, done) }
        }
        AlertDialog.Builder(c).setTitle(ch.name).setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }.show()
    }

    fun numberDialog(c: Context, sourceId: String, ch: LiveChannel, done: () -> Unit) {
        val field = input(c, ch.number?.toString().orEmpty(), t(c, "Nombor (cth. 3)", "Number (e.g. 3)"), numeric = true)
        AlertDialog.Builder(c).setTitle(t(c, "Nombor saluran: ", "Channel number: ") + ch.name)
            .setMessage(t(c, "Taip nombor ini pada remote TV semasa menonton untuk terus ke saluran ini.", "Type this number on the TV remote while watching to go straight to this channel."))
            .setView(box(c).apply { addView(field) })
            .setPositiveButton(t(c, "Simpan", "Save")) { _, _ ->
                val n = field.text.toString().trim().toIntOrNull()
                CxEdits.edit(c, sourceId) { if (n != null && n > 0) it.num[ch.key] = n else it.num.remove(ch.key) }
                done()
            }
            .setNeutralButton(t(c, "Asal", "Default")) { _, _ -> CxEdits.edit(c, sourceId) { it.num.remove(ch.key) }; done() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Long press on a source: edit, hide, set as default, manage (and remove for the user's own). */
    fun sourceActions(c: Context, s: LiveSource, edit: () -> Unit, remove: (() -> Unit)?, done: () -> Unit) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += t(c, "Edit", "Edit") to edit
        items += t(c, "Sembunyikan sumber", "Hide source") to {
            CxEdits.setSourceHidden(c, s.id, true)
            Toast.makeText(c, t(c, "Sumber disembunyikan. Tunjuk semula di Settings › Live TV › Urus playlist.",
                "Source hidden. Show it again in Settings › Live TV › Manage playlists."), Toast.LENGTH_LONG).show()
            done()
        }
        items += t(c, "Jadikan sumber lalai", "Set as default") to {
            CxLive.setDefaultSource(c, s.id)
            Toast.makeText(c, CxEdits.nameOf(c, s) + t(c, " dibuka dahulu dalam Live TV.", " opens first in Live TV."), Toast.LENGTH_SHORT).show()
        }
        items += t(c, "Urus (kategori dan saluran)", "Manage (categories and channels)") to { manageSource(c, s, done) }
        if (remove != null) items += t(c, "Buang playlist", "Remove playlist") to remove
        AlertDialog.Builder(c).setTitle(CxEdits.nameOf(c, s)).setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }.show()
    }

    /** Rename any source (on this device); the user's own can also change its link or Xtream login. */
    fun editSource(c: Context, s: LiveSource, saveOwn: (LiveSource) -> Unit, done: () -> Unit) {
        val b = box(c)
        val name = input(c, CxEdits.nameOf(c, s), t(c, "Nama", "Name")); b.addView(name)
        val url = input(c, s.url.orEmpty(), t(c, "Link playlist", "Playlist link"))
        val server = input(c, s.server.orEmpty(), "Server Xtream (http://host:port)")
        val user = input(c, s.username.orEmpty(), "Username")
        val pass = input(c, s.password.orEmpty(), "Password").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        when {
            s.own && s.kind == "xtream" -> { b.addView(server); b.addView(user); b.addView(pass) }
            s.own && s.kind == "m3u" -> b.addView(url)
            !s.own -> b.addView(TextView(c).apply {
                text = t(c, "Link sumber ini diurus oleh admin. Nama baharu hanya pada peranti ini.", "This source's link is managed by the admin. The new name is only on this device.")
                textSize = 12f; setPadding(0, dp(c, 8), 0, 0)
            })
        }
        AlertDialog.Builder(c).setTitle(t(c, "Edit sumber", "Edit source")).setView(ScrollView(c).apply { addView(b) })
            .setPositiveButton(t(c, "Simpan", "Save")) { _, _ ->
                val newName = name.text.toString().trim()
                if (s.own) {
                    val updated = when (s.kind) {
                        "xtream" -> s.copy(name = newName.ifEmpty { s.name }, server = server.text.toString().trim(), username = user.text.toString().trim(), password = pass.text.toString())
                        "m3u" -> s.copy(name = newName.ifEmpty { s.name }, url = url.text.toString().trim())
                        else -> s.copy(name = newName.ifEmpty { s.name })
                    }
                    CxEdits.edit(c, s.id) { it.alias = null }
                    if (updated.url != s.url || updated.server != s.server || updated.username != s.username || updated.password != s.password) saveOwn(updated)
                    else { CxLive.addOwn(c, updated); done() }
                } else {
                    CxEdits.edit(c, s.id) { it.alias = newName.takeIf { n -> n.isNotEmpty() && n != s.name } }
                    done()
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Settings > Live TV > Manage playlists: show or hide each source; open one to arrange it. */
    fun managePlaylists(c: Context, done: () -> Unit = {}) {
        val sources = CxLive.tvSources(c)
        if (sources.isEmpty()) { Toast.makeText(c, t(c, "Belum ada sumber Live TV.", "No Live TV source yet."), Toast.LENGTH_LONG).show(); return }
        val b = box(c)
        b.addView(TextView(c).apply { text = t(c, "Tanda untuk tunjuk. Tekan nama untuk susun kategori dan saluran.", "Tick to show. Tap a name to arrange its categories and channels."); textSize = 13f })
        lateinit var dialog: AlertDialog
        for (s in sources) {
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(c, 4), 0, dp(c, 4)) }
            row.addView(CheckBox(c).apply {
                isChecked = s.id !in CxEdits.hiddenSources(c)
                setOnCheckedChangeListener { _, on -> CxEdits.setSourceHidden(c, s.id, !on); done() }
            })
            row.addView(TextView(c).apply {
                text = CxEdits.nameOf(c, s) + (if (s.own) t(c, "  (milik anda)", "  (yours)") else "") + "  ›"; textSize = 16f
                setPadding(dp(c, 8), dp(c, 10), dp(c, 8), dp(c, 10)); isClickable = true; isFocusable = true
                setOnClickListener { manageSource(c, s, done) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            b.addView(row)
        }
        dialog = AlertDialog.Builder(c).setTitle(t(c, "Urus playlist", "Manage playlists")).setView(ScrollView(c).apply { addView(b) })
            .setPositiveButton(t(c, "Siap", "Done"), null).show()
    }

    /** One source: categories (order, show/hide) and channels (show/hide, number). */
    fun manageSource(c: Context, s: LiveSource, done: () -> Unit) {
        Toast.makeText(c, t(c, "Memuatkan ", "Loading ") + CxEdits.nameOf(c, s) + "…", Toast.LENGTH_SHORT).show()
        MainScope().launch {
            val raw = try { withContext(Dispatchers.IO) { CxLive.channels(c, s, false) } } catch (e: Exception) {
                Toast.makeText(c, (e as? LiveProblem)?.message ?: t(c, "Senarai tidak dapat dimuatkan.", "The list could not be loaded."), Toast.LENGTH_LONG).show(); return@launch
            }
            showManager(c, s, raw, done)
        }
    }

    private fun showManager(c: Context, s: LiveSource, raw: List<LiveChannel>, done: () -> Unit) {
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 16), dp(c, 4), dp(c, 16), 0) }
        val tabs = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val catTab = TextView(c).apply { text = t(c, "Kategori", "Categories"); textSize = 15f; setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8)); isClickable = true; isFocusable = true }
        val chTab = TextView(c).apply { text = t(c, "Saluran", "Channels"); textSize = 15f; setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8)); isClickable = true; isFocusable = true }
        tabs.addView(catTab); tabs.addView(chTab)
        val search = input(c, "", t(c, "Cari saluran", "Search channels"))
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(c).apply { addView(list) }
        root.addView(tabs); root.addView(search); root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 420)))
        var showingChannels = false

        fun categories(): List<String> {
            // Every category (also hidden ones, so they can be shown again), in the user's order.
            val e = CxEdits.load(c, s.id)
            val names = LinkedHashMap<String, String>()
            for (ch in raw) for (p in CxLive.groupParts(e.move[ch.key] ?: ch.group)) {
                val shown = e.rename[CxLive.groupKey(p)] ?: p
                names.getOrPut(CxLive.groupKey(shown)) { shown }
            }
            return CxEdits.sortCategories(c, s.id, names.values.toList())
        }

        fun render() {
            list.removeAllViews()
            catTab.setTypeface(null, if (showingChannels) Typeface.NORMAL else Typeface.BOLD)
            chTab.setTypeface(null, if (showingChannels) Typeface.BOLD else Typeface.NORMAL)
            search.visibility = if (showingChannels) View.VISIBLE else View.GONE
            val e = CxEdits.load(c, s.id)
            if (!showingChannels) {
                val cats = categories()
                cats.forEachIndexed { i, name ->
                    val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(CheckBox(c).apply {
                        isChecked = CxLive.groupKey(name) !in e.hiddenCats
                        setOnCheckedChangeListener { _, on -> CxEdits.edit(c, s.id) { if (on) it.hiddenCats -= CxLive.groupKey(name) else it.hiddenCats += CxLive.groupKey(name) }; done() }
                    })
                    row.addView(TextView(c).apply { text = name; textSize = 15f; setPadding(dp(c, 6), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    fun move(by: Int) {
                        val keys = cats.map { CxLive.groupKey(it) }.toMutableList()
                        val j = i + by
                        if (j !in keys.indices) return
                        keys[i] = keys[j].also { keys[j] = keys[i] }
                        CxEdits.edit(c, s.id) { it.order.clear(); it.order.addAll(keys) }
                        done(); render()
                    }
                    row.addView(ImageButton(c).apply { setImageResource(android.R.drawable.arrow_up_float); contentDescription = t(c, "Naik", "Up"); background = null; setOnClickListener { move(-1) }; isEnabled = i > 0 })
                    row.addView(ImageButton(c).apply { setImageResource(android.R.drawable.arrow_down_float); contentDescription = t(c, "Turun", "Down"); background = null; setOnClickListener { move(1) }; isEnabled = i < cats.lastIndex })
                    row.setOnLongClickListener { renameCategory(c, s.id, name) { done(); render() }; true }
                    list.addView(row)
                }
                list.addView(TextView(c).apply { text = t(c, "Tekan lama pada nama untuk tukar nama kategori.", "Long press a name to rename the category."); textSize = 12f; setPadding(0, dp(c, 8), 0, dp(c, 8)) })
            } else {
                val q = search.text.toString().trim().lowercase()
                val all = raw.mapIndexed { i, ch -> ch.copy(number = e.num[ch.key] ?: ch.number ?: (i + 1)) }
                val match = all.filter { q.isEmpty() || it.name.lowercase().contains(q) }
                for (ch in match.take(400)) {
                    val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(CheckBox(c).apply {
                        isChecked = ch.key !in e.hiddenCh
                        setOnCheckedChangeListener { _, on -> CxEdits.edit(c, s.id) { if (on) it.hiddenCh -= ch.key else it.hiddenCh += ch.key }; done() }
                    })
                    row.addView(TextView(c).apply {
                        text = (ch.number?.let { "$it  " } ?: "") + ch.name; textSize = 15f; setPadding(dp(c, 6), dp(c, 8), 0, dp(c, 8))
                        isClickable = true; isFocusable = true
                        setOnClickListener { numberDialog(c, s.id, ch) { done(); render() } }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    list.addView(row)
                }
                list.addView(TextView(c).apply {
                    text = (if (match.size > 400) t(c, "400 pertama dipaparkan; guna carian. ", "First 400 shown; use search. ") else "") +
                        t(c, "Tekan nama untuk tukar nombor saluran (untuk remote TV).", "Tap a name to change its channel number (for the TV remote).")
                    textSize = 12f; setPadding(0, dp(c, 8), 0, dp(c, 8))
                })
            }
        }
        catTab.setOnClickListener { showingChannels = false; render() }
        chTab.setOnClickListener { showingChannels = true; render() }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, cc: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, cc: Int, d: Int) {}
            override fun afterTextChanged(a: android.text.Editable?) { if (showingChannels) render() }
        })
        render()
        AlertDialog.Builder(c).setTitle(t(c, "Urus: ", "Manage: ") + CxEdits.nameOf(c, s)).setView(root)
            .setPositiveButton(t(c, "Siap", "Done")) { _, _ -> done() }
            .setNeutralButton(t(c, "Set semula", "Reset")) { _, _ ->
                AlertDialog.Builder(c).setMessage(t(c, "Buang semua susunan anda untuk sumber ini?", "Remove all your arrangements for this source?"))
                    .setPositiveButton(t(c, "Set semula", "Reset")) { _, _ -> val alias = CxEdits.load(c, s.id).alias; CxEdits.save(c, s.id, CxEdits.Edits(alias = alias)); done() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            .show()
    }
}
