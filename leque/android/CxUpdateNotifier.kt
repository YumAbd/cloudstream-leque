// CloudXtream: background check of the CloudXtream release feed; posts a notification once per
// new version. Tapping it opens the app, whose update prompt downloads and installs the APK.
// Also reminds the user once a day when the license expires within 3 days.
package com.lagradost.cloudstream3.services

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.activation.CxWorkerActivation
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

class CxUpdateNotifier(val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    companion object {
        private const val FEED = "https://cloudxpanel.infopc.xyz/api/app/latest"
        private const val WORK_NAME = "cx_update_check"
        private const val CHANNEL_ID = "cloudxtream.app_updates"
        private const val NOTIFICATION_ID = 50_011_001
        private const val NOTIFIED_KEY = "cx_update_notified_version"
        private const val EXPIRY_NOTIFICATION_ID = 50_014_001
        private const val EXPIRY_NOTIFIED_DAY = "cx_expiry_notified_day"
        private const val DAY_MS = 86_400_000L

        /** Hourly while online; WorkManager keeps the schedule across restarts and reboots. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CxUpdateNotifier>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        /** The app is open, so its own update prompt takes over from the notification. */
        fun dismiss(context: Context) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        }

        private fun versionCode(version: String?): Long? =
            version?.let { Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)").find(it) }?.destructured
                ?.let { (major, minor, patch) ->
                    major.toLong() * 1_000_000L + minor.toLong() * 1_000L + patch.toLong()
                }
    }

    private fun post(id: Int, title: String, text: String) {
        context.createNotificationChannel(
            CHANNEL_ID, context.getString(R.string.cx_notify_channel), context.getString(R.string.cx_notify_channel_des)
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloudstream_monochrome_big)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply {
                open?.let {
                    setContentIntent(
                        PendingIntentCompat.getActivity(
                            context, id, it, PendingIntent.FLAG_UPDATE_CURRENT, false
                        )
                    )
                }
            }
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
    }

    private fun remindExpiry(prefs: android.content.SharedPreferences) {
        val expires = CxWorkerActivation.licenseExpiresMs(context) ?: return
        val left = expires - System.currentTimeMillis()
        if (left <= 0L || left > 3 * DAY_MS) return
        val today = System.currentTimeMillis() / DAY_MS
        if (prefs.getLong(EXPIRY_NOTIFIED_DAY, -1L) == today) return
        val days = ((left + DAY_MS - 1) / DAY_MS).toInt()
        post(
            EXPIRY_NOTIFICATION_ID,
            context.getString(R.string.cx_expiry_title),
            context.resources.getQuantityString(R.plurals.cx_expiry_text, days, days)
        )
        prefs.edit().putLong(EXPIRY_NOTIFIED_DAY, today).apply()
    }

    override suspend fun doWork(): Result {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()
        remindExpiry(prefs)
        // Same switch as the update prompt (Settings > Updates > Show app updates).
        if (!prefs.getBoolean(context.getString(R.string.auto_update_key), true)) return Result.success()

        val feed = try {
            withContext(Dispatchers.IO) {
                val connection = URL(FEED).openConnection() as HttpsURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/json")
                try {
                    if (connection.responseCode != 200) null
                    else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: Exception) {
            null
        } ?: return Result.success()

        val remote = feed.optString("version")
        val remoteCode = versionCode(remote)
        val localCode = versionCode(
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        )
        if (!feed.optBoolean("ok") || remoteCode == null || localCode == null || remoteCode <= localCode) {
            return Result.success()
        }
        if (prefs.getString(NOTIFIED_KEY, null) == remote) return Result.success()

        post(
            NOTIFICATION_ID,
            context.getString(R.string.cx_update_title),
            context.getString(R.string.cx_update_text, remote)
        )
        prefs.edit().putString(NOTIFIED_KEY, remote).apply()
        return Result.success()
    }
}
