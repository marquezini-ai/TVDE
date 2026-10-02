package com.daniel.tvdeinsight.data.sync.backend

import com.daniel.tvdeinsight.BuildConfig
import com.daniel.tvdeinsight.data.sync.crypto.BackendRequestCrypto
import java.security.KeyPair
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface RegistrationApiResult {
    data class Success(val response: RegistrationResponse) : RegistrationApiResult
    data class Failure(
        val httpStatus: Int?,
        val code: String,
        val retryable: Boolean,
        val retryAfterSeconds: Long? = null
    ) : RegistrationApiResult
}

interface RegistrationApi {
    suspend fun register(request: RegistrationRequest, keyPair: KeyPair): RegistrationApiResult
}

@Singleton
class BackendRegistrationApi @Inject constructor(
    private val httpClient: BackendHttpClient
) : RegistrationApi {
    private val json: Json = BackendHttpClient.json
    private val random = SecureRandom()

    override suspend fun register(request: RegistrationRequest, keyPair: KeyPair): RegistrationApiResult {
        val body = json.encodeToString(request).toByteArray(Charsets.UTF_8)
        val jwk = BackendRequestCrypto.publicJwk(keyPair.public)
        val principal = BackendRequestCrypto.jwkThumbprint(jwk)
        val signed = BackendRequestCrypto.signedHeaders(
            privateKey = keyPair.private,
            method = "POST",
            path = REGISTRATION_PATH,
            principal = principal,
            body = body,
            timestamp = System.currentTimeMillis() / 1_000,
            nonce = randomNonce(),
            idempotencyKey = UUID.randomUUID().toString()
        )
        val response = try {
            httpClient.post(
                path = REGISTRATION_PATH,
                body = body,
                headers = mapOf(
                    "X-TVDE-Key-Id" to principal,
                    "X-TVDE-Timestamp" to signed.timestamp.toString(),
                    "X-TVDE-Nonce" to signed.nonce,
                    "Idempotency-Key" to signed.idempotencyKey,
                    "X-TVDE-Body-SHA256" to signed.bodySha256,
                    "X-TVDE-Signature" to signed.signature
                )
            )
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return RegistrationApiResult.Failure(null, "NETWORK_UNAVAILABLE", retryable = true)
        }
        if (response.status in 200..299) {
            return runCatching {
                RegistrationApiResult.Success(
                    json.decodeFromString<RegistrationResponse>(response.body.toString(Charsets.UTF_8))
                )
            }.getOrElse {
                RegistrationApiResult.Failure(response.status, "INVALID_RESPONSE", retryable = true)
            }
        }
        val envelope = runCatching {
            json.decodeFromString<BackendErrorEnvelope>(response.body.toString(Charsets.UTF_8))
        }.getOrNull()
        return RegistrationApiResult.Failure(
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
        const val REGISTRATION_PATH = "/v1/installations/register"

        fun request(activationKey: String, keyPair: KeyPair): RegistrationRequest = RegistrationRequest(
            activationKey = activationKey,
            devicePublicKey = SerializableEcPublicJwk.from(BackendRequestCrypto.publicJwk(keyPair.public)),
            client = BackendClientDescriptor(
                appId = BuildConfig.APPLICATION_ID,
                appVersion = BuildConfig.VERSION_NAME
            )
        )
    }
}
