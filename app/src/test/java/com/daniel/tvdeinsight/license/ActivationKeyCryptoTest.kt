package com.daniel.tvdeinsight.license

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationKeyCryptoTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val privateKey = Base64.getEncoder().encodeToString(keyPair.private.encoded)
    private val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    private val androidId = "a1b2c3d4e5f60708"

    @Test
    fun `valid signed key is accepted only for its device`() {
        val expiry = ActivationKeyCrypto.expirationFromDays(1_700_000_000_000L, 30)
        val key = ActivationKeyCrypto.generate(androidId, expiry, LicenseType.SUBSCRIPTION, privateKey)

        val result = ActivationKeyCrypto.validate(key, androidId, 1_700_000_000_001L, publicKey)

        assertTrue(result.isValid)
        assertEquals(LicenseType.SUBSCRIPTION, result.payload?.licenseType)
        assertFalse(ActivationKeyCrypto.validate(key, "0011223344556677", 1_700_000_000_001L, publicKey).isValid)
    }

    @Test
    fun `expired or altered keys are rejected`() {
        val key = ActivationKeyCrypto.generate(androidId, 1_700_000_000_000L, LicenseType.TRIAL, privateKey)

        assertEquals(
            LicenseValidationError.EXPIRED,
            ActivationKeyCrypto.validate(key, androidId, 1_700_000_000_001L, publicKey).error
        )
        val altered = key.dropLast(1) + if (key.last() == 'A') "B" else "A"
        assertFalse(ActivationKeyCrypto.validate(altered, androidId, 1_699_000_000_000L, publicKey).isValid)
    }

    @Test
    fun `activation key shape is recognised before validation`() {
        val key = ActivationKeyCrypto.generate(
            androidId,
            ActivationKeyCrypto.expirationFromDays(1_700_000_000_000L, 30),
            LicenseType.CUSTOM,
            privateKey
        )

        assertTrue(ActivationKeyCrypto.looksLikeActivationKey(key))
        assertFalse(ActivationKeyCrypto.looksLikeActivationKey("ainda não recebi uma chave"))
        assertFalse(ActivationKeyCrypto.looksLikeActivationKey("curto.demais"))
    }
}
