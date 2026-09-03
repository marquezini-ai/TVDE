package com.daniel.tvdeinsight.service.accessibility

import com.daniel.tvdeinsight.domain.model.OfferPlatform
import org.junit.Assert.assertEquals
import org.junit.Test

class TripCategoryExtractorTest {

    @Test fun `recognizes every Uber category shown in the offer card`() {
        assertEquals("UberX", TripCategoryExtractor.extract("Oferta UberX", OfferPlatform.UBER))
        assertEquals("UberX", TripCategoryExtractor.extract("Uber X", OfferPlatform.UBER))
        assertEquals("UberX Priority", TripCategoryExtractor.extract("UberX Priority", OfferPlatform.UBER))
        assertEquals("UberX Priority", TripCategoryExtractor.extract("Uber X Priority", OfferPlatform.UBER))
        assertEquals("Electric", TripCategoryExtractor.extract("Electric", OfferPlatform.UBER))
        assertEquals("Share", TripCategoryExtractor.extract("Share", OfferPlatform.UBER))
        assertEquals("Comfort", TripCategoryExtractor.extract("Comfort Teens", OfferPlatform.UBER))
        assertEquals("Comfort", TripCategoryExtractor.extract("Business Comfort", OfferPlatform.UBER))
        assertEquals("Comfort", TripCategoryExtractor.extract("Comfort Exclusivo", OfferPlatform.UBER))
        assertEquals("UberXL", TripCategoryExtractor.extract("UberXL", OfferPlatform.UBER))
        assertEquals("Black", TripCategoryExtractor.extract("Uber Black", OfferPlatform.UBER))
        assertEquals("Uber Pet", TripCategoryExtractor.extract("Uber Pet", OfferPlatform.UBER))
        assertEquals("Pacotes", TripCategoryExtractor.extract("Pacotes", OfferPlatform.UBER))
    }

    @Test fun `does not combine an UberX category with the next line into UberXL`() {
        assertEquals(
            "UberX",
            TripCategoryExtractor.extract("UberX\nLargo da Liberdade", OfferPlatform.UBER)
        )
        assertEquals(
            "UberXL",
            TripCategoryExtractor.extract("UberXL\nLargo da Liberdade", OfferPlatform.UBER)
        )
        assertEquals(null, TripCategoryExtractor.extract("Uma categoria nova", OfferPlatform.UBER))
    }

    @Test fun `keeps Bolt categories distinct from Uber categories`() {
        assertEquals("Green", TripCategoryExtractor.extract("Green", OfferPlatform.BOLT))
        assertEquals("Bolt", TripCategoryExtractor.extract("Bolt", OfferPlatform.BOLT))
        assertEquals("Comfort", TripCategoryExtractor.extract("Business Comfort", OfferPlatform.BOLT))
        assertEquals("Bolt Send", TripCategoryExtractor.extract("Bolt Send", OfferPlatform.BOLT))
        assertEquals(null, TripCategoryExtractor.extract("XL", OfferPlatform.BOLT))
    }
}
