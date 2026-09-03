package com.daniel.tvdeinsight.service.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import com.daniel.tvdeinsight.domain.model.CategoryNameSanitizer
import com.daniel.tvdeinsight.domain.model.TripOffer
import javax.inject.Inject
import javax.inject.Singleton

/** Lê apenas os elementos pertencentes ao mesmo cartão de oferta Bolt. */
@Singleton
class BoltOfferParser @Inject constructor() {

    private val priceRegex = Regex("(\\d+[,.]\\d{2})\\s*(?:€|â‚¬)")
    private val lineSegmentRegex = Regex(
        "(\\d+)\\s*min[^0-9a-zA-Z]*(\\d+[,.]?\\d*)\\s*km",
        RegexOption.IGNORE_CASE
    )

    fun parse(rootNode: AccessibilityNodeInfo): TripOffer? {
        val card = findOfferCard(rootNode) ?: return null
        val priceNode = card.findAccessibilityNodeInfosByViewId(TRIP_INFO_ID).firstOrNull() ?: return null
        val price = priceNode.text?.toString()?.let(::extractPrice) ?: return null

        // routeTitle dentro do card, nunca da janela inteira: evita misturar
        // uma oferta em transição com texto do mapa ou de outro painel.
        val routeSegments = card.findAccessibilityNodeInfosByViewId(ROUTE_TITLE_ID)
            .mapNotNull { node -> parseSegment(node.text?.toString().orEmpty()) }
        if (routeSegments.size < 2) return null

        val pickup = routeSegments[0]
        val tripSegments = routeSegments.drop(1)
        val tripDistanceKm = tripSegments.sumOf(RouteSegment::distanceKm)
        val tripDurationMinutes = tripSegments.sumOf(RouteSegment::minutes).toDouble()
        val cardText = card.collectText()
        val addresses = RouteAddressExtractor.extract(cardText)
        val category = card.extractBoltCategory(cardText)
        return TripOffer(
            price = price,
            distanceKm = pickup.distanceKm + tripDistanceKm,
            durationMinutes = pickup.minutes + tripDurationMinutes,
            additionalInfo = cardText,
            pickupDistanceKm = pickup.distanceKm,
            pickupDurationMinutes = pickup.minutes.toDouble(),
            tripDistanceKm = tripDistanceKm,
            tripDurationMinutes = tripDurationMinutes,
            pickupAddress = addresses.pickup,
            destinationAddress = addresses.destination,
            category = category,
            tollAmount = TollAmountExtractor.extract(cardText),
            hasStops = StopDetector.hasStops(cardText, routeSegments.size),
            platform = OfferPlatform.BOLT
        )
    }

    private fun findOfferCard(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val priceNodes = rootNode.findAccessibilityNodeInfosByViewId(TRIP_INFO_ID)
        priceNodes.forEach { priceNode ->
            var candidate: AccessibilityNodeInfo? = priceNode
            repeat(MAX_PARENT_LEVELS) {
                candidate?.let { node ->
                    if (node.findAccessibilityNodeInfosByViewId(ROUTE_TITLE_ID).size >= 2) return node
                    candidate = node.parent
                }
            }
        }
        return null
    }

    private fun parseSegment(text: String): RouteSegment? {
        val match = lineSegmentRegex.find(text) ?: return null
        val minutes = match.groupValues[1].toIntOrNull() ?: return null
        val distance = match.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        return RouteSegment(minutes, distance)
    }

    private fun extractPrice(text: String): Double? =
        priceRegex.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

    private fun AccessibilityNodeInfo.collectText(): String {
        val values = mutableListOf<String>()
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(this)
        while (nodes.isNotEmpty() && values.size < MAX_TEXT_NODES) {
            val node = nodes.removeFirst()
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(values::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(values::add)
            repeat(node.childCount) { index -> node.getChild(index)?.let(nodes::addLast) }
        }
        return values.joinToString("\n")
    }

    /** Todo texto não vazio em labelText pertence à categoria exibida pela Bolt. */
    private fun AccessibilityNodeInfo.extractBoltCategory(cardText: String): String? =
        boltCategoryFromLabelTexts(
            findAccessibilityNodeInfosByViewId(LABEL_TEXT_ID)
                .mapNotNull { node -> node.text?.toString() }
        ) ?: TripCategoryExtractor.extract(cardText, OfferPlatform.BOLT)

    private data class RouteSegment(val minutes: Int, val distanceKm: Double)

    private companion object {
        const val TRIP_INFO_ID = "ee.mtakso.driver:id/tripInfo"
        const val ROUTE_TITLE_ID = "ee.mtakso.driver:id/routeTitle"
        const val LABEL_TEXT_ID = "ee.mtakso.driver:id/labelText"
        const val MAX_PARENT_LEVELS = 8
        const val MAX_TEXT_NODES = 80
    }
}

/** Aceita apenas categorias da lista suportada pela Bolt. */
internal fun boltCategoryFromLabelTexts(values: Iterable<String>): String? = values
    .asSequence()
    .map(String::trim)
    .mapNotNull { CategoryNameSanitizer.cleanForPlatform(it, OfferPlatform.BOLT) }
    .firstOrNull()
