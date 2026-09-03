package com.daniel.tvdeinsight.reservations

import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.Locale

object AccessibilityNodeUtils {
    fun textOf(node: AccessibilityNodeInfo?): String = listOfNotNull(
        node?.text?.toString(), node?.contentDescription?.toString()
    ).joinToString(" ").normalizeSpaces()

    fun fullText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val parts = ArrayList<String>()
        textOf(node).takeIf(String::isNotBlank)?.let(parts::add)
        for (index in 0 until node.childCount) {
            fullText(node.getChild(index)).takeIf(String::isNotBlank)?.let(parts::add)
        }
        return parts.joinToString(" ").normalizeSpaces()
    }

    fun findById(root: AccessibilityNodeInfo?, id: String): List<AccessibilityNodeInfo> =
        runCatching { root?.findAccessibilityNodeInfosByViewId(id).orEmpty() }.getOrDefault(emptyList())

    fun findFirstById(root: AccessibilityNodeInfo?, id: String): AccessibilityNodeInfo? =
        findById(root, id).firstOrNull()

    fun findText(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val wanted = normalizeForComparison(label)
        if (normalizeForComparison(textOf(root)).contains(wanted)) return root
        for (index in 0 until root.childCount) {
            findText(root.getChild(index), label)?.let { return it }
        }
        return null
    }

    fun clickableAncestor(node: AccessibilityNodeInfo?, maxLevels: Int = 12): AccessibilityNodeInfo? {
        var current = node
        repeat(maxLevels) {
            if (current == null) return null
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
        }
        return null
    }

    fun click(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return clickableAncestor(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    fun visit(root: AccessibilityNodeInfo?, visitor: (AccessibilityNodeInfo) -> Unit) {
        if (root == null) return
        visitor(root)
        for (index in 0 until root.childCount) visit(root.getChild(index), visitor)
    }

    fun leafTexts(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        if (root.childCount == 0) return textOf(root).takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
        val result = buildList {
            for (index in 0 until root.childCount) addAll(leafTexts(root.getChild(index)))
        }
        return if (result.isNotEmpty()) result else textOf(root).takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
    }

    fun normalizeForComparison(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase(Locale.ROOT)
        .normalizeSpaces()

    private fun String.normalizeSpaces() = replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
}
