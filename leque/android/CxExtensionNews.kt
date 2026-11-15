package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * One-time popup when the admin adds or removes extensions in the CloudXtream catalog (V5.0.22).
 *
 * The extensions this device has already been told about are saved; when the catalog differs, the
 * new and removed ones are listed once, each with what it contains (e.g. "Oppadrama — Kdrama & Kmovies").
 * After the popup is closed it is not shown again until the catalog changes again.
 * NSFW extensions are never listed. A fresh install only records the current catalog.
 */
object CxExtensionNews {
    private const val TAG = "CxExtensionNews"
    private const val PREFS = "cx_extension_news_v1"
    private const val SEEN = "seen"
    private const val CATALOG_URL = "https://cloudxpanel.infopc.xyz/catalog/sumber.json"
    @Volatile private var showing = false
    // Added with V5.0.22: a device activated before this version is told about them once.
    private val FIRST_NEWS = setOf("OppaDrama", "OneTouchTV", "NontonDrakor", "Kissasian")

    private val typeWords = linkedMapOf(
        "AsianDrama" to "Drama Asia", "Movie" to "Movies", "TvSeries" to "TV Series", "Anime" to "Anime",
        "AnimeMovie" to "Anime Movies", "Cartoon" to "Cartoon", "Documentary" to "Documentary", "Live" to "Live TV"
    )

    private fun autoSummary(types: List<String>?): String =
        types.orEmpty().mapNotNull { typeWords[it] }.distinct().take(3).joinToString(" & ")

    /** Summaries written by the admin; only read when there is something to show. */
    private fun summaries(): Map<String, String> = try {
        val connection = URL(CATALOG_URL).openConnection() as HttpsURLConnection
        com.lagradost.cloudstream3.activation.CxTls.apply(connection)
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        try {
            if (connection.responseCode != 200) emptyMap() else {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val list = org.json.JSONArray(text)
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    .filter { it.optString("summary").isNotBlank() }
                    .associate { it.optString("internalName") to it.optString("summary").trim().take(80) }
            }
        } finally { connection.disconnect() }
    } catch (e: Exception) {
        Log.w(TAG, "Summaries not read: " + e.javaClass.simpleName)
        emptyMap()
    }

    /** Called after each catalog sync with the full catalog. */
    fun check(activity: Activity, catalog: List<SitePlugin>) {
        val current = catalog.filter { it.status == 1 && it.tvTypes?.contains("NSFW") != true }
            .associate { it.internalName to it }
        if (current.isEmpty()) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(SEEN, null)
        val names = JSONObject().apply { current.forEach { (k, v) -> put(k, v.name.ifBlank { k }) } }
        val seen = if (saved != null) runCatching { JSONObject(saved) }.getOrElse { JSONObject() } else {
            // A new install (activated with this version) has nothing "new". A device updated from
            // an earlier version is shown the extensions added together with this version.
            val activatedNow = activity.getSharedPreferences("cx_worker_activation_2026_v1", Context.MODE_PRIVATE)
                .contains("activated_at_ms")
            val baseline = JSONObject(names.toString())
            if (!activatedNow) FIRST_NEWS.forEach { baseline.remove(it) }
            prefs.edit().putString(SEEN, baseline.toString()).apply()
            baseline
        }
        val added = current.keys.filter { !seen.has(it) }
        val removed = seen.keys().asSequence().filter { it !in current }.toList()
        if (added.isEmpty() && removed.isEmpty()) {
            if (seen.length() != names.length()) prefs.edit().putString(SEEN, names.toString()).apply()
            return
        }
        if (showing) return
        val written = if (added.isEmpty()) emptyMap() else summaries()
        val text = StringBuilder()
        if (added.isNotEmpty()) {
            text.append("Extension baharu:\n")
            for (name in added) {
                val item = current.getValue(name)
                val about = written[name] ?: autoSummary(item.tvTypes)
                text.append("• ").append(item.name.ifBlank { name })
                if (about.isNotBlank()) text.append(" — ").append(about)
                text.append('\n')
            }
        }
        if (removed.isNotEmpty()) {
            if (text.isNotEmpty()) text.append('\n')
            text.append("Extension dibuang:\n")
            for (name in removed) text.append("• ").append(seen.optString(name, name)).append('\n')
        }
        val message = text.toString().trimEnd()
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || showing) return@runOnUiThread
            showing = true
            try {
                AlertDialog.Builder(activity)
                    .setTitle("Kemas kini extension")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .setOnDismissListener {
                        showing = false
                        // Closed: remember this catalog so the popup is not shown again.
                        prefs.edit().putString(SEEN, names.toString()).apply()
                    }
                    .show()
            } catch (e: Exception) {
                showing = false
                Log.w(TAG, "Popup not shown: " + e.javaClass.simpleName)
            }
        }
    }
}
