package com.daniel.tvdeinsight.license

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildLicenseConfigurationTest {
    @Test
    fun `client verification public key is configured`() {
        assertTrue(com.daniel.tvdeinsight.BuildConfig.LICENSE_PUBLIC_KEY_BASE64.isNotBlank())
    }
}
