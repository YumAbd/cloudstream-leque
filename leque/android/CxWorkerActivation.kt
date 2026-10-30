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
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.ConnectException
import javax.net.ssl.SSLException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import javax.net.ssl.HttpsURLConnection

/**
 * Cloudflare Worker activation protocol v1. Keystore private key never leaves Android.
 * A cached license is never considered authorization after app process restart.
 * All network calls must run off the UI thread; all failures fail closed.
 */
object CxWorkerActivation {
    private const val ALIAS = "cx_device_rsa_pkcs1_2026_v2"
    private const val SIGNATURE_SCHEME = "rsa-pkcs1-sha256"
    private const val PREFS = "cx_worker_activation_2026_v1"
    private const val DEVICE_ID = "device_id"
    private const val MAX_RESPONSE = 16384
    // Set from private build configuration after deploying Cloudflare Workers.
    // Never use a domain from an untrusted intent or editable user preference.
    private const val SERVER_ORIGIN = "https://cloudxpanel.infopc.xyz"
    @Volatile private var checkedUntilElapsed: Long = 0L
    @Volatile private var adminUntilElapsed: Long = 0L
    @Volatile private var adminToken: String? = null
    @Volatile private var lastProblem: String = "not_checked"

    fun lastActivationProblem(): String = lastProblem

    private class ApiProblem(val status: Int,val code:String) : IOException("server_error")
    private fun recordProblem(stage:String,e:Exception){
        // Never include PINs, request bodies, private keys or tokens in logs.
        val kind=when(e){
            is ApiProblem -> "HTTP_${e.status}_${e.code}"
            is UnknownHostException -> "DNS_UNREACHABLE"
            is SocketTimeoutException -> "NETWORK_TIMEOUT"
            is ConnectException -> "CONNECTION_FAILED"
            is SSLException -> "TLS_ERROR"
            is java.security.GeneralSecurityException -> "KEYSTORE_${e.javaClass.simpleName}"
            else -> e.javaClass.simpleName.take(48)
        }
        lastProblem=stage+":"+kind
        android.util.Log.w("CxDeviceActivation","Failed "+stage+" ("+kind+")")
    }

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
                ALIAS, KeyProperties.PURPOSE_SIGN
            ).setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build())
            generator.generateKeyPair()
        }
        return store.getEntry(ALIAS,null) as KeyStore.PrivateKeyEntry
    }

    private fun sign(message: String): String {
        // Android 8 KeyStore reliably supports PKCS#1 v1.5 SHA-256.
        // A new key alias prevents accidental reuse of a PSS-only key.
        val signature=Signature.getInstance("SHA256withRSA")
        signature.initSign(signingKey().privateKey)
        signature.update(message.toByteArray(StandardCharsets.UTF_8))
        return b64url(signature.sign())
    }

    /** Real Android 8 instrumentation calls this without reaching Cloudflare. */
    fun selfTestDeviceProof(): Boolean {
        val message="CX-SELFTEST\\n".replace("\\\\n","\\n")+System.nanoTime()
        return try {
            val proof=Base64.decode(sign(message),Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val verifier=Signature.getInstance("SHA256withRSA")
            verifier.initVerify(signingKey().certificate.publicKey)
            verifier.update(message.toByteArray(StandardCharsets.UTF_8))
            verifier.verify(proof)
        }catch(e:Exception){
            recordProblem("keystore_selftest",e)
            false
        }
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
            val size=connection.contentLength
            if(size>MAX_RESPONSE)throw IOException("response_too_large")
            val input=if(responseCode in 200..299)connection.inputStream else connection.errorStream
            val bytes=input?.use { stream ->
                val out=ByteArrayOutputStream()
                val chunk=ByteArray(2048)
                while(true) {
                    val sizeRead=stream.read(chunk)
                    if(sizeRead<0)break
                    out.write(chunk,0,sizeRead)
                    if(out.size()>MAX_RESPONSE)throw IOException("response_too_large")
                }
                out.toByteArray()
            }?:ByteArray(0)
            val body=runCatching{JSONObject(String(bytes,StandardCharsets.UTF_8))}.getOrElse{JSONObject()}
            if(responseCode !in 200..299){
                val error=body.optString("error","unknown").take(60)
                    .takeIf{Regex("^[a-z0-9_]+$").matches(it)}?:"unknown"
                throw ApiProblem(responseCode,error)
            }
            return body
        } finally { connection.disconnect() }
    }

    /** Use once on initial install, then call validateOnline before opening Home. */
    suspend fun activate(context: Context, pin: String): Boolean = withContext(Dispatchers.IO) {
        var stage="challenge"
        try {
            lastProblem="none"
            val challenge=post("/api/device/challenge",JSONObject().put("purpose","activate"))
            val challengeId=challenge.getString("challenge_id")
            val nonce=challenge.getString("nonce")
            stage="keystore_sign"
            val proof=sign("CX-ACTIVATE\n$challengeId\n$nonce")
            val publicKey=b64url(signingKey().certificate.publicKey.encoded)
            stage="register_device"
            val response=post("/api/device/activate",JSONObject()
                .put("challenge_id",challengeId)
                .put("public_key_spki",publicKey)
                .put("signature",proof)
                .put("signature_scheme",SIGNATURE_SCHEME)
                .put("pin",pin))
            val deviceId=response.getString("device_id")
            // Only a public identifier is persisted; no static device or admin bearer token.
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .edit().putString(DEVICE_ID,deviceId).apply()
            stage="verify_registered_device"
            return@withContext validateOnline(context)
        } catch(e:Exception) {
            recordProblem(stage,e)
            checkedUntilElapsed=0L
            false
        }
    }

    /** Call on app cold start, onResume, before provider load/player, and every 60s. */
    suspend fun validateOnline(context: Context): Boolean = withContext(Dispatchers.IO) {
        var stage="verify_challenge"
        try {
            val deviceId=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getString(DEVICE_ID,null) ?: return@withContext failClosed()
            val challenge=post("/api/device/challenge",JSONObject()
                .put("purpose","verify").put("device_id",deviceId))
            val challengeId=challenge.getString("challenge_id")
            val nonce=challenge.getString("nonce")
            stage="verify_keystore_sign"
            val proof=sign("CX-VERIFY\n$deviceId\n$challengeId\n$nonce")
            stage="verify_server"
            val valid=post("/api/device/verify",JSONObject()
                .put("device_id",deviceId)
                .put("challenge_id",challengeId)
                .put("signature",proof)
                .put("signature_scheme",SIGNATURE_SCHEME))
            if(!valid.optBoolean("authorized",false)){
                lastProblem="verify_server:NOT_AUTHORIZED"
                return@withContext failClosed()
            }
            // A verified state is intentionally process-local and short-lived.
            checkedUntilElapsed=SystemClock.elapsedRealtime()+59_000L
            lastProblem="none"
            true
        } catch(e:Exception) {
            recordProblem(stage,e)
            failClosed()
        }
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
                .put("signature",proof)
                .put("signature_scheme",SIGNATURE_SCHEME))
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
