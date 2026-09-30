package com.daniel.tvdeinsight.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo

internal data class AppWindowTarget(
    val bounds: Rect,
    val displayId: Int,
    val windowId: Int,
    /** A janela é uma sobreposição do sistema; a captura precisa ser do display. */
    val captureWholeDisplay: Boolean = false
)

/** Reads window metadata only; Uber text is still obtained exclusively through OCR on API 33+. */
@Suppress("DEPRECATION")
internal fun AccessibilityService.appWindowTarget(packageFragment: String): AppWindowTarget? {
    val candidates = mutableListOf<AppWindowTarget>()
    val displays = if (Build.VERSION.SDK_INT >= 30) windowsOnAllDisplays else null
    val groups = if (displays != null) (0 until displays.size()).map { displays.keyAt(it) to displays.valueAt(it) }
        else listOf(Display.DEFAULT_DISPLAY to windows)
    for ((displayId, windows) in groups) for (window in windows) {
        try {
            // Quando outra aplicação está ativa, a Activity de base da Uber
            // continua listada pelo Android, porém está sem Surface. Capturá-la
            // faz takeScreenshotOfWindow bloquear por segundos. Um painel real
            // em segundo plano é uma APPLICATION_OVERLAY (TYPE_SYSTEM aqui).
            if (
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                !window.isActive && !window.isFocused
            ) continue
            val node = window.root ?: continue
            try {
                if (!node.packageName.toString().contains(packageFragment, ignoreCase = true)) continue
                val bounds = Rect().also(window::getBoundsInScreen)
                // Reject Uber's 167px shortcut window without hardcoding screen halves.
                if (bounds.width() >= 240 && bounds.height() >= 240) {
                    candidates += AppWindowTarget(
                        bounds = bounds,
                        displayId = displayId,
                        windowId = window.id,
                        captureWholeDisplay = window.type == AccessibilityWindowInfo.TYPE_SYSTEM
                    )
                }
            } finally { node.recycle() }
        } finally { window.recycle() }
    }
    return candidates.maxWithOrNull(
        compareBy<AppWindowTarget> { it.captureWholeDisplay }
            .thenBy { it.bounds.width().toLong() * it.bounds.height() }
    )
}

/**
 * Android expõe APPLICATION_OVERLAY como TYPE_SYSTEM e não fornece o pacote
 * proprietário no AccessibilityWindowInfo. O chamador deve correlacionar este
 * painel a um evento recente da Uber antes de o utilizar.
 */
internal fun AccessibilityService.largeSystemOverlayTarget(
    minimumWidthPx: Int,
    minimumHeightPx: Int
): AppWindowTarget? {
    val candidates = mutableListOf<AppWindowTarget>()
    val displays = if (Build.VERSION.SDK_INT >= 30) windowsOnAllDisplays else null
    val groups = if (displays != null) (0 until displays.size()).map { displays.keyAt(it) to displays.valueAt(it) }
    else listOf(Display.DEFAULT_DISPLAY to windows)
    for ((displayId, windows) in groups) for (window in windows) {
        try {
            if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val bounds = Rect().also(window::getBoundsInScreen)
            if (bounds.width() < minimumWidthPx || bounds.height() < minimumHeightPx) continue
            candidates += AppWindowTarget(
                bounds = bounds,
                displayId = displayId,
                windowId = window.id,
                captureWholeDisplay = true
            )
        } finally { window.recycle() }
    }
    return candidates.maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }
}
