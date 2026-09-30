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
        val card = findOfferCard(rootNode) ?: findSemanticOfferCard(rootNode) ?: return null
        try {
            val cardText = card.collectText()
            val priceNodes = card.findAccessibilityNodeInfosByViewId(TRIP_INFO_ID)
            val price = try {
                priceNodes.firstOrNull()?.text?.toString()?.let(::extractPrice)
                    ?: extractPrice(cardText)
                    ?: return null
            } finally {
                priceNodes.recycleAll()
            }

            // routeTitle dentro do card, nunca da janela inteira: evita misturar
            // uma oferta em transição com texto do mapa ou de outro painel.
            val routeNodes = card.findAccessibilityNodeInfosByViewId(ROUTE_TITLE_ID)
            val routeSegments = try {
                routeNodes.mapNotNull { node -> parseSegment(node.text?.toString().orEmpty()) }
                    .ifEmpty { collectSemanticRouteSegments(card) }
            } finally {
                routeNodes.recycleAll()
            }
            if (routeSegments.size < 2) return null

            val pickup = routeSegments[0]
            val tripSegments = routeSegments.drop(1)
            val tripDistanceKm = tripSegments.sumOf(RouteSegment::distanceKm)
            val tripDurationMinutes = tripSegments.sumOf(RouteSegment::minutes).toDouble()
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
        } finally {
            card.recycle()
        }
    }

    private fun findOfferCard(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val priceNodes = rootNode.findAccessibilityNodeInfosByViewId(TRIP_INFO_ID)
        try {
            priceNodes.forEach { priceNode ->
                var candidate: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(priceNode)
                var level = 0
                while (candidate != null && level++ < MAX_PARENT_LEVELS) {
                    val node = candidate
                    val routeNodes = node.findAccessibilityNodeInfosByViewId(ROUTE_TITLE_ID)
                    val matches = routeNodes.size >= 2
                    routeNodes.recycleAll()
                    if (matches) {
                        val result = AccessibilityNodeInfo.obtain(node)
                        node.recycle()
                        return result
                    }
                    candidate = node.parent
                    node.recycle()
                }
            }
        } finally {
            priceNodes.recycleAll()
        }
        return null
    }

    /**
     * Fallback for Bolt layout variants that keep the values accessible but
     * change/remove the internal resource IDs. It is reached only after the
     * cheap ID-based lookup fails and stays inside the Bolt window subtree.
     */
    private fun findSemanticOfferCard(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = ArrayDeque<NodeRef>()
        nodes.add(NodeRef(rootNode, owned = false))
        var inspected = 0
        try {
            while (nodes.isNotEmpty() && inspected++ < MAX_FALLBACK_NODES) {
                val ref = nodes.removeFirst()
                val node = ref.node
                try {
                    val nodeText = node.text?.toString().orEmpty()
                    if (extractPrice(nodeText) != null) {
                        var candidate: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                        var level = 0
                        while (candidate != null && level++ < MAX_PARENT_LEVELS) {
                            val current = candidate
                            if (collectSemanticRouteSegments(current).size >= 2) {
                                val result = AccessibilityNodeInfo.obtain(current)
                                current.recycle()
                                return result
                            }
                            candidate = current.parent
                            current.recycle()
                        }
                    }
                    repeat(node.childCount) { index ->
                        node.getChild(index)?.let { nodes.addLast(NodeRef(it, owned = true)) }
                    }
                } finally {
                    if (ref.owned) node.recycle()
                }
            }
        } finally {
            nodes.forEach { if (it.owned) it.node.recycle() }
        }
        return null
    }

    private fun collectSemanticRouteSegments(card: AccessibilityNodeInfo): List<RouteSegment> {
        val values = ArrayList<RouteSegment>(3)
        val nodes = ArrayDeque<NodeRef>()
        nodes.add(NodeRef(card, owned = false))
        var inspected = 0
        try {
            while (nodes.isNotEmpty() && inspected++ < MAX_FALLBACK_NODES) {
                val ref = nodes.removeFirst()
                try {
                    parseSegment(ref.node.text?.toString().orEmpty())?.let(values::add)
                    repeat(ref.node.childCount) { index ->
                        ref.node.getChild(index)?.let { nodes.addLast(NodeRef(it, owned = true)) }
                    }
                } finally {
                    if (ref.owned) ref.node.recycle()
                }
            }
        } finally {
            nodes.forEach { if (it.owned) it.node.recycle() }
        }
        return values.distinctBy { it.minutes to it.distanceKm }
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
        val nodes = ArrayDeque<NodeRef>()
        nodes.add(NodeRef(this, owned = false))
        try {
            while (nodes.isNotEmpty() && values.size < MAX_TEXT_NODES) {
                val ref = nodes.removeFirst()
                try {
                    ref.node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(values::add)
                    ref.node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(values::add)
                    repeat(ref.node.childCount) { index ->
                        ref.node.getChild(index)?.let { nodes.addLast(NodeRef(it, owned = true)) }
                    }
                } finally {
                    if (ref.owned) ref.node.recycle()
                }
            }
        } finally {
            nodes.forEach { if (it.owned) it.node.recycle() }
        }
        return values.joinToString("\n")
    }

    /** Todo texto não vazio em labelText pertence à categoria exibida pela Bolt. */
    private fun AccessibilityNodeInfo.extractBoltCategory(cardText: String): String? {
        val labelNodes = findAccessibilityNodeInfosByViewId(LABEL_TEXT_ID)
        return try {
            boltCategoryFromLabelTexts(labelNodes.mapNotNull { node -> node.text?.toString() })
                ?: TripCategoryExtractor.extract(cardText, OfferPlatform.BOLT)
        } finally {
            labelNodes.recycleAll()
        }
    }

    private fun Iterable<AccessibilityNodeInfo>.recycleAll() = forEach(AccessibilityNodeInfo::recycle)

    private data class NodeRef(val node: AccessibilityNodeInfo, val owned: Boolean)

    private data class RouteSegment(val minutes: Int, val distanceKm: Double)

    private companion object {
        const val TRIP_INFO_ID = "ee.mtakso.driver:id/tripInfo"
        const val ROUTE_TITLE_ID = "ee.mtakso.driver:id/routeTitle"
        const val LABEL_TEXT_ID = "ee.mtakso.driver:id/labelText"
        const val MAX_PARENT_LEVELS = 8
        const val MAX_TEXT_NODES = 80
        const val MAX_FALLBACK_NODES = 120
    }
}

/** Aceita apenas categorias da lista suportada pela Bolt. */
internal fun boltCategoryFromLabelTexts(values: Iterable<String>): String? = values
    .asSequence()
    .map(String::trim)
    .mapNotNull { CategoryNameSanitizer.cleanForPlatform(it, OfferPlatform.BOLT) }
    .firstOrNull()
