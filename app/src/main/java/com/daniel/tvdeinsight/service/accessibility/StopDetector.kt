package com.daniel.tvdeinsight.service.accessibility

import java.text.Normalizer

/** Identifica paradas indicadas no texto ou na estrutura da rota da oferta. */
internal object StopDetector {
    fun hasStops(cardText: String, routeSegmentCount: Int = 0): Boolean =
        routeSegmentCount > NORMAL_ROUTE_SEGMENT_COUNT || containsExplicitStop(cardText)

    private fun containsExplicitStop(text: String): Boolean {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
        return STOP_MARKER_REGEX.containsMatchIn(normalized) ||
            MULTI_DESTINATION_REGEX.containsMatchIn(normalized)
    }

    private const val NORMAL_ROUTE_SEGMENT_COUNT = 2
    private val STOP_MARKER_REGEX = Regex("\\b(?:parad(?:a|as)|parage(?:m|ns)|stops?)\\b")
    private val MULTI_DESTINATION_REGEX = Regex(
        "\\b(?:multi[-\\s]?(?:stop|destino|destinos)|multiple\\s+stops|(?:varios|varias)\\s+destinos?|destinos?\\s+multiplos?)\\b"
    )
}
