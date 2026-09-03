package com.daniel.tvdeinsight.service.ocr

import com.daniel.tvdeinsight.service.accessibility.UberOfferParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UberOfferCardTextExtractorTest {
    private val extractor = UberOfferCardTextExtractor()

    @Test
    fun `ignores priority bonus and reads both route legs below it`() {
        val text = extractor.extract(
            listOf(
                block("€ 0,72 por km", 20),
                block("UberX Priority", 400),
                block("€ 4,11", 455),
                block("Após dedução de taxa de serviço", 525),
                block("+€ 0,74 incluído para embarque", 590),
                block("3 minutos (0.9 km) de distância", 675),
                block("Travessa do Veloso 34, Porto", 725),
                block("Viagem de 15 minutos (4.8 km)", 790),
                block("Rua de Sá da Bandeira 304, Porto", 840),
                block("< de 1 km do carregamento rápido", 920)
            )
        )

        requireNotNull(text)
        assertTrue(text.contains("€ 4,11"))
        assertTrue(text.contains("3 minutos (0.9 km)"))
        assertTrue(text.contains("Viagem de 15 minutos (4.8 km)"))
        assertFalse(text.contains("0,72 por km"))
        assertFalse(text.contains("incluído para embarque"))

        val offer = UberOfferParser().parse(text)
        requireNotNull(offer)
        assertTrue(offer.distanceKm == 5.7)
        assertTrue(offer.durationMinutes == 18.0)
    }

    @Test
    fun `uses the next section directly for a normal offer`() {
        val text = extractor.extract(
            listOf(
                block("UberX", 300),
                block("€ 6,34", 350),
                block("Após dedução de taxa de serviço", 420),
                block("11 minutos (8.5 km) de distância", 510),
                block("Rua das Arroteias 85, Gondomar", 560),
                block("Viagem de 15 minutos (9.3 km)", 640),
                block("Avenida da República 1098", 700)
            )
        )

        requireNotNull(text)
        assertTrue(text.contains("€ 6,34"))
        assertTrue(text.contains("11 minutos (8.5 km)"))
        assertTrue(text.contains("Viagem de 15 minutos (9.3 km)"))
    }

    @Test
    fun `rejects an incomplete route instead of allowing a wrong calculation`() {
        val text = extractor.extract(
            listOf(
                block("UberX", 300),
                block("€ 4,62", 350),
                block("Após dedução de taxa de serviço", 420),
                block("7 minutos (3.5 km) de distância", 510)
            )
        )

        assertNull(text)
    }

    @Test
    fun `reads a card when the service fee anchor is split into OCR lines`() {
        val text = extractor.extract(
            listOf(
                block("UberX", 300),
                block("€ 4,68", 350),
                block("Após dedução de taxa", 420),
                block("de serviço", 460),
                block("8 minutos (4.5 km) de distância", 520),
                block("Viagem de 11 minutos (5.3 km)", 610)
            )
        )

        requireNotNull(text)
        val offer = UberOfferParser().parse(text)
        requireNotNull(offer)
        assertTrue(offer.distanceKm == 9.8)
        assertTrue(offer.durationMinutes == 19.0)
    }

    @Test fun `reads the full UberX card layout from the supplied examples`() {
        val text = extractor.extract(
            listOf(
                block("UberX Priority", 300),
                block("€ 3,98", 360),
                block("Após dedução de taxa de serviço", 430),
                block("+€ 0,74 incluído para embarque", 495),
                block("1 min (0.4 km) de distância", 570),
                block("Avenida do Doutor Manuel Teixeira Ruela, Matosinhos", 635),
                block("Viagem de 11 minutos (4.8 km)", 720),
                block("Rua de Roberto Ivens 717, Matosinhos", 785)
            )
        )

        requireNotNull(text)
        assertFalse(text.contains("incluído para embarque"))
        val offer = UberOfferParser().parse(text)
        requireNotNull(offer)
        assertTrue(offer.price == 3.98)
        assertTrue(offer.pickupDistanceKm == 0.4)
        assertTrue(offer.tripDistanceKm == 4.8)
        assertTrue(offer.pickupAddress == "Avenida do Doutor Manuel Teixeira Ruela, Matosinhos")
        assertTrue(offer.destinationAddress == "Rua de Roberto Ivens 717, Matosinhos")
        assertTrue(offer.category == "UberX Priority")
    }

    @Test fun `falls back to the route structure when service fee text is not recognized`() {
        val text = extractor.extract(
            listOf(
                block("UberX", 300),
                block("€ 2,85", 360),
                block("1 min (0.3 km) de distância", 500),
                block("Rua de João Rosa 181, Matosinhos", 560),
                block("Viagem de 7 minutos (2.6 km)", 650),
                block("Travessa Central do Seixo 158, Matosinhos", 710)
            )
        )

        requireNotNull(text)
        val offer = UberOfferParser().parse(text)
        requireNotNull(offer)
        assertTrue(offer.price == 2.85)
        assertTrue(offer.category == "UberX")
    }

    @Test fun `reads a route split across adjacent OCR lines`() {
        val text = extractor.extract(
            listOf(
                block("UberX", 300),
                block("€ 7,20", 360),
                block("Após dedução de taxa de serviço", 420),
                block("5 min", 500),
                block("(1,5 km) de distância", 545),
                block("Viagem 18 min", 630),
                block("(9,7 km)", 675)
            )
        )

        requireNotNull(text)
        val offer = UberOfferParser().parse(text)
        requireNotNull(offer)
        assertTrue(offer.price == 7.20)
        assertTrue(offer.distanceKm == 11.2)
        assertTrue(offer.durationMinutes == 23.0)
    }

    @Test fun `reads a route split across three OCR lines`() {
        val text = extractor.extract(
            listOf(
                block("UberX", 300),
                block("€ 5,20", 360),
                block("Após dedução de taxa de serviço", 420),
                block("8 minutos", 500),
                block("(4.5", 545),
                block("km) de distância", 590),
                block("Viagem de 7 minutos", 680),
                block("(3.2", 725),
                block("km)", 770)
            )
        )

        requireNotNull(text)
        val offer = UberOfferParser().parse(text)
        requireNotNull(offer)
        assertTrue(offer.pickupDistanceKm == 4.5)
        assertTrue(offer.tripDistanceKm == 3.2)
    }

    @Test fun `reads a route split across four OCR lines`() {
        val text = extractor.extract(
            listOf(
                block("UberX", 300),
                block("€ 5,20", 360),
                block("Após dedução de taxa de serviço", 420),
                block("8 minutos", 500),
                block("(4.5", 545),
                block("km) de", 590),
                block("distância", 635),
                block("Viagem de", 710),
                block("7 minutos", 755),
                block("(3.2", 800),
                block("km)", 845)
            )
        )

        requireNotNull(text)
        val offer = UberOfferParser().parse(text)
        requireNotNull(offer)
        assertTrue(offer.pickupDistanceKm == 4.5)
        assertTrue(offer.tripDistanceKm == 3.2)
    }

    @Test fun `tolerates overlapping OCR bounds around the service fee anchor`() {
        val text = extractor.extract(
            listOf(
                block("UberX", 300),
                block("€ 5,43", 390), // bottom 430; overlaps the anchor at 420
                block("Após dedução de taxa", 420),
                block("de serviço", 470), // bottom 510
                block("9 minutos (3.8 km) de distância", 490), // starts before 510
                block("Rua do Pinheiro Manso 817, Porto", 550),
                block("Viagem de 7 minutos (2.6 km)", 620),
                block("Rua Sara Afonso, Senhora da Hora", 680)
            )
        )

        requireNotNull(text)
        val offer = UberOfferParser().parse(text)
        requireNotNull(offer)
        assertTrue(offer.price == 5.43)
        assertTrue(offer.pickupDistanceKm == 3.8)
        assertTrue(offer.tripDistanceKm == 2.6)
    }

    @Test fun `recovers anchored price when OCR omits the euro symbol`() {
        val text = extractor.extract(
            listOf(
                block("UberX", 300),
                block("5,43", 360),
                block("Após dedução de taxa de serviço", 420),
                block("9 minutos (3.8 km) de distância", 510),
                block("Viagem de 7 minutos (2.6 km)", 610)
            )
        )

        requireNotNull(text)
        assertTrue(text.contains("€ 5,43"))
        assertTrue(UberOfferParser().parse(text)?.price == 5.43)
    }

    @Test fun `keeps up to four destination address lines and stops before controls`() {
        val text = extractor.extract(
            listOf(
                block("UberX", 300),
                block("€ 9,40", 360),
                block("Após dedução de taxa de serviço", 420),
                block("4 minutos (1.2 km) de distância", 500),
                block("Rua da Recolha 1, Porto", 550),
                block("Viagem de 20 minutos (12.4 km)", 620),
                block("Avenida Infante", 675),
                block("Dom Henrique 100", 720),
                block("Vila Nova de Gaia", 765),
                block("Portugal", 810),
                block("Selecionar", 870)
            )
        )

        requireNotNull(text)
        assertTrue(text.contains("Avenida Infante"))
        assertTrue(text.contains("Vila Nova de Gaia"))
        assertFalse(text.contains("Selecionar"))
    }

    @Test fun `recognizes partial offer signals for an immediate retry`() {
        assertTrue(extractor.resemblesOffer("Após dedução de taxa de serviço\nViagem de 7 minutos (2.6 km)"))
        assertFalse(extractor.resemblesOffer("Rua de Santa Catarina, Porto"))
    }

    @Test fun `reads the Electric card layout and its two route legs`() {
        val text = extractor.extract(
            listOf(
                block("Electric", 320),
                block("€ 5,43", 380),
                block("Após dedução de taxa de serviço", 450),
                block("9 minutos (3.8 km) de distância", 560),
                block("Rua do Pinheiro Manso 817, Porto", 625),
                block("Viagem de 7 minutos (2.6 km)", 710),
                block("Rua Sara Afonso, Senhora da Hora", 775),
                block("Selecionar", 900)
            )
        )

        requireNotNull(text)
        val offer = UberOfferParser().parse(text)
        requireNotNull(offer)
        assertTrue(offer.price == 5.43)
        assertTrue(offer.pickupDistanceKm == 3.8)
        assertTrue(offer.tripDistanceKm == 2.6)
        assertTrue(offer.category == "Electric")
    }

    @Test fun `recovers a Priority price without decimal and never uses the pickup bonus`() {
        val card = extractor.extractCard(
            listOf(
                block("UberX Priority", 300),
                block("€705", 360),
                block("Após dedução de taxa de serviço", 430),
                block("€ 0,85", 495),
                block("incluído para embarque", 535),
                block("4 minutos (1.5 km) de distância", 610),
                block("Porto", 660),
                block("Viagem de 20 minutos (9.7 km)", 730),
                block("Rua Alexandre Herculano 79, Vila Nova de Gaia", 790)
            )
        )

        requireNotNull(card)
        assertTrue(card.text.contains("€ 7,05"))
        assertEquals("UberX Priority", card.category)
        val offer = UberOfferParser().parse(card.text)
        requireNotNull(offer)
        assertEquals(7.05, offer.price, 0.0)
        assertEquals(11.2, offer.distanceKm, 0.0001)
        assertEquals(24.0, offer.durationMinutes, 0.0001)
    }

    @Test fun `leaves unknown category empty instead of inventing a category`() {
        val card = extractor.extractCard(
            listOf(
                block("Berline Premium Locale X", 300),
                block("€ 8,40", 360),
                block("Após dedução de taxa de serviço", 430),
                block("3 minutos (1.2 km) de distância", 510),
                block("Viagem de 14 minutos (7.4 km)", 620)
            )
        )

        requireNotNull(card)
        assertNull(card.category)
    }

    @Test fun `keeps the current card isolated from the previous TVDE overlay`() {
        val card = extractor.extractCard(
            listOf(
                block("UberX Priority", 120),
                block("REJEITAR (MÍNIMO)", 170),
                block("€ 0,63 por km livre", 220),
                block("€ 18,4 por hora", 270),
                block("€ 4,96 valor líquido", 320),
                block("Comfort X", 700),
                block("€ 5,52", 760),
                block("Após dedução de taxa de serviço", 830),
                block("8 minutos (3.6 km) de distância", 920),
                block("Rua do Monte Branco 267, Gondomar", 980),
                block("Viagem de 10 minutos (4.3 km)", 1050),
                block("Rua da Fábrica 116, Gondomar", 1110)
            )
        )

        requireNotNull(card)
        assertEquals("Comfort", card.category)
        assertFalse(card.text.contains("4,96"))
        val offer = UberOfferParser().parse(card.text)
        requireNotNull(offer)
        assertEquals(5.52, offer.price, 0.0)
        assertEquals(7.9, offer.distanceKm, 0.0001)
        assertEquals(18.0, offer.durationMinutes, 0.0001)
    }

    @Test fun `does not require complete addresses when both route metrics exist`() {
        val card = extractor.extract(
            listOf(
                block("Qualquer Categoria", 300),
                block("€ 7,05", 360),
                block("Após dedução de taxa de serviço", 430),
                block("4 minutos (1.5 km) de distância", 520),
                block("Viagem de 20 minutos (9.7 km)", 620)
            )
        )

        requireNotNull(card)
        val offer = UberOfferParser().parse(card)
        requireNotNull(offer)
        assertEquals(11.2, offer.distanceKm, 0.0001)
        assertEquals(24.0, offer.durationMinutes, 0.0001)
    }

    @Test fun `reads a long destination written with hours and minutes`() {
        val card = extractor.extractCard(
            listOf(
                block("UberX", 300),
                block("€ 57,21", 360),
                block("Após dedução de taxa de serviço", 430),
                block("4 minutos (1.8 km) de distância", 520),
                block("Rua do Padre Rebelo da Costa 96, Porto", 580),
                block("Viagem de 1 h e 51 min (153.9 km)", 650),
                block("Rua do Rosal 2D, Vigo", 720)
            )
        )

        requireNotNull(card)
        assertEquals("UberX", card.category)
        val offer = UberOfferParser().parse(card.text)
        requireNotNull(offer)
        assertEquals(1.8, offer.pickupDistanceKm!!, 0.0001)
        assertEquals(153.9, offer.tripDistanceKm!!, 0.0001)
        assertEquals(111.0, offer.tripDurationMinutes!!, 0.0001)
    }

    @Test fun `recovers OCR l used instead of one in a long duration`() {
        val card = extractor.extractCard(
            listOf(
                block("UberX", 300),
                block("€ 57,21", 360),
                block("Após dedução de taxa de serviço", 430),
                block("4 minutos (1.8 km) de distância", 520),
                block("Viagem de l h e 51 min (153.9 km)", 650)
            )
        )

        requireNotNull(card)
        val offer = UberOfferParser().parse(card.text)
        requireNotNull(offer)
        assertEquals(111.0, offer.tripDurationMinutes!!, 0.0001)
    }

    private fun block(text: String, top: Int) =
        UberOfferCardTextExtractor.OcrBlock(text = text, top = top, bottom = top + 40)
}
