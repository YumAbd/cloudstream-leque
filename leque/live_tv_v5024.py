#!/usr/bin/env python3
"""CloudXtream V5.0.23 (with the mouse and keyboard changes): Live TV menu.

- playlists from CloudXtream Control (the official free channels for everyone, others for chosen
  users) and, where the admin allows it, the user's own playlists: a link in any form (M3U,
  shortlink, link without extension, full Xtream link), Xtream Codes login, a file, or sent from a
  phone with a code
- groups, search, favourites (long press), recently watched; CH+/CH- change channel in the player
- official YouTube live channels play inside the app with YouTube's embedded player
- Radio menu: Malaysian stations (Radio Browser) and the admin's radio playlists
- menus: phone (portrait) Movies, Search, Live TV, Radio, More (Library, Downloads, Settings);
  landscape / TV rail: Movies, Search, Live TV, Radio, Library, Downloads, Settings
The code lives in leque/android/CxLive.kt and CxLiveFragment.kt (copied here)."""
from pathlib import Path
import re, sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
HERE=Path(__file__).resolve().parent
files={}
def read(path):
    return files[path] if path in files else (ROOT/path).read_text(encoding="utf8")
def patch(path,before,after):
    current=read(path)
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.23 Live TV anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

# Released together with the mouse/keyboard changes as 5.0.23 (no version change here).
src="app/src/main/java/com/lagradost/cloudstream3/"
res="app/src/main/res/"
files[src+"ui/live/CxLive.kt"]=(HERE/"android"/"CxLive.kt").read_text(encoding="utf8")
files[src+"ui/live/CxLiveFragment.kt"]=(HERE/"android"/"CxLiveFragment.kt").read_text(encoding="utf8")
files[src+"ui/live/CxXtream.kt"]=(HERE/"android"/"CxXtream.kt").read_text(encoding="utf8")
files[src+"ui/settings/CxLiveSettings.kt"]=(HERE/"android"/"CxLiveSettings.kt").read_text(encoding="utf8")
files[src+"ui/live/CxLiveManage.kt"]=(HERE/"android"/"CxLiveManage.kt").read_text(encoding="utf8")
files[src+"ui/live/CxLivePlayer.kt"]=(HERE/"android"/"CxLivePlayer.kt").read_text(encoding="utf8")

files[res+"drawable/cx_live_tv.xml"]='''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path android:fillColor="@android:color/white"
        android:pathData="M21,6h-7.59l3.29,-3.29L16,2l-4,4 -4,-4 -0.71,0.71L10.59,6L3,6c-1.1,0 -2,0.89 -2,2v12c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2L23,8c0,-1.11 -0.9,-2 -2,-2zM21,20L3,20L3,8h18v12zM9,10v8l7,-4z"/>
</vector>
'''
# Icons (Material Design paths).
CX_ICONS={'cx_movie': 'M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z', 'cx_radio': 'M3.24,6.15C2.51,6.43 2,7.17 2,8v12c0,1.1 0.89,2 2,2h16c1.11,0 2,-0.9 2,-2L22,8c0,-1.11 -0.89,-2 -2,-2L8.3,6l8.26,-3.34L15.88,1 3.24,6.15zM7,20c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM20,12h-2v-2h-2v2L4,12L4,8h16v4z', 'cx_more': 'M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z', 'cx_ic_refresh': 'M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z', 'cx_ic_add': 'M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z', 'cx_ic_phone': 'M15.5,1h-8C6.12,1 5,2.12 5,3.5v17C5,21.88 6.12,23 7.5,23h8c1.38,0 2.5,-1.12 2.5,-2.5v-17C18,2.12 16.88,1 15.5,1zM11.5,22c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5zM16,18H7V4h9v14z', 'cx_ic_search': 'M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z', 'cx_ic_close':'M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z', 'cx_ic_pause':'M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z', 'cx_ic_play':'M8,5v14l11,-7z', 'cx_cat_sport':'M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,5.3l1.35,-0.95c1.82,0.56 3.37,1.76 4.38,3.34l-0.39,1.34 -1.35,0.46L13,6.7L13,5.3zM9.65,4.35L11,5.3v1.4L7.01,9.49l-1.35,-0.46 -0.39,-1.34c1.01,-1.57 2.56,-2.77 4.38,-3.34zM7.08,17.11l-1.14,0.1C4.73,15.81 4,13.99 4,12c0,-0.12 0.01,-0.23 0.02,-0.35l1,-0.73 1.38,0.48 1.46,4.34 -0.78,1.37zM14.5,19.59c-0.79,0.26 -1.63,0.41 -2.5,0.41s-1.71,-0.15 -2.5,-0.41l-0.69,-1.49 0.64,-1.1h5.11l0.64,1.11 -0.7,1.48zM14.27,15h-4.54l-1.35,-4.02L12,8.44l3.63,2.54L14.27,15zM18.06,17.21l-1.14,-0.1 -0.79,-1.37 1.46,-4.34 1.39,-0.47 1,0.73c0.01,0.11 0.02,0.22 0.02,0.34 0,1.99 -0.73,3.81 -1.94,5.21z', 'cx_cat_news':'M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM14,17H7v-2h7V17zM17,13H7v-2h10V13zM17,9H7V7h10V9z', 'cx_cat_kids':'M9,11.75c-0.69,0 -1.25,0.56 -1.25,1.25s0.56,1.25 1.25,1.25 1.25,-0.56 1.25,-1.25 -0.56,-1.25 -1.25,-1.25zM15,11.75c-0.69,0 -1.25,0.56 -1.25,1.25s0.56,1.25 1.25,1.25 1.25,-0.56 1.25,-1.25 -0.56,-1.25 -1.25,-1.25zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8 0,-0.29 0.02,-0.58 0.05,-0.86 2.36,-1.05 4.23,-2.98 5.21,-5.37C11.07,8.33 14.05,10 17.42,10c0.78,0 1.53,-0.09 2.25,-0.26 0.21,0.71 0.33,1.47 0.33,2.26 0,4.41 -3.59,8 -8,8z', 'cx_cat_music':'M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z', 'cx_cat_religion':'M19,1l-5,5v11l5,-4.5V1zM1,6v14.65c0,0.25 0.25,0.5 0.5,0.5 0.1,0 0.15,-0.05 0.25,-0.05C3.1,20.45 5.05,20 6.5,20c1.95,0 4.05,0.4 5.5,1.5V6c-1.45,-1.1 -3.55,-1.5 -5.5,-1.5S2.45,4.9 1,6zM23,19.5V6c-0.6,-0.45 -1.25,-0.75 -2,-1v13.5c-1.1,-0.35 -2.3,-0.5 -3.5,-0.5 -1.7,0 -4.15,0.65 -5.5,1.5v2c1.35,-0.85 3.8,-1.5 5.5,-1.5 1.65,0 3.35,0.3 4.75,1.05 0.1,0.05 0.15,0.05 0.25,0.05 0.25,0 0.5,-0.25 0.5,-0.5v-1.1z', 'cx_cat_docu':'M5,13.18v4L12,21l7,-3.82v-4L12,17l-7,-3.82zM12,3L1,9l11,6 9,-4.91V17h2V9L12,3z', 'cx_cat_player':'M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,16.5v-9l6,4.5 -6,4.5z', 'cx_cat_all':'M4,8h4V4H4v4zM10,20h4v-4h-4v4zM4,20h4v-4H4v4zM4,14h4v-4H4v4zM10,14h4v-4h-4v4zM16,4v4h4V4h-4zM10,8h4V4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z', 'cx_ic_star': 'M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z'}
for name,path in CX_ICONS.items():
    files[res+"drawable/"+name+".xml"]=('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path android:fillColor="@android:color/white" android:pathData="'''+path+'''"/>
</vector>
''')
def item(i,icon,title): return '        <item android:id="@+id/'+i+'" android:icon="@drawable/'+icon+'" android:title="'+title+'" />\n'
HEAD='<?xml version="1.0" encoding="utf-8"?>\n<menu xmlns:android="http://schemas.android.com/apk/res/android">\n'
# Landscape / TV rail: seven items, Live TV first.
files[res+"menu/cx_rail_menu.xml"]=HEAD+item("navigation_live","cx_live_tv","Live TV")+item("navigation_home","cx_movie","Filem")+\
    item("navigation_search","search_icon","@string/title_search")+item("navigation_radio","cx_radio","Radio")+item("navigation_library","library_icon_selector","@string/library")+\
    item("navigation_downloads","netflix_download","@string/title_downloads")+item("navigation_settings","settings_icon_selector","@string/title_settings")+"</menu>\n"
# Phone (portrait) bar, five items at most: More opens Library, Downloads and Settings.
old=read(res+"menu/bottom_nav_menu.xml")
if 'navigation_downloads' not in old or 'navigation_settings' not in old: sys.exit("FAIL: V5.0.23 Live TV unexpected bottom_nav_menu.xml")
files[res+"menu/bottom_nav_menu.xml"]=HEAD+item("navigation_live","cx_live_tv","Live TV")+item("navigation_home","cx_movie","Filem")+\
    item("navigation_search","search_icon","@string/title_search")+item("navigation_radio","cx_radio","Radio")+item("navigation_cx_more","cx_more","Lagi")+"</menu>\n"
for layout in ("layout/activity_main.xml","layout/activity_main_tv.xml"):
    current=read(res+layout)
    new,n=re.subn(r'app:menu="@menu/bottom_nav_menu"(\s*\n\s*app:menuGravity)',r'app:menu="@menu/cx_rail_menu"\1',current)
    if n!=1: sys.exit("FAIL: V5.0.23 Live TV rail menu anchor count="+str(n)+" in "+layout)
    files[res+layout]=new

patch(res+"navigation/mobile_navigation.xml",'''    <fragment
        android:id="@+id/navigation_search"''','''    <fragment
        android:id="@+id/navigation_live"
        android:name="com.lagradost.cloudstream3.ui.live.CxLiveFragment"
        android:label="Live TV"
        app:enterAnim="@anim/enter_anim"
        app:exitAnim="@anim/exit_anim"
        app:popEnterAnim="@anim/enter_anim"
        app:popExitAnim="@anim/exit_anim" />

    <fragment
        android:id="@+id/navigation_radio"
        android:name="com.lagradost.cloudstream3.ui.live.CxLiveFragment"
        android:label="Radio"
        app:enterAnim="@anim/enter_anim"
        app:exitAnim="@anim/exit_anim"
        app:popEnterAnim="@anim/enter_anim"
        app:popExitAnim="@anim/exit_anim">
        <argument
            android:name="mode"
            android:defaultValue="radio"
            app:argType="string" />
    </fragment>

    <fragment
        android:id="@+id/navigation_search"''')

p=src+"MainActivity.kt"
patch(p,'''        val isNavVisible = listOf(
            R.id.navigation_home,
            R.id.navigation_search,''','''        val isNavVisible = listOf(
            R.id.navigation_home,
            R.id.navigation_search,
            R.id.navigation_live,
            R.id.navigation_radio,''')
patch(p,'''                for (focusView in arrayOf(
                    R.id.navigation_downloads,
                    R.id.navigation_home,
                    R.id.navigation_search,''','''                for (focusView in arrayOf(
                    R.id.navigation_downloads,
                    R.id.navigation_home,
                    R.id.navigation_search,
                    R.id.navigation_live,
                    R.id.navigation_radio,''')
patch(p,'''            for (id in arrayOf(
                R.id.navigation_home,
                R.id.navigation_search,
                R.id.navigation_library,''','''            for (id in arrayOf(
                R.id.navigation_live,
                R.id.navigation_home,
                R.id.navigation_search,
                R.id.navigation_radio,
                R.id.navigation_library,''')

# First screen: films unless the admin set Live TV for the license; the user can choose Films,
# Live TV or Radio in Settings. Only on a normal start (not a link, not after a rotation).
patch(p,'''        val rail = binding?.navRailView
        if (rail != null) {''','''        com.lagradost.cloudstream3.ui.live.CxLive.refreshConfigIfOld(this)
        if (savedInstanceState == null && intent?.data == null && (intent?.action == null || intent?.action == Intent.ACTION_MAIN)) {
            val cxFirst = when (com.lagradost.cloudstream3.ui.live.CxLive.startScreen(this)) {
                "live" -> R.id.navigation_live
                "radio" -> R.id.navigation_radio
                else -> 0
            }
            if (cxFirst != 0) binding?.navHostFragment?.post {
                try {
                    navController.navigate(cxFirst, null, NavOptions.Builder().setLaunchSingleTop(true)
                        .setPopUpTo(navController.graph.startDestinationId, false).build())
                } catch (e: Exception) {
                    android.util.Log.w("CxLive", "First screen not opened (" + e.javaClass.simpleName + ")")
                }
            }
        }

        val rail = binding?.navRailView
        if (rail != null) {''')

# Films: the extension picker shows every category except Live, Others and NSFW by default
# (new key, so the old default of Movies + Series is replaced once).
patch(src+"utils/DataStoreHelper.kt",'''    private var homePreferenceStrings: List<String> by UserPreferenceDelegate(
        "home_pref_homepage",
        listOf(TvType.Movie, TvType.TvSeries).map { it.name })''','''    private var homePreferenceStrings: List<String> by UserPreferenceDelegate(
        "home_pref_homepage_cx2",
        TvType.values().filter { it != TvType.Live && it != TvType.Others && it != TvType.NSFW }.map { it.name })''')

patch("app/src/main/AndroidManifest.xml",'''        <activity android:name=".activation.CxActivationActivity"
            android:exported="false" />''','''        <activity android:name=".activation.CxActivationActivity"
            android:exported="false" />
        <!-- Live TV: official YouTube live channels in YouTube's embedded player (V5.0.24). -->
        <activity android:name=".ui.live.CxLivePlayerActivity"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|navigation|uiMode"
            android:screenOrientation="sensorLandscape"
            android:supportsPictureInPicture="false"
            android:exported="false" />
        <activity android:name=".ui.live.CxYoutubeActivity"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|navigation|uiMode"
            android:screenOrientation="sensorLandscape"
            android:exported="false" />''')

# Previous episode / channel went forward (index + 1) instead of back.
patch(src+"ui/player/PlayerGeneratorViewModel.kt",'''        if (generator?.hasPrev(episodeIndex) == true) {
            episodeIndex += 1''','''        if (generator?.hasPrev(episodeIndex) == true) {
            episodeIndex -= 1''')

# Navigation highlight: items missing from a menu (e.g. Downloads on the phone bar) crashed the app.
p=src+"MainActivity.kt"
patch(p,"""            when (destination.id) {
                in listOf(""","""            when (destination.id) {
                R.id.navigation_library -> {
                    navRailView.menu.findItem(R.id.navigation_library)?.isChecked = true
                    (navView.menu.findItem(R.id.navigation_library) ?: navView.menu.findItem(R.id.navigation_cx_more))?.isChecked = true
                }

                in listOf(""")
for item_id in ("navigation_downloads","navigation_settings"):
    patch(p,"""                    navRailView.menu.findItem(R.id."""+item_id+""").isChecked = true
                    navView.menu.findItem(R.id."""+item_id+""").isChecked = true""","""                    navRailView.menu.findItem(R.id."""+item_id+""")?.isChecked = true
                    (navView.menu.findItem(R.id."""+item_id+""") ?: navView.menu.findItem(R.id.navigation_cx_more))?.isChecked = true""")
patch(p,"""    private fun onNavDestinationSelected(item: MenuItem, navController: NavController): Boolean {
""","""    /** Phone bar "More" (V5.0.24): Library, Downloads and Settings. */
    private fun showCxMore(navController: NavController) {
        val anchor = binding?.navView?.findViewById<View>(R.id.navigation_cx_more) ?: binding?.navView ?: return
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menu.add(0, R.id.navigation_library, 0, getString(R.string.library))
        popup.menu.add(0, R.id.navigation_downloads, 1, getString(R.string.title_downloads))
        popup.menu.add(0, R.id.navigation_settings, 2, getString(R.string.title_settings))
        popup.setOnMenuItemClickListener { lastNavTime = 0L; onNavDestinationSelected(it, navController); true }
        popup.show()
    }

    private fun onNavDestinationSelected(item: MenuItem, navController: NavController): Boolean {
        if (item.itemId == R.id.navigation_cx_more) {
            showCxMore(navController)
            return false
        }
""")

# Profiles (Netflix style): the start-up profile picker has no "+" and no edit button, so profiles
# are added or locked from inside the app (profile icon, or Settings -> Profil & kunci). A profile,
# also the only one, can be locked with a PIN; a locked profile always asks for it at start-up.
p=src+"ui/account/AccountSelectActivity2.kt"
patch(p,'        const val IS_EDITING_FROM_MAIN_ACTIVITY = "isEditingFromMainActivity"\n',
        '        const val IS_EDITING_FROM_MAIN_ACTIVITY = "isEditingFromMainActivity"\n'
        '        /** True on the start-up profile picker (not opened from inside the app). */\n'
        '        @JvmStatic var cxStartup = false\n')
patch(p,"        val isFromMainActivity = intent.getBooleanExtra(IS_FROM_MAIN_ACTIVITY, false)\n",
        "        val isFromMainActivity = intent.getBooleanExtra(IS_FROM_MAIN_ACTIVITY, false)\n"
        "        cxStartup = !isFromMainActivity && !isEditingFromMainActivity\n")
p=src+"ui/account/AccountScreen.kt"
patch(p,"                IconButton(\n                    onClick = {\n                        onAction(AccountAction.SetEditMode(!state.canEditAccount))",
        "                if (!AccountSelectActivity2.cxStartup) IconButton(\n                    onClick = {\n                        onAction(AccountAction.SetEditMode(!state.canEditAccount))")
patch(p,"                                up = edit","                                if (!AccountSelectActivity2.cxStartup) up = edit")
patch(p,"            var hasFocus by remember { mutableStateOf(false) }\n            val size = 100.dp",
        "            // \"+\" only while managing profiles inside the app.\n            if (state.canEditAccount) {\n"
        "            var hasFocus by remember { mutableStateOf(false) }\n            val size = 100.dp")
patch(p,"""                Text(
                    text = stringResource(R.string.add_account),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }""","""                Text(
                    text = stringResource(R.string.add_account),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            }""")
patch(res+"layout/main_settings.xml",'            <TextView\n                android:id="@+id/settings_credits"',
      '            <TextView\n                android:id="@+id/settings_cx_profiles"\n                style="@style/SettingsItem"\n'
      '                android:text="Profil &amp; kunci profil" />\n\n            <TextView\n                android:id="@+id/settings_credits"')

patch(src+"ui/settings/SettingsFragment.kt","""            // Default focus on TV
            if (isLayout(TV)) {
                settingsGeneral.requestFocus()
            }""","""            // Default focus on TV
            if (isLayout(TV)) {
                settingsGeneral.requestFocus()
            }
            // Profiles: add, edit and lock them with a PIN (V5.0.24).
            root.findViewById<View>(R.id.settings_cx_profiles)?.apply {
                if (isLayout(TV)) { isFocusable = true; isFocusableInTouchMode = true }
                setOnClickListener { with(com.lagradost.cloudstream3.ui.account.AccountHelper) { activity.showAccountSelectLinear() } }
            }""")

# Any profile can be locked, also the first (default) one; the start-up picker cannot open the
# edit dialog by a long press (a locked profile could be deleted there without its PIN).
p=src+"ui/account/AccountScreen.kt"
patch(p,"""                    if (account.keyIndex != 0) {
                        SwitchPreferenceWidget(""","""                    run {
                        SwitchPreferenceWidget(""")
patch(p,"""            /** Long click -> edit without having to press the edit button for better UX */
            onAction(AccountAction.EditAccount(account))""","""            /** Long click -> edit without having to press the edit button for better UX */
            if (!AccountSelectActivity2.cxStartup) onAction(AccountAction.EditAccount(account))""")

# Settings (the Compose screen the app shows): Profile, General (with the main menu), Live TV, Player, Layout, Updates.
p=src+"ui/settings/SettingsFragmentScreen.kt"
patch(p,"""        SettingsNavigation(
            title = R.string.category_player,""","""        SettingsNavigation(
            title = R.string.cx_live_tv,
            navigation = R.id.action_navigation_global_to_navigation_settings_cx_live,
            screen = CxLiveSettingsScreen,
            icon = R.drawable.cx_live_tv,
            subtitle = persistentListOf(
                R.string.cx_live_source,
                R.string.cx_live_manage,
                R.string.cx_live_subs,
                R.string.cx_live_adult
            )
        ),
        SettingsNavigation(
            title = R.string.category_player,""")
patch(res+"navigation/mobile_navigation.xml","""    <action
        android:id="@+id/action_navigation_global_to_navigation_settings_updates""","""    <action
        android:id="@+id/action_navigation_global_to_navigation_settings_cx_live"
        app:destination="@id/navigation_settings_cx_live"
        app:enterAnim="@anim/enter_anim"
        app:exitAnim="@anim/exit_anim"
        app:popEnterAnim="@anim/enter_anim"
        app:popExitAnim="@anim/exit_anim" />
    <action
        android:id="@+id/action_navigation_global_to_navigation_settings_updates""")
patch(res+"navigation/mobile_navigation.xml","""    <fragment
        android:id="@+id/navigation_settings_updates""","""    <fragment
        android:id="@+id/navigation_settings_cx_live"
        android:name="com.lagradost.cloudstream3.ui.settings.CxLiveSettings2"
        android:label="@string/title_settings"
        app:enterAnim="@anim/enter_anim"
        app:exitAnim="@anim/exit_anim"
        app:popEnterAnim="@anim/enter_anim"
        app:popExitAnim="@anim/exit_anim" />

    <fragment
        android:id="@+id/navigation_settings_updates""")
# General: the main menu first.
p=src+"ui/settings/SettingsGeneralScreen.kt"
patch(p,"""        return persistentListOf(
            Preference.PreferenceGroup(title = stringResource(R.string.extension_language),""","""        val cxLive = com.lagradost.cloudstream3.ui.live.CxLive
        var cxStart by remember { mutableStateOf(cxLive.startScreenName(context)) }
        return persistentListOf(
            Preference.PreferenceGroup(title = stringResource(R.string.cx_main_menu), preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(R.string.cx_main_menu),
                    subtitle = stringResource(R.string.cx_main_menu_sub, cxStart),
                    icon = painterResource(R.drawable.cx_live_tv),
                    onClick = { cxLive.chooseStartScreen(context) { cxStart = cxLive.startScreenName(context) } },
                )
            )),
            Preference.PreferenceGroup(title = stringResource(R.string.extension_language),""")

# Text in the app's language: Malay for Bahasa Melayu, English otherwise.
from xml.sax.saxutils import escape as _esc
CX_STRINGS={
 "cx_main_menu":("Main menu","Menu utama"),
 "cx_main_menu_sub":("%1$s opens first (Films / Live TV / Radio)","%1$s dibuka dahulu (Filem / Live TV / Radio)"),
 "cx_films":("Films","Filem"),
 "cx_live_tv":("Live TV","Live TV"),
 "cx_radio":("Radio","Radio"),
 "cx_live_source":("Default source","Sumber lalai"),
 "cx_live_no_sources":("No Live TV source yet.","Belum ada sumber Live TV."),
 "cx_live_subs":("Subtitles","Sari kata"),
 "cx_auto":("Auto","Auto"),
 "cx_malay":("Malay","Bahasa Melayu"),
 "cx_live_adult":("Adult (18+) categories","Kategori dewasa (18+)"),
 "cx_live_adult_hidden":("Hidden · always hidden in a Kids profile","Disembunyikan · sentiasa tersembunyi dalam profil Kanak-kanak"),
 "cx_live_adult_shown":("Shown · still hidden in a Kids profile","Ditunjukkan · tetap tersembunyi dalam profil Kanak-kanak"),
 "cx_live_adult_kids":("Not allowed in a Kids profile.","Tidak dibenarkan dalam profil Kanak-kanak."),
 "cx_live_adult_confirm_title":("Show adult categories?","Tunjuk kategori dewasa?"),
 "cx_live_adult_confirm_text":("Adult (18+) categories and channels in IPTV playlists will be shown. They stay hidden in a Kids profile.","Kategori dan saluran dewasa (18+) dalam playlist IPTV akan ditunjukkan. Ia tetap tersembunyi dalam profil Kanak-kanak."),
 "cx_show":("Show","Tunjuk"),
 "cx_profile":("Profile","Profil"),
 "cx_live_manage":("Manage playlists","Urus playlist"),
 "cx_live_manage_sub":("Show or hide sources, arrange categories and channels","Tunjuk atau sembunyi sumber, susun kategori dan saluran"),
 "cx_profile_sub":("Add, edit or lock profiles with a PIN","Tambah, ubah atau kunci profil dengan PIN"),
}
for _path,_col in (("app/src/main/res/values/strings.xml",0),("app/src/main/res/values-b+ms/strings.xml",1)):
    _lines="".join('    <string name="'+k+'">'+_esc(v[_col])+'</string>\n' for k,v in CX_STRINGS.items())
    patch(_path,"</resources>","    <!-- CloudXtream Live TV settings -->\n"+_lines+"</resources>")
p=src+"ui/settings/SettingsFragmentScreen.kt"
patch(p,"""                            screens.forEach { settingsTab ->
                                SettingsTab(settingsTab)
                            }""","""                            CxSettingsRows()
                            screens.forEach { settingsTab ->
                                SettingsTab(settingsTab)
                            }""")
patch(p,"""    @Composable
    fun SettingsTab(settingsTab: SettingsNavigation) {""","""    /** CloudXtream row: profiles and their lock (first row of Settings). */
    @Composable
    fun CxSettingsRows() {
        val ctx = LocalContext.current
        TextPreferenceWidget(
            title = stringResource(R.string.cx_profile),
            icon = painterResource(R.drawable.ic_outline_account_circle_24),
            subtitle = stringResource(R.string.cx_profile_sub),
            onPreferenceClick = {
                activity?.navigate(R.id.accountSelectActivity, Bundle().apply { putBoolean("isFromMainActivity", true) })
            })
    }

    @Composable
    fun SettingsTab(settingsTab: SettingsNavigation) {""")

# Live TV subtitles: the language chosen in Settings (Auto: Malay, else English, else the app's language).
p=src+"ui/player/GeneratorPlayer.kt"
patch(p,"""    private fun autoSelectSubtitles() {
        //Log.i(TAG, "autoSelectSubtitles")
        safe {
""","""    private fun cxIsLive() = (currentMeta as? ExtractorUri)?.tvType == TvType.Live

    /** True when a Live TV channel's subtitles were handled here. */
    private fun cxLiveAutoSelect(): Boolean {
        if (!cxIsLive()) return false
        val ctx = context ?: return true
        val live = com.lagradost.cloudstream3.ui.live.CxLive
        val langs = live.subtitleLanguages(ctx, getAutoSelectLanguageTagIETF())
        val current = player.getCurrentPreferredSubtitle()
        if (current != null && langs.any { current.matchesLanguageCode(it) }) return true
        val sub = langs.firstNotNullOfOrNull { l -> sortSubs(viewModel.state.subtitles).firstOrNull { it.matchesLanguageCode(l) } }
            ?: return live.subtitleChoice(ctx) != "auto"
        if (setSubtitles(sub, false)) {
            player.saveData()
            player.reloadPlayer(ctx)
            player.handleEvent(CSPlayerEvent.Play)
        }
        return true
    }

    private fun autoSelectSubtitles() {
        //Log.i(TAG, "autoSelectSubtitles")
        if (cxLiveAutoSelect()) return
        safe {
""")
patch(p,"""    private fun cxMaybeAutoSubtitles() {
        val ctx = context ?: return
""","""    private fun cxMaybeAutoSubtitles() {
        val ctx = context ?: return
        if (cxIsLive()) return // live channels: no online subtitle search
""")

# Live TV on a TV remote: typing a channel number (1-9999) opens that channel of the list.
p=src+"ui/player/FullScreenPlayer.kt"
patch(p,"""    private fun handleKeyDownEvent(keyCode: Int): Boolean? {
        // adb shell input keyevent [INT]
""","""    /** Live TV: a digit typed on the remote (true when used as a channel number). */
    protected open fun cxChannelDigit(digit: Int): Boolean = false

    private fun handleKeyDownEvent(keyCode: Int): Boolean? {
        // adb shell input keyevent [INT]
        val cxDigit = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (cxDigit >= 0 && cxChannelDigit(cxDigit)) return true
""")
patch(src+"ui/player/PlayerGeneratorViewModel.kt","""    fun loadThisEpisode(index: Int) {""","""    fun cxVideoCount(): Int = generator?.videos?.size ?: 0

    fun loadThisEpisode(index: Int) {""")
p=src+"ui/player/GeneratorPlayer.kt"
patch(p,"""    private fun cxIsLive() = (currentMeta as? ExtractorUri)?.tvType == TvType.Live
""","""    private fun cxIsLive() = (currentMeta as? ExtractorUri)?.tvType == TvType.Live

    private var cxTyped = ""
    private var cxTypedToast: Toast? = null
    private val cxTypedHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun cxChannelDigit(digit: Int): Boolean {
        if (!cxIsLive()) return false
        val ctx = context ?: return false
        val count = viewModel.cxVideoCount()
        if (count <= 1 && com.lagradost.cloudstream3.ui.live.CxLive.numbered.isEmpty()) return false
        cxTyped = (cxTyped + digit).takeLast(4)
        cxTypedToast?.cancel()
        cxTypedToast = Toast.makeText(ctx, "Saluran " + cxTyped, Toast.LENGTH_SHORT).also { it.show() }
        cxTypedHandler.removeCallbacksAndMessages(null)
        cxTypedHandler.postDelayed({
            val n = cxTyped.toIntOrNull() ?: 0
            cxTyped = ""
            if (!isAdded) return@postDelayed
            val live = com.lagradost.cloudstream3.ui.live.CxLive
            // The channel numbers of the source (the playlist's, the user's, else the position).
            val target = live.byNumber(n)
            val inList = target?.let { t -> live.playing.indexOfFirst { it.key == t.key } } ?: -1
            when {
                target != null && inList in 0 until viewModel.cxVideoCount() -> {
                    isNextEpisode = true
                    releasePlayer()
                    viewModel.loadThisEpisode(inList)
                }
                target != null -> activity?.let { com.lagradost.cloudstream3.ui.live.cxOpenNumbered(it, target) }
                live.numbered.isEmpty() && n in 1..viewModel.cxVideoCount() -> {
                    isNextEpisode = true
                    releasePlayer()
                    viewModel.loadThisEpisode(n - 1)
                }
                else -> context?.let { Toast.makeText(it, "Tiada saluran " + n, Toast.LENGTH_SHORT).show() }
            }
        }, 1500)
        return true
    }
""")
# Settings: adult categories row
p=src+"ui/settings/SettingsFragmentScreen.kt"
# A crash that put the app in safe mode is reported once (admin panel, Telegram).
patch(src+"MainActivity.kt","""            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            builder.setTitle(R.string.safe_mode_title)""","""            com.lagradost.cloudstream3.ui.live.CxCrash.report(this, lastError)
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            builder.setTitle(R.string.safe_mode_title)""")

# Films: movies and series of the Xtream accounts the user can see, as providers (before plugins load).
patch(src+"MainActivity.kt","""        ioSafe { SafeFile.check(this@MainActivity) }
""","""        ioSafe { SafeFile.check(this@MainActivity) }
        try { com.lagradost.cloudstream3.ui.live.CxXtream.register(this) } catch (e: Exception) {
            android.util.Log.w("CxXtream", "Xtream providers not registered (" + e.javaClass.simpleName + ")")
        }
""")

for path,source in files.items():
    target=ROOT/path
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(source,encoding="utf8")
print("PASS: V5.0.23 Live TV",len(files),"files")
