#!/usr/bin/env python3
"""CloudXtream V5.0.24: Live TV menu.

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
# Icons (Material Design paths).
CX_ICONS={'cx_movie': 'M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z', 'cx_radio': 'M3.24,6.15C2.51,6.43 2,7.17 2,8v12c0,1.1 0.89,2 2,2h16c1.11,0 2,-0.9 2,-2L22,8c0,-1.11 -0.89,-2 -2,-2L8.3,6l8.26,-3.34L15.88,1 3.24,6.15zM7,20c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM20,12h-2v-2h-2v2L4,12L4,8h16v4z', 'cx_more': 'M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z', 'cx_ic_refresh': 'M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z', 'cx_ic_add': 'M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z', 'cx_ic_phone': 'M15.5,1h-8C6.12,1 5,2.12 5,3.5v17C5,21.88 6.12,23 7.5,23h8c1.38,0 2.5,-1.12 2.5,-2.5v-17C18,2.12 16.88,1 15.5,1zM11.5,22c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5zM16,18H7V4h9v14z', 'cx_ic_search': 'M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z', 'cx_ic_star': 'M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z'}
for name,path in CX_ICONS.items():
    files[res+"drawable/"+name+".xml"]=('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path android:fillColor="@android:color/white" android:pathData="'''+path+'''"/>
</vector>
''')
def item(i,icon,title): return '        <item android:id="@+id/'+i+'" android:icon="@drawable/'+icon+'" android:title="'+title+'" />\n'
HEAD='<?xml version="1.0" encoding="utf-8"?>\n<menu xmlns:android="http://schemas.android.com/apk/res/android">\n'
# Landscape / TV rail: seven items.
files[res+"menu/cx_rail_menu.xml"]=HEAD+item("navigation_home","cx_movie","Filem")+item("navigation_search","search_icon","@string/title_search")+\
    item("navigation_live","cx_live_tv","Live TV")+item("navigation_radio","cx_radio","Radio")+item("navigation_library","library_icon_selector","@string/library")+\
    item("navigation_downloads","netflix_download","@string/title_downloads")+item("navigation_settings","settings_icon_selector","@string/title_settings")+"</menu>\n"
# Phone (portrait) bar, five items at most: More opens Library, Downloads and Settings.
old=read(res+"menu/bottom_nav_menu.xml")
if 'navigation_downloads' not in old or 'navigation_settings' not in old: sys.exit("FAIL: V5.0.24 unexpected bottom_nav_menu.xml")
files[res+"menu/bottom_nav_menu.xml"]=HEAD+item("navigation_home","cx_movie","Filem")+item("navigation_search","search_icon","@string/title_search")+\
    item("navigation_live","cx_live_tv","Live TV")+item("navigation_radio","cx_radio","Radio")+item("navigation_cx_more","cx_more","Lagi")+"</menu>\n"
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
                R.id.navigation_home,
                R.id.navigation_search,
                R.id.navigation_live,
                R.id.navigation_radio,
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

for path,source in files.items():
    target=ROOT/path
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(source,encoding="utf8")
print("PASS: V5.0.24 Live TV",len(files),"files")
