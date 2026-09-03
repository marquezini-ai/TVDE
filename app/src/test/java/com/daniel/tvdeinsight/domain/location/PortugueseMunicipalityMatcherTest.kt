package com.daniel.tvdeinsight.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortugueseMunicipalityMatcherTest {
    private val matcher = PortugueseMunicipalityCatalog.matcher

    @Test fun `recognizes municipality after comma regardless of accents case and postal code`() {
        assertEquals("Valongo", matcher.findInPickupAddress("Bairro do Calvário 300, VALONGO"))
        assertEquals("Gondomar", matcher.findInPickupAddress("Rua Alto de Barreiros 1395, GOndomar"))
        assertEquals("Porto", matcher.findInPickupAddress("Rua da Constituição 768, Porto 4200-195"))
        assertEquals("Ílhavo", matcher.findInPickupAddress("Rua Central 1, ilhavo 3830-000"))
    }

    @Test fun `uses the last comma and prefers the most specific municipality name`() {
        assertEquals("Porto", matcher.findInPickupAddress("Rua Central 10, Maia, Porto"))
        assertEquals("Porto de Mós", matcher.findInPickupAddress("Rua do Castelo 2, Porto de Mos"))
        assertEquals("Vila Real de Santo António", matcher.findInPickupAddress("Rua A 1, Vila Real de Santo Antonio"))
        assertEquals("Santa Cruz das Flores", matcher.findInPickupAddress("Rua B 2, Santa Cruz das Flores"))
    }

    @Test fun `discards ambiguous municipalities without the catalog qualifier`() {
        assertNull(matcher.findInPickupAddress("Rua C 3, Calheta"))
        assertNull(matcher.findInPickupAddress("Rua B 2, Lagoa"))
    }

    @Test fun `discards street only unknown and missing municipality`() {
        assertNull(matcher.findInPickupAddress("Rua de Cândido dos Reis"))
        assertNull(matcher.findInPickupAddress("Rua Central 1, Localidade Inventada"))
        assertNull(matcher.findInPickupAddress("Rua sem localidade, "))
        assertNull(matcher.findInPickupAddress(null))
    }

    @Test fun `follows representative addresses from the supplied workbook`() {
        assertEquals("Matosinhos", matcher.findInPickupAddress("Alameda da Azenha de Cima 52, Matosinhos"))
        assertEquals("Porto", matcher.findInPickupAddress("Av Brasil 150, Porto 4150-155"))
        assertEquals("Porto", matcher.findInPickupAddress("Av. Fernão de Magalhães, 7, Porto"))
        assertEquals("Matosinhos", matcher.findInPickupAddress("Avenida da República 451, Matosinhos, Portugal"))
        assertEquals("Porto", matcher.findInPickupAddress("Brasileiro de Pereiró, Ramalde, Porto"))
        assertEquals("Póvoa de Varzim", matcher.findInPickupAddress("Rua Tenente Valadim 20, Póvoa de Varzim"))
        assertNull(matcher.findInPickupAddress("Av Dr Manuel Teixeira Ruela 72, Senhora da Hora 4460-362"))
        assertNull(matcher.findInPickupAddress("Av. Vimara Peres, 4000-098 Porto, Portugal"))
        assertNull(matcher.findInPickupAddress("Vila Nova de Gaia"))
        assertNull(matcher.findInPickupAddress("Avenida da República 1956, Vila Nova de"))
    }
}
