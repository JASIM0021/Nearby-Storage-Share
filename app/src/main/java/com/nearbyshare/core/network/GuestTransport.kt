package com.nearbyshare.core.network

import android.net.Uri
import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.core.network.protocol.StorageInfo

/**
 * What the app can do with a host it is connected to, whichever way it connected (Google Nearby or the local network).
 * The file browser, the Files-app drive and the transfer notifications only know this interface.
 */
interface GuestTransport {
    suspend fun storageInfo(): StorageInfo
    suspend fun list(parentId: String?): List<RemoteFile>
    suspend fun createFolder(name: String, parentId: String?): RemoteFile
    suspend fun stat(fileId: String): RemoteFile
    suspend fun rename(fileId: String, newName: String): RemoteFile
    suspend fun search(query: String): List<RemoteFile>
    suspend fun delete(fileId: String)
    suspend fun cancelTransfer(transferId: String)

    /** [name] overrides the file's display name (needed for plain file uris that carry none). Returns the host's file id. */
    suspend fun upload(uri: Uri, parentId: String?, name: String? = null): String?
    suspend fun download(file: RemoteFile, dest: Uri)
}
