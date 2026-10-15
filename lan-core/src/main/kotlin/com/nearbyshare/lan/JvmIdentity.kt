package com.nearbyshare.lan

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory

/**
 * TLS identity for desktop (and tests): an EC P-256 key with a self-signed certificate, kept in a PKCS#12 file.
 * Needs BouncyCastle (desktop and test classpaths only; the Android app uses the Android Keystore instead).
 *
 * Limitation: the key file's password sits in a file next to it, so this protects against a different app reading the
 * key by accident, not against someone with access to the user's profile folder.
 */
object JvmIdentity {
    fun generateInMemory(commonName: String): LanIdentity {
        val (ks, password) = newKeyStore(commonName)
        return fromKeyStore(ks, password)
    }

    fun loadOrCreate(dir: File, commonName: String): LanIdentity {
        dir.mkdirs()
        val p12 = File(dir, "identity.p12")
        val passFile = File(dir, "identity.pass")
        if (!p12.isFile || !passFile.isFile) {
            val (ks, password) = newKeyStore(commonName)
            p12.outputStream().use { ks.store(it, password) }
            passFile.writeText(String(password))
        }
        val password = passFile.readText().toCharArray()
        val ks = KeyStore.getInstance("PKCS12").apply { p12.inputStream().use { load(it, password) } }
        return fromKeyStore(ks, password)
    }

    private fun newKeyStore(commonName: String): Pair<KeyStore, CharArray> {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val subject = X500Name("CN=${commonName.replace(Regex("[,+=\"\\\\<>;]"), " ")}")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            subject, BigInteger(64, SecureRandom()), Date(now - 24 * 3_600_000L), Date(now + 3650L * 24 * 3_600_000L), subject, keys.public,
        ).build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        val password = randomHex(16).toCharArray()
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("identity", keys.private, password, arrayOf(cert))
        }
        return ks to password
    }

    private fun fromKeyStore(ks: KeyStore, password: CharArray): LanIdentity {
        val cert = ks.getCertificate("identity") as X509Certificate
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, password) }
        return LanIdentity(kmf.keyManagers, LanTls.fingerprint(cert))
    }
}
