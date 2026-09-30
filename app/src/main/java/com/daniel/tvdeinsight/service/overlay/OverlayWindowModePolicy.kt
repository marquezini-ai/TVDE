package com.daniel.tvdeinsight.service.overlay

internal data class VisibleApplicationWindow(
    val windowId: Int,
    val displayId: Int,
    val bounds: OverlayBounds
)

/** Decides whether cards must stay inside a real split-screen/DeX pane. */
internal object OverlayWindowModePolicy {
    fun shouldConstrainToPane(
        targetWindowId: Int,
        targetDisplayId: Int,
        targetCapturesWholeDisplay: Boolean,
        display: OverlayBounds,
        visibleApplicationWindows: List<VisibleApplicationWindow>,
        desktopMode: Boolean
    ): Boolean {
        if (targetCapturesWholeDisplay) return false
        if (desktopMode || targetDisplayId != DEFAULT_DISPLAY_ID) return true
        if (!display.isValid) return false

        val target = visibleApplicationWindows.firstOrNull {
            it.windowId == targetWindowId && it.displayId == targetDisplayId && it.bounds.isValid
        } ?: return false
        val displayArea = area(display)
        if (displayArea <= 0L) return false

        return visibleApplicationWindows.any { other ->
            if (
                other.windowId == target.windowId ||
                other.displayId != target.displayId ||
                !other.bounds.isValid
            ) return@any false

            val targetArea = area(target.bounds)
            val otherArea = area(other.bounds)
            val smallerArea = minOf(targetArea, otherArea)
            if (
                targetArea * 100 < displayArea * MIN_PANE_AREA_PERCENT ||
                otherArea * 100 < displayArea * MIN_PANE_AREA_PERCENT
            ) return@any false

            val overlapArea = intersectionArea(target.bounds, other.bounds)
            val materiallyDisjoint = overlapArea * 100 <= smallerArea * MAX_OVERLAP_PERCENT
            val combinedCoverage = (targetArea + otherArea - overlapArea) * 100 >=
                displayArea * MIN_COMBINED_COVERAGE_PERCENT
            materiallyDisjoint && combinedCoverage
        }
    }

    private fun area(bounds: OverlayBounds): Long =
        bounds.width.toLong().coerceAtLeast(0L) * bounds.height.toLong().coerceAtLeast(0L)

    private fun intersectionArea(first: OverlayBounds, second: OverlayBounds): Long {
        val width = (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0)
        val height = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0)
        return width.toLong() * height.toLong()
    }

    private const val DEFAULT_DISPLAY_ID = 0
    private const val MIN_PANE_AREA_PERCENT = 15L
    private const val MIN_COMBINED_COVERAGE_PERCENT = 45L
    private const val MAX_OVERLAP_PERCENT = 5L
}
