package com.daniel.tvdeinsight.license

import com.daniel.tvdeinsight.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildLicenseConfigurationTest {
    @Test
    fun `client verification public key is configured`() {
        assertTrue(BuildConfig.LICENSE_PUBLIC_KEY_BASE64.isNotBlank())
    }

    @Test
    fun `admin private key generates keys accepted by the configured client public key`() {
        if (!BuildConfig.IS_ADMIN_APP) return
        assertTrue(BuildConfig.ADMIN_LICENSE_PRIVATE_KEY_BASE64.isNotBlank())
        val androidId = "0011223344556677"
        val token = ActivationKeyCrypto.generate(
            androidId = androidId,
            expiresAtMillis = 1_900_000_000_000L,
            licenseType = LicenseType.SUBSCRIPTION,
            privateKeyBase64 = BuildConfig.ADMIN_LICENSE_PRIVATE_KEY_BASE64
        )

        assertTrue(
            ActivationKeyCrypto.validate(
                activationKey = token,
                expectedAndroidId = androidId,
                nowMillis = 1_800_000_000_000L,
                publicKeyBase64 = BuildConfig.LICENSE_PUBLIC_KEY_BASE64
            ).isValid
        )
    }
}
