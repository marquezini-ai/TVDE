package com.daniel.tvdeinsight.service.ocr

/**
 * Guards the monetary field against an OCR digit being appended to a normal
 * Uber fare (for example, €17,90 becoming €172,90). Every fare, including a
 * legitimate €150,00 ride, must agree with an independent recognition before
 * it is published. A failed independent pass is not evidence of a different
 * price: the caller must obtain one bounded, later frame instead of discarding
 * a complete real offer.
 */
internal object UberFareValidation {
    private const val MONEY_EPSILON = 0.009

    enum class Resolution {
        /** Both recognizers agree in the same frame. */
        USE_PRIMARY,
        /** The untouched image was unreadable; obtain one later frame. */
        AWAIT_SECOND_FRAME,
        /** Both recognizers read a value but they disagree. */
        REJECT
    }

    fun requiresIndependentRead(fare: Double): Boolean = fare.isFinite() && fare > 0.0

    fun resolveSuspiciousFare(primaryFare: Double, independentFare: Double?): Resolution {
        val verifiedFare = independentFare ?: return Resolution.AWAIT_SECOND_FRAME
        return if (kotlin.math.abs(primaryFare - verifiedFare) <= MONEY_EPSILON) {
            Resolution.USE_PRIMARY
        } else {
            Resolution.REJECT
        }
    }
}
