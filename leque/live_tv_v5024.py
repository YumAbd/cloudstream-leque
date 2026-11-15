#!/usr/bin/env python3
"""CloudXtream V5.0.24: Live TV menu.

- playlists from CloudXtream Control (the official free channels for everyone, others for chosen
  users) and, where the admin allows it, the user's own playlists: a link in any form (M3U,
  shortlink, link without extension, full Xtream link), Xtream Codes login, a file, or sent from a
  phone with a code
- groups, search, favourites (long press), recently watched; CH+/CH- change channel in the player
- official YouTube live channels play inside the app with YouTube's embedded player
- menu: TV / tablet rail gets "Live TV"; the phone bar shows Live TV instead of Downloads, which
  moves to Settings -> Muat turun
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
    if count!=1: sys.exit("FAIL: V5.0.24 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50023"','versionCode = "50024"')
patch("gradle/libs.versions.toml",'versionName = "5.0.23"','versionName = "5.0.24"')
src="app/src/main/java/com/lagradost/cloudstream3/"
res="app/src/main/res/"
files[src+"ui/live/CxLive.kt"]=(HERE/"android"/"CxLive.kt").read_text(encoding="utf8")
files[src+"ui/live/CxLiveFragment.kt"]=(HERE/"android"/"CxLiveFragment.kt").read_text(encoding="utf8")

files[res+"drawable/cx_live_tv.xml"]='''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path android:fillColor="@android:color/white"
        android:pathData="M21,6h-7.59l3.29,-3.29L16,2l-4,4 -4,-4 -0.71,0.71L10.59,6L3,6c-1.1,0 -2,0.89 -2,2v12c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2L23,8c0,-1.11 -0.9,-2 -2,-2zM21,20L3,20L3,8h18v12zM9,10v8l7,-4z"/>
</vector>
'''
# Rail (TV, tablet): six items. The phone bar is limited to five by Android: Live TV replaces Downloads.
files[res+"menu/cx_rail_menu.xml"]='''<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
        <item android:id="@+id/navigation_home" android:icon="@drawable/home_icon_selector" android:title="@string/title_home" />
        <item android:id="@+id/navigation_search" android:icon="@drawable/search_icon" android:title="@string/title_search" />
        <item android:id="@+id/navigation_live" android:icon="@drawable/cx_live_tv" android:title="Live TV" />
        <item android:id="@+id/navigation_library" android:icon="@drawable/library_icon_selector" android:title="@string/library" />
        <item android:id="@+id/navigation_downloads" android:icon="@drawable/netflix_download" android:title="@string/title_downloads" />
        <item android:id="@+id/navigation_settings" android:icon="@drawable/settings_icon_selector" android:title="@string/title_settings" />
</menu>
'''
patch(res+"menu/bottom_nav_menu.xml",'''        <item
            android:id="@+id/navigation_downloads"
            android:icon="@drawable/netflix_download"
            android:title="@string/title_downloads" />''','''        <item
            android:id="@+id/navigation_live"
            android:icon="@drawable/cx_live_tv"
            android:title="Live TV" />''')
for layout in ("layout/activity_main.xml","layout/activity_main_tv.xml"):
    current=read(res+layout)
    new,n=re.subn(r'app:menu="@menu/bottom_nav_menu"(\s*\n\s*app:menuGravity)',r'app:menu="@menu/cx_rail_menu"\1',current)
    if n!=1: sys.exit("FAIL: V5.0.24 rail menu anchor count="+str(n)+" in "+layout)
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
        android:id="@+id/navigation_search"''')

p=src+"MainActivity.kt"
patch(p,'''        val isNavVisible = listOf(
            R.id.navigation_home,
            R.id.navigation_search,''','''        val isNavVisible = listOf(
            R.id.navigation_home,
            R.id.navigation_search,
            R.id.navigation_live,''')
patch(p,'''                for (focusView in arrayOf(
                    R.id.navigation_downloads,
                    R.id.navigation_home,
                    R.id.navigation_search,''','''                for (focusView in arrayOf(
                    R.id.navigation_downloads,
                    R.id.navigation_home,
                    R.id.navigation_search,
                    R.id.navigation_live,''')
patch(p,'''            for (id in arrayOf(
                R.id.navigation_home,
                R.id.navigation_search,
                R.id.navigation_library,''','''            for (id in arrayOf(
                R.id.navigation_home,
                R.id.navigation_search,
                R.id.navigation_live,
                R.id.navigation_library,''')

patch("app/src/main/AndroidManifest.xml",'''        <activity android:name=".activation.CxActivationActivity"
            android:exported="false" />''','''        <activity android:name=".activation.CxActivationActivity"
            android:exported="false" />
        <!-- Live TV: official YouTube live channels in YouTube's embedded player (V5.0.24). -->
        <activity android:name=".ui.live.CxYoutubeActivity"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|navigation|uiMode"
            android:screenOrientation="sensorLandscape"
            android:exported="false" />''')

# Previous episode / channel went forward (index + 1) instead of back.
patch(src+"ui/player/PlayerGeneratorViewModel.kt",'''        if (generator?.hasPrev(episodeIndex) == true) {
            episodeIndex += 1''','''        if (generator?.hasPrev(episodeIndex) == true) {
            episodeIndex -= 1''')

# Phone: Downloads is in Settings now that Live TV takes its place in the bottom bar.
patch(res+"layout/main_settings.xml",'''            <TextView
                android:id="@+id/settings_credits"''','''            <TextView
                android:id="@+id/settings_cx_downloads"
                style="@style/SettingsItem"
                android:text="@string/title_downloads" />

            <TextView
                android:id="@+id/settings_credits"''')
patch(src+"ui/settings/SettingsFragment.kt",'''            // Default focus on TV
            if (isLayout(TV)) {
                settingsGeneral.requestFocus()
            }''','''            // Default focus on TV
            if (isLayout(TV)) {
                settingsGeneral.requestFocus()
            }
            // Phone: Downloads (its place in the bottom bar is Live TV). TV and tablets have it in the rail.
            root.findViewById<View>(R.id.settings_cx_downloads)?.apply {
                visibility = if (activity?.findViewById<View>(R.id.nav_rail_view)?.isShown == true) View.GONE else View.VISIBLE
                setOnClickListener { activity?.navigate(R.id.navigation_downloads) }
            }''')

for path,source in files.items():
    target=ROOT/path
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(source,encoding="utf8")
print("PASS: V5.0.24 Live TV",len(files),"files")
