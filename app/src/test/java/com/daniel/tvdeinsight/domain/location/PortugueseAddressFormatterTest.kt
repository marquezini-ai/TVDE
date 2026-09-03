package com.daniel.tvdeinsight.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortugueseAddressFormatterTest {
    @Test fun `removes only a trailing country`() {
        assertEquals(
            "Avenida da República 451, Matosinhos",
            PortugueseAddressFormatter.withoutCountry("Avenida da República 451, Matosinhos, Portugal")
        )
        assertEquals(
            "Portugal Norte Shopping, Senhora da Hora",
            PortugueseAddressFormatter.withoutCountry("Portugal Norte Shopping, Senhora da Hora")
        )
    }

    @Test fun `extracts the last locality before the first number`() {
        assertEquals("Porto", PortugueseAddressFormatter.lastLocalityBeforeNumber("Av Brasil 150, Porto 4150-155"))
        assertEquals("Porto", PortugueseAddressFormatter.lastLocalityBeforeNumber("Local, Campanhã, Porto"))
        assertNull(PortugueseAddressFormatter.lastLocalityBeforeNumber("Rua sem vírgula Porto"))
        assertNull(PortugueseAddressFormatter.lastLocalityBeforeNumber("Rua A, 4000-098 Porto"))
    }
}
