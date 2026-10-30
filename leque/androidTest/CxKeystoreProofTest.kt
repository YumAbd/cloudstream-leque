package com.lagradost.cloudstream3.activation

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs inside the actual Android 8 KeyStore provider (not a JVM mock).
 * The private device signing key remains nonexportable.
 */
@RunWith(AndroidJUnit4::class)
class CxKeystoreProofTest {
    @Test fun rsaPkcs1ProofCanSignAndVerifyOnDevice() {
        assertTrue(
            "AndroidKeyStore RSA-PKCS1 SHA256 must be functional",
            CxWorkerActivation.selfTestDeviceProof()
        )
    }
}
