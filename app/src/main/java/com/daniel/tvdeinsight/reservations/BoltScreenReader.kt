package com.daniel.tvdeinsight.reservations

import android.view.accessibility.AccessibilityNodeInfo

data class BoltScreen(val isBoltVisible: Boolean, val isPedidos: Boolean)

object BoltScreenReader {
    const val SCHEDULED_SEGMENT_ID = "ee.mtakso.driver:id/scheduledSegment"
    const val ACCEPTED_SEGMENT_ID = "ee.mtakso.driver:id/acceptedSegment"
    const val PEDIDOS_TITLE_ID = "ee.mtakso.driver:id/title"

    fun read(root: AccessibilityNodeInfo?): BoltScreen {
        if (root == null || root.packageName?.toString() != AutomationContract.BOLT_PACKAGE) {
            return BoltScreen(false, false)
        }
        val acceptedSegment = AccessibilityNodeUtils.findFirstById(root, ACCEPTED_SEGMENT_ID)
        val scheduledSegment = AccessibilityNodeUtils.findFirstById(root, SCHEDULED_SEGMENT_ID)
        val pedidosTitle = AccessibilityNodeUtils.findFirstById(root, PEDIDOS_TITLE_ID)
        val titleText = AccessibilityNodeUtils.normalizeForComparison(AccessibilityNodeUtils.textOf(pedidosTitle))
        val acceptedText = AccessibilityNodeUtils.findById(root, PEDIDOS_TITLE_ID)
            .map(AccessibilityNodeUtils::textOf)
            .firstOrNull { AccessibilityNodeUtils.normalizeForComparison(it) == "aceites" }
            .orEmpty()
            .let(AccessibilityNodeUtils::normalizeForComparison)

        val pedidos = when {
            acceptedSegment?.isSelected == true -> false
            scheduledSegment?.isSelected == true -> true
            titleText == "pedidos" -> true
            acceptedText == "aceites" && titleText.isBlank() -> false
            else -> AccessibilityNodeUtils.findText(root, "Pedidos") != null &&
                AccessibilityNodeUtils.findText(root, "Aceites")?.isSelected != true
        }
        return BoltScreen(true, pedidos)
    }
}
