// CloudXtream: first-run guide (shown once) and the in-app license expiry reminder (once a day).
package com.lagradost.cloudstream3.ui

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.activation.CxWorkerActivation

object CxHints {
    private const val PREFS = "cx_hints_v1"
    private const val ONBOARDING_DONE = "onboarding_done"
    private const val EXPIRY_SHOWN_DAY = "expiry_shown_day"
    private const val DAY_MS = 86_400_000L

    private val pages = listOf(
        R.string.cx_onb1_title to R.string.cx_onb1_text,
        R.string.cx_onb2_title to R.string.cx_onb2_text,
        R.string.cx_onb3_title to R.string.cx_onb3_text,
    )

    fun showOnboardingIfNeeded(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ONBOARDING_DONE, false)) return
        fun done() = prefs.edit().putBoolean(ONBOARDING_DONE, true).apply()
        fun show(index: Int) {
            if (activity.isFinishing || activity.isDestroyed) return
            val last = index == pages.lastIndex
            val (title, text) = pages[index]
            AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                .setTitle(activity.getString(title) + "  (" + (index + 1) + "/" + pages.size + ")")
                .setMessage(text)
                .setCancelable(false)
                .setPositiveButton(if (last) R.string.cx_onb_start else R.string.cx_onb_next) { _, _ ->
                    if (last) done() else show(index + 1)
                }
                .apply { if (!last) setNegativeButton(R.string.cx_onb_skip) { _, _ -> done() } }
                .show()
        }
        show(0)
    }

    fun remindExpiryIfNeeded(activity: Activity) {
        val expires = CxWorkerActivation.licenseExpiresMs(activity) ?: return
        val left = expires - System.currentTimeMillis()
        if (left <= 0L || left > 3 * DAY_MS) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = System.currentTimeMillis() / DAY_MS
        if (prefs.getLong(EXPIRY_SHOWN_DAY, -1L) == today) return
        prefs.edit().putLong(EXPIRY_SHOWN_DAY, today).apply()
        val days = ((left + DAY_MS - 1) / DAY_MS).toInt()
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(R.string.cx_expiry_title)
            .setMessage(activity.resources.getQuantityString(R.plurals.cx_expiry_text, days, days))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
