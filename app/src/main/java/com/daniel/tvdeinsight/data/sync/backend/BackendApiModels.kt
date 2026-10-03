package com.daniel.tvdeinsight.data.sync.backend

import com.daniel.tvdeinsight.data.sync.crypto.EcPublicJwk
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SerializableEcPublicJwk(
    val kty: String,
    val crv: String,
    val x: String,
    val y: String
) {
    companion object {
        fun from(value: EcPublicJwk) = SerializableEcPublicJwk(value.kty, value.crv, value.x, value.y)
    }
}

@Serializable
data class BackendClientDescriptor(
    @SerialName("app_id") val appId: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("api_version") val apiVersion: Int = 1
)

@Serializable
data class RegistrationRequest(
    @SerialName("activation_key") val activationKey: String,
    @SerialName("device_public_key") val devicePublicKey: SerializableEcPublicJwk,
    val client: BackendClientDescriptor
)

@Serializable
data class SyncPolicyResponse(
    @SerialName("max_events_per_sync") val maxEventsPerSync: Int,
    @SerialName("max_request_bytes") val maxRequestBytes: Int,
    @SerialName("timestamp_skew_seconds") val timestampSkewSeconds: Int,
    @SerialName("nonce_retention_seconds") val nonceRetentionSeconds: Int,
    @SerialName("request_idempotency_retention_seconds") val requestIdempotencyRetentionSeconds: Int,
    @SerialName("cursor_retention_seconds") val cursorRetentionSeconds: Int,
    @SerialName("own_changes_page_size") val ownChangesPageSize: Int,
    @SerialName("sync_requests_per_minute") val syncRequestsPerMinute: Int,
    @SerialName("sync_burst") val syncBurst: Int,
    @SerialName("worker_attempts_per_run") val workerAttemptsPerRun: Int,
    @SerialName("max_event_future_skew_seconds") val maxEventFutureSkewSeconds: Int
)

@Serializable
data class RegistrationResponse(
    @SerialName("installation_id") val installationId: String,
    val role: String,
    @SerialName("policy_version") val policyVersion: Int,
    @SerialName("server_time") val serverTime: Long,
    @SerialName("sync_policy") val syncPolicy: SyncPolicyResponse
)

@Serializable
data class BackendErrorBody(
    val code: String,
    val message: String,
    val retryable: Boolean,
    @SerialName("request_id") val requestId: String,
    @SerialName("server_time") val serverTime: Long
)

@Serializable
data class BackendErrorEnvelope(val error: BackendErrorBody)

@Serializable
data class ClientLicenseIssueRequest(
    @SerialName("android_id") val androidId: String,
    @SerialName("expires_at_epoch_ms") val expiresAtEpochMillis: Long,
    @SerialName("license_type") val licenseType: String = "CUSTOM"
)

@Serializable
data class ClientLicenseIssueResponse(
    @SerialName("activation_key") val activationKey: String,
    @SerialName("android_id") val androidId: String,
    @SerialName("expires_at_epoch_ms") val expiresAtEpochMillis: Long,
    @SerialName("license_type") val licenseType: String,
    @SerialName("server_time") val serverTime: Long
)
