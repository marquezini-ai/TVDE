package com.daniel.tvdeinsight.service.ocr

/** Optional bonus is already included in fare; NEVER add it again. */
data class UberVisionFields(
    val fare: Double,
    val priorityFee: Double?,
    val pickupMinutes: Int,
    val pickupKm: Double,
    val tripMinutes: Int,
    val tripKm: Double
) {
    companion object {
        private val flags = setOf(RegexOption.IGNORE_CASE)
        val fareRegex = Regex("""(?:€\s*(\d+[.,]\d{2})|(\d+[.,]\d{2})\s*€)""")
        val priorityRegex = Regex("""\+\s*€\s*(\d+[.,]\d{2})\s*inclu[ií]do""", flags)
        val pickupRegex = Regex("""(\d+)\s*min(?:uto)?s?\s*\(\s*([\d.,]+)\s*km\s*\)\s*de\s*dist[aâ]ncia""", flags)
        val tripRegex = Regex("""Viagem\s+de\s+(?:(\d+)\s*h(?:oras?)?\s*)?(?:(\d+)\s*min(?:uto)?s?\s*)?\(\s*([\d.,]+)\s*km\s*\)""", flags)

        fun parse(text: String): UberVisionFields? {
            val bonus = priorityRegex.find(text)
            val fare = fareRegex.findAll(text).firstOrNull { match ->
                (bonus == null || match.range.first !in bonus.range) &&
                    !text.take(match.range.first).trimEnd().endsWith("+")
            } ?: return null
            val pickup = pickupRegex.find(text) ?: return null
            val trip = tripRegex.find(text) ?: return null
            fun String.number() = replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            val minutes = (trip.groupValues[1].toIntOrNull() ?: 0) * 60 + (trip.groupValues[2].toIntOrNull() ?: 0)
            if (minutes <= 0) return null
            return UberVisionFields(
                fare.groupValues.drop(1).first { it.isNotBlank() }.number() ?: return null,
                bonus?.groupValues?.get(1)?.number(),
                pickup.groupValues[1].toIntOrNull() ?: return null,
                pickup.groupValues[2].number() ?: return null,
                minutes, trip.groupValues[3].number() ?: return null
            )
        }
    }
}
