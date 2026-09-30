package com.daniel.tvdeinsight.service.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UberFareValidationTest {
    @Test fun `ordinary fare with unreadable independent pass awaits one later frame`() {
        assertTrue(UberFareValidation.requiresIndependentRead(17.90))
        assertEquals(
            UberFareValidation.Resolution.AWAIT_SECOND_FRAME,
            UberFareValidation.resolveSuspiciousFare(17.90, null)
        )
    }

    @Test fun `appended OCR digit is rejected when the independent read differs`() {
        assertEquals(
            UberFareValidation.Resolution.REJECT,
            UberFareValidation.resolveSuspiciousFare(172.90, 17.90)
        )
    }

    @Test fun `unreadable independent high fare is held for a second frame without a price cap`() {
        assertEquals(
            UberFareValidation.Resolution.AWAIT_SECOND_FRAME,
            UberFareValidation.resolveSuspiciousFare(172.90, null)
        )
    }

    @Test fun `matching independent high fare remains valid without an upper limit`() {
        assertTrue(UberFareValidation.requiresIndependentRead(150.00))
        assertEquals(
            UberFareValidation.Resolution.USE_PRIMARY,
            UberFareValidation.resolveSuspiciousFare(150.00, 150.00)
        )
    }
}
