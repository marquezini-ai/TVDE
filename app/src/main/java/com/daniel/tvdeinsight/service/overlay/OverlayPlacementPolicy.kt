package com.daniel.tvdeinsight.service.overlay

/** Pure geometry used by the overlay manager and its local unit tests. */
internal data class OverlayBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isValid: Boolean get() = width > 0 && height > 0
}

internal data class OverlayInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

internal object OverlayPlacementPolicy {
    /**
     * Resolves a stable usable rectangle from the physical display. A floating
     * Uber offer is passed with [constrainToAnchor] false; an actual app pane in
     * split-screen/DeX is passed with it true.
     */
    fun resolve(
        display: OverlayBounds,
        insets: OverlayInsets,
        anchor: OverlayBounds?,
        constrainToAnchor: Boolean,
        avoidTopBottom: Int?,
        controlGapPx: Int,
        normalPhoneHorizontalInsetPx: Int = 0
    ): OverlayBounds {
        require(display.isValid) { "Display bounds must be valid" }

        val safe = OverlayBounds(
            left = display.left + insets.left.coerceAtLeast(0),
            top = display.top + insets.top.coerceAtLeast(0),
            right = display.right - insets.right.coerceAtLeast(0),
            bottom = display.bottom - insets.bottom.coerceAtLeast(0)
        ).takeIf(OverlayBounds::isValid) ?: display

        // A standard phone must always use one deterministic lane.  Accessibility
        // windows belonging to an offer are transient and must never move this
        // lane into the native Uber/Bolt offer card.  The values below match the
        // protected green area: a narrow horizontal gutter and a 34%-high lane
        // beginning 8.8% below the usable top edge.  Two decision cards fit in
        // this region on normal portrait phones.
        if (!constrainToAnchor) {
            val horizontalInset = normalPhoneHorizontalInsetPx
                .coerceAtLeast(0)
                .coerceAtMost((safe.width - 1) / 2)
            val laneTop = safe.top + (safe.height * NORMAL_PHONE_TOP_FRACTION).toInt()
            val laneBottom = safe.top + (safe.height * NORMAL_PHONE_BOTTOM_FRACTION).toInt()
            return OverlayBounds(
                left = safe.left + horizontalInset,
                top = laneTop.coerceIn(safe.top, safe.bottom - 1),
                right = safe.right - horizontalInset,
                bottom = laneBottom.coerceIn(laneTop + 1, safe.bottom)
            )
        }

        val constrained = if (anchor?.isValid == true) {
            intersection(safe, anchor) ?: safe
        } else {
            safe
        }

        val requestedTop = avoidTopBottom
            ?.plus(controlGapPx.coerceAtLeast(0))
            ?.coerceAtLeast(constrained.top)
            ?: constrained.top
        return constrained.copy(top = requestedTop.coerceAtMost(constrained.bottom - 1))
    }

    private fun intersection(first: OverlayBounds, second: OverlayBounds): OverlayBounds? {
        val result = OverlayBounds(
            left = maxOf(first.left, second.left),
            top = maxOf(first.top, second.top),
            right = minOf(first.right, second.right),
            bottom = minOf(first.bottom, second.bottom)
        )
        return result.takeIf(OverlayBounds::isValid)
    }

    private const val NORMAL_PHONE_TOP_FRACTION = 0.088
    private const val NORMAL_PHONE_BOTTOM_FRACTION = 0.43
}
