// CloudXtream licensed-activation. Since V5.0.22 a PIN is entered once: an activated device stays
// activated (no license checks) until the app is uninstalled. A reinstall on the same phone/TV takes
// back its device slot with the same PIN (the server matches a hash of the Android ID).
// Cryptographic private key never leaves Android Keystore.
package com.lagradost.cloudstream3.activation

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.DataStoreHelper
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
    private const val PROFILE_NAME_APPLIED = "profile_name_applied"
    private const val LICENSE_EXPIRES = "license_expires_ms"
    private const val MAX_RESPONSE = 16384
    // Server lease (revalidate_in) is clamped locally so a bad response cannot extend it.
    private const val MIN_LEASE_SECONDS = 30L
    private const val MAX_LEASE_SECONDS = 86_400L
    private const val VERIFIED_AT = "verified_at_ms"
    private const val VERIFIED_UNTIL = "verified_until_ms"
    private const val HARDWARE_SENT = "hardware_registered_v1"
    // Set from private build configuration after deploying Cloudflare Workers.
    // Never use a domain from an untrusted intent or editable user preference.
    private const val SERVER_ORIGIN = "https://cloudxpanel.infopc.xyz"
    @Volatile private var checkedUntilElapsed: Long = 0L
    @Volatile private var adminUntilElapsed: Long = 0L
    @Volatile private var adminToken: String? = null
    @Volatile private var lastProblem: String = "not_checked"
    @Volatile private var lastErrorCode: String? = null
    @Volatile private var lastErrorBody: JSONObject? = null

    fun lastActivationProblem(): String = lastProblem
    /** Server error code of the last failure (e.g. pin_expired, device_limit_reached), if any. */
    fun lastServerError(): String? = lastErrorCode
    /** Server answer of the last failure (device_limit_reached lists the license's devices). */
    fun lastServerErrorBody(): JSONObject? = lastErrorBody

    private class ApiProblem(val status: Int,val code:String,val body:JSONObject) : IOException("server_error")
    private fun recordProblem(stage:String,e:Exception){
        // Never include PINs, request bodies, private keys or tokens in logs.
        val kind=when(e){
            is ApiProblem -> "HTTP_${e.status}_${e.code}"
            is UnknownHostException -> "DNS_UNREACHABLE"
            is SocketTimeoutException -> "NETWORK_TIMEOUT"
            is ConnectException -> "CONNECTION_FAILED"
            is SSLException -> tlsKind(e)
            is java.security.GeneralSecurityException -> "KEYSTORE_${e.javaClass.simpleName}"
            else -> e.javaClass.simpleName.take(48)
        }
        lastProblem=stage+":"+kind
        lastErrorCode=(e as? ApiProblem)?.code
        lastErrorBody=(e as? ApiProblem)?.body
        android.util.Log.w("CxDeviceActivation","Failed "+stage+" ("+kind+")")
    }

    /** TLS_CLOCK: the device date is wrong; TLS_UNTRUSTED: certificate not trusted; else TLS_ERROR. */
    private fun tlsKind(e: Exception): String {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is java.security.cert.CertificateExpiredException ||
                cause is java.security.cert.CertificateNotYetValidException) return "TLS_CLOCK"
            if (cause is java.security.cert.CertPathValidatorException) return "TLS_UNTRUSTED"
            cause = cause.cause
        }
        return "TLS_ERROR"
    }

    /** An activated device is always allowed: the license is not checked again (V5.0.22). */
    fun hasFreshCheck(): Boolean {
        val context = com.lagradost.cloudstream3.CloudStreamApp.context ?: return false
        return isActivated(context)
    }
    /** Never: periodic license checks ended in V5.0.22. */
    @Suppress("UNUSED_PARAMETER")
    fun needsRenewal(withinMs: Long): Boolean = false

    /**
     * SHA-256 of this phone/TV's Android ID. It stays the same after the app is uninstalled and
     * installed again (same signing key), so the server can give a reinstall its old device slot.
     */
    private fun hardwareId(context: Context): String? = try {
        val id = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        if (id.isNullOrBlank() || id == "9774d56d682e549c") null
        else java.security.MessageDigest.getInstance("SHA-256")
            .digest(("cloudxtream-hardware:" + id).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }

    /**
     * Devices activated before V5.0.22 send their hardware id once (a signed check), so they can
     * also be reinstalled with their PIN. The answer never locks the app.
     */
    suspend fun registerHardwareOnce(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(HARDWARE_SENT, false)) return@withContext
        val deviceId = prefs.getString(DEVICE_ID, null) ?: return@withContext
        val hardware = hardwareId(context) ?: return@withContext
        try {
            val challenge = post("/api/device/challenge", JSONObject().put("purpose", "verify").put("device_id", deviceId))
            val challengeId = challenge.getString("challenge_id")
            val nonce = challenge.getString("nonce")
            val proof = sign("CX-VERIFY\n$deviceId\n$challengeId\n$nonce")
            val valid = post("/api/device/verify", JSONObject()
                .put("device_id", deviceId).put("challenge_id", challengeId).put("signature", proof)
                .put("signature_scheme", SIGNATURE_SCHEME).put("hardware_id", hardware))
            if (valid.optBoolean("authorized", false)) applyProfileName(context, valid.optString("display_name", ""))
            prefs.edit().putBoolean(HARDWARE_SENT, true).apply()
        } catch (e: ApiProblem) {
            // Refused (e.g. the owner removed this device): nothing to register; never ask again.
            prefs.edit().putBoolean(HARDWARE_SENT, true).apply()
        } catch (e: Exception) {
            android.util.Log.w("CxDeviceActivation", "Hardware id not sent yet (" + e.javaClass.simpleName + ")")
        }
    }

    /**
     * After an app restart, the last successful check still counts until its lease ends, so the
     * server is contacted about once a day. A clock moved backwards (or too far ahead of the
     * recorded check) invalidates it and the app checks online again.
     */
    @Volatile private var restored = false
    private fun restoreLease() {
        if (restored || checkedUntilElapsed > 0L) return
        restored = true
        val context = com.lagradost.cloudstream3.CloudStreamApp.context ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(DEVICE_ID)) return
        val at = prefs.getLong(VERIFIED_AT, 0L)
        val until = prefs.getLong(VERIFIED_UNTIL, 0L)
        val now = System.currentTimeMillis()
        if (at <= 0L || now < at - 60_000L || now >= until || until - at > MAX_LEASE_SECONDS * 1000L) return
        checkedUntilElapsed = SystemClock.elapsedRealtime() + (until - now)
    }
    private fun saveLease(context: Context, leaseMs: Long) {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(VERIFIED_AT, now).putLong(VERIFIED_UNTIL, now + leaseMs).apply()
    }
    private fun clearLease() {
        com.lagradost.cloudstream3.CloudStreamApp.context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.remove(VERIFIED_AT)?.remove(VERIFIED_UNTIL)?.apply()
    }

    private fun leaseMillis(response: JSONObject): Long {
        val seconds=response.optLong("revalidate_in",60L).coerceIn(MIN_LEASE_SECONDS,MAX_LEASE_SECONDS)
        return seconds*1000L-5_000L
    }
    fun hasAdminAccess(): Boolean = hasFreshCheck() &&
        SystemClock.elapsedRealtime() < adminUntilElapsed && adminToken != null
    fun isActivated(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(DEVICE_ID)

    /**
     * Names the default profile after the license user created in the admin panel.
     * Only the default name or a name this app applied earlier is replaced, so a profile
     * the user renamed on the phone is never overwritten.
     */
    private fun applyProfileName(context: Context, displayName: String) {
        val name = displayName.trim().take(40)
        if (name.isEmpty()) return
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val applied = prefs.getString(PROFILE_NAME_APPLIED, null)
            val defaultName = context.getString(R.string.default_account)
            val accounts = DataStoreHelper.accounts
            val current = accounts.firstOrNull { it.keyIndex == 0 }
            val currentName = current?.name ?: defaultName
            if (currentName == name) return
            val replaceable = currentName == defaultName || currentName == "Mod v5 by Leque" || currentName == applied
            if (!replaceable) return
            DataStoreHelper.accounts = if (current != null) {
                accounts.map { if (it.keyIndex == 0) it.copy(name = name) else it }.toTypedArray()
            } else {
                accounts + DataStoreHelper.Account(keyIndex = 0, name = name, defaultImageIndex = 0)
            }
            prefs.edit().putString(PROFILE_NAME_APPLIED, name).apply()
        } catch (e: Exception) {
            android.util.Log.w("CxDeviceActivation", "Profile name not applied (" + e.javaClass.simpleName + ")")
        }
    }

    /** Used only when the owner has reset the previous device and issued a fresh PIN. */
    fun clearStoredDeviceIdForReactivation(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(DEVICE_ID)
            .remove(VERIFIED_AT).remove(VERIFIED_UNTIL).apply()
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

    /**
     * A request signed by this device (Live TV, V5.0.24): a fresh challenge is signed like an online
     * check. Returns the server's answer; throws IOException with the server error code on refusal.
     */
    fun signedPost(context: Context, path: String, extra: JSONObject = JSONObject(), maxBytes: Int = 600_000): JSONObject {
        val deviceId=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(DEVICE_ID,null)
            ?: throw IOException("not_activated")
        val challenge=post("/api/device/challenge",JSONObject().put("purpose","verify").put("device_id",deviceId))
        val challengeId=challenge.getString("challenge_id")
        val nonce=challenge.getString("nonce")
        val body=JSONObject(extra.toString()).put("device_id",deviceId).put("challenge_id",challengeId)
            .put("signature",sign("CX-VERIFY\n$deviceId\n$challengeId\n$nonce")).put("signature_scheme",SIGNATURE_SCHEME)
        return request("POST",path,body,null,maxBytes)
    }
    /** Server error code of a refused request (e.g. user_playlists_not_allowed), or null. */
    fun serverCode(e: Throwable): String? = (e as? ApiProblem)?.code

    private fun request(method: String,path: String,data: JSONObject? = null,bearer: String? = null,maxBytes: Int = MAX_RESPONSE): JSONObject {
        val connection=(URL(origin()+path).openConnection() as HttpsURLConnection).apply {
            CxTls.apply(this)
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
            if(size>maxBytes)throw IOException("response_too_large")
            val input=if(responseCode in 200..299)connection.inputStream else connection.errorStream
            val bytes=input?.use { stream ->
                val out=ByteArrayOutputStream()
                val chunk=ByteArray(2048)
                while(true) {
                    val sizeRead=stream.read(chunk)
                    if(sizeRead<0)break
                    out.write(chunk,0,sizeRead)
                    if(out.size()>maxBytes)throw IOException("response_too_large")
                }
                out.toByteArray()
            }?:ByteArray(0)
            val body=runCatching{JSONObject(String(bytes,StandardCharsets.UTF_8))}.getOrElse{JSONObject()}
            if(responseCode !in 200..299){
                val error=body.optString("error","unknown").take(60)
                    .takeIf{Regex("^[a-z0-9_]+$").matches(it)}?:"unknown"
                throw ApiProblem(responseCode,error,body)
            }
            return body
        } finally { connection.disconnect() }
    }

    /** Phone/TV model shown to the owner and in the "replace a device" list. */
    private fun deviceLabel(): String {
        val maker=android.os.Build.MANUFACTURER.orEmpty().trim()
        val model=android.os.Build.MODEL.orEmpty().trim()
        return (if(model.startsWith(maker,ignoreCase=true)) model else "$maker $model").trim().take(60)
    }

    /** Once per install. Replacing another device is only done by the owner (V5.0.22). */
    suspend fun activate(context: Context, pin: String): Boolean = withContext(Dispatchers.IO) {
        var stage="challenge"
        try {
            lastProblem="none"
            lastErrorCode=null
            lastErrorBody=null
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
                .put("device_label",deviceLabel())
                .apply { hardwareId(context)?.let { put("hardware_id",it) } }
                .put("pin",pin))
            val deviceId=response.getString("device_id")
            // Only a public identifier is persisted; no static device or admin bearer token.
            // The hardware id was sent with the activation, so it never needs registering again.
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .edit().putString(DEVICE_ID,deviceId).putBoolean(HARDWARE_SENT,true)
                .putLong("activated_at_ms",System.currentTimeMillis()).apply()
            applyProfileName(context,response.optString("display_name",""))
            lastProblem="none"
            return@withContext true
        } catch(e:Exception) {
            recordProblem(stage,e)
            checkedUntilElapsed=0L
            false
        }
    }

    /** Call on app cold start, before provider load/player, and before the lease ends (see needsRenewal). */
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
            if(!valid.optBoolean("authorized",false) || valid.optString("device_id")!=deviceId){
                lastProblem="verify_server:NOT_AUTHORIZED"
                return@withContext failClosed()
            }
            // A verified state is intentionally process-local and short-lived.
            val lease=leaseMillis(valid)
            checkedUntilElapsed=SystemClock.elapsedRealtime()+lease
            saveLease(context,lease)
            applyProfileName(context,valid.optString("display_name",""))
            // Last known license expiry (none = -1), used for the reminder notification.
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putLong(LICENSE_EXPIRES,if(valid.isNull("license_expires_ms"))-1L else valid.optLong("license_expires_ms",-1L))
                .apply()
            lastProblem="none"
            lastErrorCode=null
            lastErrorBody=null
            true
        } catch(e:Exception) {
            recordProblem(stage,e)
            failClosed()
        }
    }

    /** No expiry reminders: the license is not checked after activation (V5.0.22). */
    @Suppress("UNUSED_PARAMETER")
    fun licenseExpiresMs(context: Context): Long? = null

    /** "Report a problem" from the player; signed by this device like an online check. */
    suspend fun reportProblem(context: Context, title: String, source: String, detail: String, appVersion: String, kind: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val deviceId=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                    .getString(DEVICE_ID,null) ?: return@withContext false
                val challenge=post("/api/device/challenge",JSONObject()
                    .put("purpose","verify").put("device_id",deviceId))
                val challengeId=challenge.getString("challenge_id")
                val nonce=challenge.getString("nonce")
                val proof=sign("CX-VERIFY\n$deviceId\n$challengeId\n$nonce")
                post("/api/device/report",JSONObject()
                    .put("device_id",deviceId).put("challenge_id",challengeId)
                    .put("signature",proof).put("signature_scheme",SIGNATURE_SCHEME)
                    .put("title",title.take(200)).put("source",source.take(200))
                    .put("detail",detail.take(if (kind == "crash") 3000 else 500)).put("app_version",appVersion.take(20))
                    .apply { if (kind != null) put("kind",kind) })
                    .optBoolean("ok",false)
            } catch(e:Exception) {
                recordProblem("report",e)
                false
            }
        }

    private fun failClosed(): Boolean {
        checkedUntilElapsed=0L
        clearLease()
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
