package com.nearbyshare.core.security

import android.content.Context
import android.os.Build
import android.util.Base64
import com.nearbyshare.core.common.toHex
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Stable random device id + user-visible name, and the small HMAC helpers used by the pairing protocol. */
class DeviceIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }

    var displayName: String
        get() = prefs.getString("display_name", null) ?: (Build.MODEL ?: "Android")
        set(value) {
            prefs.edit().putString("display_name", sanitizeName(value)).apply()
        }

    /** Advertised as "name|deviceId"; the id is a claim, proven later by the HMAC handshake. */
    /** [hasKey] = "I already hold a pairing key for the host I'm connecting to" (guests only). Lets the host ask for re-pairing automatically. */
    fun endpointName(hasKey: Boolean = false): String = "${sanitizeName(displayName)}|$deviceId" + if (hasKey) KEY_FLAG else ""

    companion object {
        private const val KEY_FLAG = "|k"

        fun sanitizeName(raw: String): String =
            raw.replace('|', ' ').filter { !it.isISOControl() }.trim().take(40).ifBlank { "Android" }

        fun hasKeyFlag(raw: String): Boolean = raw.endsWith(KEY_FLAG)

        fun parseEndpointName(raw: String): Pair<String, String>? {
            val s = raw.removeSuffix(KEY_FLAG)
            val idx = s.lastIndexOf('|')
            if (idx <= 0) return null
            val id = s.substring(idx + 1)
            return runCatching { UUID.fromString(id) }.getOrNull()?.let { s.substring(0, idx) to id }
        }

        private val random = SecureRandom()

        fun randomToken(bytes: Int = 32): String =
            Base64.encodeToString(ByteArray(bytes).also(random::nextBytes), Base64.NO_WRAP or Base64.URL_SAFE)

        /** Both sides derive the HMAC key from the shared pairing token without ever re-sending it. */
        fun keyFromToken(token: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).toHex().toByteArray()

        fun hmac(key: ByteArray, vararg parts: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach { mac.update(it.toByteArray()); mac.update(0) }
            return mac.doFinal().toHex()
        }

        fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
