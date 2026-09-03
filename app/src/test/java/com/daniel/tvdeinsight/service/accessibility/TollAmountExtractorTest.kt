package com.daniel.tvdeinsight.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class TollAmountExtractorTest {

    @Test fun `reads a Bolt toll formatted with a dot separator`() {
        assertEquals(0.40, TollAmountExtractor.extract("Portagem • 0,40 €"), 0.0)
    }

    @Test fun `reads a Bolt toll formatted with euros written out`() {
        assertEquals(1.25, TollAmountExtractor.extract("Portagem 1,25 euros"), 0.0)
    }
}
