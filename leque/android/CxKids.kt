// CloudXtream: Kids profiles. A kids profile only opens titles that are clearly for children,
// and leaving it, or editing/adding profiles from it, needs the parent PIN set for it.
package com.lagradost.cloudstream3.ui.account

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.DataStoreHelper

object CxKids {
    private const val PREFS = "cx_kids_profiles_v1"
    private val KID_TAGS = listOf(
        "animation", "animasi", "animated", "cartoon", "kartun", "family", "keluarga",
        "kids", "children", "kanak", "anak"
    )
    private val KID_RATINGS = setOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "U", "SU", "ALL", "SEMUA")

    // Set after the parent PIN was entered, for the one action it unlocked.
    @Volatile private var unlocked = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pinFor(context: Context, keyIndex: Int): String? = prefs(context).getString("pin_$keyIndex", null)

    fun set(context: Context, keyIndex: Int, pin: String?) {
        prefs(context).edit().apply {
            if (pin == null) remove("pin_$keyIndex") else putString("pin_$keyIndex", pin)
        }.apply()
    }

    fun isKids(context: Context, keyIndex: Int): Boolean = pinFor(context, keyIndex) != null

    /** Editing or adding profiles while in a kids profile, or editing a kids profile, needs its PIN. */
    fun needsPinToEdit(context: Context, targetKeyIndex: Int): Boolean =
        !unlocked && (isKids(context, DataStoreHelper.selectedKeyIndex) || isKids(context, targetKeyIndex))

    /** Leaving the current kids profile for [targetKeyIndex] needs the parent PIN. */
    fun needsPinToLeave(context: Context, targetKeyIndex: Int): Boolean =
        !unlocked && targetKeyIndex != DataStoreHelper.selectedKeyIndex &&
            isKids(context, DataStoreHelper.selectedKeyIndex)

    /** Asks for the parent PIN (current kids profile first, else [targetKeyIndex]'s), then runs [proceed]. */
    fun askPin(context: Context, targetKeyIndex: Int, proceed: () -> Unit) {
        val pin = pinFor(context, DataStoreHelper.selectedKeyIndex) ?: pinFor(context, targetKeyIndex)
            ?: return proceed()
        AccountHelper.showPinInputDialog(context, pin, false) { entered ->
            if (entered == null) return@showPinInputDialog
            unlocked = true
            try {
                proceed()
            } finally {
                unlocked = false
            }
        }
    }

    /** False when a kids profile is active and the title is not clearly made for children. */
    fun allows(load: LoadResponse): Boolean {
        val context = CloudStreamApp.context ?: return true
        if (!isKids(context, DataStoreHelper.selectedKeyIndex)) return true
        if (load.type == TvType.NSFW) return false
        if (load.type == TvType.Cartoon) return true
        val rating = load.contentRating?.trim()?.uppercase()
        if (rating != null && rating in KID_RATINGS) return true
        val tags = load.tags.orEmpty().map { it.lowercase() }
        return tags.any { tag -> KID_TAGS.any { tag.contains(it) } }
    }
}
