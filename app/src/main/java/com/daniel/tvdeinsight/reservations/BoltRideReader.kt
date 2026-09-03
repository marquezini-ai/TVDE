package com.daniel.tvdeinsight.reservations

import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

data class RideHit(
    val candidate: RideCandidate,
    val clickNode: AccessibilityNodeInfo,
    val clickTargets: List<AccessibilityNodeInfo>
)

/** Leitura dos cartões mantendo os campos separados como na MacroDroid. */
object BoltRideReader {
    // Os sufixos $2/$3/$4/$5 do MacroDroid distinguem ocorrências repetidas;
    // não fazem parte do viewId real exposto pela árvore Android.
    private const val CATEGORY_ID = "ee.mtakso.driver:id/labelText"

    fun findCandidates(root: AccessibilityNodeInfo): List<RideHit> {
        // “Pedido aceite” e “Ocorreu um erro” são diálogos de resultado, não
        // ofertas. Impede que o texto do diálogo seja gravado como uma viagem.
        val screenText = AccessibilityNodeUtils.normalizeForComparison(AccessibilityNodeUtils.fullText(root))
        if (RESULT_DIALOG_MARKERS.any(screenText::contains)) return emptyList()
        val results = linkedMapOf<String, RideHit>()
        for (categoryNode in AccessibilityNodeUtils.findById(root, CATEGORY_ID)) {
            val categoryText = AccessibilityNodeUtils.textOf(categoryNode)
            if (!isCategory(categoryText)) continue
            val card = findRideContainer(categoryNode) ?: continue
            val cardText = AccessibilityNodeUtils.fullText(card)
            val route = RideParser.extractRoute(AccessibilityNodeUtils.leafTexts(card), cardText)
            val candidate = RideParser.parseCardText(cardText)?.copy(
                tripDate = TripDateResolver.resolve(cardText),
                origin = route.first,
                destination = route.second
            )
            DiagnosticLogger.log("Campos de cartão: categoria='$categoryText', texto='${cardText.take(220)}', parse=${candidate != null}")
            if (candidate != null) {
                val clickTargets = clickTargets(categoryNode, card)
                results[candidate.fingerprint] = RideHit(candidate, clickTargets.first(), clickTargets)
            }
        }

        // Algumas versões da Bolt alteram a hierarquia e deixam de expor um dos
        // IDs da macro. Nesse caso, tenta-se o cartão agregado como fallback,
        // sem abandonar o caminho principal baseado nos IDs conhecidos.
        AccessibilityNodeUtils.visit(root) { node ->
            if (results.size >= MAX_CANDIDATES) return@visit
            val text = AccessibilityNodeUtils.fullText(node)
            if (text.length !in 10..400) return@visit
            val clickNode = AccessibilityNodeUtils.clickableAncestor(node) ?: return@visit
            val candidate = RideParser.parseCardText(text) ?: return@visit
            results.putIfAbsent(candidate.fingerprint, RideHit(candidate, clickNode, listOf(clickNode)))
        }
        return results.values.toList()
    }

    private fun findRideContainer(categoryNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = categoryNode
        repeat(MAX_PARENT_LEVELS) {
            if (current == null) return null
            if (RideParser.parseCardText(AccessibilityNodeUtils.fullText(current)) != null) return current
            current = current?.parent
        }
        return null
    }

    private fun isCategory(text: String): Boolean = CATEGORY_REGEX.containsMatchIn(text)

    private fun clickTargets(categoryNode: AccessibilityNodeInfo, card: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        return listOfNotNull(
            AccessibilityNodeUtils.clickableAncestor(categoryNode, maxLevels = 8),
            AccessibilityNodeUtils.clickableAncestor(card, maxLevels = 8),
            card
        ).distinctBy { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            "${node.viewIdResourceName}|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        }
    }

    private val CATEGORY_REGEX = Regex("(?i)\\b(Bolt|Green|Comfort|Premium|XL|Pet|Gama\\s+el[eé]trica)\\b")
    private val RESULT_DIALOG_MARKERS = listOf("pedido aceite", "ocorreu um erro")
    private const val MAX_CANDIDATES = 30
    private const val MAX_PARENT_LEVELS = 12
}
