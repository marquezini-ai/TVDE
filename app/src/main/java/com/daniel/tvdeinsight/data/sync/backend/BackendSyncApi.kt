package com.daniel.tvdeinsight.data.sync.backend

import com.daniel.tvdeinsight.data.sync.crypto.BackendRequestCrypto
import java.security.KeyPair
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.decodeFromString

sealed interface BackendSyncApiResult {
    data class Success(val response: BackendSyncResponse) : BackendSyncApiResult
    data class Failure(
        val httpStatus: Int?,
        val code: String,
        val retryable: Boolean,
        val retryAfterSeconds: Long? = null
    ) : BackendSyncApiResult
}

@Singleton
class BackendSyncApi @Inject constructor(
    private val httpClient: BackendHttpClient
) {
    private val random = SecureRandom()

    suspend fun sync(
        installationId: String,
        keyPair: KeyPair,
        requestBody: String,
        idempotencyKey: String
    ): BackendSyncApiResult {
        val body = requestBody.toByteArray(Charsets.UTF_8)
        val signed = BackendRequestCrypto.signedHeaders(
            privateKey = keyPair.private,
            method = "POST",
            path = SYNC_PATH,
            principal = installationId,
            body = body,
            timestamp = System.currentTimeMillis() / 1_000,
            nonce = randomNonce(),
            idempotencyKey = idempotencyKey
        )
        val response = try {
            httpClient.post(
                path = SYNC_PATH,
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
            return BackendSyncApiResult.Failure(null, "NETWORK_UNAVAILABLE", retryable = true)
        }
        if (response.status in 200..299) {
            return runCatching {
                BackendSyncApiResult.Success(
                    BackendHttpClient.json.decodeFromString<BackendSyncResponse>(
                        response.body.toString(Charsets.UTF_8)
                    )
                )
            }.getOrElse {
                BackendSyncApiResult.Failure(response.status, "INVALID_RESPONSE", retryable = true)
            }
        }
        val envelope = runCatching {
            BackendHttpClient.json.decodeFromString<BackendErrorEnvelope>(response.body.toString(Charsets.UTF_8))
        }.getOrNull()
        return BackendSyncApiResult.Failure(
            httpStatus = response.status,
            code = envelope?.error?.code ?: "HTTP_${response.status}",
            retryable = envelope?.error?.retryable ?: (response.status == 429 || response.status >= 500),
            retryAfterSeconds = response.retryAfterSeconds
        )
    }

    private fun randomNonce(): String = ByteArray(16).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    companion object {
        const val SYNC_PATH = "/v1/sync"
    }
}
