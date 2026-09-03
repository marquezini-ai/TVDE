package com.daniel.tvdeinsight.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UberOfferParserTest {
    private val parser = UberOfferParser()

    @Test fun `parses an offer using Portuguese decimal values`() {
        val offer = parser.parse("8,20 €\n5,4 km\n14 min\nInclui 1 paragem")

        requireNotNull(offer)
        assertEquals(8.20, offer.price, 0.0)
        assertEquals(5.4, offer.distanceKm, 0.0)
        assertEquals(14.0, offer.durationMinutes, 0.0)
        assertEquals("8,20 €\n5,4 km\n14 min\nInclui 1 paragem", offer.additionalInfo)
    }

    @Test fun `does not create an incomplete offer`() {
        assertNull(parser.parse("8,20 €\n5,4 km"))
    }

    @Test fun `parses a value when the currency symbol comes first`() {
        val offer = parser.parse("€ 8,20\n5,4 km\n14 minutos")

        requireNotNull(offer)
        assertEquals(8.20, offer.price, 0.0)
    }

    @Test fun `adds pickup and passenger-trip metrics from an Uber offer`() {
        val offer = parser.parse(
            "UberX Priority\n€ 4,87\n4 minutos (1.8 km) de distância\n" +
                "Viagem de 12 minutos (5.6 km)"
        )

        requireNotNull(offer)
        assertEquals(4.87, offer.price, PRECISION)
        assertEquals(7.4, offer.distanceKm, PRECISION)
        assertEquals(16.0, offer.durationMinutes, PRECISION)
        assertEquals(1.8, offer.pickupDistanceKm!!, PRECISION)
        assertEquals(5.6, offer.tripDistanceKm!!, PRECISION)
    }

    @Test fun `recovers l glyphs in OCR duration tokens`() {
        val offer = parser.parse(
            "UberX Priority\n€ 4,99\n5 minutos (1.8 km) de distância\n" +
                "Viagem de ll minutos (3.7 km)\nSelecionar"
        )

        requireNotNull(offer)
        assertEquals(4.99, offer.price, PRECISION)
        assertEquals(16.0, offer.durationMinutes, PRECISION)
        assertEquals(5.5, offer.distanceKm, PRECISION)
    }

    @Test fun `accepts Select card when OCR drops k from destination km`() {
        val offer = parser.parse(
            "UberX\n€ 9,64\n7 minutos (2.2 km) de distância\n" +
                "Viagem de 22 minutos (17.4 m)\nSelecionar"
        )

        requireNotNull(offer)
        assertEquals(2.2, offer.pickupDistanceKm!!, PRECISION)
        assertEquals(17.4, offer.tripDistanceKm!!, PRECISION)
        assertEquals(19.6, offer.distanceKm, PRECISION)
    }

    @Test fun `accepts exclusive card with Aceitar marker`() {
        val offer = parser.parse(
            "UberX Exclusivo\n€ 3,37\n4 minutos (1.4 km)\n" +
                "Viagem de 7 minutos (3.2 km)\nAceitar"
        )

        requireNotNull(offer)
        assertEquals(3.37, offer.price, PRECISION)
        assertEquals(4.6, offer.distanceKm, PRECISION)
    }

    @Test fun `keeps the pickup distance separate from the destination distance`() {
        val offer = parser.parse(
            "UberX\n€ 2,85\n1 min (0.2 km) de distância\n" +
                "C. C. Maiashopping, Maia\nViagem de 5 minutos (1.6 km)\nRua Dr. Egas Moniz 115"
        )

        requireNotNull(offer)
        assertEquals(0.2, offer.pickupDistanceKm!!, PRECISION)
        assertEquals(1.6, offer.tripDistanceKm!!, PRECISION)
        assertEquals(1.8, offer.distanceKm, PRECISION)
        assertEquals(6.0, offer.durationMinutes, PRECISION)
    }

    @Test fun `rejects a partial Uber card with only the passenger trip segment`() {
        val offer = parser.parse(
            "UberX\nâ‚¬ 3,40\nViagem de 5 minutos (2.4 km)\nRua de Serpa Pinto 80, Maia"
        )

        assertNull(offer)
    }

    @Test fun `recovers a lost decimal separator only when speed would be impossible`() {
        val offer = parser.parse(
            "UberX\n€ 2,85\n1 min (0.2 km) de distância\n" +
                "Viagem de 5 minutos (16 km)"
        )

        requireNotNull(offer)
        assertEquals(1.6, offer.tripDistanceKm!!, PRECISION)
        assertEquals(1.8, offer.distanceKm, PRECISION)
    }

    @Test fun `rejects an OCR distance that would require an impossible speed`() {
        assertNull(
            parser.parse(
                "€ 4,27 Após dedução de taxa de serviço\n" +
                "15 minutos (999 km) de distância\n" +
                    "Viagem de 9 minutos (3,7 km)"
            )
        )
    }

    private companion object { const val PRECISION = 0.0001 }
}
