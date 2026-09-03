package com.daniel.tvdeinsight.domain.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMapsRouteUrlBuilderTest {
    @Test fun `builds route from offer location through pickup to destination`() {
        val url = requireNotNull(
            GoogleMapsRouteUrlBuilder.build(
                originAtOffer = "41.157944,-8.629105",
                pickup = "Rua da Recolha 1, Porto, Portugal",
                destination = "Rua do Destino 2, Matosinhos, Portugal"
            )
        )

        assertTrue(url.contains("origin=41.157944%2C-8.629105"))
        assertTrue(url.contains("waypoints=Rua+da+Recolha+1%2C+Porto"))
        assertTrue(url.contains("destination=Rua+do+Destino+2%2C+Matosinhos"))
        assertFalse(url.contains("Portugal"))
    }

    @Test fun `falls back to pickup for records without offer location`() {
        val url = requireNotNull(GoogleMapsRouteUrlBuilder.build(null, "Recolha", "Destino"))
        assertTrue(url.contains("origin=Recolha"))
        assertFalse(url.contains("waypoints="))
    }

    @Test fun `requires pickup and destination`() {
        assertNull(GoogleMapsRouteUrlBuilder.build("Origem", null, "Destino"))
        assertNull(GoogleMapsRouteUrlBuilder.build("Origem", "Recolha", null))
    }
}
