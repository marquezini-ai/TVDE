package com.daniel.tvdeinsight.service.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPlacementPolicyTest {
    private val display = OverlayBounds(0, 0, 1080, 2340)
    private val systemInsets = OverlayInsets(top = 84, bottom = 120)

    @Test
    fun `normal phone uses the fixed protected lane and ignores suspended Uber rectangle`() {
        val suspendedUberOffer = OverlayBounds(48, 280, 1032, 1080)

        val result = OverlayPlacementPolicy.resolve(
            display = display,
            insets = systemInsets,
            anchor = suspendedUberOffer,
            constrainToAnchor = false,
            avoidTopBottom = null,
            controlGapPx = 24,
            normalPhoneHorizontalInsetPx = 48
        )

        assertEquals(OverlayBounds(48, 271, 1032, 1002), result)
    }

    @Test
    fun `split screen is constrained to the real app pane`() {
        val rightPane = OverlayBounds(540, 84, 1080, 2220)

        val result = OverlayPlacementPolicy.resolve(
            display = display,
            insets = systemInsets,
            anchor = rightPane,
            constrainToAnchor = true,
            avoidTopBottom = null,
            controlGapPx = 24
        )

        assertEquals(rightPane, result)
    }

    @Test
    fun `normal phone lane is not moved by a Bolt body label`() {
        val result = OverlayPlacementPolicy.resolve(
            display = display,
            insets = systemInsets,
            anchor = null,
            constrainToAnchor = false,
            // A stale body-text match must never shift the fixed lane.
            avoidTopBottom = 1514,
            controlGapPx = 24,
            normalPhoneHorizontalInsetPx = 48
        )

        assertEquals(OverlayBounds(48, 271, 1032, 1002), result)
    }

    @Test
    fun `invalid transient anchor never collapses the usable display`() {
        val offDisplay = OverlayBounds(1600, 300, 2100, 900)

        val result = OverlayPlacementPolicy.resolve(
            display = display,
            insets = systemInsets,
            anchor = offDisplay,
            constrainToAnchor = true,
            avoidTopBottom = null,
            controlGapPx = 24
        )

        assertEquals(OverlayBounds(0, 84, 1080, 2220), result)
    }
}
