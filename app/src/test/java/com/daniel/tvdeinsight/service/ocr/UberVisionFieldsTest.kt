package com.daniel.tvdeinsight.service.ocr

import org.junit.Assert.*
import org.junit.Test

class UberVisionFieldsTest {
    private fun text(fare: String = "€ 4,07", trip: String = "14 minutos (4.0 km)") = """
        UberX Priority Exclusivo
        $fare
        Após dedução de taxa de serviço
        +€ 0,74 incluído para embarque
        4 minutos (1.6 km) de distância
        Rua Manuel Pinto de Azevedo 461, Porto
        Viagem de $trip
        Rua de Calouste Gulbenkian 213, Porto
        < de 1 km do carregamento rápido
        Aceitar
    """.trimIndent()

    @Test fun `fare excludes included priority and charging distance`() {
        val fields = requireNotNull(UberVisionFields.parse(text()))
        assertEquals(4.07, fields.fare, 0.001)
        assertEquals(0.74, fields.priorityFee!!, 0.001)
        assertEquals(1.6, fields.pickupKm, 0.001)
        assertEquals(4.0, fields.tripKm, 0.001)
        assertEquals(14, fields.tripMinutes)
    }
    @Test fun `euro suffix arbitrary whitespace and comma distance`() {
        val fields = requireNotNull(UberVisionFields.parse(text("4,07  €").replace(" ", " \n\t").replace("1.6", "1,6")))
        assertEquals(4.07, fields.fare, 0.001)
        assertEquals(4, fields.pickupMinutes)
    }
    @Test fun `hour format remains supported`() {
        assertEquals(60, UberVisionFields.parse(text(trip = "1 h (53.0 km)"))!!.tripMinutes)
        assertEquals(90, UberVisionFields.parse(text(trip = "1 h 30 minutos (80.0 km)"))!!.tripMinutes)
    }
    @Test fun `optional bonus and selection button`() {
        val input = text().replace("+€ 0,74 incluído para embarque", "").replace("Aceitar", "Selecionar")
        assertNull(UberVisionFields.parse(input)!!.priorityFee)
    }
    @Test fun `incomplete frame is not repaired using bonus`() {
        assertNull(UberVisionFields.parse(text(fare = "")))
        assertNull(UberVisionFields.parse(text(trip = "(4.0 km)")))
        assertNull(UberVisionFields.parse(text().replace("4 minutos (1.6 km) de distância", "")))
    }
    @Test fun `bonus before fare is not selected as fare`() {
        assertEquals(4.07, UberVisionFields.parse("+€ 0,74 incluído\n" + text())!!.fare, 0.001)
    }
}
