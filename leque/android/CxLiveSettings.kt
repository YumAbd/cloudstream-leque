package com.lagradost.cloudstream3.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.live.CxLive
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import kotlinx.collections.immutable.persistentListOf

/** Settings > Live TV: the source it opens on, subtitles and adult categories. */
object CxLiveSettingsScreen : SearchableSettings {
    @Composable
    override fun getTitleRes(): String = stringResource(R.string.cx_live_tv)

    @Composable
    override fun getPreferences(): List<Preference> {
        val ctx = LocalContext.current
        var source by remember { mutableStateOf(CxLive.defaultSourceName(ctx)) }
        var subs by remember { mutableStateOf(CxLive.subtitleName(ctx)) }
        var player by remember { mutableStateOf(playerName(ctx)) }
        var adultOn by remember { mutableStateOf(CxLive.adultShown(ctx)) }
        var adult by remember { mutableStateOf(CxLive.adultName(ctx)) }
        return persistentListOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.cx_live_source),
                subtitle = source,
                icon = painterResource(R.drawable.cx_live_tv),
                onClick = { CxLive.chooseDefaultSource(ctx) { source = CxLive.defaultSourceName(ctx) } },
            ),
            Preference.PreferenceItem.TextPreference(
                title = com.lagradost.cloudstream3.ui.live.CxLiveManage.t(ctx, "Pemain Live TV", "Live TV player"),
                subtitle = player,
                icon = painterResource(R.drawable.cx_live_tv),
                onClick = {
                    CxLive.setClassicPlayer(ctx, !CxLive.classicPlayer(ctx))
                    player = playerName(ctx)
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.cx_live_subs),
                subtitle = subs,
                icon = painterResource(R.drawable.ic_outline_subtitles_24),
                onClick = { CxLive.chooseSubtitles(ctx) { subs = CxLive.subtitleName(ctx) } },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.cx_live_manage),
                subtitle = stringResource(R.string.cx_live_manage_sub),
                icon = painterResource(R.drawable.cx_cat_all),
                onClick = { com.lagradost.cloudstream3.ui.live.CxLiveManage.managePlaylists(ctx) { source = CxLive.defaultSourceName(ctx) } },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.cx_live_adult),
                subtitle = adult,
                icon = painterResource(R.drawable.video_locked),
                // On / off switch: show or hide adult (18+) categories (never in a Kids profile).
                widget = {
                    androidx.compose.material3.Switch(checked = adultOn, onCheckedChange = {
                        CxLive.toggleAdult(ctx) { adultOn = CxLive.adultShown(ctx); adult = CxLive.adultName(ctx) }
                    })
                },
                onClick = { CxLive.toggleAdult(ctx) { adultOn = CxLive.adultShown(ctx); adult = CxLive.adultName(ctx) } },
            ),
        )
    }
}

private fun playerName(ctx: android.content.Context) = if (CxLive.classicPlayer(ctx))
    com.lagradost.cloudstream3.ui.live.CxLiveManage.t(ctx, "Klasik (pemain filem) · tekan untuk tukar", "Classic (movie player) · tap to change")
else com.lagradost.cloudstream3.ui.live.CxLiveManage.t(ctx, "CloudXtream (gaya OTT: senarai saluran, jadual, ◀ ▶) · tekan untuk tukar", "CloudXtream (OTT style: channel list, schedule, ◀ ▶) · tap to change")

/** Glue code to open the Live TV settings from the navigation graph. */
class CxLiveSettings2 : BaseComposeFragment(), SearchableSettings by CxLiveSettingsScreen
