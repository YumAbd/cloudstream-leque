#!/usr/bin/env python3
"""CloudXtream V5.0.14: Bahasa Melayu, first-run guide, player sleep timer and problem reports,
license expiry reminder, Kids profiles, PIN for every profile and a fresher Continue Watching."""
from pathlib import Path
from xml.sax.saxutils import escape
import re, sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
HERE=Path(__file__).resolve().parent
files={}
def read(path):
    return files[path] if path in files else (ROOT/path).read_text(encoding="utf8")
def patch(path,before,after):
    current=read(path)
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.14 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)
def add_file(path,name):
    if (ROOT/path).exists(): sys.exit("FAIL: duplicate class "+name)
    files[path]=(HERE/"android"/name).read_text(encoding="utf8")

patch("gradle/libs.versions.toml",'versionCode = "50013"','versionCode = "50014"')
patch("gradle/libs.versions.toml",'versionName = "5.0.13"','versionName = "5.0.14"')

# ---------------------------------------------------------------- strings (English + Malay)
def res(text):
    return escape(text).replace("'","\\'").replace('"','\\"').replace("\n","\\n")
CX={  # name: (English, Bahasa Melayu)
 "cx_notify_channel":("App updates","Kemas kini aplikasi"),
 "cx_notify_channel_des":("New versions and license reminders","Versi baharu dan peringatan lesen"),
 "cx_update_title":("CloudXtream update available","Kemas kini CloudXtream tersedia"),
 "cx_update_text":("Version %1$s is ready. Tap to update.","Versi %1$s sudah tersedia. Tekan untuk kemas kini."),
 "cx_download_title":("Downloading update","Memuat turun kemas kini"),
 "cx_download_connecting":("Connecting…","Menyambung…"),
 "cx_expiry_title":("License expiring soon","Lesen hampir tamat"),
 "cx_onb_next":("Next","Seterusnya"),
 "cx_onb_skip":("Skip","Langkau"),
 "cx_onb_start":("Start watching","Mula menonton"),
 "cx_onb1_title":("Welcome to CloudXtream","Selamat datang ke CloudXtream"),
 "cx_onb1_text":("Find movies and series in Search. Tap a title to see its details, then tap Play.",
                 "Cari movie dan siri di tab Carian. Tekan tajuk untuk lihat butirannya, kemudian tekan Main."),
 "cx_onb2_title":("Continue watching","Sambung tonton"),
 "cx_onb2_text":("Unfinished movies and episodes appear at the top of Home. Long-press one to remove it.\n\nIn the player, the More button has a sleep timer and Report a problem.",
                 "Tontonan yang belum habis muncul di bahagian atas Home. Tekan lama untuk membuangnya.\n\nDalam player, butang Lagi ada pemasa tidur dan Lapor masalah."),
 "cx_onb3_title":("Profiles for the family","Profile untuk keluarga"),
 "cx_onb3_text":("Tap the profile picture to add profiles. Each profile keeps its own watch history and searches.\n\nEdit a profile to lock it with a PIN, or make it a Kids profile.",
                 "Tekan gambar profile untuk tambah profile. Setiap profile ada sejarah tontonan dan carian sendiri.\n\nEdit profile untuk kunci dengan PIN, atau jadikan Profile Kanak-kanak."),
 "cx_player_more":("More","Lagi"),
 "cx_sleep_timer":("Sleep timer","Pemasa tidur"),
 "cx_sleep_timer_on":("Sleep timer (%d min left)","Pemasa tidur (%d minit lagi)"),
 "cx_sleep_off":("Off","Tutup"),
 "cx_sleep_minutes":("%d minutes","%d minit"),
 "cx_sleep_set":("The video will pause in %d minutes","Video akan berhenti dalam %d minit"),
 "cx_sleep_cancelled":("Sleep timer off","Pemasa tidur dimatikan"),
 "cx_sleep_paused":("Sleep timer: video paused","Pemasa tidur: video dihentikan"),
 "cx_report":("Report a problem","Lapor masalah"),
 "cx_report_sending":("Sending report…","Menghantar laporan…"),
 "cx_report_sent":("Thank you! The report was sent to the owner.","Terima kasih! Laporan telah dihantar kepada pemilik."),
 "cx_report_failed":("The report could not be sent. Check the Internet and try again.","Laporan gagal dihantar. Semak Internet dan cuba lagi."),
 "cx_kids_profile":("Kids profile (parent PIN needed to leave)","Profile Kanak-kanak (perlu PIN ibu bapa untuk keluar)"),
 "cx_kids_blocked":("This title is not available in the Kids profile.","Tajuk ini tidak tersedia dalam Profile Kanak-kanak."),
}
REASONS=(["The video does not play","Keeps loading or freezing","Wrong or missing subtitles","Audio out of sync","Wrong movie or episode","Something else"],
         ["Video tidak boleh dimainkan","Asyik loading atau tersekat","Sari kata salah atau tiada","Audio tidak selari","Movie atau episod salah","Lain-lain"])
EXPIRY=({"one":"Your license expires in %d day. Contact the owner to extend it.","other":"Your license expires in %d days. Contact the owner to extend it."},
        {"other":"Lesen anda tamat dalam %d hari. Hubungi pemilik lesen untuk menyambungnya."})

# Malay for upstream strings shown in everyday screens that the upstream Malay file lacks.
MS_UPSTREAM={
 "app_name":"CloudXtream by Leque","category_updates":"Kemas kini","episodes":"Episod",
 "resume_time_left":"%dm\\nlagi","queued":"dalam giliran","action_default":"Lalai","video_singular":"Video",
 "episode_action_cast_mirror":"Cast mirror","episode_action_play_mirror":"Main mirror","episode_action_reload_links":"Muat semula pautan",
 "no_update_found":"Tiada kemas kini","video_skip_op":"Langkau OP","skip_update":"Langkau kemas kini ini",
 "search_suggestions":"Cadangan carian","search_suggestions_des":"Tunjuk cadangan carian semasa menaip","clear_suggestions":"Kosongkan cadangan",
 "subtitle_offset":"Selaraskan sari kata","player_loaded_subtitles":"Dimuatkan %s","player_load_subtitles":"Muat dari fail",
 "player_load_subtitles_online":"Muat dari Internet","player_load_one_subtitle_online":"Muat yang pertama tersedia","video_info":"Info media",
 "trailer":"Treler","tracks":"Trek","audio_tracks":"Trek audio","video_tracks":"Trek video","player_pref":"Pemain video pilihan",
 "player_settings_always_ask":"Sentiasa tanya","player_settings_select_cast_device":"Pilih peranti cast",
 "sort_rating_desc":"Rating (Tinggi ke Rendah)","sort_rating_asc":"Rating (Rendah ke Tinggi)","sort_updated_new":"Dikemas kini (Baharu ke Lama)",
 "sort_updated_old":"Dikemas kini (Lama ke Baharu)","sort_alphabetical_a":"Abjad (A ke Z)","sort_alphabetical_z":"Abjad (Z ke A)",
 "sort_episodes_number_asc":"Episod (Menaik)","sort_episodes_number_desc":"Episod (Menurun)","sort_episodes_rating_high_low":"Rating (Tertinggi)",
 "sort_episodes_rating_low_high":"Rating (Terendah)","sort_episodes_date_newest":"Tarikh tayangan (Terbaharu)","sort_episodes_date_oldest":"Tarikh tayangan (Terlama)",
 "sort_button_episode":"Ep %s","sort_button_rating":"Rating %s","sort_button_date":"Tarikh %s",
 "subscription_deleted":"Berhenti melanggan %s","profile_settings":"Tetapan profile","qualities":"Kualiti",
 "no_account":"Tiada akaun","skip_startup_account_select_pref":"Langkau pilihan profile semasa mula","use_default_account":"Guna profile lalai",
 "custom":"Tersuai","show":"Tunjuk","dont_show":"Jangan tunjuk","confirm_before_exiting_title":"Sahkan sebelum keluar",
 "confirm_before_exiting_desc":"Tunjuk dialog sebelum keluar dari aplikasi","volume_exceeded_100":"Kelantangan melebihi 100%",
 "slide_up_again_to_exceed_100":"Leret ke atas sekali lagi untuk melebihi 100%","go_to_downloads":"Pergi ke Muat Turun",
 "no_internet_connection":"Tiada sambungan Internet.\\n\\nSila sambung ke Internet dan cuba lagi, atau tonton muat turun anda semasa luar talian.",
 "speedup_title":"Tekan lama untuk laju","speedup_summary":"Tekan dan tahan untuk kelajuan 2x",
 "edit_profile_image_title":"Tukar gambar profile","edit_profile_image_url":"URL","edit_profile_image_picker":"Pilih gambar",
 "edit_profile_image_default":"Gambar lalai","edit_profile_image_hint":"Masukkan URL gambar profile","edit_profile_image_error_empty":"Tiada URL",
 "edit_profile_image_error_invalid":"URL atau gambar tidak sah","edit_profile_image_success":"Gambar berjaya dikemas kini",
 "action_mark_watched_up_to_this_episode":"Tanda sudah ditonton hingga episod ini","action_remove_mark_watched_up_to_this_episode":"Buang tanda ditonton hingga episod ini",
 "action_reload":"Dimuat semula","reload_provider":"Muat semula sumber","name":"Nama","source_name":"Nama sumber","resolution_and_name":"Resolusi dan nama",
 "download_all":"Muat turun semua","cancel_all":"Batal semua","download_episode_range":"Muat turun episod %s?","cancel_queue_message":"Batalkan semua muat turun dalam giliran?",
 "player_is_live":"Langsung","need_storage":"Benarkan untuk muat turun episod","show_cast_in_details":"Tunjuk panel pelakon",
 "hide_player_control_names":"Sorok nama butang player","preview_seekbar":"Pratonton seekbar","preview_seekbar_desc":"Tunjuk gambar kecil semasa mencari dalam video",
 "subs_subtitle_alignment":"Kedudukan sari kata","player_notification_channel_name":"Notifikasi player",
 "player_notification_channel_description":"Kawal tontonan dari latar belakang","all_subtitles_bold":"Semua sari kata tebal",
 "all_subtitles_italic":"Semua sari kata condong","background":"Latar belakang","parallel_downloads":"Muat turun serentak",
 "poster_size_settings":"Saiz poster","poster_size_settings_des":"Tukar saiz poster","unable_to_inflate":"Paparan gagal dibuat dengan betul. Sila laporkan: %s",
}

def add_resources(path,entries,arrays,plurals):
    xml=read(path)
    lines=[]
    for name,value in entries.items():
        pattern=re.compile(r'\n[ \t]*<string name="'+re.escape(name)+r'"[^>]*>.*?</string>',re.S)
        if pattern.search(xml): xml=pattern.sub("",xml,count=1)
        lines.append('    <string name="'+name+'">'+value+'</string>')
    for name,items in arrays.items():
        lines.append('    <string-array name="'+name+'">'+"".join("<item>"+res(i)+"</item>" for i in items)+'</string-array>')
    for name,qty in plurals.items():
        lines.append('    <plurals name="'+name+'">'+"".join('<item quantity="'+q+'">'+res(t)+'</item>' for q,t in qty.items())+'</plurals>')
    if xml.count("</resources>")!=1: sys.exit("FAIL: V5.0.14 resources end in "+path)
    files[path]=xml.replace("</resources>","    <!-- CloudXtream V5.0.14 -->\n"+"\n".join(lines)+"\n</resources>")

add_resources("app/src/main/res/values/strings.xml",{k:res(v[0]) for k,v in CX.items()},
              {"cx_report_reasons":REASONS[0]},{"cx_expiry_text":EXPIRY[0]})
ms={k:res(v[1]) for k,v in CX.items()}
ms.update({k:escape(v).replace("'","\\'") for k,v in MS_UPSTREAM.items()})
add_resources("app/src/main/res/values-b+ms/strings.xml",ms,{"cx_report_reasons":REASONS[1]},{"cx_expiry_text":EXPIRY[1]})

# ---------------------------------------------------------------- Bahasa Melayu by default
p="app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt"
patch(p,'''        // Set English on a clean install without overriding existing user settings.
        val langPreferenceKey = getString(R.string.locale_key)
        if (!settingsManager.contains(langPreferenceKey)) {
            settingsManager.edit { putString(langPreferenceKey, "en") }
        }''','''        // Bahasa Melayu by default; installs still on the old English default switch once.
        // Users can choose another language in Settings > General.
        val langPreferenceKey = getString(R.string.locale_key)
        if (!settingsManager.getBoolean("cx_lang_ms_v5014", false)) {
            if (settingsManager.getString(langPreferenceKey, "en") == "en") {
                settingsManager.edit { putString(langPreferenceKey, "ms") }
            }
            settingsManager.edit { putBoolean("cx_lang_ms_v5014", true) }
        }''')

# ---------------------------------------------------------------- first-run guide, expiry reminder
patch(p,"        com.lagradost.cloudstream3.services.CxUpdateNotifier.dismiss(this)\n",
      "        com.lagradost.cloudstream3.services.CxUpdateNotifier.dismiss(this)\n"
      "        com.lagradost.cloudstream3.ui.CxHints.showOnboardingIfNeeded(this)\n"
      "        com.lagradost.cloudstream3.ui.CxHints.remindExpiryIfNeeded(this)\n")
add_file("app/src/main/java/com/lagradost/cloudstream3/ui/CxHints.kt","CxHints.kt")

# Update download dialog texts from resources (V5.0.13 used English literals).
p="app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt"
patch(p,'            text = "Connecting..."\n','            text = context.getString(R.string.cx_download_connecting)\n')
patch(p,'            .setTitle("Downloading update")\n','            .setTitle(R.string.cx_download_title)\n')

# ---------------------------------------------------------------- player: More (sleep timer, report)
p="app/src/main/res/layout/player_custom_layout.xml"
patch(p,'''                            <com.google.android.material.button.MaterialButton
                                android:id="@+id/player_skip_op"''','''                            <com.google.android.material.button.MaterialButton
                                android:id="@+id/player_cx_more_btt"
                                style="@style/VideoButton"
                                android:layout_height="40dp"
                                android:text="@string/cx_player_more"
                                app:icon="@drawable/ic_baseline_more_vert_24" />

                            <com.google.android.material.button.MaterialButton
                                android:id="@+id/player_skip_op"''')
p="app/src/main/java/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt"
patch(p,'''    private fun loadLink(link: VideoLink?, sameEpisode: Boolean) {''','''    // CloudXtream: the player's More button: sleep timer and "Report a problem".
    private val cxSleepHandler = Handler(Looper.getMainLooper())
    private var cxSleepEndsAt = 0L
    private fun cxShowMoreMenu() {
        val ctx = context ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val sleepLabel = if (cxSleepEndsAt > now)
            ctx.getString(R.string.cx_sleep_timer_on, ((cxSleepEndsAt - now) / 60_000L).toInt() + 1)
        else ctx.getString(R.string.cx_sleep_timer)
        androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setItems(arrayOf(sleepLabel, ctx.getString(R.string.cx_report))) { _, which ->
                if (which == 0) cxShowSleepTimer(ctx) else cxShowReport(ctx)
            }
            .show()
    }
    private fun cxShowSleepTimer(ctx: android.content.Context) {
        val minutes = intArrayOf(0, 15, 30, 45, 60, 90, 120)
        val labels = minutes.map {
            if (it == 0) ctx.getString(R.string.cx_sleep_off) else ctx.getString(R.string.cx_sleep_minutes, it)
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle(R.string.cx_sleep_timer)
            .setItems(labels) { _, which ->
                cxSleepHandler.removeCallbacksAndMessages(null)
                val chosen = minutes[which]
                if (chosen == 0) {
                    cxSleepEndsAt = 0L
                    showToast(R.string.cx_sleep_cancelled, Toast.LENGTH_SHORT)
                    return@setItems
                }
                cxSleepEndsAt = android.os.SystemClock.elapsedRealtime() + chosen * 60_000L
                cxSleepHandler.postDelayed({
                    cxSleepEndsAt = 0L
                    player.handleEvent(CSPlayerEvent.Pause)
                    showToast(R.string.cx_sleep_paused, Toast.LENGTH_LONG)
                }, chosen * 60_000L)
                showToast(ctx.getString(R.string.cx_sleep_set, chosen), Toast.LENGTH_SHORT)
            }
            .show()
    }
    private fun cxShowReport(ctx: android.content.Context) {
        val reasons = ctx.resources.getStringArray(R.array.cx_report_reasons)
        androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle(R.string.cx_report)
            .setItems(reasons) { _, which ->
                val title = getPlayerVideoTitle()
                val source = currentSelectedLink?.first?.let { it.source + " · " + it.name }
                    ?: currentSelectedLink?.second?.name ?: ""
                val appContext = ctx.applicationContext
                showToast(R.string.cx_report_sending, Toast.LENGTH_SHORT)
                ioSafe {
                    val sent = com.lagradost.cloudstream3.activation.CxWorkerActivation.reportProblem(
                        appContext, title, source, reasons[which], com.lagradost.cloudstream3.BuildConfig.VERSION_NAME
                    )
                    activity?.runOnUiThread {
                        showToast(if (sent) R.string.cx_report_sent else R.string.cx_report_failed, Toast.LENGTH_LONG)
                    }
                }
            }
            .show()
    }

    private fun loadLink(link: VideoLink?, sameEpisode: Boolean) {''')
patch(p,'''        super.onBindingCreated(binding, savedInstanceState)
''','''        super.onBindingCreated(binding, savedInstanceState)
        binding.root.findViewById<android.view.View>(R.id.player_cx_more_btt)?.setOnClickListener { cxShowMoreMenu() }
''')
patch(p,'''        cxStallHandler.removeCallbacksAndMessages(null)
        ResultFragment.updateUI()''','''        cxStallHandler.removeCallbacksAndMessages(null)
        cxSleepHandler.removeCallbacksAndMessages(null)
        ResultFragment.updateUI()''')

# ---------------------------------------------------------------- profiles: PIN for every profile, Kids
add_file("app/src/main/java/com/lagradost/cloudstream3/ui/account/CxKids.kt","CxKids.kt")
p="app/src/main/res/layout/account_edit_dialog.xml"
patch(p,'''            android:text="@string/lock_profile"
            android:textColor="?attr/grayTextColor" />
''','''            android:text="@string/lock_profile"
            android:textColor="?attr/grayTextColor" />

        <CheckBox
            android:id="@+id/cxKidsProfileCheckbox"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:buttonTint="?attr/textColor"
            android:nextFocusUp="@id/lockProfileCheckbox"
            android:nextFocusDown="@id/apply_btt"
            android:text="@string/cx_kids_profile"
            android:textColor="?attr/grayTextColor" />
''')
p="app/src/main/java/com/lagradost/cloudstream3/ui/account/AccountHelper.kt"
patch(p,'''        val binding = AccountEditDialogBinding.inflate(LayoutInflater.from(context), null, false)
        val builder = AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setView(binding.root)

        var currentEditAccount = account
''','''        // CloudXtream: a kids profile (or editing from one) needs the parent PIN first.
        if (CxKids.needsPinToEdit(context, account.keyIndex)) {
            CxKids.askPin(context, account.keyIndex) {
                showAccountEditDialog(context, account, isNewAccount, accountEditCallback, accountDeleteCallback)
            }
            return
        }
        val binding = AccountEditDialogBinding.inflate(LayoutInflater.from(context), null, false)
        val builder = AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setView(binding.root)

        var currentEditAccount = account
        var cxKidsPin = if (isNewAccount) null else CxKids.pinFor(context, account.keyIndex)
''')
files[p]=read(p).replace('''                    accountEditCallback.invoke(currentEditAccount)''','''                    CxKids.set(context, currentEditAccount.keyIndex, cxKidsPin)
                    accountEditCallback.invoke(currentEditAccount)''')
files[p]=files[p].replace('''                accountEditCallback.invoke(currentEditAccount)
                dialog.dismissSafe()''','''                CxKids.set(context, currentEditAccount.keyIndex, cxKidsPin)
                accountEditCallback.invoke(currentEditAccount)
                dialog.dismissSafe()''')
if files[p].count("CxKids.set(")!=2: sys.exit("FAIL: V5.0.14 kids save anchors")
# Every profile, including the first one, can be locked with a PIN.
patch(p,'''        if (currentEditAccount.keyIndex == getDefaultAccount(context).keyIndex) {
            binding.lockProfileCheckbox.isVisible = false
            if (currentEditAccount.lockPin != null) {
                currentEditAccount = currentEditAccount.copy(lockPin = null)
            }
        }
''','''        // CloudXtream: the first profile can be PIN-locked too.
''')
patch(p,'''        canSetPin = true
''','''        canSetPin = true

        binding.cxKidsProfileCheckbox.isChecked = cxKidsPin != null
        binding.cxKidsProfileCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                cxKidsPin = null
                return@setOnCheckedChangeListener
            }
            if (cxKidsPin != null) return@setOnCheckedChangeListener
            // The parent chooses the PIN that is needed to leave the kids profile.
            showPinInputDialog(context, null, true) { pin ->
                if (pin == null) binding.cxKidsProfileCheckbox.isChecked = false else cxKidsPin = pin
            }
        }
''')
p="app/src/main/java/com/lagradost/cloudstream3/ui/account/AccountViewModel.kt"
patch(p,'''        // Check if the selected account has a lock PIN set
''','''        // CloudXtream: leaving a kids profile needs the parent PIN.
        if (!forStartup && CxKids.needsPinToLeave(context, account.keyIndex)) {
            CxKids.askPin(context, account.keyIndex) {
                handleAccountSelect(account, context, forStartup, reloadForActivity)
            }
            return
        }

        // Check if the selected account has a lock PIN set
''')
p="app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt"
patch(p,'''                    val mainId = loadResponse.getId()
''','''                    if (!com.lagradost.cloudstream3.ui.account.CxKids.allows(loadResponse)) {
                        _page.postValue(
                            Resource.Failure(
                                false,
                                com.lagradost.cloudstream3.CloudStreamApp.context?.getString(R.string.cx_kids_blocked)
                                    ?: "Not available in the Kids profile"
                            )
                        )
                        return@ioSafe
                    }
                    val mainId = loadResponse.getId()
''')

# ---------------------------------------------------------------- Continue Watching
p="app/src/main/java/com/lagradost/cloudstream3/ui/home/HomeFragment.kt"
patch(p,'''    override fun onDestroyView() {''','''    // CloudXtream: refresh Continue Watching whenever Home is shown again (e.g. back from the player).
    override fun onResume() {
        super.onResume()
        homeViewModel.reloadStored()
    }

    override fun onDestroyView() {''')

for path,source in files.items():
    target=ROOT/path;target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(source,encoding="utf8")
print("PASS: V5.0.14 Malay, guide, player tools, expiry reminder, Kids profiles",len(files),"files")
