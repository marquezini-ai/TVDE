package com.daniel.tvdeinsight.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryNameSanitizerTest {
    @Test
    fun removesPromotionalLabelsWithoutChangingTheCategory() {
        assertEquals("UberX Priority", CategoryNameSanitizer.clean("UberX Priority Exclusivo"))
        assertEquals("Comfort", CategoryNameSanitizer.clean("ii Comfort Exclusive"))
    }

    @Test
    fun returnsNullWhenOnlyPromotionalLabelsAreRead() {
        assertNull(CategoryNameSanitizer.clean("Exclusivo ii"))
    }
}
