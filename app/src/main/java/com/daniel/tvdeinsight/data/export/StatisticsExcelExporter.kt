package com.daniel.tvdeinsight.data.export

import com.daniel.tvdeinsight.domain.model.DecisionType
import com.daniel.tvdeinsight.domain.model.EvaluationCriterion
import com.daniel.tvdeinsight.domain.model.OfferHistoryEntry
import com.daniel.tvdeinsight.domain.location.PortugueseAddressFormatter
import com.daniel.tvdeinsight.data.format.PtDecimalFormatter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/**
 * Gera SpreadsheetML 2003 com extensão .xls. É um formato nativo compatível
 * com Microsoft Excel, sem acrescentar uma biblioteca pesada ao APK.
 */
object StatisticsExcelExporter {
    const val MIME_TYPE = "application/vnd.ms-excel"

    fun write(outputStream: OutputStream, entries: List<OfferHistoryEntry>) {
        OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
            writer.write(workbookXml(entries))
        }
    }

    internal fun workbookXml(entries: List<OfferHistoryEntry>): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<?mso-application progid=\"Excel.Sheet\"?>")
        appendLine("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">")
        appendLine("<Styles><Style ss:ID=\"Header\"><Font ss:Bold=\"1\"/></Style></Styles>")
        appendWorksheet("Resumo", SUMMARY_HEADERS, entries.summaryRows())
        appendWorksheet("Ofertas", EXPORT_HEADERS, entries.map { it.exportValues() })
        appendWorksheet("Plataformas", PLATFORM_HEADERS, entries.platformRows())
        appendWorksheet("Por horário", HOURLY_HEADERS, entries.hourlyRows())
        appendLine("</Workbook>")
    }

    private fun StringBuilder.appendWorksheet(name: String, headers: List<String>, rows: List<List<String>>) {
        append("<Worksheet ss:Name=\"").append(name.xmlEscape()).appendLine("\"><Table>")
        appendRow(headers, styleId = "Header")
        rows.forEach { appendRow(it) }
        appendLine("</Table></Worksheet>")
    }

    private fun StringBuilder.appendRow(values: List<String>, styleId: String? = null) {
        append("<Row>")
        values.forEach { value ->
            append("<Cell")
            styleId?.let { append(" ss:StyleID=\"").append(it).append("\"") }
            append("><Data ss:Type=\"String\">")
            append(value.xmlEscape())
            append("</Data></Cell>")
        }
        appendLine("</Row>")
    }

    private fun OfferHistoryEntry.exportValues(): List<String> = listOf(
        dateTimeLabel(),
        platform.label,
        category.orEmpty(),
        decisionType.exportLabel(),
        exportReason(),
        tripValue.currency(),
        (netTripValue ?: tripValue).currency(),
        valorPorKmBruto.currency(),
        valorPorKm.currency(),
        valorPorHora.currency(),
        pickupDistanceKm.distance(),
        pickupDurationMinutes.duration(),
        PortugueseAddressFormatter.withoutCountry(pickupAddress).orEmpty(),
        PortugueseAddressFormatter.withoutCountry(currentLocationAddress).orEmpty(),
        currentLocationLatitude.coordinate(),
        currentLocationLongitude.coordinate(),
        destinationDistanceKm.distance(),
        destinationDurationMinutes.duration(),
        PortugueseAddressFormatter.withoutCountry(destinationAddress).orEmpty(),
        tollAmount.currency(),
        activeCriteria.sortedBy(EvaluationCriterion::ordinal).joinToString(", ") { it.label }
    )

    private fun List<OfferHistoryEntry>.summaryRows(): List<List<String>> {
        val netValues = mapNotNull(OfferHistoryEntry::netTripValue)
        return listOf(
            listOf("Total de ofertas", size.toString()),
            listOf("Valor médio da oferta", map(OfferHistoryEntry::tripValue).averageOrZero().currency()),
            listOf("Km bruto mediano", map(OfferHistoryEntry::valorPorKmBruto).medianOrZero().currencyPerKm()),
            listOf("Km livre mediano", map(OfferHistoryEntry::valorPorKm).medianOrZero().currencyPerKm()),
            listOf("Hora mediana", map(OfferHistoryEntry::valorPorHora).medianOrZero().currencyPerHour()),
            listOf("Valor líquido médio", netValues.averageOrNull()?.currency().orEmpty()),
            listOf("Recolha média", mapNotNull(OfferHistoryEntry::pickupDistanceKm).averageOrNull()?.distance().orEmpty()),
            listOf("Ofertas verdes", percentage { it.decisionType == DecisionType.ACEITAR }),
            listOf("Ofertas amarelas", percentage { it.decisionType == DecisionType.ANALISAR }),
            listOf("Ofertas vermelhas", percentage { it.decisionType == DecisionType.REJEITAR })
        )
    }

    private fun List<OfferHistoryEntry>.platformRows(): List<List<String>> =
        groupBy(OfferHistoryEntry::platform)
            .toSortedMap(compareBy { it.ordinal })
            .map { (platform, entries) ->
                listOf(
                    platform.label,
                    entries.size.toString(),
                    entries.map(OfferHistoryEntry::tripValue).averageOrZero().currency(),
                    entries.map(OfferHistoryEntry::valorPorKmBruto).medianOrZero().currencyPerKm(),
                    entries.map(OfferHistoryEntry::valorPorKm).medianOrZero().currencyPerKm(),
                    entries.map(OfferHistoryEntry::valorPorHora).medianOrZero().currencyPerHour(),
                    entries.mapNotNull(OfferHistoryEntry::pickupDistanceKm).averageOrNull()?.distance().orEmpty(),
                    entries.percentage { it.decisionType == DecisionType.ACEITAR }
                )
            }

    private fun List<OfferHistoryEntry>.hourlyRows(): List<List<String>> {
        val zone = ZoneId.systemDefault()
        return groupBy { entry ->
            val dateTime = Instant.ofEpochMilli(entry.recordedAtMillis).atZone(zone)
            Triple(dateTime.dayOfWeek, dateTime.hour, entry.platform)
        }.toSortedMap(compareBy<Triple<java.time.DayOfWeek, Int, com.daniel.tvdeinsight.domain.model.OfferPlatform>>(
            { it.first.value }, { it.second }, { it.third.ordinal }
        )).map { (key, entries) ->
            listOf(
                key.first.getDisplayName(TextStyle.FULL, PORTUGUESE_LOCALE),
                String.format(Locale.ROOT, "%02d:00", key.second),
                key.third.label,
                entries.size.toString(),
                entries.map(OfferHistoryEntry::tripValue).averageOrZero().currency(),
                entries.map(OfferHistoryEntry::valorPorKm).medianOrZero().currencyPerKm(),
                entries.map(OfferHistoryEntry::valorPorHora).medianOrZero().currencyPerHour()
            )
        }
    }

    private fun OfferHistoryEntry.exportReason(): String {
        if (isStopRejection) return "Rejeitada por paradas intermediárias."
        val prefix = decisionType.exportLabel().replaceFirstChar(Char::uppercase)
        val visibleCriteria = activeCriteria.filter { criterion ->
            criterion != EvaluationCriterion.VALOR_MINIMO || decisionType == DecisionType.REJEITAR
        }
        val responsible = visibleCriteria.filter { criterionDecisions[it] == decisionType }
            .ifEmpty { visibleCriteria }
        if (responsible.isEmpty()) return "$prefix pelos critérios ativos."
        return "$prefix ${responsible.sortedBy(EvaluationCriterion::ordinal).reasonPhrase()}."
    }

    private fun List<EvaluationCriterion>.reasonPhrase(): String {
        val phrases = map { criterion -> criterion.reasonLabel() }
        return when (phrases.size) {
            1 -> phrases.single()
            2 -> "${phrases[0]} e ${phrases[1]}"
            else -> phrases.dropLast(1).joinToString(", ") + " e ${phrases.last()}"
        }
    }

    private fun EvaluationCriterion.reasonLabel(): String = when (this) {
        EvaluationCriterion.RECOLHA -> "pela distância de recolha"
        EvaluationCriterion.KM -> "pelo valor por quilómetro"
        EvaluationCriterion.HORA -> "pelo valor por hora"
        EvaluationCriterion.VIAGEM_LONGA -> "pela distância do destino"
        EvaluationCriterion.VALOR_MINIMO -> "pelo valor mínimo"
    }

    private fun DecisionType.exportLabel(): String = when (this) {
        DecisionType.ACEITAR -> "Aceita"
        DecisionType.REJEITAR -> "Rejeitada"
        DecisionType.ANALISAR -> "Analisada"
    }

    private fun OfferHistoryEntry.dateTimeLabel(): String = DateFormat.getDateTimeInstance(
        DateFormat.LONG,
        DateFormat.SHORT,
        PORTUGUESE_LOCALE
    ).format(Date(recordedAtMillis))

    private fun Double.currency(): String = "€ ${PtDecimalFormatter.two(this)}"
    private fun Double.currencyPerKm(): String = "${PtDecimalFormatter.two(this)} €/km"
    private fun Double.currencyPerHour(): String = "${PtDecimalFormatter.two(this)} €/h"
    private fun Double?.distance(): String = this?.let { "${PtDecimalFormatter.two(it)} km" }.orEmpty()
    private fun Double?.duration(): String = this?.let { "${PtDecimalFormatter.two(it)} min" }.orEmpty()
    private fun Double?.coordinate(): String = this?.let { String.format(Locale.ROOT, "%.6f", it) }.orEmpty()

    private fun String.xmlEscape(): String = buildString(length) {
        this@xmlEscape.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '\"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                }
            )
        }
    }

    private fun List<Double>.medianOrZero(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
    private fun <T> List<T>.percentage(predicate: (T) -> Boolean): String =
        String.format(PORTUGUESE_LOCALE, "%.0f%%", if (isEmpty()) 0.0 else count(predicate) * 100.0 / size)

    private val EXPORT_HEADERS = listOf(
        "Data e hora", "Plataforma", "Categoria", "Decisão", "Motivo",
        "Valor", "Líquido", "Km", "Km livre", "Hora",
        "Km recolha", "Tempo recolha", "Endereço recolha",
        "Localização no momento da oferta", "Latitude", "Longitude",
        "Km destino", "Tempo destino", "Endereço destino", "Portagem", "Critérios ativos"
    )
    private val SUMMARY_HEADERS = listOf("Indicador", "Valor")
    private val PLATFORM_HEADERS = listOf(
        "Plataforma", "Ofertas", "Valor médio", "Km bruto mediano", "Km livre mediano",
        "Hora mediana", "Recolha média", "Ofertas verdes"
    )
    private val HOURLY_HEADERS = listOf(
        "Dia da semana", "Hora", "Plataforma", "Ofertas", "Valor médio", "Km livre mediano", "Hora mediana"
    )
    private val PORTUGUESE_LOCALE = Locale("pt", "PT")
}
