package com.daniel.tvdeinsight.data.sync.backend

import com.daniel.tvdeinsight.data.sync.crypto.BackendRequestCrypto
import com.daniel.tvdeinsight.data.sync.crypto.DeviceKeyStore
import com.daniel.tvdeinsight.data.sync.crypto.DeviceKeyStoreException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RegistrationOutcome {
    data class Active(val installationId: String) : RegistrationOutcome
    data class RetryLater(val code: String, val retryAfterSeconds: Long? = null) : RegistrationOutcome
    data class PermanentFailure(val status: BackendRegistrationStatus, val code: String) : RegistrationOutcome
    data object ActivationRequired : RegistrationOutcome
    data object KeyRecoveryRequired : RegistrationOutcome
}

@Singleton
class BackendRegistrationManager @Inject constructor(
    private val store: BackendRegistrationStore,
    private val keyStore: DeviceKeyStore,
    private val registrationApi: BackendRegistrationApi
) {
    fun configureActivationKey(activationKey: String) = store.savePendingActivationKey(activationKey)

    suspend fun registerPending(): RegistrationOutcome {
        val current = store.state()
        current.installationId?.let { return RegistrationOutcome.Active(it) }
        val activationKey = store.pendingActivationKey() ?: return RegistrationOutcome.ActivationRequired
        val allowKeyCreation = current.keyThumbprint == null
        val keyPair = try {
            keyStore.loadOrCreate(allowCreate = allowKeyCreation)
        } catch (_: DeviceKeyStoreException.Missing) {
            store.markFailure(
                BackendRegistrationStatus.KEY_RECOVERY_REQUIRED,
                "DEVICE_KEY_MISSING",
                discardActivationKey = false
            )
            return RegistrationOutcome.KeyRecoveryRequired
        } catch (_: DeviceKeyStoreException.Invalidated) {
            store.markFailure(
                BackendRegistrationStatus.KEY_RECOVERY_REQUIRED,
                "DEVICE_KEY_INVALIDATED",
                discardActivationKey = false
            )
            return RegistrationOutcome.KeyRecoveryRequired
        } catch (_: DeviceKeyStoreException.Unavailable) {
            store.markFailure(
                BackendRegistrationStatus.RETRYABLE_ERROR,
                "KEYSTORE_UNAVAILABLE",
                discardActivationKey = false
            )
            return RegistrationOutcome.RetryLater("KEYSTORE_UNAVAILABLE")
        }
        val thumbprint = BackendRequestCrypto.jwkThumbprint(BackendRequestCrypto.publicJwk(keyPair.public))
        if (current.keyThumbprint != null && current.keyThumbprint != thumbprint) {
            store.markFailure(
                BackendRegistrationStatus.KEY_RECOVERY_REQUIRED,
                "DEVICE_KEY_CHANGED",
                discardActivationKey = false
            )
            return RegistrationOutcome.KeyRecoveryRequired
        }
        store.rememberKeyThumbprint(thumbprint)
        return when (val result = registrationApi.register(
            BackendRegistrationApi.request(activationKey, keyPair),
            keyPair
        )) {
            is RegistrationApiResult.Success -> {
                store.markActive(result.response, thumbprint)
                RegistrationOutcome.Active(result.response.installationId)
            }
            is RegistrationApiResult.Failure -> handleFailure(result)
        }
    }

    private fun handleFailure(failure: RegistrationApiResult.Failure): RegistrationOutcome {
        if (failure.retryable) {
            store.markFailure(
                BackendRegistrationStatus.RETRYABLE_ERROR,
                failure.code,
                discardActivationKey = false
            )
            return RegistrationOutcome.RetryLater(failure.code, failure.retryAfterSeconds)
        }
        val status = RegistrationFailurePolicy.permanentStatus(failure.code)
        store.markFailure(status, failure.code, discardActivationKey = true)
        return RegistrationOutcome.PermanentFailure(status, failure.code)
    }
}

object RegistrationFailurePolicy {
    fun permanentStatus(code: String): BackendRegistrationStatus = when (code) {
        "LICENSE_INVALID" -> BackendRegistrationStatus.INVALID
        "LICENSE_EXPIRED" -> BackendRegistrationStatus.EXPIRED
        "LICENSE_REVOKED", "INSTALLATION_REVOKED" -> BackendRegistrationStatus.REVOKED
        "LICENSE_ALREADY_BOUND" -> BackendRegistrationStatus.ALREADY_BOUND
        "INVALID_SIGNATURE" -> BackendRegistrationStatus.SIGNATURE_INVALID
        "TIMESTAMP_OUT_OF_RANGE" -> BackendRegistrationStatus.CLOCK_ERROR
        else -> BackendRegistrationStatus.INVALID
    }
}
