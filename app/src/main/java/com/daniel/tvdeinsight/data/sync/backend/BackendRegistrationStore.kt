package com.daniel.tvdeinsight.data.sync.backend

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class BackendRegistrationStatus {
    NOT_CONFIGURED,
    PENDING,
    ACTIVE,
    INVALID,
    EXPIRED,
    REVOKED,
    ALREADY_BOUND,
    SIGNATURE_INVALID,
    CLOCK_ERROR,
    KEY_RECOVERY_REQUIRED,
    RETRYABLE_ERROR
}

data class BackendRegistrationState(
    val status: BackendRegistrationStatus,
    val installationId: String? = null,
    val role: String? = null,
    val keyThumbprint: String? = null,
    val policyVersion: Int? = null,
    val lastErrorCode: String? = null
)

@Singleton
class BackendRegistrationStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        PREFERENCES_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    @Synchronized
    fun state(): BackendRegistrationState = BackendRegistrationState(
        status = preferences.getString(STATUS, null)
            ?.let { runCatching { BackendRegistrationStatus.valueOf(it) }.getOrNull() }
            ?: BackendRegistrationStatus.NOT_CONFIGURED,
        installationId = preferences.getString(INSTALLATION_ID, null),
        role = preferences.getString(ROLE, null),
        keyThumbprint = preferences.getString(KEY_THUMBPRINT, null),
        policyVersion = preferences.getInt(POLICY_VERSION, 0).takeIf { it > 0 },
        lastErrorCode = preferences.getString(LAST_ERROR_CODE, null)
    )

    @Synchronized
    fun savePendingActivationKey(value: String) {
        val normalized = value.trim()
        require(normalized.length in 20..4096) { "Activation key has an invalid length" }
        preferences.edit()
            .putString(PENDING_ACTIVATION_KEY, normalized)
            .putString(STATUS, BackendRegistrationStatus.PENDING.name)
            .remove(LAST_ERROR_CODE)
            .apply()
    }

    @Synchronized
    fun pendingActivationKey(): String? = preferences.getString(PENDING_ACTIVATION_KEY, null)

    @Synchronized
    fun rememberKeyThumbprint(thumbprint: String) {
        val existing = preferences.getString(KEY_THUMBPRINT, null)
        check(existing == null || existing == thumbprint) { "Stored device identity does not match Keystore" }
        preferences.edit().putString(KEY_THUMBPRINT, thumbprint).apply()
    }

    @Synchronized
    fun markActive(response: RegistrationResponse, thumbprint: String) {
        preferences.edit()
            .putString(STATUS, BackendRegistrationStatus.ACTIVE.name)
            .putString(INSTALLATION_ID, response.installationId)
            .putString(ROLE, response.role)
            .putString(KEY_THUMBPRINT, thumbprint)
            .putInt(POLICY_VERSION, response.policyVersion)
            .remove(PENDING_ACTIVATION_KEY)
            .remove(LAST_ERROR_CODE)
            .apply()
    }

    @Synchronized
    fun markFailure(status: BackendRegistrationStatus, errorCode: String, discardActivationKey: Boolean) {
        preferences.edit()
            .putString(STATUS, status.name)
            .putString(LAST_ERROR_CODE, errorCode.take(80))
            .also { if (discardActivationKey) it.remove(PENDING_ACTIVATION_KEY) }
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "backend_registration_secure_store"
        const val PENDING_ACTIVATION_KEY = "pending_activation_key"
        const val STATUS = "status"
        const val INSTALLATION_ID = "installation_id"
        const val ROLE = "role"
        const val KEY_THUMBPRINT = "key_thumbprint"
        const val POLICY_VERSION = "policy_version"
        const val LAST_ERROR_CODE = "last_error_code"
    }
}
