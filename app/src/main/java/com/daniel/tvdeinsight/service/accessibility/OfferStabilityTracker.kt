package com.daniel.tvdeinsight.service.accessibility

import com.daniel.tvdeinsight.domain.model.OfferPlatform
import com.daniel.tvdeinsight.domain.model.TripOffer
import kotlin.math.round

/**
 * Confirma leituras consecutivas sem deixar que eventos de uma plataforma
 * invalidem a leitura pendente da outra. A assinatura normaliza imprecisões
 * binárias de Double antes de comparar a mesma oferta.
 */
internal class OfferStabilityTracker(
    private val requiredConsecutiveReadings: Int,
    private val duplicateWindowMs: Long
) {
    private data class Candidate(val signature: String, val readings: Int)
    private data class Published(val signature: String, val timestampMs: Long)

    private val candidates = mutableMapOf<OfferPlatform, Candidate>()
    private val published = mutableMapOf<OfferPlatform, Published>()

    fun shouldPublish(offer: TripOffer, nowMs: Long): Boolean {
        val platform = offer.platform
        val signature = offer.stabilitySignature()
        val previous = candidates[platform]
        val readings = if (previous?.signature == signature) previous.readings + 1 else 1
        candidates[platform] = Candidate(signature, readings)

        if (readings < requiredConsecutiveReadings) return false

        val lastPublished = published[platform]
        if (lastPublished?.signature == signature && nowMs - lastPublished.timestampMs < duplicateWindowMs) {
            return false
        }

        published[platform] = Published(signature, nowMs)
        return true
    }

    /**
     * Android 12L e inferiores já recebem a oferta através da árvore de
     * acessibilidade. Publica a primeira leitura válida sem confirmação, mas
     * mantém a mesma janela de deduplicação usada pelo fluxo confirmado.
     */
    fun shouldPublishImmediately(offer: TripOffer, nowMs: Long): Boolean {
        val platform = offer.platform
        val signature = offer.stabilitySignature()
        val lastPublished = published[platform]
        if (lastPublished?.signature == signature && nowMs - lastPublished.timestampMs < duplicateWindowMs) {
            return false
        }

        candidates.remove(platform)
        published[platform] = Published(signature, nowMs)
        return true
    }

    fun isAwaitingConfirmation(offer: TripOffer): Boolean {
        val candidate = candidates[offer.platform] ?: return false
        return candidate.signature == offer.stabilitySignature() &&
            candidate.readings < requiredConsecutiveReadings
    }

    fun clear() {
        candidates.clear()
        published.clear()
    }
}

internal fun TripOffer.stabilitySignature(): String = listOf(
    platform.name,
    price.rounded(100),
    distanceKm.rounded(10),
    durationMinutes.rounded(1),
    pickupDistanceKm?.rounded(10),
    pickupDurationMinutes?.rounded(1),
    tripDistanceKm?.rounded(10),
    tripDurationMinutes?.rounded(1),
    hasStops
).joinToString("|")

private fun Double.rounded(scale: Int): Double = round(this * scale) / scale
