package com.nearbyshare.feature

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nearbyshare.container
import com.nearbyshare.core.common.GIB
import com.nearbyshare.core.common.formatBytes
import com.nearbyshare.core.database.AccessMode
import com.nearbyshare.core.database.AuditEvent
import com.nearbyshare.core.database.Device
import com.nearbyshare.core.database.DeviceAccess
import com.nearbyshare.core.database.TrustState
import com.nearbyshare.service.StorageHostService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DeviceRow(val device: Device, val access: DeviceAccess?)
data class StorageSnapshot(val totalBytes: Long, val freeBytes: Long)

class HostViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val db = c.db

    val config = db.hostConfig().observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val usedBytes = db.files().observeCommitted().stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val audit: StateFlow<List<AuditEvent>> = db.audit().observeRecent(30).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val devices: StateFlow<List<DeviceRow>> = combine(db.devices().observeAll(), db.access().observeAll()) { d, a ->
        d.map { dev -> DeviceRow(dev, a.firstOrNull { it.deviceId == dev.deviceId }) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Bytes stored per guest device id. */
    val usage: StateFlow<Map<String, Long>> = db.files().observeUsageByDevice()
        .map { rows -> rows.associate { it.id to it.bytes } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    fun fileCount(deviceId: String) = db.files().observeFileCount(deviceId)
    fun transfersFor(deviceId: String) = db.transfers().observeByDevice(deviceId, 200)
    fun auditFor(deviceId: String) = db.audit().observeByDevice(deviceId, 100)

    /** null = follow the host's overall quota. Clamped to the host quota so a device can never be promised more than exists. */
    fun setDeviceQuota(deviceId: String, bytes: Long?) {
        val cap = config.value?.quotaBytes ?: Long.MAX_VALUE
        updateAccess(deviceId) { it.copy(quotaOverrideBytes = bytes?.coerceAtMost(cap)) }
    }

    /** null = never expires. */
    fun setDeviceExpiry(deviceId: String, hoursFromNow: Long?) {
        updateAccess(deviceId) { it.copy(expiresAt = hoursFromNow?.let { h -> System.currentTimeMillis() + h * 3_600_000L }) }
    }

    val guests = c.host.guests
    val pending = c.host.pending
    val advertising = c.host.advertising
    val hostError = c.host.error

    private val _storage = MutableStateFlow(StorageSnapshot(0, 0))
    val storage: StateFlow<StorageSnapshot> = _storage.asStateFlow()

    var deviceName: String
        get() = c.identity.displayName
        set(value) { c.identity.displayName = value }

    init {
        refreshStorage()
    }

    fun refreshStorage() {
        _storage.value = StorageSnapshot(c.scanner.totalBytes(), c.scanner.freeBytes())
    }

    fun setSharing(on: Boolean) {
        viewModelScope.launch {
            val cfg = db.hostConfig().get() ?: return@launch
            db.hostConfig().upsert(cfg.copy(sharingEnabled = on, updatedAt = System.currentTimeMillis()))
            if (on) StorageHostService.start(getApplication()) else StorageHostService.stop(getApplication())
        }
    }

    /** Resumes hosting after the process was killed while sharing was enabled. Only call while the app is visible. */
    fun resumeIfNeeded() {
        viewModelScope.launch {
            if (db.hostConfig().get()?.sharingEnabled == true && !c.host.advertising.value) StorageHostService.start(getApplication())
        }
    }

    /** Returns an error message, or null when applied. */
    fun setQuota(bytes: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val cfg = db.hostConfig().get() ?: return@launch
            val stored = withContext(Dispatchers.IO) { db.files().sumCommitted() }
            val max = c.scanner.maxOfferable(cfg.safetyReserveBytes, stored)
            val error = when {
                bytes < GIB / 4 -> "Minimum shared storage is 256 MB"
                bytes < stored -> "Guests already store ${formatBytes(stored)}. Delete files first or pick a larger limit."
                bytes > max -> "Only ${formatBytes(max)} can be offered while keeping ${formatBytes(cfg.safetyReserveBytes)} free for your phone."
                else -> null
            }
            if (error == null) db.hostConfig().upsert(cfg.copy(quotaBytes = bytes, updatedAt = System.currentTimeMillis()))
            refreshStorage()
            onResult(error)
        }
    }

    fun setAccessMode(mode: AccessMode) = updateConfig { it.copy(accessMode = mode) }

    fun setMaxGuests(n: Int) = updateConfig { it.copy(maxGuests = n.coerceIn(1, 8)) }

    /** Display name of the chosen visible folder (e.g. "NearbyStorage"), or null when none/unavailable. */
    val visibleFolderName: StateFlow<String?> = config
        .map { cfg -> cfg?.visibleTreeUri?.let { c.visible.root(it)?.name } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** Back to hidden app storage. Files already in the visible folder stay there and stay accessible. */
    fun usePrivateStorage() = updateConfig { it.copy(storageMode = com.nearbyshare.core.database.StorageMode.PRIVATE) }

    /**
     * Host picked a folder. The permission is persisted and never released: files saved in earlier folders
     * are still referenced by their old location and must stay readable. [onResult] gets an error message or null.
     */
    fun useVisibleFolder(treeUri: android.net.Uri, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        treeUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (e: SecurityException) {
                    return@withContext "Android would not give this app access to that folder. Pick a different one."
                }
                if (c.visible.root(treeUri.toString()) == null) "That folder is not writable. Pick a different one." else null
            }
            if (error == null) {
                updateConfig {
                    it.copy(storageMode = com.nearbyshare.core.database.StorageMode.VISIBLE, visibleTreeUri = treeUri.toString())
                }
            }
            onResult(error)
        }
    }

    fun setDefaults(read: Boolean? = null, write: Boolean? = null, overwrite: Boolean? = null, delete: Boolean? = null, folders: Boolean? = null) =
        updateConfig {
            it.copy(
                defaultCanRead = read ?: it.defaultCanRead,
                defaultCanWrite = write ?: it.defaultCanWrite,
                defaultCanOverwrite = overwrite ?: it.defaultCanOverwrite,
                defaultCanDelete = delete ?: it.defaultCanDelete,
                defaultCanCreateFolders = folders ?: it.defaultCanCreateFolders,
            )
        }

    private fun updateConfig(transform: (com.nearbyshare.core.database.HostConfig) -> com.nearbyshare.core.database.HostConfig) {
        viewModelScope.launch {
            val cfg = db.hostConfig().get() ?: return@launch
            db.hostConfig().upsert(transform(cfg).copy(updatedAt = System.currentTimeMillis()))
        }
    }

    fun resolvePairing(endpointId: String, approve: Boolean) = c.host.resolvePairing(endpointId, approve)

    fun updateAccess(deviceId: String, transform: (DeviceAccess) -> DeviceAccess) {
        viewModelScope.launch {
            val a = db.access().get(deviceId) ?: return@launch
            db.access().upsert(transform(a))
        }
    }

    fun revoke(deviceId: String) {
        updateAccess(deviceId) { it.copy(revokedAt = System.currentTimeMillis()) }
        c.host.disconnectDevice(deviceId)
    }

    fun restore(deviceId: String) = updateAccess(deviceId) { it.copy(revokedAt = null) }

    fun block(deviceId: String) {
        viewModelScope.launch { db.devices().setTrust(deviceId, TrustState.BLOCKED) }
        c.host.disconnectDevice(deviceId)
    }

    /** Forgets the pairing key so the device must pair again (files and permissions are kept). */
    fun resetPairing(deviceId: String) {
        c.host.disconnectDevice(deviceId)
        viewModelScope.launch { db.devices().clearSecret(deviceId) }
    }

    fun unblock(deviceId: String) {
        viewModelScope.launch { db.devices().setTrust(deviceId, TrustState.APPROVED) }
    }
}
