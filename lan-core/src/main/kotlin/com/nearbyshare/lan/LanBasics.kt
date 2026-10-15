package com.nearbyshare.lan

import java.security.MessageDigest
import java.security.SecureRandom

object LanConstants {
    /** TCP port a host listens on (falls back to a free port if taken). Shown to the user for connect-by-address. */
    const val DEFAULT_PORT = 45820

    /** UDP port hosts announce themselves on and guests listen on. */
    const val DISCOVERY_PORT = 45821

    const val CHUNK_BYTES = 60 * 1024
    const val MAX_FRAME_BYTES = 70 * 1024
    const val CONNECT_TIMEOUT_MS = 8_000
    const val HANDSHAKE_TIMEOUT_MS = 15_000
    const val PAIRING_TIMEOUT_MS = 120_000
    const val IDLE_TIMEOUT_MS = 90_000
    const val PING_INTERVAL_MS = 20_000L
}

private val random = SecureRandom()

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

fun randomHex(byteCount: Int): String = ByteArray(byteCount).also(random::nextBytes).toHexString()

fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

/** Device names are shown to other people, so strip control characters and cap the length. */
fun sanitizeDeviceName(raw: String): String =
    raw.filter { !it.isISOControl() }.trim().take(40).ifBlank { "Device" }

fun isUuid(s: String): Boolean = runCatching { java.util.UUID.fromString(s) }.isSuccess && s.length == 36
