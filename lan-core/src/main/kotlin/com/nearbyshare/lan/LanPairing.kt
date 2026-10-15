package com.nearbyshare.lan

import java.security.MessageDigest

/**
 * First-time pairing: both people compare a 6-digit code shown on the two screens (like Bluetooth "numeric comparison").
 *
 * The code is derived from both certificate fingerprints AND a random value from each side. To stop an attacker in the
 * middle from grinding certificates until the two codes happen to match, the guest commits to its random value
 * (hash) in its first message, before the host reveals its own; only then does the guest reveal. An attacker therefore
 * gets one 1-in-a-million chance per pairing attempt, and a failed attempt shows two different codes to the humans.
 */
object LanPairing {
    fun commitment(guestNonce: String): String = sha256Hex(guestNonce.toByteArray())

    /** "482 913" - the code both screens must show. */
    fun sas(guestFingerprint: String, hostFingerprint: String, guestNonce: String, hostNonce: String): String {
        val d = MessageDigest.getInstance("SHA-256")
        d.update("NSS-SAS-1".toByteArray())
        for (part in listOf(guestFingerprint, hostFingerprint, guestNonce, hostNonce)) {
            d.update(part.toByteArray())
            d.update(0)
        }
        val h = d.digest()
        val n = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        val six = "%06d".format(n % 1_000_000)
        return six.substring(0, 3) + " " + six.substring(3)
    }
}
