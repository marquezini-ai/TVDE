package com.daniel.tvdeinsight.service.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.*
import com.daniel.tvdeinsight.domain.model.DecisionType
import com.daniel.tvdeinsight.domain.model.EvaluationCriterion
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import com.daniel.tvdeinsight.domain.model.RuleResult
import com.daniel.tvdeinsight.data.repository.ThemePreferencesRepository
import com.daniel.tvdeinsight.logging.AppLogger
import com.daniel.tvdeinsight.R
import com.daniel.tvdeinsight.ui.theme.DecisionColors
import com.daniel.tvdeinsight.ui.theme.ThemeMode
import com.daniel.tvdeinsight.ui.theme.decisionColors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val PORTUGUESE_LOCALE = Locale("pt", "PT")

private fun Rect.toOverlayBounds() = OverlayBounds(left, top, right, bottom)
private fun OverlayBounds.toAndroidRect() = Rect(left, top, right, bottom)

@Singleton
class DecisionOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    themePreferencesRepository: ThemePreferencesRepository
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private data class Entry(
        val platform: OfferPlatform, val view: android.widget.FrameLayout, val compose: ComposeView,
        val owner: OverlayLifecycleOwner, val store: ViewModelStore,
        val manager: WindowManager, val displayContext: Context, val displayId: Int,
        val windowType: Int,
        var decision: RuleResult, var anchor: Rect?, var constrainToAnchor: Boolean,
        var avoidTopPx: Int?, var hideJob: Job? = null
    )
    /** One touchable window per display; all platform cards are children of it. */
    private data class Host(
        val root: android.widget.FrameLayout,
        val manager: WindowManager,
        val context: Context,
        val displayId: Int,
        val windowType: Int,
        val params: WindowManager.LayoutParams,
        val owner: OverlayLifecycleOwner,
        val store: ViewModelStore
    )
    private val entries = linkedMapOf<OfferPlatform, Entry>()
    private val hosts = linkedMapOf<Int, Host>()
    private val dismissed = mutableMapOf<OfferPlatform, Pair<RuleResult, Long>>()
    private var themeMode = ThemeMode.AUTOMATIC
    /**
     * A context supplied by the active AccessibilityService carries Android's
     * accessibility-overlay token.  This layer is above application-owned
     * offer panels (including the Uber Radar sheet), unlike a generic app
     * overlay which some OEMs can place underneath that sheet.
     */
    private var accessibilityOverlayContext: Context? = null

    init {
        scope.launch {
            themePreferencesRepository.themeMode.collect {
                themeMode = it
                entries.values.forEach(::render)
            }
        }
    }

    fun attachAccessibilityOverlayContext(serviceContext: Context) {
        // onServiceConnected() and showDecision() normally run on the main
        // looper. Set the context synchronously there so the first offer cannot
        // race the post and fall back to an invalid application window token.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            accessibilityOverlayContext = serviceContext
        } else {
            mainHandler.post { accessibilityOverlayContext = serviceContext }
        }
    }

    fun detachAccessibilityOverlayContext(serviceContext: Context) {
        mainHandler.post {
            if (accessibilityOverlayContext === serviceContext) {
                accessibilityOverlayContext = null
            }
        }
    }

    fun showDecision(
        decision: RuleResult,
        anchor: Rect? = null,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
        avoidTopPx: Int? = null,
        constrainToAnchor: Boolean = false
    ) {
        mainHandler.post {
            // Accessibility overlays are authorised by the running service and
            // do not require the user to grant SYSTEM_ALERT_WINDOW. Checking
            // canDrawOverlays() unconditionally silently dropped cards whenever
            // the launcher/another app was in front. Only require that setting
            // when we truly have to fall back to an application overlay.
            if (accessibilityOverlayContext == null && !Settings.canDrawOverlays(context)) return@post
            val priorDismissal = dismissed[decision.platform]
            if (priorDismissal != null && priorDismissal.first == decision &&
                android.os.SystemClock.elapsedRealtime() - priorDismissal.second < 20_000L) return@post
            var entry = entries[decision.platform]
            if (entry != null && entry.displayId != displayId) {
                removeNow(decision.platform)
                entry = null
            }
            if (entry == null) {
                val display = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId)
                    ?: return@post
                val accessibilityContext = accessibilityOverlayContext
                val useAccessibilityOverlay = accessibilityContext != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                val windowType = if (useAccessibilityOverlay) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                }
                // TYPE_ACCESSIBILITY_OVERLAY must use the WindowManager obtained
                // from the AccessibilityService context itself. Creating a
                // TYPE_ACCESSIBILITY_OVERLAY WindowContext on Android 12/Samsung
                // can lose the service token and throw BadTokenException on the
                // first card. Generic application overlays still use a proper
                // WindowContext as required by Android 11+.
                val displayContext = if (useAccessibilityOverlay && displayId == android.view.Display.DEFAULT_DISPLAY) {
                    accessibilityContext!!
                } else {
                    (accessibilityContext ?: context).createDisplayContext(display)
                }
                val windowContext = if (useAccessibilityOverlay) {
                    displayContext
                } else if (Build.VERSION.SDK_INT >= 30) {
                    displayContext.createWindowContext(windowType, null)
                } else {
                    displayContext
                }
                val manager = windowContext.getSystemService(WindowManager::class.java)
                val owner = OverlayLifecycleOwner().apply {
                    performRestore(null)
                    handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                }
                val store = ViewModelStore()
                // Intercept at dispatch level on ALL Android versions, including Compose descendants.
                val view = object : android.widget.FrameLayout(windowContext) {
                    private var downX = 0f
                    private var downY = 0f
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY }
                            MotionEvent.ACTION_UP -> {
                                val slop = android.view.ViewConfiguration.get(windowContext).scaledTouchSlop
                                if (kotlin.math.abs(event.rawX - downX) <= slop && kotlin.math.abs(event.rawY - downY) <= slop) {
                                    performClick()
                                    dismiss(decision.platform)
                                }
                            }
                        }
                        return true
                    }
                }
                view.setViewTreeLifecycleOwner(owner)
                view.setViewTreeSavedStateRegistryOwner(owner)
                view.setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner { override val viewModelStore = store })
                val compose = ComposeView(windowContext)
                view.addView(compose, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
                val created = Entry(decision.platform, view, compose, owner, store, manager, windowContext,
                    displayId, windowType, decision, anchor?.let(::Rect), constrainToAnchor, avoidTopPx)
                render(created)
                try {
                    // Keep exactly one accessibility-overlay window per display.
                    // Independent windows race in z-order and can nest one card over
                    // another when Uber and Bolt offers arrive together.
                    val host = hosts[displayId] ?: createHost(windowContext, displayId, windowType, manager)
                    host.root.addView(view, android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    ))
                    owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                    entries[decision.platform] = created
                    view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> relayout() }
                    compose.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> relayout() }
                    relayoutAfterAttach(view)
                    entry = created
                    mainHandler.post(::relayout)
                } catch (error: Exception) {
                    hosts[displayId]?.let { host ->
                        if (host.root.childCount == 0) {
                            runCatching { host.manager.removeViewImmediate(host.root) }
                            host.owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                            host.store.clear()
                            hosts.remove(displayId)
                        }
                    }
                    compose.disposeComposition()
                    owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                    store.clear()
                    AppLogger.warn("Não foi possível apresentar o card", error)
                    return@post
                }
            } else {
                entry.decision = decision
                entry.anchor = anchor?.let(::Rect)
                entry.constrainToAnchor = constrainToAnchor
                entry.avoidTopPx = avoidTopPx
                render(entry)
            }
            val current = entry ?: return@post
            current.hideJob?.cancel()
            current.hideJob = scope.launch { delay(10_000L); removeNow(decision.platform); relayout() }
            relayout()
        }
    }

    private fun createHost(
        displayContext: Context,
        displayId: Int,
        windowType: Int,
        manager: WindowManager
    ): Host {
        // Compose resolves the window recomposer from the root view tree when
        // the host is attached. The old per-card owner was attached below the
        // window root, which is not visible to Android 16's lookup and crashed
        // the accessibility service on the first rendered card.
        val owner = OverlayLifecycleOwner().apply {
            performRestore(null)
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        val store = ViewModelStore()
        val root = android.widget.FrameLayout(displayContext).apply {
            clipChildren = true
            clipToPadding = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner {
                override val viewModelStore = store
            })
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        try {
            manager.addView(root, params)
        } catch (error: Exception) {
            owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
            throw error
        }
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        return Host(root, manager, displayContext, displayId, windowType, params, owner, store).also {
            hosts[displayId] = it
        }
    }

    fun updateAnchor(
        platform: OfferPlatform,
        anchor: Rect,
        displayId: Int,
        constrainToAnchor: Boolean = false
    ) {
        mainHandler.post {
            val entry = entries[platform] ?: return@post
            if (entry.displayId != displayId) {
                showDecision(entry.decision, anchor, displayId, entry.avoidTopPx, constrainToAnchor)
            } else {
                entry.anchor = Rect(anchor)
                entry.constrainToAnchor = constrainToAnchor
                relayout()
            }
        }
    }

    private fun render(entry: Entry) {
        val dark = when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.AUTOMATIC -> entry.displayContext.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        entry.compose.setContent { DecisionCardOverlay(entry.decision, dark) { dismiss(entry.platform) } }
    }

    /**
     * WindowManager.addView() may return before Android dispatches attachment
     * to the accessibility-overlay root. Compose cannot be measured until that
     * happens because its WindowRecomposer does not exist yet.
     */
    private fun relayoutAfterAttach(view: android.view.View) {
        if (view.isAttachedToWindow) {
            view.post(::relayout)
            return
        }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attachedView: android.view.View) {
                attachedView.removeOnAttachStateChangeListener(this)
                attachedView.post(::relayout)
            }

            override fun onViewDetachedFromWindow(detachedView: android.view.View) = Unit
        })
    }

    private fun dismiss(platform: OfferPlatform) {
        entries[platform]?.let { dismissed[platform] = it.decision to android.os.SystemClock.elapsedRealtime() }
        removeNow(platform)
        relayout()
    }

    private fun area(entry: Entry): Rect {
        /*
         * currentWindowMetrics is deliberately not used here. For an
         * accessibility overlay it can describe the overlay host itself after
         * updateViewLayout(), so every relayout feeds the previous card
         * position back into the next calculation and walks the cards down the
         * screen. maximumWindowMetrics is stable and represents the physical
         * display; split-screen/DeX is applied explicitly through the app pane
         * anchor below.
         */
        val displayBounds: Rect
        val safe: android.graphics.Insets?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = entry.manager.maximumWindowMetrics
            displayBounds = Rect(metrics.bounds)
            safe = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout()
            )
        } else {
            val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(entry.displayId)
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display?.getRealSize(size)
            displayBounds = Rect(
                0,
                0,
                size.x.takeIf { it > 0 } ?: entry.displayContext.resources.displayMetrics.widthPixels,
                size.y.takeIf { it > 0 } ?: entry.displayContext.resources.displayMetrics.heightPixels
            )
            safe = null
        }
        val resolved = OverlayPlacementPolicy.resolve(
            display = displayBounds.toOverlayBounds(),
            insets = OverlayInsets(
                left = safe?.left ?: 0,
                top = safe?.top ?: 0,
                right = safe?.right ?: 0,
                bottom = safe?.bottom ?: 0
            ),
            anchor = entry.anchor?.toOverlayBounds(),
            constrainToAnchor = entry.constrainToAnchor,
            avoidTopBottom = entry.avoidTopPx,
            controlGapPx = (8 * entry.displayContext.resources.displayMetrics.density).toInt(),
            // 18 dp becomes 47 px on the S25 (420 dpi), exactly matching the
            // horizontal limits of the requested green safe area.
            normalPhoneHorizontalInsetPx = (18 * entry.displayContext.resources.displayMetrics.density).toInt()
        )
        return resolved.toAndroidRect()
    }

    /**
     * Layout every card inside its display host.  The host is resized to the
     * measured stack, so no card can overlap another because a separate window
     * reported a temporary zero height during Compose's first frame.
     */
    private fun relayout() {
        try {
            relayoutAttachedHosts()
        } catch (error: Exception) {
            // A presentation failure must never terminate the 24/7
            // AccessibilityService and consequently stop every Uber/Bolt read.
            AppLogger.warn("Falha isolada ao calcular posição dos cards", error)
        }
    }

    private fun relayoutAttachedHosts() {
        entries.values.groupBy { it.displayId }.forEach { (displayId, grouped) ->
            val host = hosts[displayId] ?: return@forEach
            val gap = (6 * grouped.first().displayContext.resources.displayMetrics.density).toInt()
            val candidates = grouped.map { entry ->
                val available = area(entry)
                // Never call measure() here. On Android 16 the accessibility
                // overlay is attached asynchronously; forcing Compose to
                // measure before attachment throws "Cannot locate
                // windowRecomposer" and terminates the accessibility service.
                // The first frame uses the bounded fallback and the normal
                // layout callback replaces it with the actual measured height.
                LayoutCandidate(
                    entry = entry,
                    area = available,
                    height = entry.view.measuredHeight
                        .takeIf { entry.view.isAttachedToWindow && it > 0 }
                        ?: estimatedHeight(entry)
                )
            }
            if (candidates.isEmpty()) return@forEach

            // Cards whose real app areas overlap belong to the same lane and
            // are stacked. Disjoint split-screen/DeX panes each start at their
            // own top edge instead of being pushed below the other app.
            val remaining = candidates.toMutableList()
            val placements = mutableListOf<CardPlacement>()
            while (remaining.isNotEmpty()) {
                val lane = mutableListOf(remaining.removeAt(0))
                var expanded: Boolean
                do {
                    expanded = false
                    val iterator = remaining.iterator()
                    while (iterator.hasNext()) {
                        val candidate = iterator.next()
                        if (lane.any { Rect.intersects(it.area, candidate.area) }) {
                            lane += candidate
                            iterator.remove()
                            expanded = true
                        }
                    }
                } while (expanded)

                var laneCursor = lane.maxOf { it.area.top }
                lane.forEach { candidate ->
                    val bottom = (laneCursor + candidate.height)
                        .coerceAtMost(candidate.area.bottom)
                        .coerceAtLeast(laneCursor + 1)
                    placements += CardPlacement(
                        candidate = candidate,
                        bounds = Rect(candidate.area.left, laneCursor, candidate.area.right, bottom)
                    )
                    laneCursor = bottom + gap
                }
            }

            val hostLeft = placements.minOf { it.bounds.left }
            val hostTop = placements.minOf { it.bounds.top }
            val hostRight = placements.maxOf { it.bounds.right }
            val hostBottom = placements.maxOf { it.bounds.bottom }
            placements.forEach { placement ->
                val entry = placement.candidate.entry
                val childParams = (entry.view.layoutParams as? android.widget.FrameLayout.LayoutParams)
                    ?: android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                val childWidth = placement.bounds.width().coerceAtLeast(1)
                val childLeft = placement.bounds.left - hostLeft
                val childTop = placement.bounds.top - hostTop
                if (
                    childParams.width != childWidth ||
                    childParams.height != android.widget.FrameLayout.LayoutParams.WRAP_CONTENT ||
                    childParams.leftMargin != childLeft ||
                    childParams.topMargin != childTop
                ) {
                    childParams.width = childWidth
                    // WRAP_CONTENT is essential: an exact fallback height would
                    // become self-fulfilling and Android could never report the
                    // card's real Compose height on the following layout pass.
                    childParams.height = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    childParams.leftMargin = childLeft
                    childParams.topMargin = childTop
                    entry.view.layoutParams = childParams
                }
            }
            val hostWidth = (hostRight - hostLeft).coerceAtLeast(1)
            val hostHeight = (hostBottom - hostTop).coerceAtLeast(1)
            val params = host.params
            if (params.x != hostLeft || params.y != hostTop ||
                params.width != hostWidth || params.height != hostHeight) {
                params.x = hostLeft
                params.y = hostTop
                params.width = hostWidth
                params.height = hostHeight
                runCatching { host.manager.updateViewLayout(host.root, params) }
                    .onFailure { AppLogger.warn("Falha ao reposicionar host dos cards", it) }
                    .onSuccess {
                        AppLogger.debug(
                            "Cards posicionados: display=$displayId, x=$hostLeft, y=$hostTop, " +
                                "largura=$hostWidth, altura=$hostHeight, quantidade=${placements.size}"
                        )
                    }
            }
        }
    }

    private data class LayoutCandidate(val entry: Entry, val area: Rect, val height: Int)
    private data class CardPlacement(val candidate: LayoutCandidate, val bounds: Rect)

    /** Small fallback only; the attached Compose view is normally measured above. */
    private fun estimatedHeight(entry: Entry): Int {
        val density = entry.displayContext.resources.displayMetrics.density
        return ((if (entry.decision.netTripValue != null) 150 else 180) * density).toInt()
    }

    fun removeOverlay(platform: OfferPlatform? = null) {
        mainHandler.post {
            (platform?.let(::listOf) ?: entries.keys.toList()).forEach(::removeNow)
            relayout()
        }
    }

    private fun removeNow(platform: OfferPlatform) {
        val entry = entries.remove(platform) ?: return
        entry.hideJob?.cancel()
        val host = hosts[entry.displayId]
        runCatching { host?.root?.removeView(entry.view) }
        entry.compose.disposeComposition()
        entry.owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        entry.store.clear()
        if (host != null && host.root.childCount == 0) {
            runCatching { host.manager.removeViewImmediate(host.root) }
            host.owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            host.store.clear()
            hosts.remove(entry.displayId)
        }
    }
}
class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun handleLifecycleEvent(event: Lifecycle.Event) = lifecycleRegistry.handleLifecycleEvent(event)
    fun performRestore(savedState: android.os.Bundle?) = savedStateRegistryController.performRestore(savedState)
}

@Composable
fun DecisionCardOverlay(decision: RuleResult, darkTheme: Boolean, onClick: () -> Unit) {
    val isStopRejection = decision.isStopRejection
    val isLongTripRejection = !isStopRejection &&
        EvaluationCriterion.VIAGEM_LONGA in decision.activeCriteria &&
        decision.criterionDecisions[EvaluationCriterion.VIAGEM_LONGA] == DecisionType.REJEITAR
    val isMinimumTripValueRejection = !isStopRejection &&
        EvaluationCriterion.VALOR_MINIMO in decision.activeCriteria &&
        decision.criterionDecisions[EvaluationCriterion.VALOR_MINIMO] == DecisionType.REJEITAR

    val (cardDecisionType, titleText) = if (isStopRejection) {
        DecisionType.REJEITAR to "REJEITAR (PARADAS)"
    } else if (isLongTripRejection) {
        DecisionType.REJEITAR to "REJEITAR (LONGA)"
    } else if (isMinimumTripValueRejection) {
        DecisionType.REJEITAR to "REJEITAR (MÍNIMO)"
    } else {
        when (decision.type) {
            DecisionType.ACEITAR -> DecisionType.ACEITAR to "ACEITAR"
            DecisionType.REJEITAR -> DecisionType.REJEITAR to "REJEITAR"
            else -> DecisionType.ANALISAR to "ANALISAR"
        }
    }
    val colors = decisionColors(cardDecisionType, darkTheme)

    val criteriosAtivos = listOf(
        EvaluationCriterion.KM,
        EvaluationCriterion.HORA,
        EvaluationCriterion.VIAGEM_LONGA,
        EvaluationCriterion.VALOR_MINIMO,
        EvaluationCriterion.RECOLHA
    ).filter { it in decision.activeCriteria }
    val criteriosRotulo = criteriosAtivos.joinToString(" • ") { criterion ->
        when (criterion) {
            EvaluationCriterion.KM -> "Km"
            EvaluationCriterion.HORA -> "Hora"
            EvaluationCriterion.VIAGEM_LONGA -> "Longas"
            EvaluationCriterion.VALOR_MINIMO -> "Valor mín."
            EvaluationCriterion.RECOLHA -> "Recolha"
        }
    }
    val informacaoRodape = criteriosRotulo
    val usesCompactMetrics = decision.netTripValue != null

    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).clickable { onClick() },
            shape = RoundedCornerShape(28.dp),
            color = colors.background,
            contentColor = colors.content,
            border = BorderStroke(2.dp, colors.border),
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = if (usesCompactMetrics) 8.dp else 20.dp,
                    vertical = if (usesCompactMetrics) 12.dp else 16.dp
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (usesCompactMetrics) 5.dp else 7.dp)
                ) {
                    PlatformLogo(
                        platform = decision.platform,
                        compact = usesCompactMetrics
                    )
                    Box(
                        modifier = Modifier.background(colors.badge, RoundedCornerShape(50)).padding(
                            horizontal = if (usesCompactMetrics) 11.dp else 14.dp,
                            vertical = 3.dp
                        )
                    ) {
                        Text(
                            text = titleText,
                            color = colors.badgeContent,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (usesCompactMetrics) 11.sp else 12.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(if (usesCompactMetrics) 6.dp else 14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Os três indicadores são sempre apresentados. A cor de cada um mostra
                    // a sua classificação individual; só os focos ativos decidem a cor geral.
                    DecisionMetric(
                        value = decision.pickupDistanceKm?.let { String.format(PORTUGUESE_LOCALE, "%.1f km", it) } ?: "—",
                        label = "recolha",
                        status = decision.criterionDecisions.getValue(EvaluationCriterion.RECOLHA),
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        compact = usesCompactMetrics
                    )
                    MetricDivider(compact = usesCompactMetrics)
                    DecisionMetric(
                        value = String.format(PORTUGUESE_LOCALE, "€ %.2f", decision.valorPorKm),
                        label = if (decision.isVehicleCostPerKmApplied) "por km livre" else "por km",
                        status = decision.criterionDecisions.getValue(EvaluationCriterion.KM),
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        compact = usesCompactMetrics
                    )
                    MetricDivider(compact = usesCompactMetrics)
                    DecisionMetric(
                        value = String.format(PORTUGUESE_LOCALE, "€ %.1f", decision.valorPorHora),
                        label = "por hora",
                        status = decision.criterionDecisions.getValue(EvaluationCriterion.HORA),
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        compact = usesCompactMetrics
                    )
                    decision.netTripValue?.let { netTripValue ->
                        MetricDivider(compact = true)
                        DecisionMetric(
                            value = String.format(PORTUGUESE_LOCALE, "€ %.2f", netTripValue),
                            label = "valor líquido",
                            status = decision.type,
                            colors = colors,
                            modifier = Modifier.weight(1f),
                            compact = true,
                            neutralValue = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(if (usesCompactMetrics) 6.dp else 12.dp))

                Text(
                    text = informacaoRodape,
                    color = colors.content.copy(alpha = 0.66f),
                    fontWeight = FontWeight.Normal,
                    fontSize = if (usesCompactMetrics) 11.sp else 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PlatformLogo(platform: OfferPlatform, compact: Boolean) {
    val logo = when (platform) {
        OfferPlatform.UBER -> R.drawable.logo_uber
        OfferPlatform.BOLT -> R.drawable.logo_bolt
        OfferPlatform.UNKNOWN -> null
    } ?: return
    androidx.compose.foundation.Image(
        painter = painterResource(logo),
        contentDescription = platform.label,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(if (compact) 24.dp else 30.dp)
            .clip(CircleShape)
    )
}

@Composable
private fun DecisionMetric(
    value: String,
    label: String,
    status: DecisionType,
    colors: DecisionColors,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    neutralValue: Boolean = false
) {
    val valueColor = if (neutralValue) {
        colors.content.copy(alpha = 0.92f)
    } else colors.metricColor(status)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 19.sp else 23.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            color = colors.content.copy(alpha = 0.74f),
            fontWeight = FontWeight.Medium,
            fontSize = if (compact) 11.sp else 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MetricDivider(compact: Boolean = false) {
    val dividerColor = LocalContentColor.current.copy(alpha = 0.25f)
    Box(
        modifier = Modifier
            .height(if (compact) 32.dp else 38.dp)
            .width(1.dp)
            .background(dividerColor)
    )
}
