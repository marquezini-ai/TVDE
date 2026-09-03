package com.daniel.tvdeinsight.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteAddressExtractorTest {

    @Test fun `extracts addresses between the pickup and destination route metrics`() {
        val addresses = RouteAddressExtractor.extract(
            """
            4 min (1,5 km) de distância
            Rua da Prata, Lisboa
            Viagem de 12 min (6,2 km)
            Avenida da Liberdade, Lisboa
            """.trimIndent()
        )

        assertEquals("Rua da Prata, Lisboa", addresses.pickup)
        assertEquals("Avenida da Liberdade, Lisboa", addresses.destination)
    }

    @Test fun `does not turn financial or decision text into an address`() {
        val addresses = RouteAddressExtractor.extract(
            """
            4 min (1,5 km) de distância
            Viagem de 12 min (6,2 km)
            Foco: € por hora
            """.trimIndent()
        )

        assertEquals(null, addresses.pickup)
        assertEquals(null, addresses.destination)
    }

    @Test fun `joins multiline addresses and stops before the card controls`() {
        val addresses = RouteAddressExtractor.extract(
            """
            4 min (1,5 km) de distância
            Avenida do Doutor Manuel
            Teixeira Ruela 72, Matosinhos
            Viagem de 12 min (6,2 km)
            Rua de Roberto Ivens 717
            Matosinhos
            Portugal
            Selecionar
            """.trimIndent()
        )

        assertEquals("Avenida do Doutor Manuel Teixeira Ruela 72, Matosinhos", addresses.pickup)
        assertEquals("Rua de Roberto Ivens 717 Matosinhos Portugal", addresses.destination)
    }
}
