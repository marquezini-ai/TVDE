package com.daniel.tvdeinsight.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class BoltOfferParserTest {

    @Test fun `uses the labelText value only when it belongs to the Bolt category list`() {
        assertEquals(
            "Comfort",
            boltCategoryFromLabelTexts(listOf("", "  Business Comfort  "))
        )
        assertEquals(null, boltCategoryFromLabelTexts(listOf("Executivo especial")))
    }
}
