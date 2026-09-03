package com.daniel.tvdeinsight.service.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopDetectorTest {

    @Test fun `identifies Bolt stop labels in Portuguese and English`() {
        assertTrue(StopDetector.hasStops("Inclui 1 paragem"))
        assertTrue(StopDetector.hasStops("2 paradas"))
        assertTrue(StopDetector.hasStops("Multi destino"))
        assertTrue(StopDetector.hasStops("Multiple stops"))
    }

    @Test fun `identifies an extra Bolt route segment as a stop even without a label`() {
        assertTrue(StopDetector.hasStops("Rota da oferta", routeSegmentCount = 3))
        assertFalse(StopDetector.hasStops("Rota até ao destino", routeSegmentCount = 2))
    }
}
