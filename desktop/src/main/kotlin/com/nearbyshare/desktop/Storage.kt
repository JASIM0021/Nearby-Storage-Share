package com.nearbyshare.desktop

import com.nearbyshare.lan.PinStore
import com.nearbyshare.lan.PinnedHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.util.UUID

/** This PC's id and display name. The id is random and created once. */
class Device(val id: String, val name: String) {
    companion object {
        fun loadOrCreate(file: File): Device {
            file.parentFile?.mkdirs()
            if (file.isFile) {
                runCatching {
                    val o = JSONObject(file.readText())
                    return Device(o.getString("id"), o.getString("name"))
                }
            }
            val name = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Windows PC"
            val d = Device(UUID.randomUUID().toString(), name.take(40))
            file.writeText(JSONObject().put("id", d.id).put("name", d.name).toString())
            return d
        }
    }
}

/** A host this PC has paired with, and where it was last reached. */
class SavedHost(val id: String, val name: String, val fingerprint: String, val address: String, val port: Int)

/**
 * The hosts this PC trusts, by certificate fingerprint (trust on first use). Kept as a small JSON file in the app-data folder.
 */
class FilePins(private val file: File) : PinStore {
    private val lock = Any()
    private var hosts: MutableList<SavedHost> = load()

    private fun load(): MutableList<SavedHost> = runCatching {
        val arr = JSONObject(file.readText()).getJSONArray("hosts")
        MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            SavedHost(o.getString("id"), o.getString("name"), o.getString("fp"), o.optString("addr"), o.optInt("port", 0))
        }
    }.getOrDefault(mutableListOf())

    private fun persist() {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        hosts.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("fp", it.fingerprint).put("addr", it.address).put("port", it.port)) }
        file.writeText(JSONObject().put("hosts", arr).toString())
    }

    fun all(): List<SavedHost> = synchronized(lock) { hosts.toList() }

    override suspend fun expectedFingerprint(hostId: String): String? = synchronized(lock) { hosts.firstOrNull { it.id == hostId }?.fingerprint }

    override suspend fun findByFingerprint(fingerprint: String): PinnedHost? =
        synchronized(lock) { hosts.firstOrNull { it.fingerprint == fingerprint }?.let { PinnedHost(it.id, it.name) } }

    override suspend fun save(hostId: String, name: String, fingerprint: String) = synchronized(lock) {
        val old = hosts.firstOrNull { it.id == hostId }
        hosts.removeAll { it.id == hostId }
        hosts.add(SavedHost(hostId, name, fingerprint, old?.address ?: "", old?.port ?: 0))
        persist()
    }

    fun rememberAddress(hostId: String, address: String, port: Int) = synchronized(lock) {
        val i = hosts.indexOfFirst { it.id == hostId }
        if (i >= 0) {
            val h = hosts[i]
            hosts[i] = SavedHost(h.id, h.name, h.fingerprint, address, port)
            persist()
        }
    }

    fun forgetFingerprint(fingerprint: String) = synchronized(lock) {
        if (hosts.removeAll { it.fingerprint == fingerprint }) persist()
    }

    fun forget(hostId: String) = synchronized(lock) {
        if (hosts.removeAll { it.id == hostId }) persist()
    }
}
