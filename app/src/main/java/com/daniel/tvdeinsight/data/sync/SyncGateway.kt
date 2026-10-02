package com.daniel.tvdeinsight.data.sync

/**
 * Boundary between locally persisted offers and a remote synchronization transport.
 * Capture, parsing and decision code must not depend on HTTP or Google APIs.
 */
interface SyncGateway {
    suspend fun synchronize(): SyncGatewayResult
}

sealed interface SyncGatewayResult {
    data class Completed(val hasMore: Boolean = false, val pendingEvents: Int = 0) : SyncGatewayResult
    data class RetryLater(val code: String, val retryAfterSeconds: Long? = null) : SyncGatewayResult
    data class PermanentFailure(val code: String) : SyncGatewayResult
    data object NotConfigured : SyncGatewayResult
}
