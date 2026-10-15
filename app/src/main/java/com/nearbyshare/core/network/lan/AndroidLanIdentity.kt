package com.nearbyshare.core.network.lan

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nearbyshare.lan.LanIdentity
import com.nearbyshare.lan.LanTls
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.security.auth.x500.X500Principal

/**
 * This phone's TLS identity for the network connection method: an EC key that lives in the Android Keystore (it cannot be
 * copied off the device) with a self-signed certificate. Other devices pin the certificate's fingerprint when they pair.
 */
object AndroidLanIdentity {
    private const val ALIAS = "nss_lan_identity"

    fun get(): LanIdentity {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) generate()
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, null) }
        return LanIdentity(kmf.keyManagers, LanTls.fingerprint(cert))
    }

    private fun generate() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // TLS 1.2 and 1.3 sign with different digests depending on the negotiated suite
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setCertificateSubject(X500Principal("CN=Nearby Storage Share"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - 24 * 3_600_000L))
            .setCertificateNotAfter(Date(now + 3650L * 24 * 3_600_000L))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }
}
