package com.daniel.tvdeinsight.service.accessibility

/**
 * Obtém, quando visíveis no card, os endereços junto dos dois segmentos da rota.
 * O cálculo da oferta nunca depende destes textos: se a plataforma não os expuser,
 * o histórico mostra o campo como não disponível em vez de inventar um endereço.
 */
internal object RouteAddressExtractor {
    data class RouteAddresses(val pickup: String?, val destination: String?)

    fun extract(cardText: String): RouteAddresses {
        val lines = cardText.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return RouteAddresses(null, null)

        val pickupIndex = lines.indexOfFirst(::isPickupMetric)
        val tripIndex = (pickupIndex + 1 until lines.size)
            .firstOrNull { index -> isTripMetric(lines[index]) || isPickupMetric(lines[index]) }
            ?: -1
        if (pickupIndex < 0 || tripIndex < 0) return RouteAddresses(null, null)

        val pickup = findAddress(lines, pickupIndex + 1, tripIndex) ?:
            findAddress(lines, (pickupIndex - MAX_LINES_BEFORE_METRIC).coerceAtLeast(0), pickupIndex)
        val destination = findAddress(lines, tripIndex + 1, lines.size)
        return RouteAddresses(pickup, destination)
    }

    private fun isPickupMetric(line: String): Boolean =
        line.contains("km", ignoreCase = true) &&
            line.contains("min", ignoreCase = true) &&
            (line.contains("dist", ignoreCase = true) || !line.contains("viagem", ignoreCase = true))

    private fun isTripMetric(line: String): Boolean =
        line.contains("km", ignoreCase = true) &&
            line.contains("min", ignoreCase = true) &&
            (line.contains("viagem", ignoreCase = true) || line.contains("trip", ignoreCase = true))

    private fun findAddress(lines: List<String>, start: Int, endExclusive: Int): String? {
        val candidates = lines.subList(start.coerceAtMost(lines.size), endExclusive.coerceAtMost(lines.size))
        val firstAddressIndex = candidates.indexOfFirst(::looksLikeAddress)
        if (firstAddressIndex < 0) return null

        return candidates.drop(firstAddressIndex)
            .takeWhile(::looksLikeAddress)
            .take(MAX_ADDRESS_LINES)
            .joinToString(" ")
            .take(MAX_ADDRESS_LENGTH)
            .takeIf(String::isNotBlank)
    }

    private fun looksLikeAddress(line: String): Boolean {
        val normalized = line.lowercase()
        return line.length >= MIN_ADDRESS_LENGTH &&
            line.any(Char::isLetter) &&
            !METRIC_MARKERS.any(normalized::contains)
    }

    private val METRIC_MARKERS = setOf(
        " min", " km", "€", "eur", "viagem", "trip", "taxa", "servi", "foco",
        "aceitar", "rejeitar", "analisar", "uber", "bolt", "parada", "priority",
        "selecionar", "carregamento", "exclusivo"
    )

    private const val MAX_LINES_BEFORE_METRIC = 2
    private const val MIN_ADDRESS_LENGTH = 3
    private const val MAX_ADDRESS_LINES = 4
    private const val MAX_ADDRESS_LENGTH = 240
}
