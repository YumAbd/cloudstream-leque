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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.ui.account.AccountSelectActivity2
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * First-launch activation. This Activity is not exported: only app entrypoints can start it.
 * Since V5.0.22 the PIN is entered once; an activated device opens the app directly, also offline.
 * Uninstalling the app removes the activation: the same PIN activates it again on the same device.
 *
 * Device identity uses an Android Keystore RSA key, never a MAC address.
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
        root.addView(caption("Aplikasi ini perlu diaktifkan sekali dengan PIN daripada admin CloudXtream.\nSambungan Internet diperlukan semasa pengaktifan.",15f,Color.WHITE))
        status=caption("Menyemak pengaktifan…",14f,Color.rgb(175,209,227))
        root.addView(status)
        pin=EditText(this).apply {
            hint="PIN pengaktifan (8 aksara)"
            setSingleLine(true)
            gravity=Gravity.CENTER
            textSize=22f
            letterSpacing=0.18f
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters=arrayOf(InputFilter.AllCaps(),InputFilter.LengthFilter(8))
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
        val spacer=caption("PIN hanya perlu dimasukkan sekali. Jika aplikasi dipadam dan dipasang semula, masukkan PIN yang sama.",12f,Color.LTGRAY)
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
        "pin_not_found","invalid_pin" -> "PIN tidak sah. Semak semula PIN (8 aksara), atau minta PIN daripada admin CloudXtream."
        "pin_expired" -> "PIN ini telah TAMAT TEMPOH. Sila hubungi admin CloudXtream untuk mendapatkan PIN baharu."
        "pin_replaced" -> "PIN ini telah digantikan dengan PIN yang lebih baharu. Sila guna PIN terkini daripada admin CloudXtream."
        "license_inactive" -> "Lesen anda sedang DIGANTUNG atau DIBATALKAN. Sila hubungi admin CloudXtream."
        "license_expired" -> "Lesen anda telah TAMAT TEMPOH. Hubungi admin CloudXtream untuk menyambungnya."
        "device_revoked" -> "Pengaktifan peranti ini tidak lagi sah. Tekan \"Saya ada PIN baharu\" dan masukkan PIN daripada admin CloudXtream."
        "device_limit_reached" -> "PIN ini sudah digunakan pada peranti lain. Hubungi admin CloudXtream."
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

    private fun verifyExisting(){
        if(busy)return
        // Activated: open at once (no license check). Devices activated before V5.0.22 send their
        // hardware id once in the background so a reinstall can reuse the same PIN.
        if(CxWorkerActivation.hasFreshCheck()){
            val app=applicationContext
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                CxWorkerActivation.registerHardwareOnce(app)
            }
            enterApp(); return
        }
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
    private fun activate(){
        if(busy)return
        val code=pin.text.toString().trim().uppercase(Locale.ROOT)
        // 8 characters; PINs issued before V5.0.22 have 6 and still work.
        if(!Regex("^([A-Z2-7]{8}|[A-Z2-7]{6})$").matches(code)){
            status.text="Masukkan 8 aksara PIN (huruf A–Z dan nombor 2–7)."
            return
        }
        setBusy(true)
        status.text="Sedang mengaktifkan. Pastikan Internet bersambung…"
        lifecycleScope.launch {
            val ok=CxWorkerActivation.activate(this@CxActivationActivity,code)
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
