package com.daniel.tvdeinsight.data.sync

/**
 * Boundary between locally persisted offers and a remote synchronization transport.
 * Capture, parsing and decision code must not depend on HTTP or Google APIs.
 */
interface SyncGateway {
    suspend fun synchronize(): SyncGatewayResult
}

sealed interface SyncGatewayResult {
    data object Completed : SyncGatewayResult
    data object NotConfigured : SyncGatewayResult
}
