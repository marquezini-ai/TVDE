package com.daniel.tvdeinsight.reservations

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Converte Hoje/Amanhã e datas abreviadas da Bolt para uma data inequívoca. */
object TripDateResolver {
    private val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale("pt", "PT"))
    private val dayMonthRegex = Regex(
        "\\b(\\d{1,2})\\s+(janeiro|jan|fevereiro|fev|marco|mar|abril|abr|maio|mai|junho|jun|julho|jul|agosto|ago|setembro|set|outubro|out|novembro|nov|dezembro|dez)\\b",
        RegexOption.IGNORE_CASE
    )
    private val numericDateRegex = Regex("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{4}))?\\b")
    private val months = mapOf(
        "jan" to 1, "fev" to 2, "mar" to 3, "abr" to 4,
        "mai" to 5, "jun" to 6, "jul" to 7, "ago" to 8,
        "set" to 9, "out" to 10, "nov" to 11, "dez" to 12
    )

    fun resolve(text: String, today: LocalDate = LocalDate.now()): String {
        return resolveDate(text, today).format(formatter)
    }

    fun resolveDate(text: String, today: LocalDate = LocalDate.now()): LocalDate {
        val normalized = AccessibilityNodeUtils.normalizeForComparison(text)
        return when {
            Regex("\\bhoje\\b").containsMatchIn(normalized) -> today
            Regex("\\bamanha\\b").containsMatchIn(normalized) -> today.plusDays(1)
            Regex("\\bontem\\b").containsMatchIn(normalized) -> today.minusDays(1)
            numericDateRegex.find(normalized) != null -> numericDateRegex.find(normalized)?.let { match ->
                val day = match.groupValues[1].toIntOrNull() ?: return@let today
                val month = match.groupValues[2].toIntOrNull() ?: return@let today
                val year = match.groupValues[3].toIntOrNull()
                runCatching {
                    if (year != null) LocalDate.of(year, month, day)
                    else LocalDate.of(today.year, month, day).let { candidate ->
                        if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
                    }
                }.getOrElse { today }
            } ?: today
            else -> dayMonthRegex.find(normalized)?.let { match ->
                val day = match.groupValues[1].toIntOrNull() ?: return@let null
                val month = months[match.groupValues[2].take(3)] ?: return@let null
                runCatching {
                    LocalDate.of(today.year, month, day).let { candidate ->
                        // A data sem ano anterior ao dia atual representa a
                        // próxima ocorrência, incluindo a passagem de ano.
                        if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
                    }
                }.getOrNull()
            } ?: today
        }
    }

    fun parseResolvedDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value, formatter) }.getOrNull()
}
