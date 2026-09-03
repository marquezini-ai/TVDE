package com.daniel.tvdeinsight.service.ocr

import com.daniel.tvdeinsight.domain.model.TripOffer
import com.daniel.tvdeinsight.service.accessibility.stabilitySignature

/**
 * Confirma exclusivamente as leituras OCR da Uber no Android 13+.
 *
 * A primeira leitura completa fica fixa. Leituras diferentes não a substituem:
 * são descartadas até que a mesma oferta seja lida novamente ou a pequena
 * janela de confirmação expire. Isso impede que um bónus ou um valor do overlay
 * passe a ser o novo candidato durante a confirmação.
 */
internal class OcrOfferConfirmationTracker(
    private val confirmationWindowMs: Long,
    private val duplicateWindowMs: Long
) {
    enum class Result {
        AWAITING_CONFIRMATION,
        CONFIRMED,
        MISMATCH,
        DUPLICATE
    }

    private data class Pending(val signature: String, val startedAtMs: Long)
    private data class Published(val signature: String, val timestampMs: Long)

    private var pending: Pending? = null
    private var published: Published? = null

    fun observe(offer: TripOffer, nowMs: Long): Result {
        val signature = offer.stabilitySignature()
        val lastPublished = published
        if (
            lastPublished?.signature == signature &&
                nowMs - lastPublished.timestampMs < duplicateWindowMs
        ) {
            return Result.DUPLICATE
        }

        val current = pending
        if (current == null || nowMs - current.startedAtMs > confirmationWindowMs) {
            pending = Pending(signature, nowMs)
            return Result.AWAITING_CONFIRMATION
        }

        if (current.signature != signature) {
            return Result.MISMATCH
        }

        pending = null
        published = Published(signature, nowMs)
        return Result.CONFIRMED
    }

    fun clearPending() {
        pending = null
    }

    fun clear() {
        pending = null
        published = null
    }
}
