package com.daniel.tvdeinsight.data.sync.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

sealed class DeviceKeyStoreException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Missing : DeviceKeyStoreException("Device signing key is missing")
    class Invalidated(cause: Throwable? = null) : DeviceKeyStoreException("Device signing key is invalidated", cause)
    class Unavailable(cause: Throwable) : DeviceKeyStoreException("Android Keystore is unavailable", cause)
}

@Singleton
class DeviceKeyStore @Inject constructor() {
    @Synchronized
    fun loadOrCreate(allowCreate: Boolean): KeyPair {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (existing != null) return KeyPair(existing.certificate.publicKey, existing.privateKey)
            if (!allowCreate) throw DeviceKeyStoreException.Missing()
            generate()
        } catch (error: DeviceKeyStoreException) {
            throw error
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw DeviceKeyStoreException.Invalidated(error)
        } catch (error: UnrecoverableKeyException) {
            throw DeviceKeyStoreException.Invalidated(error)
        } catch (error: Exception) {
            throw DeviceKeyStoreException.Unavailable(error)
        }
    }

    fun containsKey(): Boolean = try {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.containsAlias(KEY_ALIAS)
    } catch (_: Exception) {
        false
    }

    private fun generate(): KeyPair {
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).run {
            initialize(specification)
            generateKeyPair()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tvde_backend_signing_v1"
    }
}
