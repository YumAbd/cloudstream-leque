package com.lagradost.cloudstream3.activation

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.ui.account.AccountSelectActivity2
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * First-launch and online lease gate. This Activity is not exported: only app
 * entrypoints can start it. Zero Internet means zero access; no offline grace.
 *
 * Device identity uses an Android Keystore RSA-PSS key, never a MAC address.
 * V5.0.4 is a preview; privileged settings and FCM update delivery are separate.
 */
class CxActivationActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var pin: EditText
    private lateinit var submit: Button
    private lateinit var retry: Button
    private lateinit var changeDevice: Button
    private var busy=false

    private fun dp(value:Int)=(value*resources.displayMetrics.density+0.5f).toInt()
    private fun caption(text:String,size:Float,color:Int):TextView =
        TextView(this).apply {
            this.text=text
            textSize=size
            setTextColor(color)
            gravity=Gravity.CENTER
            setPadding(dp(4),dp(8),dp(4),dp(8))
        }
    private fun button(label:String)=Button(this).apply {
        text=label
        isAllCaps=false
        setTextColor(Color.WHITE)
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.rgb(4,14,21)
        window.navigationBarColor=Color.BLACK
        val outer=ScrollView(this).apply {
            setBackgroundColor(Color.rgb(5,12,21))
            isFillViewport=true
        }
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER
            setPadding(dp(28),dp(32),dp(28),dp(32))
        }
        root.addView(caption("CLOUDXTREAM",29f,Color.rgb(0,204,239)))
        root.addView(caption("by Leque",16f,Color.LTGRAY))
        root.addView(caption("Aplikasi ini perlu diaktifkan dengan PIN daripada admin CloudXtream.\nSambungan Internet diperlukan.",15f,Color.WHITE))
        status=caption("Menyemak pengaktifan…",14f,Color.rgb(175,209,227))
        root.addView(status)
        pin=EditText(this).apply {
            hint="PIN pengaktifan (6 aksara)"
            setSingleLine(true)
            gravity=Gravity.CENTER
            textSize=22f
            letterSpacing=0.18f
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters=arrayOf(InputFilter.AllCaps(),InputFilter.LengthFilter(6))
            imeOptions=EditorInfo.IME_ACTION_DONE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSelectAllOnFocus(true)
        }
        root.addView(pin,LinearLayout.LayoutParams(-1,dp(60)))
        submit=button("Aktifkan peranti").also { root.addView(it,LinearLayout.LayoutParams(-1,dp(58))) }
        retry=button("Cuba semak semula").also { root.addView(it,LinearLayout.LayoutParams(-1,dp(58))) }
        changeDevice=button("Saya ada PIN baharu").also {
            root.addView(it,LinearLayout.LayoutParams(-1,dp(56)))
        }
        val spacer=caption("PIN ada tempoh sah yang ditetapkan oleh admin CloudXtream.\nHubungi admin CloudXtream jika peranti perlu ditetapkan semula.",12f,Color.LTGRAY)
        root.addView(spacer)
        outer.addView(root)
        setContentView(outer)
        submit.setOnClickListener { activate() }
        pin.setOnEditorActionListener { _,_,_->activate();true }
        retry.setOnClickListener {verifyExisting()}
        changeDevice.setOnClickListener {
            CxWorkerActivation.clearStoredDeviceIdForReactivation(this)
            render()
            status.text="Masukkan PIN BAHARU yang diberi oleh admin CloudXtream."
        }
        render()
        if(CxWorkerActivation.isActivated(this))verifyExisting()
    }

    private fun render(){
        val already=CxWorkerActivation.isActivated(this)
        pin.visibility=if(already)View.GONE else View.VISIBLE
        submit.visibility=if(already)View.GONE else View.VISIBLE
        retry.visibility=if(already)View.VISIBLE else View.GONE
        changeDevice.visibility=if(already)View.VISIBLE else View.GONE
        if(!already)status.text="Masukkan PIN yang diberi oleh admin CloudXtream."
    }
    private fun setBusy(value:Boolean){
        busy=value
        submit.isEnabled=!value
        retry.isEnabled=!value
        changeDevice.isEnabled=!value
        pin.isEnabled=!value
    }
    /** Plain-language reason for a refused PIN or a device that lost access (server error codes). */
    private fun reason(code:String?):String? = when(code){
        "pin_not_found","invalid_pin" -> "PIN tidak sah. Semak semula 6 aksara PIN, atau minta PIN daripada admin CloudXtream."
        "pin_expired" -> "PIN ini telah TAMAT TEMPOH. Sila hubungi admin CloudXtream untuk mendapatkan PIN baharu."
        "pin_replaced" -> "PIN ini telah digantikan dengan PIN yang lebih baharu. Sila guna PIN terkini daripada admin CloudXtream."
        "license_inactive" -> "Lesen anda sedang DIGANTUNG atau DIBATALKAN. Sila hubungi admin CloudXtream."
        "license_expired" -> "Lesen anda telah TAMAT TEMPOH. Hubungi admin CloudXtream untuk menyambungnya."
        "device_revoked" -> "Peranti ini telah DIKELUARKAN daripada lesen (mungkin diganti dengan peranti lain). Tekan \"Saya ada PIN baharu\" dan masukkan PIN daripada admin CloudXtream."
        "device_limit_reached" -> "Had peranti lesen ini sudah PENUH. Minta admin CloudXtream mengeluarkan peranti lama atau menambah had."
        "device_not_found" -> "Peranti yang dipilih sudah tiada dalam lesen. Cuba aktifkan semula."
        "too_many_attempts" -> "Terlalu banyak percubaan. Tunggu 15 minit dan cuba lagi."
        else -> null
    }

    /**
     * Network problems before the server could answer. A wrong device date is the usual cause of
     * TLS errors on TV boxes, so it is checked first: any date before this build is certainly wrong.
     */
    private fun connectionReason():String? {
        val kind=CxWorkerActivation.lastActivationProblem().substringAfter(':',"")
        val clockWrong=System.currentTimeMillis()<1793491200000L // 1 Nov 2026
        if(kind.startsWith("TLS")&&(clockWrong||kind=="TLS_CLOCK"))
            return "Tarikh & masa peranti TIDAK BETUL ("+date(System.currentTimeMillis())+"). Buka Tetapan → Tarikh & masa, "+
                "hidupkan tarikh & masa automatik (atau tetapkan tarikh hari ini), kemudian cuba lagi."
        return when {
            kind.startsWith("TLS") -> "Sambungan selamat ke server CloudXtream GAGAL ("+kind+"). Semak tarikh & masa peranti, "+
                "matikan VPN/DNS peribadi, atau cuba rangkaian lain (contoh hotspot telefon). Jika masih gagal, hubungi admin CloudXtream."
            kind=="DNS_UNREACHABLE" -> "Server CloudXtream tidak dapat dicari. Semak sambungan Internet, matikan VPN/DNS peribadi, atau cuba rangkaian lain."
            kind=="NETWORK_TIMEOUT"||kind=="CONNECTION_FAILED" -> "Tiada sambungan ke server CloudXtream. Semak Internet dan cuba lagi."
            else -> null
        }
    }

    private fun date(ms:Long):String =
        if(ms<=0L)"—" else java.text.SimpleDateFormat("d MMM yyyy, HH:mm",Locale("ms")).format(java.util.Date(ms))

    /** License full but the PIN is valid: let the user pick which of their devices this one replaces. */
    private fun offerReplace(){
        val body=CxWorkerActivation.lastServerErrorBody() ?: return
        val list=body.optJSONArray("devices") ?: return
        if(!body.optBoolean("can_replace") || list.length()==0) return
        val ids=ArrayList<String>(); val labels=ArrayList<String>()
        for(i in 0 until list.length()){
            val d=list.optJSONObject(i) ?: continue
            val id=d.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name=d.optString("label").takeIf { it.isNotBlank() && it!="null" } ?: ("Peranti "+id.take(8))
            ids.add(id)
            labels.add(name+"\nTerakhir digunakan: "+date(d.optLong("last_seen_ms",d.optLong("created_ms"))))
        }
        if(ids.isEmpty())return
        AlertDialog.Builder(this)
            .setTitle("Had peranti penuh ("+body.optInt("active_devices")+"/"+body.optInt("max_devices")+"). Pilih peranti untuk diganti:")
            .setItems(labels.toTypedArray()){ _,which ->
                AlertDialog.Builder(this)
                    .setTitle("Ganti peranti?")
                    .setMessage(labels[which].substringBefore("\n")+" akan dikeluarkan daripada lesen, dan peranti ini akan diaktifkan sebagai gantinya.")
                    .setPositiveButton("Ganti"){ _,_ -> activate(ids[which]) }
                    .setNegativeButton("Batal",null)
                    .show()
            }
            .setNegativeButton("Batal",null)
            .show()
    }

    private fun verifyExisting(){
        if(busy)return
        setBusy(true)
        status.text="Menyemak peranti anda dengan CloudXtream…"
        lifecycleScope.launch {
            val ok=CxWorkerActivation.validateOnline(this@CxActivationActivity)
            if(ok)enterApp()
            else {
                setBusy(false)
                val expired=CxWorkerActivation.licenseExpiresMs(this@CxActivationActivity)
                    ?.let { it<=System.currentTimeMillis() }==true
                status.text=(reason(CxWorkerActivation.lastServerError()) ?: connectionReason())
                    ?: if(expired)"Lesen anda telah tamat tempoh. Hubungi admin CloudXtream untuk menyambungnya, kemudian tekan Cuba semak semula."
                    else "Semakan dalam talian gagal ("+CxWorkerActivation.lastActivationProblem()+
                    "). Semak Internet, atau minta admin CloudXtream beri PIN baharu."
            }
        }
    }
    private fun activate(replaceDeviceId:String?=null){
        if(busy)return
        val code=pin.text.toString().trim().uppercase(Locale.ROOT)
        if(!Regex("^[A-Z2-7]{6}$").matches(code)){
            status.text="Masukkan tepat 6 aksara (huruf A–Z dan nombor 2–7)."
            return
        }
        setBusy(true)
        status.text="Sedang mengaktifkan. Pastikan Internet bersambung…"
        lifecycleScope.launch {
            val ok=CxWorkerActivation.activate(this@CxActivationActivity,code,replaceDeviceId)
            if(ok)enterApp()
            else {
                setBusy(false)
                render()
                status.text=if(CxWorkerActivation.isActivated(this@CxActivationActivity))
                    "Peranti TELAH didaftarkan, tetapi semakan semula gagal ("+
                        CxWorkerActivation.lastActivationProblem()+
                        "). JANGAN minta PIN lain. Tekan Cuba semak semula."
                else (reason(CxWorkerActivation.lastServerError()) ?: connectionReason())
                    ?: ("Pengaktifan tidak berjaya ("+CxWorkerActivation.lastActivationProblem()+
                    "). Semak PIN dan Internet, atau hubungi admin CloudXtream.")
                if(!CxWorkerActivation.isActivated(this@CxActivationActivity) &&
                    CxWorkerActivation.lastServerError()=="device_limit_reached"){
                    status.text="Had peranti lesen ini sudah PENUH. Pilih peranti lama untuk diganti, atau minta admin CloudXtream mengeluarkan peranti lama."
                    offerReplace()
                }
            }
        }
    }
    private fun enterApp(){
        startActivity(Intent(this,AccountSelectActivity2::class.java))
        finish()
    }
}

/** Invoked before account-selection, MainActivity and exported player UI. */
object CxActivationGuard {
    fun allow(activity:Activity):Boolean {
        if(CxWorkerActivation.hasFreshCheck())return true
        lock(activity)
        return false
    }
    fun lock(activity:Activity){
        if(activity.isFinishing||activity.isDestroyed)return
        activity.startActivity(Intent(activity,CxActivationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        activity.finish()
    }
}
