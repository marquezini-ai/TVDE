package com.daniel.tvdeinsight.data.sync.backend

import com.daniel.tvdeinsight.data.sync.crypto.BackendRequestCrypto
import com.daniel.tvdeinsight.data.sync.crypto.DeviceKeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString

sealed interface ClientLicenseIssueResult {
    data class Success(val response: ClientLicenseIssueResponse) : ClientLicenseIssueResult
    data class Failure(val code: String, val retryable: Boolean) : ClientLicenseIssueResult
}

@Singleton
class AdminLicenseIssuer @Inject constructor(
    private val registrationStore: BackendRegistrationStore,
    private val keyStore: DeviceKeyStore,
    private val httpClient: BackendHttpClient
) {
    private val random = SecureRandom()

    suspend fun issue(androidId: String, expiresAtMillis: Long): ClientLicenseIssueResult {
        val registration = registrationStore.state()
        val installationId = registration.installationId
            ?: return ClientLicenseIssueResult.Failure("BACKEND_NOT_CONFIGURED", false)
        if (registration.status != BackendRegistrationStatus.ACTIVE || registration.role != "ADMIN") {
            return ClientLicenseIssueResult.Failure("ADMIN_BACKEND_REGISTRATION_REQUIRED", false)
        }
        val keyPair = runCatching { keyStore.loadOrCreate(allowCreate = false) }
            .getOrElse { return ClientLicenseIssueResult.Failure("DEVICE_KEY_UNAVAILABLE", false) }
        val request = ClientLicenseIssueRequest(androidId.trim().lowercase(), expiresAtMillis)
        val body = BackendHttpClient.json.encodeToString(request).toByteArray(Charsets.UTF_8)
        val idempotencyKey = UUID.randomUUID().toString()
        val signed = BackendRequestCrypto.signedHeaders(
            privateKey = keyPair.private,
            method = "POST",
            path = PATH,
            principal = installationId,
            body = body,
            timestamp = System.currentTimeMillis() / 1_000,
            nonce = ByteArray(16).also(random::nextBytes).let {
                Base64.getUrlEncoder().withoutPadding().encodeToString(it)
            },
            idempotencyKey = idempotencyKey
        )
        val response = try {
            httpClient.post(
                path = PATH,
                body = body,
                headers = mapOf(
                    "X-TVDE-Installation-Id" to installationId,
                    "X-TVDE-Timestamp" to signed.timestamp.toString(),
                    "X-TVDE-Nonce" to signed.nonce,
                    "Idempotency-Key" to signed.idempotencyKey,
                    "X-TVDE-Body-SHA256" to signed.bodySha256,
                    "X-TVDE-Signature" to signed.signature
                )
            )
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return ClientLicenseIssueResult.Failure("NETWORK_UNAVAILABLE", true)
        }
        if (response.status in 200..299) {
            return runCatching {
                ClientLicenseIssueResult.Success(
                    BackendHttpClient.json.decodeFromString<ClientLicenseIssueResponse>(
                        response.body.toString(Charsets.UTF_8)
                    )
                )
            }.getOrElse { ClientLicenseIssueResult.Failure("INVALID_RESPONSE", true) }
        }
        val error = runCatching {
            BackendHttpClient.json.decodeFromString<BackendErrorEnvelope>(response.body.toString(Charsets.UTF_8))
        }.getOrNull()?.error
        return ClientLicenseIssueResult.Failure(
            error?.code ?: "HTTP_${response.status}",
            error?.retryable ?: (response.status == 429 || response.status >= 500)
        )
    }

    private companion object {
        const val PATH = "/v1/admin/client-licenses/issue"
    }
}
