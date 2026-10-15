package com.nearbyshare.lan

import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/**
 * This device's long-term TLS identity: a private key with a self-signed certificate. [fingerprint] is the SHA-256 of the
 * certificate and is what other devices pin after pairing.
 */
class LanIdentity(val keyManagers: Array<KeyManager>, val fingerprint: String)

/**
 * TLS with self-signed certificates on both sides (mutual TLS) and trust-on-first-use pinning.
 *
 * The trust manager below deliberately accepts any certificate during the handshake: there is no certificate authority here.
 * That is only safe because every caller reads the peer's certificate fingerprint straight after the handshake and either
 * checks it against a pinned value or compares a short code on both screens (see [LanPairing]) BEFORE sending any data.
 */
object LanTls {
    private val acceptAnyCertificate = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun context(identity: LanIdentity): SSLContext =
        SSLContext.getInstance("TLS").apply { init(identity.keyManagers, arrayOf(acceptAnyCertificate), SecureRandom()) }

    fun fingerprint(cert: X509Certificate): String = sha256Hex(cert.encoded)

    /** Fingerprint of the certificate the other side presented. Call only after the handshake has completed. */
    fun peerFingerprint(socket: SSLSocket): String {
        val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw SSLPeerUnverifiedException("peer presented no certificate")
        return fingerprint(cert)
    }

    /** Only modern protocol versions; TLS 1.3 where the platform has it (Android 10+, Java 11+), otherwise TLS 1.2. */
    fun modernProtocols(supported: Array<String>): Array<String> =
        supported.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray().also {
            require(it.isNotEmpty()) { "no TLS 1.2 / 1.3 support" }
        }
}
