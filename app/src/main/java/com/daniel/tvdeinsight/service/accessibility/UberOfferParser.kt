package com.daniel.tvdeinsight.service.accessibility

import com.daniel.tvdeinsight.domain.model.TripOffer
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import javax.inject.Inject
import javax.inject.Singleton

/** Converte exclusivamente o texto já isolado do cartão de oferta Uber. */
@Singleton
class UberOfferParser @Inject constructor() {

    private val priceRegex = Regex(
        "(?:€|â‚¬|EUR)\\s*(\\d+[,.]\\d{2})|(\\d+[,.]\\d{2})\\s*(?:€|â‚¬|EUR)",
        RegexOption.IGNORE_CASE
    )
    // Em alguns cartões o ML Kit perde o "k" de "km" e devolve apenas "m".
    // A distância Uber é sempre expressa em quilómetros; aceitar este caso
    // evita descartar os cartões enviados a todos os motoristas (Selecionar).
    private val kmRegex = Regex("(\\d+[,.]?\\d*)\\s*(?:km|quil|m\\b)", RegexOption.IGNORE_CASE)
    private val minRegex = Regex("(\\d+)\\s*(?:min|minuto|m\\b)", RegexOption.IGNORE_CASE)
    private val routeDurationPattern =
        "(?:(?:(\\d+)\\s*h(?:ora?s?)?(?:\\s*(?:e|and)?\\s*(\\d+)\\s*min(?:uto)?s?)?)|(?:(\\d+)\\s*(?:min(?:uto)?s?|m\\b)))"
    private val routeSegmentRegex = Regex(
        "$routeDurationPattern\\s*[^\\d]{0,32}\\(?\\s*(\\d+[,.]?\\d*)\\s*(?:km|quil|m\\b)",
        RegexOption.IGNORE_CASE
    )
    private val passengerTripMarkerRegex = Regex("\\bviag\\w*\\s*(?:de\\s*)?", RegexOption.IGNORE_CASE)

    fun parse(cardText: String): TripOffer? {
        if (cardText.isBlank()) return null

        val normalizedCardText = cardText.lowercase()
            .replace(Regex("v[li]agem"), "viagem")
            .replace("rnin", "min")
            .replace(Regex("\\b[li]\\s*h(?:e\\b)?"), "1 h")
            .replace(Regex("\\b([0-9ilsobgaz]+)([,.][0-9ilsobgaz]+)?\\s*(?=(?:min(?:uto)?s?|km|quil|m\\b))")) { match ->
                val mapDigit = { str: String ->
                    str.replace(Regex("[il]"), "1").replace("s", "5").replace("o", "0")
                        .replace("b", "8").replace("g", "6").replace("a", "4").replace("z", "2")
                }
                mapDigit(match.groupValues[1]) + mapDigit(match.groupValues[2])
            }
        val price = extractPrice(cardText) ?: return null
        val routeSegments = routeSegmentRegex.findAll(normalizedCardText).mapNotNull { match ->
            val hours = match.groupValues[1].toIntOrNull() ?: 0
            val hourMinutes = match.groupValues[2].toIntOrNull() ?: 0
            val plainMinutes = match.groupValues[3].toIntOrNull() ?: 0
            val minutes = hours * 60 + hourMinutes + plainMinutes
            if (minutes <= 0) return@mapNotNull null
            val distance = parseDistance(match.groupValues[4], minutes) ?: return@mapNotNull null
            RouteSegment(minutes, distance, match.range.first)
        }.toList()
        val passengerTripStart = passengerTripMarkerRegex.find(normalizedCardText)?.range?.first
        val passengerSegment = if (passengerTripStart != null) {
            routeSegments.firstOrNull { it.startIndex >= passengerTripStart }
        } else {
            routeSegments.getOrNull(1)
        }
        val pickupSegment = if (passengerTripStart != null) {
            routeSegments.lastOrNull { it.startIndex < passengerTripStart }
        } else {
            routeSegments.getOrNull(0)
        }

        // Um card Uber completo tem obrigatoriamente os dois trechos. Aceitar apenas a
        // linha "Viagem de ..." faz a distância ao destino ser tratada como recolha,
        // levando a valores €/km e €/hora incorretos. Neste caso aguardamos uma leitura
        // seguinte da árvore/OCR, em vez de publicar dados parciais.
        if (passengerTripStart != null && (pickupSegment == null || passengerSegment == null)) {
            return null
        }
        val identifiedSegments = listOfNotNull(pickupSegment, passengerSegment).distinctBy { it.startIndex }

        val distances = kmRegex.findAll(cardText)
            .mapNotNull { parseDistance(it.groupValues[1]) }
            .toList()
        val durations = minRegex.findAll(normalizedCardText).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        val totalDistance = identifiedSegments.takeIf { it.isNotEmpty() }?.sumOf { it.distanceKm } ?: distances.sum()
        val totalDuration = identifiedSegments.takeIf { it.isNotEmpty() }?.sumOf { it.minutes } ?: durations.sum()

        if (totalDistance < 0.1 || totalDuration < 1) return null
        if (totalDistance * 60 / totalDuration > MAX_REASONABLE_AVERAGE_SPEED_KMH) return null
        if (identifiedSegments.any { it.distanceKm * 60 / it.minutes > MAX_REASONABLE_AVERAGE_SPEED_KMH }) {
            return null
        }

        val hasStops = StopDetector.hasStops(cardText)
        val addresses = RouteAddressExtractor.extract(cardText)
        val category = TripCategoryExtractor.extract(cardText, OfferPlatform.UBER)
        return TripOffer(
            price = price,
            distanceKm = totalDistance,
            durationMinutes = totalDuration.toDouble(),
            additionalInfo = cardText,
            pickupDistanceKm = pickupSegment?.distanceKm,
            pickupDurationMinutes = pickupSegment?.minutes?.toDouble(),
            tripDistanceKm = passengerSegment?.distanceKm,
            tripDurationMinutes = passengerSegment?.minutes?.toDouble(),
            pickupAddress = addresses.pickup,
            destinationAddress = addresses.destination,
            category = category,
            hasStops = hasStops,
            platform = OfferPlatform.UBER
        )
    }

    private fun extractPrice(text: String): Double? {
        val match = priceRegex.find(text) ?: return null
        val value = match.groupValues[1].ifBlank { match.groupValues[2] }
        return value.replace(',', '.').toDoubleOrNull()
    }

    /**
     * O ML Kit ocasionalmente perde o separador de um decimal (por exemplo, "1,6" vira
     * "16"). Só recuperamos esse separador quando o número sem ele exigiria velocidade
     * impossível para o respetivo trecho; assim uma distância real de 16 km permanece 16 km.
     */
    private fun parseDistance(rawValue: String, minutes: Int? = null): Double? {
        val compactValue = rawValue.replace(" ", "").trim()
        val parsedValue = compactValue.replace(',', '.').toDoubleOrNull() ?: return null
        val hasDecimalSeparator = compactValue.contains(',') || compactValue.contains('.')
        if (hasDecimalSeparator || minutes == null || minutes <= 0 || compactValue.length < 2) {
            return parsedValue
        }

        val requiresImpossibleSpeed = parsedValue * 60 / minutes > MAX_REASONABLE_AVERAGE_SPEED_KMH
        if (!requiresImpossibleSpeed) return parsedValue

        val recoveredValue = "${compactValue.dropLast(1)}.${compactValue.last()}".toDoubleOrNull() ?: return parsedValue
        return recoveredValue.takeIf { it * 60 / minutes <= MAX_REASONABLE_AVERAGE_SPEED_KMH } ?: parsedValue
    }

    private data class RouteSegment(val minutes: Int, val distanceKm: Double, val startIndex: Int)

    private companion object {
        const val MAX_REASONABLE_AVERAGE_SPEED_KMH = 150.0
    }
}
