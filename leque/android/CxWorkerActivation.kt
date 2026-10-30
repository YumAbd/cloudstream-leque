// CloudXtream licensed-activation preview; online verification required every 60 seconds.
// Cryptographic private key never leaves Android Keystore.
package com.lagradost.cloudstream3.activation

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import javax.net.ssl.HttpsURLConnection
import java.security.spec.PSSParameterSpec

/**
 * Cloudflare Worker activation protocol v1. Keystore private key never leaves Android.
 * A cached license is never considered authorization after app process restart.
 * All network calls must run off the UI thread; all failures fail closed.
 */
object CxWorkerActivation {
    private const val ALIAS = "cx_device_rsa_pss_2026_v1"
    private const val PREFS = "cx_worker_activation_2026_v1"
    private const val DEVICE_ID = "device_id"
    private const val MAX_RESPONSE = 16384
    // Set from private build configuration after deploying Cloudflare Workers.
    // Never use a domain from an untrusted intent or editable user preference.
    private const val SERVER_ORIGIN = "https://cloudxpanel.infopc.xyz"
    @Volatile private var checkedUntilElapsed: Long = 0L
    @Volatile private var adminUntilElapsed: Long = 0L
    @Volatile private var adminToken: String? = null

    fun hasFreshCheck(): Boolean = SystemClock.elapsedRealtime() < checkedUntilElapsed
    fun hasAdminAccess(): Boolean = hasFreshCheck() &&
        SystemClock.elapsedRealtime() < adminUntilElapsed && adminToken != null
    fun isActivated(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(DEVICE_ID)

    /** Used only when the owner has reset the previous device and issued a fresh PIN. */
    fun clearStoredDeviceIdForReactivation(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(DEVICE_ID).apply()
        checkedUntilElapsed=0L
        adminUntilElapsed=0L
        adminToken=null
    }

    private fun origin(): String {
        require(SERVER_ORIGIN.startsWith("https://") && !SERVER_ORIGIN.contains("REPLACE_WITH")) {
            "CloudXtream activation Worker is not configured"
        }
        return SERVER_ORIGIN.trimEnd('/')
    }

    private fun b64url(bytes: ByteArray): String = Base64.encodeToString(
        bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    )

    private fun signingKey(): KeyStore.PrivateKeyEntry {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PSS).build())
            generator.generateKeyPair()
        }
        return store.getEntry(ALIAS,null) as KeyStore.PrivateKeyEntry
    }

    private fun sign(message: String): String {
        val signature=Signature.getInstance("SHA256withRSA/PSS")
        signature.initSign(signingKey().privateKey)
        signature.setParameter(PSSParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1
        ))
        signature.update(message.toByteArray(StandardCharsets.UTF_8))
        return b64url(signature.sign())
    }

    private fun post(path: String, data: JSONObject, bearer: String? = null): JSONObject =
        request("POST",path,data,bearer)

    private fun request(method: String,path: String,data: JSONObject? = null,bearer: String? = null): JSONObject {
        val connection=(URL(origin()+path).openConnection() as HttpsURLConnection).apply {
            requestMethod=method
            connectTimeout=8000
            readTimeout=8000
            instanceFollowRedirects=false
            setRequestProperty("Accept","application/json")
            if(data!=null){doOutput=true;setRequestProperty("Content-Type","application/json")}
            if(bearer!=null)setRequestProperty("Authorization","Bearer $bearer")
        }
        try {
            if(data!=null)connection.outputStream.use { it.write(data.toString().toByteArray(StandardCharsets.UTF_8)) }
            val responseCode=connection.responseCode
            if(responseCode !in 200..299)throw IllegalStateException("Activation rejected: $responseCode")
            val size=connection.contentLength
            if(size>MAX_RESPONSE)throw IllegalStateException("Response too large")
            val bytes=connection.inputStream.use { input ->
                val out=ByteArrayOutputStream()
                val chunk=ByteArray(2048)
                while(true) {
                    val sizeRead=input.read(chunk)
                    if(sizeRead<0)break
                    out.write(chunk,0,sizeRead)
                    if(out.size()>MAX_RESPONSE)throw IllegalStateException("Response too large")
                }
                out.toByteArray()
            }
            return JSONObject(String(bytes,StandardCharsets.UTF_8))
        } finally { connection.disconnect() }
    }

    /** Use once on initial install, then call validateOnline before opening Home. */
    suspend fun activate(context: Context, pin: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val challenge=post("/api/device/challenge",JSONObject().put("purpose","activate"))
            val challengeId=challenge.getString("challenge_id")
            val nonce=challenge.getString("nonce")
            val proof=sign("CX-ACTIVATE\n$challengeId\n$nonce")
            val publicKey=b64url(signingKey().certificate.publicKey.encoded)
            val response=post("/api/device/activate",JSONObject()
                .put("challenge_id",challengeId)
                .put("public_key_spki",publicKey)
                .put("signature",proof)
                .put("pin",pin))
            val deviceId=response.getString("device_id")
            // Only a public identifier is persisted; no static device or admin bearer token.
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .edit().putString(DEVICE_ID,deviceId).apply()
            return@withContext validateOnline(context)
        } catch (_: Exception) {
            checkedUntilElapsed=0L
            false
        }
    }

    /** Call on app cold start, onResume, before provider load/player, and every 60s. */
    suspend fun validateOnline(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val deviceId=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getString(DEVICE_ID,null) ?: return@withContext failClosed()
            val challenge=post("/api/device/challenge",JSONObject()
                .put("purpose","verify").put("device_id",deviceId))
            val challengeId=challenge.getString("challenge_id")
            val nonce=challenge.getString("nonce")
            val proof=sign("CX-VERIFY\n$deviceId\n$challengeId\n$nonce")
            val valid=post("/api/device/verify",JSONObject()
                .put("device_id",deviceId)
                .put("challenge_id",challengeId)
                .put("signature",proof))
            if(!valid.optBoolean("authorized",false))return@withContext failClosed()
            // A verified state is intentionally process-local and short-lived.
            checkedUntilElapsed=SystemClock.elapsedRealtime()+59_000L
            true
        } catch (_: Exception) {failClosed()}
    }

    private fun failClosed(): Boolean {
        checkedUntilElapsed=0L
        adminUntilElapsed=0L
        adminToken=null
        return false
    }

    /** Superuser requires own device activation and a separate password + TOTP login. */
    suspend fun adminLogin(context: Context, username: String, password: String, totp: String): Boolean = withContext(Dispatchers.IO) {
        if(!validateOnline(context))return@withContext false
        try {
            val deviceId=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getString(DEVICE_ID,null) ?: return@withContext false
            val challenge=post("/api/device/challenge",JSONObject()
                .put("purpose","verify").put("device_id",deviceId))
            val challengeId=challenge.getString("challenge_id")
            val nonce=challenge.getString("nonce")
            val proof=sign("CX-VERIFY\n$deviceId\n$challengeId\n$nonce")
            val result=post("/api/mobile-admin/login",JSONObject()
                .put("username",username).put("password",password).put("totp",totp)
                .put("device_id",deviceId).put("challenge_id",challengeId)
                .put("signature",proof))
            val token=result.getString("access_token")
            adminToken=token
            return@withContext refreshAdminStatus()
        } catch (_: Exception) {adminToken=null;adminUntilElapsed=0L;false}
    }

    suspend fun refreshAdminStatus(): Boolean = withContext(Dispatchers.IO) {
        try {
            val token=adminToken ?: return@withContext false
            val status=request("GET","/api/mobile-admin/me",bearer=token)
            if(status.optString("role")!="superuser")throw IllegalStateException("Invalid role")
            adminUntilElapsed=SystemClock.elapsedRealtime()+59_000L
            true
        }catch(_:Exception){adminUntilElapsed=0L;adminToken=null;false}
    }

    suspend fun adminLogout()=withContext(Dispatchers.IO){
        val token=adminToken
        adminToken=null;adminUntilElapsed=0L
        if(token!=null)try{post("/api/mobile-admin/logout",JSONObject(),token)}catch(_:Exception){}
    }
}
