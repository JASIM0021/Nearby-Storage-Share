package com.nearbyshare.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live byte counters keyed by transfer id; the database only records state changes, not every chunk. */
class TransferProgress {
    private val _bytes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val bytes: StateFlow<Map<String, Long>> = _bytes.asStateFlow()

    fun set(transferId: String, transferred: Long) = _bytes.update { it + (transferId to transferred) }
    fun clear(transferId: String) = _bytes.update { it - transferId }
}
