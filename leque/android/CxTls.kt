// CloudXtream: HTTPS to cloudxpanel.infopc.xyz on old Android. Cloudflare serves certificates from
// Let's Encrypt (ISRG Root X1/X2) or Google Trust Services (GTS Root R1/R4); Android 7.0 and older
// do not have all of these roots. Android 7.0+ gets them from res/xml/network_security_config.xml;
// Android 6 ignores that file, so our own HTTPS calls use a trust store with the same roots added.
package com.lagradost.cloudstream3.activation

import android.os.Build
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.R
import java.net.URLConnection
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

object CxTls {
    private val ROOTS = intArrayOf(
        R.raw.cx_isrg_root_x1, R.raw.cx_isrg_root_x2, R.raw.cx_gts_root_r1, R.raw.cx_gts_root_r4
    )
    @Volatile private var factory: SSLSocketFactory? = null

    /** On Android 6, trusts the system roots plus the bundled ones for this connection. */
    fun apply(connection: URLConnection) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N || connection !is HttpsURLConnection) return
        (factory ?: build())?.let { connection.sslSocketFactory = it }
    }

    @Synchronized
    private fun build(): SSLSocketFactory? = factory ?: try {
        val context = CloudStreamApp.context ?: return null
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        val system = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        for (alias in system.aliases()) store.setCertificateEntry(alias, system.getCertificate(alias))
        val certificates = CertificateFactory.getInstance("X.509")
        ROOTS.forEachIndexed { i, id ->
            context.resources.openRawResource(id).use { store.setCertificateEntry("cx_root_$i", certificates.generateCertificate(it)) }
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory.also { factory = it }
    } catch (e: Exception) {
        android.util.Log.w("CxTls", "Bundled roots unavailable: " + e.javaClass.simpleName)
        null
    }
}
