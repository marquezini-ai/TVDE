package com.daniel.tvdeinsight.service.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityProcessingModeTest {
    @Test
    fun `Android 12L and lower publish valid accessibility readings immediately`() {
        assertTrue(usesImmediateAccessibilityPublication(32))
        assertTrue(usesImmediateAccessibilityPublication(31))
    }

    @Test
    fun `Android 13 and higher retain the existing confirmation flow`() {
        assertFalse(usesImmediateAccessibilityPublication(33))
        assertFalse(usesImmediateAccessibilityPublication(36))
    }
}
