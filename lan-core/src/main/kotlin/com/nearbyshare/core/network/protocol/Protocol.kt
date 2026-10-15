package com.nearbyshare.core.network.protocol

import org.json.JSONArray
import org.json.JSONObject

const val PROTOCOL_VERSION = 1
const val SERVICE_ID = "com.nearbyshare.storage.v1"
const val MAX_CONTROL_BYTES = 64 * 1024

object MsgType {
    const val HELLO = "HELLO"
    const val CHALLENGE = "CHALLENGE"
    const val AUTH = "AUTH"
    const val AUTH_RESULT = "AUTH_RESULT"
    const val STORAGE_INFO = "STORAGE_INFO"
    const val LIST_DIRECTORY = "LIST_DIRECTORY"
    const val MKDIR = "MKDIR"
    const val STAT = "STAT"
    const val RENAME = "RENAME"
    const val SEARCH = "SEARCH"
    const val UPLOAD_BEGIN = "UPLOAD_BEGIN"
    const val UPLOAD_RESULT = "UPLOAD_RESULT"
    const val DOWNLOAD_BEGIN = "DOWNLOAD_BEGIN"
    const val DELETE_FILE = "DELETE_FILE"
    const val TRANSFER_CANCEL = "TRANSFER_CANCEL"
    const val REVOKED = "REVOKED"

    // Same-network (LAN) pairing and keep-alive. See com.nearbyshare.lan.LanPairing for how the code is derived.
    const val PAIR_CHALLENGE = "PAIR_CHALLENGE"
    const val PAIR_REVEAL = "PAIR_REVEAL"
    const val PAIR_CONFIRM = "PAIR_CONFIRM"
    const val PING = "PING"
    const val PONG = "PONG"
}

object ErrorCode {
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val DENIED = "DENIED"
    const val EXPIRED = "EXPIRED"
    const val SHARING_OFF = "SHARING_OFF"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val BAD_REQUEST = "BAD_REQUEST"
    const val NOT_FOUND = "NOT_FOUND"
    const val NAME_EXISTS = "NAME_EXISTS"
    const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    const val NO_SPACE = "NO_SPACE"
    const val BUSY = "BUSY"
    const val INTEGRITY = "INTEGRITY"
    const val INTERNAL = "INTERNAL"
    const val UNAVAILABLE = "UNAVAILABLE"
}

/** Control message. Every message carries the protocol version, a request id, and is capped at 64 KiB. */
class Message(
    val type: String,
    val id: String,
    val isResponse: Boolean = false,
    val ok: Boolean = true,
    val body: JSONObject = JSONObject(),
) {
    fun encode(): ByteArray {
        val bytes = JSONObject()
            .put("v", PROTOCOL_VERSION)
            .put("t", type)
            .put("id", id)
            .put("res", isResponse)
            .put("ok", ok)
            .put("b", body)
            .toString()
            .toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_CONTROL_BYTES) { "control message too large" }
        return bytes
    }

    fun error(): Pair<String, String> = body.optString("code", ErrorCode.INTERNAL) to body.optString("msg", "")

    companion object {
        /** Returns null for anything malformed, oversized, or from a different protocol version. */
        fun decode(bytes: ByteArray): Message? {
            if (bytes.size > MAX_CONTROL_BYTES) return null
            return runCatching {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                if (o.getInt("v") != PROTOCOL_VERSION) return null
                val id = o.getString("id")
                if (id.length > 64) return null
                Message(o.getString("t"), id, o.optBoolean("res"), o.optBoolean("ok", true), o.optJSONObject("b") ?: JSONObject())
            }.getOrNull()
        }

        fun error(type: String, id: String, code: String, msg: String) =
            Message(type, id, true, false, JSONObject().put("code", code).put("msg", msg))
    }
}

data class Permissions(
    val read: Boolean,
    val write: Boolean,
    val overwrite: Boolean,
    val delete: Boolean,
    val folders: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().put("r", read).put("w", write).put("o", overwrite).put("d", delete).put("f", folders)

    companion object {
        fun fromJson(o: JSONObject?) = Permissions(
            o?.optBoolean("r") ?: false, o?.optBoolean("w") ?: false, o?.optBoolean("o") ?: false,
            o?.optBoolean("d") ?: false, o?.optBoolean("f") ?: false,
        )
    }
}

data class StorageInfo(val quotaBytes: Long, val usedBytes: Long, val remainingBytes: Long, val permissions: Permissions) {
    fun toJson(): JSONObject = JSONObject().put("quota", quotaBytes).put("used", usedBytes).put("remaining", remainingBytes)
        .put("perms", permissions.toJson())

    companion object {
        fun fromJson(o: JSONObject) = StorageInfo(
            o.getLong("quota"), o.getLong("used"), o.getLong("remaining"), Permissions.fromJson(o.optJSONObject("perms")),
        )
    }
}

data class RemoteFile(
    val fileId: String,
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
    /** Folder the item lives in; null at the top level. Lets a drive browser rebuild paths from a single lookup. */
    val parentId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", fileId).put("n", name).put("d", isFolder).put("s", sizeBytes).put("u", updatedAt)
        .also { if (parentId != null) it.put("p", parentId) }

    companion object {
        fun fromJson(o: JSONObject) = RemoteFile(
            o.getString("id"), o.getString("n"), o.getBoolean("d"), o.getLong("s"), o.getLong("u"), o.optString("p").ifEmpty { null },
        )
        fun listFromJson(a: JSONArray) = (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }
}

/** Raised by host-side operations; the code is sent to the guest, the message must be non-sensitive. */
class OpException(val code: String, message: String) : Exception(message)
