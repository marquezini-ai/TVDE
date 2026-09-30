package com.daniel.tvdeinsight.service.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowModePolicyTest {
    private val display = OverlayBounds(0, 0, 1080, 2340)

    @Test
    fun `small Bolt target alone never means split screen`() {
        val target = VisibleApplicationWindow(7, 0, OverlayBounds(40, 980, 1040, 2100))

        assertFalse(
            OverlayWindowModePolicy.shouldConstrainToPane(
                targetWindowId = 7,
                targetDisplayId = 0,
                targetCapturesWholeDisplay = false,
                display = display,
                visibleApplicationWindows = listOf(target),
                desktopMode = false
            )
        )
    }

    @Test
    fun `floating offer over full screen app never means split screen`() {
        val boltOffer = VisibleApplicationWindow(7, 0, OverlayBounds(40, 980, 1040, 2100))
        val backgroundApp = VisibleApplicationWindow(8, 0, OverlayBounds(0, 0, 1080, 2340))

        assertFalse(
            OverlayWindowModePolicy.shouldConstrainToPane(
                targetWindowId = 7,
                targetDisplayId = 0,
                targetCapturesWholeDisplay = false,
                display = display,
                visibleApplicationWindows = listOf(boltOffer, backgroundApp),
                desktopMode = false
            )
        )
    }

    @Test
    fun `two disjoint application panes mean real split screen`() {
        val upper = VisibleApplicationWindow(7, 0, OverlayBounds(0, 0, 1080, 1120))
        val lower = VisibleApplicationWindow(8, 0, OverlayBounds(0, 1160, 1080, 2340))

        assertTrue(
            OverlayWindowModePolicy.shouldConstrainToPane(
                targetWindowId = 7,
                targetDisplayId = 0,
                targetCapturesWholeDisplay = false,
                display = display,
                visibleApplicationWindows = listOf(upper, lower),
                desktopMode = false
            )
        )
    }

    @Test
    fun `external display means DeX pane`() {
        val target = VisibleApplicationWindow(7, 2, OverlayBounds(100, 100, 900, 1600))

        assertTrue(
            OverlayWindowModePolicy.shouldConstrainToPane(
                targetWindowId = 7,
                targetDisplayId = 2,
                targetCapturesWholeDisplay = false,
                display = display,
                visibleApplicationWindows = listOf(target),
                desktopMode = false
            )
        )
    }

    @Test
    fun `system offer surface always stays in fixed phone lane`() {
        assertFalse(
            OverlayWindowModePolicy.shouldConstrainToPane(
                targetWindowId = 7,
                targetDisplayId = 2,
                targetCapturesWholeDisplay = true,
                display = display,
                visibleApplicationWindows = emptyList(),
                desktopMode = true
            )
        )
    }
}
