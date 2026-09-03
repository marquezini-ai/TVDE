package com.daniel.tvdeinsight.service.accessibility

import com.daniel.tvdeinsight.domain.model.OfferPlatform

/** Lê apenas categorias válidas que estejam explicitamente visíveis no card. */
internal object TripCategoryExtractor {
    fun extract(cardText: String, platform: OfferPlatform): String? {
        val lines = cardText.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        val candidates = lines.ifEmpty { listOf(cardText) }

        return candidates.firstNotNullOfOrNull { candidate ->
            extractFromCandidate(candidate, platform)
        }
    }

    private fun extractFromCandidate(text: String, platform: OfferPlatform): String? {
        return com.daniel.tvdeinsight.domain.model.CategoryNameSanitizer
            .cleanForPlatform(text, platform)
    }
}
