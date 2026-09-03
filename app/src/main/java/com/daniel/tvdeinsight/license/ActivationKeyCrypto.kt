package com.daniel.tvdeinsight.license

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

enum class LicenseType(val label: String) {
    TRIAL("Experimental"),
    SUBSCRIPTION("Subscrição"),
    CUSTOM("Personalizada")
}

data class ActivationPayload(
    val androidId: String,
    val expiresAtMillis: Long,
    val licenseType: LicenseType
)

enum class LicenseValidationError {
    EMPTY_KEY,
    INVALID_FORMAT,
    INVALID_PAYLOAD,
    INVALID_SIGNATURE,
    DEVICE_MISMATCH,
    EXPIRED,
    PUBLIC_KEY_NOT_CONFIGURED,
    PRIVATE_KEY_NOT_CONFIGURED
}

data class LicenseValidation(
    val payload: ActivationPayload? = null,
    val error: LicenseValidationError? = null
) {
    val isValid: Boolean get() = payload != null && error == null
}

/**
 * Chave compacta para envio por texto: Base64URL(payload).Base64URL(assinatura).
 * A assinatura ECDSA usa SHA-256; o Cliente contém somente a chave pública.
 */
object ActivationKeyCrypto {
    private const val PAYLOAD_VERSION = "v1"
    private val androidIdPattern = Regex("^[A-Fa-f0-9]{8,64}$")
    private val activationKeyPartPattern = Regex("^[A-Za-z0-9_-]{20,}$")

    /** Indica se o texto tem o formato compacto esperado antes de tentar ativá-lo. */
    fun looksLikeActivationKey(activationKey: String): Boolean {
        val parts = activationKey.trim().split('.', limit = 2)
        return parts.size == 2 && parts.all(activationKeyPartPattern::matches)
    }

    fun generate(
        androidId: String,
        expiresAtMillis: Long,
        licenseType: LicenseType,
        privateKeyBase64: String
    ): String {
        val normalizedId = normalizeAndroidId(androidId)
            ?: throw IllegalArgumentException("ANDROID_ID inválido")
        require(expiresAtMillis > 0L) { "Data de expiração inválida" }
        val privateKey = privateKeyBase64.toPrivateKey()
        val payload = listOf(PAYLOAD_VERSION, normalizedId, expiresAtMillis, licenseType.name)
            .joinToString("|")
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(payloadBytes)
        val signature = signer.sign()
        return "${payloadBytes.toBase64Url()}.${signature.toBase64Url()}"
    }

    fun validate(
        activationKey: String,
        expectedAndroidId: String,
        nowMillis: Long,
        publicKeyBase64: String
    ): LicenseValidation {
        if (activationKey.isBlank()) return LicenseValidation(error = LicenseValidationError.EMPTY_KEY)
        val expectedId = normalizeAndroidId(expectedAndroidId)
            ?: return LicenseValidation(error = LicenseValidationError.INVALID_PAYLOAD)
        val parts = activationKey.trim().split('.', limit = 2)
        if (parts.size != 2 || parts.any(String::isBlank)) {
            return LicenseValidation(error = LicenseValidationError.INVALID_FORMAT)
        }

        val payloadBytes = runCatching { parts[0].fromBase64Url() }
            .getOrElse { return LicenseValidation(error = LicenseValidationError.INVALID_FORMAT) }
        val signatureBytes = runCatching { parts[1].fromBase64Url() }
            .getOrElse { return LicenseValidation(error = LicenseValidationError.INVALID_FORMAT) }
        // Rejeita representações Base64URL não canónicas. Sem isto, alterar apenas
        // os bits de preenchimento do último carácter pode produzir os mesmos bytes.
        if (payloadBytes.toBase64Url() != parts[0] || signatureBytes.toBase64Url() != parts[1]) {
            return LicenseValidation(error = LicenseValidationError.INVALID_FORMAT)
        }
        val payload = parsePayload(payloadBytes) ?: return LicenseValidation(error = LicenseValidationError.INVALID_PAYLOAD)
        val publicKey = runCatching { publicKeyBase64.toPublicKey() }
            .getOrElse { return LicenseValidation(error = LicenseValidationError.PUBLIC_KEY_NOT_CONFIGURED) }

        val verifier = runCatching {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey)
                update(payloadBytes)
            }.verify(signatureBytes)
        }.getOrDefault(false)
        if (!verifier) return LicenseValidation(error = LicenseValidationError.INVALID_SIGNATURE)
        if (payload.androidId != expectedId) return LicenseValidation(error = LicenseValidationError.DEVICE_MISMATCH)
        if (nowMillis > payload.expiresAtMillis) return LicenseValidation(error = LicenseValidationError.EXPIRED)
        return LicenseValidation(payload = payload)
    }

    fun expirationFromDays(nowMillis: Long, days: Int): Long {
        require(days in 1..3_650) { "Período de licença inválido" }
        return nowMillis + days * MILLIS_PER_DAY
    }

    private fun parsePayload(bytes: ByteArray): ActivationPayload? {
        val values = bytes.toString(Charsets.UTF_8).split('|')
        if (values.size != 4 || values[0] != PAYLOAD_VERSION) return null
        val androidId = normalizeAndroidId(values[1]) ?: return null
        val expiration = values[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val type = LicenseType.entries.firstOrNull { it.name == values[3] } ?: return null
        return ActivationPayload(androidId, expiration, type)
    }

    private fun normalizeAndroidId(value: String): String? =
        value.trim().lowercase().takeIf(androidIdPattern::matches)

    private fun String.toPrivateKey(): PrivateKey {
        require(isNotBlank()) { "Chave privada não configurada" }
        val bytes = Base64.getDecoder().decode(trim())
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    private fun String.toPublicKey(): PublicKey {
        require(isNotBlank()) { "Chave pública não configurada" }
        val bytes = Base64.getDecoder().decode(trim())
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    private fun ByteArray.toBase64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun String.fromBase64Url(): ByteArray = Base64.getUrlDecoder().decode(this)

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
}
