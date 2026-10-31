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
        root.addView(caption("Aplikasi ini perlu diaktifkan dengan PIN daripada pemilik lesen.\nSambungan Internet diperlukan.",15f,Color.WHITE))
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
        val spacer=caption("PIN ada tempoh sah yang ditetapkan oleh pemilik.\nHubungi pemilik lesen jika peranti perlu ditetapkan semula.",12f,Color.LTGRAY)
        root.addView(spacer)
        outer.addView(root)
        setContentView(outer)
        submit.setOnClickListener { activate() }
        pin.setOnEditorActionListener { _,_,_->activate();true }
        retry.setOnClickListener {verifyExisting()}
        changeDevice.setOnClickListener {
            CxWorkerActivation.clearStoredDeviceIdForReactivation(this)
            render()
            status.text="Masukkan PIN BAHARU yang diberi oleh pemilik."
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
        if(!already)status.text="Masukkan PIN yang diberi oleh pemilik lesen."
    }
    private fun setBusy(value:Boolean){
        busy=value
        submit.isEnabled=!value
        retry.isEnabled=!value
        changeDevice.isEnabled=!value
        pin.isEnabled=!value
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
                status.text=if(expired)"Lesen anda telah tamat tempoh. Hubungi pemilik lesen untuk menyambungnya, kemudian tekan Cuba semak semula."
                    else "Semakan dalam talian gagal ("+CxWorkerActivation.lastActivationProblem()+
                    "). Semak Internet, atau minta pemilik beri PIN baharu."
            }
        }
    }
    private fun activate(){
        if(busy)return
        val code=pin.text.toString().trim().uppercase(Locale.ROOT)
        if(!Regex("^[A-Z2-7]{6}$").matches(code)){
            status.text="Masukkan tepat 6 aksara (huruf A–Z dan nombor 2–7)."
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
                else "Pengaktifan tidak berjaya ("+CxWorkerActivation.lastActivationProblem()+
                    "). Semak PIN dan Internet, atau hubungi pemilik."
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
