package com.daniel.tvdeinsight.reservations

import java.util.Locale

data class RideCandidate(
    val category: String,
    val startMinutes: Int,
    val distanceKm: Double,
    val payout: Double,
    val sourceText: String,
    val displayedCategory: String = category,
    val timeText: String = "",
    val tripDate: String = "",
    val origin: String = "",
    val destination: String = "",
    val endMinutes: Int = startMinutes
) {
    val valuePerKm: Double get() = if (distanceKm > 0.0) payout / distanceKm else 0.0
    val durationHours: Double
        get() {
            val duration = if (endMinutes >= startMinutes) endMinutes - startMinutes
            else 24 * 60 - startMinutes + endMinutes
            return duration / 60.0
        }
    val historyId: String get() = RideIdentity.forCandidate(this)
    val fingerprint: String
        get() = "${category.lowercase(Locale.ROOT)}|$startMinutes|$distanceKm|$payout|$tripDate|$origin|$destination"
}

/** Parser isolado do Android. Os regexes da MacroDroid ficam centralizados aqui. */
object RideParser {
    private val categoryRegex = Regex("(?i)\\b(Bolt|Green|Comfort|Premium|XL|Pet|Gama\\s+el[eé]trica)\\b")
    // Equivalente a: \\b\\d{2}:\\b\\d{2} usado em Ler_tela.macro.
    private val macroTimeRegex = Regex("\\b\\d{2}:\\b\\d{2}(?:\\s*[–-]\\s*\\d{2}:\\b\\d{2})?")
    private val distanceRegex = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*km\\b")
    private val moneyRegex = Regex(
        "(?i)(?:€\\s*((?:\\d[\\d .]*)(?:[.,]\\d{1,2})?)|((?:\\d[\\d .]*)(?:[.,]\\d{1,2})?)\\s*€)"
    )

    /** Fallback para cartões em que os textos chegam agregados numa única árvore. */
    fun parse(text: String): RideCandidate? = parseCardText(text)

    fun parseMacroFields(categoryText: String, titleText: String, distanceText: String): RideCandidate? {
        val rawCategory = categoryRegex.find(categoryText)?.groupValues?.get(1) ?: return null
        val category = canonicalCategory(rawCategory)
        // A árvore de acessibilidade da Bolt pode devolver NBSP entre o valor
        // e o símbolo €. Para o regex, esse espaço deve ser tratado como normal.
        val normalizedTitle = titleText.replace('\u00A0', ' ')
        val time = macroTimeRegex.find(normalizedTitle)?.value ?: return null
        val parsedTimes = parseTimes(time)
        val hour = parsedTimes.first.first
        val minute = parsedTimes.first.second
        if (hour !in 0..23 || minute !in 0..59) return null

        // A Bolt pode mostrar o total e, na mesma linha, a portagem. O valor da
        // portagem não é o pagamento total: identifica-se o valor imediatamente
        // antes de "portagem" e exclui-se apenas esse token.
        val payout = extractPayout(normalizedTitle) ?: return null
        val distance = distanceRegex.find(distanceText)?.groupValues?.get(1)
            ?.replace(',', '.')?.toDoubleOrNull() ?: return null
        return RideCandidate(
            category = category,
            startMinutes = hour * 60 + minute,
            endMinutes = parsedTimes.second?.let { it.first * 60 + it.second } ?: hour * 60 + minute,
            distanceKm = distance,
            payout = payout,
            sourceText = "$categoryText | $titleText | $distanceText",
            displayedCategory = rawCategory.trim(),
            timeText = time,
            tripDate = TripDateResolver.resolve(normalizedTitle),
            origin = extractRoute(normalizedTitle).first,
            destination = extractRoute(normalizedTitle).second
        )
    }

    fun parseCardText(text: String): RideCandidate? {
        val normalized = text.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
        val rawCategory = categoryRegex.find(normalized)?.groupValues?.get(1) ?: return null
        val category = canonicalCategory(rawCategory)
        val time = macroTimeRegex.find(normalized)?.value ?: return null
        val parsedTimes = parseTimes(time)
        val hour = parsedTimes.first.first
        val minute = parsedTimes.first.second
        val distance = distanceRegex.find(normalized)?.groupValues?.get(1)
            ?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val payout = extractPayout(normalized) ?: return null
        val route = extractRoute(normalized)
        return RideCandidate(
            category = category,
            startMinutes = hour * 60 + minute,
            endMinutes = parsedTimes.second?.let { it.first * 60 + it.second } ?: hour * 60 + minute,
            distanceKm = distance,
            payout = payout,
            sourceText = normalized,
            displayedCategory = rawCategory.trim(),
            timeText = time,
            tripDate = TripDateResolver.resolve(normalized),
            origin = route.first,
            destination = route.second
        )
    }

    fun extractRoute(text: String): Pair<String, String> {
        val addresses = Regex("(?i)perto\\s+de\\s+(.+?)(?=\\s+perto\\s+de\\s+|$)")
            .findAll(text.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim())
            .map { it.groupValues[1].trim(' ', '·', '|') }
            .filter { it.isNotBlank() }
            .toList()
        return if (addresses.size >= 2) addresses[0] to addresses[1] else "" to ""
    }

    fun extractRoute(texts: List<String>, fallbackText: String): Pair<String, String> {
        val addresses = texts.flatMap { text ->
            Regex("(?i)perto\\s+de\\s+(.+)$").find(text)?.let {
                listOf(it.groupValues[1].trim(' ', '·', '|'))
            }.orEmpty()
        }.filter { it.isNotBlank() }.distinct()
        return if (addresses.size >= 2) {
            addresses[0] to addresses[1]
        } else {
            extractRoute(fallbackText)
        }
    }

    private fun moneyValue(match: MatchResult): Double? {
        val raw = match.groupValues.getOrNull(1).orEmpty()
            .ifBlank { match.groupValues.getOrNull(2).orEmpty() }
        val compact = raw.replace(" ", "")
        val normalized = if (compact.contains(',') && compact.contains('.')) {
            compact.replace(".", "").replace(',', '.')
        } else {
            compact.replace(',', '.')
        }
        return normalized.toDoubleOrNull()
    }

    private fun extractPayout(text: String): Double? {
        val matches = moneyRegex.findAll(text).toList()
        if (matches.isEmpty()) return null

        val tollMarkers = Regex("(?i)\\b(portagem|toll)\\b").findAll(text).toList()
        val tollValues = tollMarkers.mapNotNull { marker ->
            matches
                .filter { it.range.last < marker.range.first }
                .minByOrNull { marker.range.first - it.range.last }
                ?.takeIf { marker.range.first - it.range.last <= 20 }
        }.toSet()

        return matches
            .asSequence()
            .filterNot { it in tollValues }
            .mapNotNull(::moneyValue)
            .firstOrNull()
            ?: matches.asSequence().mapNotNull(::moneyValue).firstOrNull()
    }

    private fun canonicalCategory(value: String): String =
        if (AccessibilityNodeUtils.normalizeForComparison(value) == "gama eletrica") "Green" else value

    private fun parseTimes(value: String): Pair<Pair<Int, Int>, Pair<Int, Int>?> {
        val values = Regex("\\d{2}:\\d{2}").findAll(value).map {
            it.value.split(':').let { parts -> parts[0].toInt() to parts[1].toInt() }
        }.toList()
        return (values.firstOrNull() ?: (0 to 0)) to values.getOrNull(1)
    }
}
