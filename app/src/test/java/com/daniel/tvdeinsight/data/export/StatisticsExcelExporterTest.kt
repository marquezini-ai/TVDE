package com.daniel.tvdeinsight.data.export

import com.daniel.tvdeinsight.domain.model.DecisionType
import com.daniel.tvdeinsight.domain.model.EvaluationCriterion
import com.daniel.tvdeinsight.domain.model.OfferHistoryEntry
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsExcelExporterTest {

    @Test fun `exports every history detail with Excel escaping and the minimum value rejection reason`() {
        val xml = StatisticsExcelExporter.workbookXml(
            listOf(
                OfferHistoryEntry(
                    id = 1L,
                    recordedAtMillis = 1_786_132_800_000L,
                    platform = OfferPlatform.UBER,
                    valorPorKm = 0.80,
                    valorPorHora = 18.50,
                    valorPorKmBruto = 0.90,
                    netTripValue = 3.10,
                    pickupDistanceKm = 1.5,
                    destinationDistanceKm = 3.0,
                    tripValue = 3.00,
                    pickupDurationMinutes = 4.0,
                    destinationDurationMinutes = 8.0,
                    currentLocationAddress = "Rua do Motorista, Porto, Portugal",
                    currentLocationLatitude = 41.157944,
                    currentLocationLongitude = -8.629105,
                    pickupAddress = "Rua A & <B>, Porto, Portugal",
                    destinationAddress = "Rua C, Matosinhos, Portugal",
                    category = "UberX",
                    decisionType = DecisionType.REJEITAR,
                    activeCriteria = setOf(EvaluationCriterion.VALOR_MINIMO),
                    criterionDecisions = mapOf(EvaluationCriterion.VALOR_MINIMO to DecisionType.REJEITAR)
                )
            )
        )

        assertTrue(xml.contains("<?mso-application progid=\"Excel.Sheet\"?>"))
        assertTrue(xml.contains("Valor mínimo"))
        assertTrue(xml.contains("Rejeitada pelo valor mínimo."))
        assertTrue(xml.contains("Rua A &amp; &lt;B&gt;, Porto"))
        assertTrue(xml.contains("Rua do Motorista, Porto"))
        assertFalse(xml.contains(", Portugal"))
        assertTrue(xml.contains("41.157944"))
        assertTrue(xml.contains("€ 3,00"))
        assertTrue(xml.contains("1,50 km"))
        assertTrue(xml.contains("Localização no momento da oferta"))
        assertTrue(xml.contains("Endereço destino"))
        assertTrue(xml.contains("ss:Name=\"Resumo\""))
        assertTrue(xml.contains("ss:Name=\"Ofertas\""))
        assertTrue(xml.contains("ss:Name=\"Plataformas\""))
        assertTrue(xml.contains("ss:Name=\"Por horário\""))
        assertTrue(xml.contains("Km livre mediano"))
    }
}
