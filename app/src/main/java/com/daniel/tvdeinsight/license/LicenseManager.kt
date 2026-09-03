package com.daniel.tvdeinsight.license

import android.content.Context
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.daniel.tvdeinsight.BuildConfig
import com.daniel.tvdeinsight.logging.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LicenseStatus {
    NOT_ACTIVATED,
    VALID,
    EXPIRED,
    DEVICE_MISMATCH,
    CLOCK_ROLLBACK,
    INVALID_KEY,
    CRYPTO_NOT_CONFIGURED
}

data class LicenseState(
    val status: LicenseStatus,
    val androidId: String,
    val expiresAtMillis: Long? = null,
    val licenseType: LicenseType? = null
) {
    val isValid: Boolean get() = status == LicenseStatus.VALID
}

/**
 * Persiste a ativação em EncryptedSharedPreferences e recusa retrocessos no
 * relógio do dispositivo. A verificação criptográfica usa somente a chave
 * pública compilada no flavor Cliente.
 */
@Singleton
class LicenseManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val lock = Any()
    private val encryptedPreferences by lazy { createEncryptedPreferences() }
    private val _state = MutableStateFlow(LicenseState(LicenseStatus.NOT_ACTIVATED, deviceAndroidId()))
    val state: StateFlow<LicenseState> = _state.asStateFlow()

    init {
        runCatching { refresh() }
            .onFailure { AppLogger.warn("Não foi possível carregar a licença", it) }
    }

    fun deviceAndroidId(): String = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    ).orEmpty().trim().lowercase()

    fun refresh(nowMillis: Long = System.currentTimeMillis()): LicenseState = synchronized(lock) {
        val androidId = deviceAndroidId()
        val preferences = encryptedPreferences
        val lastSeen = preferences.getLong(LAST_EXECUTION_AT, 0L)
        if (lastSeen > 0L && nowMillis < lastSeen) {
            return@synchronized updateState(
                LicenseState(LicenseStatus.CLOCK_ROLLBACK, androidId)
            )
        }

        val key = preferences.getString(ACTIVATION_KEY, null)
            ?: return@synchronized updateState(LicenseState(LicenseStatus.NOT_ACTIVATED, androidId))
        val validation = ActivationKeyCrypto.validate(
            activationKey = key,
            expectedAndroidId = androidId,
            nowMillis = nowMillis,
            publicKeyBase64 = BuildConfig.LICENSE_PUBLIC_KEY_BASE64
        )
        val nextState = validation.toLicenseState(androidId)
        if (nextState.isValid) {
            preferences.edit().putLong(LAST_EXECUTION_AT, maxOf(lastSeen, nowMillis)).apply()
        }
        updateState(nextState)
    }

    fun activate(activationKey: String, nowMillis: Long = System.currentTimeMillis()): LicenseState = synchronized(lock) {
        val androidId = deviceAndroidId()
        val preferences = encryptedPreferences
        val lastSeen = preferences.getLong(LAST_EXECUTION_AT, 0L)
        if (lastSeen > 0L && nowMillis < lastSeen) {
            return@synchronized updateState(LicenseState(LicenseStatus.CLOCK_ROLLBACK, androidId))
        }

        val validation = ActivationKeyCrypto.validate(
            activationKey = activationKey,
            expectedAndroidId = androidId,
            nowMillis = nowMillis,
            publicKeyBase64 = BuildConfig.LICENSE_PUBLIC_KEY_BASE64
        )
        val nextState = validation.toLicenseState(androidId)
        if (nextState.isValid) {
            preferences.edit()
                .putString(ACTIVATION_KEY, activationKey.trim())
                .putLong(LAST_EXECUTION_AT, maxOf(lastSeen, nowMillis))
                .putLong(EXPIRES_AT, nextState.expiresAtMillis ?: 0L)
                .apply()
            AppLogger.info("Licença ativada: expira=${nextState.expiresAtMillis}, tipo=${nextState.licenseType}")
        } else {
            AppLogger.warn("Ativação recusada: estado=${nextState.status}")
        }
        updateState(nextState)
    }

    private fun updateState(newState: LicenseState): LicenseState {
        _state.value = newState
        return newState
    }

    private fun LicenseValidation.toLicenseState(androidId: String): LicenseState {
        val validPayload = payload
        if (validPayload != null && error == null) {
            return LicenseState(
                status = LicenseStatus.VALID,
                androidId = androidId,
                expiresAtMillis = validPayload.expiresAtMillis,
                licenseType = validPayload.licenseType
            )
        }
        return LicenseState(
            status = when (error) {
                LicenseValidationError.EXPIRED -> LicenseStatus.EXPIRED
                LicenseValidationError.DEVICE_MISMATCH -> LicenseStatus.DEVICE_MISMATCH
                LicenseValidationError.PUBLIC_KEY_NOT_CONFIGURED -> LicenseStatus.CRYPTO_NOT_CONFIGURED
                else -> LicenseStatus.INVALID_KEY
            },
            androidId = androidId
        )
    }

    private fun createEncryptedPreferences() = EncryptedSharedPreferences.create(
        context,
        PREFERENCES_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private companion object {
        const val PREFERENCES_NAME = "license_secure_store"
        const val ACTIVATION_KEY = "activation_key"
        const val EXPIRES_AT = "expires_at"
        const val LAST_EXECUTION_AT = "last_execution_at"
    }
}
