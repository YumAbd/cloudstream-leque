package com.lagradost.cloudstream3.activation

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import org.json.JSONObject

/** Device, network and app details sent with a report or a crash (no personal data). */
object CxDiag {
    fun base(c: Context): JSONObject {
        val o = JSONObject()
        runCatching { o.put("network", network(c)) }
        runCatching { o.put("dns", dns(c)) }
        o.put("device", (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim())
        o.put("form", form(c))
        o.put("os", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
        runCatching {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            o.put("ram", (mi.availMem shr 20).toString() + " MB free / " + (mi.totalMem shr 20) + " MB")
        }
        runCatching { o.put("disk", (StatFs(c.filesDir.path).availableBytes shr 20).toString() + " MB free") }
        runCatching { o.put("app", "v" + c.packageManager.getPackageInfo(c.packageName, 0).versionName) }
        return o
    }

    private fun form(c: Context): String {
        val ui = c.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (ui == Configuration.UI_MODE_TYPE_TELEVISION || c.packageManager.hasSystemFeature("android.software.leanback")) return "Android TV"
        return if (c.resources.configuration.smallestScreenWidthDp >= 600) "Tablet" else "Phone"
    }

    private fun network(c: Context): String {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet / LAN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            else -> "Other"
        }
        return kind + if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) " + VPN" else ""
    }

    private fun dns(c: Context): String {
        val p = androidx.preference.PreferenceManager.getDefaultSharedPreferences(c)
        val v = p.getInt(c.getString(com.lagradost.cloudstream3.R.string.dns_key), 1)
        return when (v) {
            0 -> "System (ISP)"
            1 -> "Google (DoH)"
            2 -> "Cloudflare (DoH)"
            4 -> "AdGuard (DoH)"
            5 -> "DNS.WATCH (DoH)"
            6 -> "Quad9 (DoH)"
            7 -> "DNS.SB (DoH)"
            8 -> "Canadian Shield (DoH)"
            else -> "DoH ($v)"
        }
    }

    /** "200 OK (645 ms) [host]" for the website of an extension, or the failure. */
    fun ping(url: String?): String? {
        val u = url?.takeIf { it.startsWith("http") } ?: return null
        val host = runCatching { java.net.URI(u).host }.getOrNull() ?: return null
        val started = System.currentTimeMillis()
        return try {
            val request = okhttp3.Request.Builder().url(u).header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36").build()
            val client = com.lagradost.cloudstream3.app.baseClient.newBuilder()
                .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { r ->
                r.code.toString() + " " + r.message.ifBlank { if (r.isSuccessful) "OK" else "" }.trim() + " (" + (System.currentTimeMillis() - started) + " ms) [" + host + "]"
            }
        } catch (e: Exception) {
            e.javaClass.simpleName + " (" + (System.currentTimeMillis() - started) + " ms) [" + host + "]"
        }
    }
}
