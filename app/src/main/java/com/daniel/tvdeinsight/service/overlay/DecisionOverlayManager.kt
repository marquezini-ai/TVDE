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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

@Singleton
class DecisionOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    themePreferencesRepository: ThemePreferencesRepository
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class OverlayEntry(
        val platform: OfferPlatform,
        val view: ComposeView,
        val lifecycleOwner: OverlayLifecycleOwner,
        val viewModelStore: ViewModelStore,
        var decision: RuleResult,
        var anchor: Rect?,
        var hideJob: Job? = null
    )

    /** Uma janela por plataforma permite Uber e Bolt permanecerem visíveis juntos. */
    private val entries = mutableMapOf<OfferPlatform, OverlayEntry>()
    @Volatile private var themeMode: ThemeMode = ThemeMode.AUTOMATIC

    init {
        coroutineScope.launch {
            themePreferencesRepository.themeMode.collect { themeMode = it }
        }
    }

    /** Mostra/atualiza somente o card da plataforma que originou a leitura. */
    fun showDecision(decision: RuleResult, anchor: Rect? = null) {
        mainHandler.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                AppLogger.warn("Card ${decision.platform.label} não mostrado: permissão de sobreposição ausente")
                return@post
            }
            val platform = decision.platform
            val existing = entries[platform]
            if (existing != null && decision.valorPorHora <= 0.5) {
                return@post
            }

            if (existing != null && existing.decision.isEquivalentTo(decision)) {
                existing.anchor = anchor?.let(::Rect)
                updateOverlayLayout(existing.view, existing.anchor)
                return@post
            }

            if (existing != null) {
                existing.decision = decision
                existing.anchor = anchor?.let(::Rect)
                updateOverlayLayout(existing.view, existing.anchor)
                existing.view.setContent {
                    DecisionCardOverlay(
                        decision = decision,
                        darkTheme = isDarkTheme(),
                        onClick = { removeOverlay(platform) }
                    )
                }
            } else {
                val lifecycleOwner = OverlayLifecycleOwner()
                lifecycleOwner.performRestore(null)
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                val viewModelStore = ViewModelStore()
                val view = ComposeView(context).apply {
                    setContent {
                        DecisionCardOverlay(
                            decision = decision,
                            darkTheme = isDarkTheme(),
                            onClick = { removeOverlay(platform) }
                        )
                    }
                    // Até Android 12L, trata o toque diretamente no overlay.
                    // Assim o card fecha mesmo se a Uber não for a janela ativa.
                    // Android 13+ conserva a interação Compose atual.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        setOnTouchListener { touchedView, touchEvent ->
                            if (touchEvent.action == MotionEvent.ACTION_UP) {
                                touchedView.performClick()
                                removeOverlay(platform)
                            }
                            true
                        }
                    }
                }

                val params = WindowManager.LayoutParams(
                    overlayWidth(anchor),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    if (anchor == null) {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = 140.dpToPx(context)
                    } else {
                        gravity = Gravity.TOP or Gravity.START
                        x = overlayX(anchor, overlayWidth(anchor))
                        y = overlayY(anchor)
                    }
                }

                val viewModelStoreOwner = object : ViewModelStoreOwner {
                    override val viewModelStore = viewModelStore
                }

                view.setViewTreeLifecycleOwner(lifecycleOwner)
                view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                view.setViewTreeViewModelStoreOwner(viewModelStoreOwner)

                try {
                    windowManager.addView(view, params)
                    lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                    entries[platform] = OverlayEntry(
                        platform = platform,
                        view = view,
                        lifecycleOwner = lifecycleOwner,
                        viewModelStore = viewModelStore,
                        decision = decision,
                        anchor = anchor?.let(::Rect)
                    )
                } catch (e: Exception) {
                    view.disposeComposition()
                    lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                    viewModelStore.clear()
                    AppLogger.warn("Não foi possível mostrar o overlay de decisão", e)
                }
            }

            entries[platform]?.hideJob?.cancel()
            entries[platform]?.hideJob = coroutineScope.launch {
                delay(10000L)
                removeOverlay(platform)
            }
        }
    }

    /** Remove apenas uma plataforma; sem argumento limpa todos os cards. */
    fun removeOverlay(platform: OfferPlatform? = null) {
        mainHandler.post {
            val toRemove = if (platform == null) entries.keys.toList() else listOf(platform)
            toRemove.forEach { key ->
                val entry = entries.remove(key) ?: return@forEach
                entry.hideJob?.cancel()
                try {
                    windowManager.removeView(entry.view)
                } catch (e: Exception) {
                    AppLogger.warn("Não foi possível remover o overlay de decisão", e)
                }
                entry.view.disposeComposition()
                entry.lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                entry.viewModelStore.clear()
            }
        }
    }

    private fun RuleResult.isEquivalentTo(other: RuleResult): Boolean =
        valorPorKm == other.valorPorKm &&
            valorPorHora == other.valorPorHora &&
            netTripValue == other.netTripValue &&
            type == other.type &&
            platform == other.platform &&
            pickupDistanceKm == other.pickupDistanceKm &&
            activeCriteria == other.activeCriteria &&
            criterionDecisions == other.criterionDecisions

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }

    private fun overlayWidth(anchor: Rect?): Int {
        if (anchor == null) return WindowManager.LayoutParams.MATCH_PARENT
        val displayWidth = context.resources.displayMetrics.widthPixels
        val minimum = 300.dpToPx(context).coerceAtMost(displayWidth)
        return anchor.width().coerceAtLeast(minimum).coerceAtMost(displayWidth)
    }

    private fun overlayX(anchor: Rect, width: Int): Int {
        val displayWidth = context.resources.displayMetrics.widthPixels
        return anchor.left.coerceIn(0, (displayWidth - width).coerceAtLeast(0))
    }

    private fun overlayY(anchor: Rect): Int =
        (anchor.top + 8.dpToPx(context)).coerceAtLeast(8.dpToPx(context))

    private fun updateOverlayLayout(view: ComposeView, anchor: Rect?) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (anchor == null) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.x = 0
            params.y = 140.dpToPx(context)
        } else {
            params.width = overlayWidth(anchor)
            params.gravity = Gravity.TOP or Gravity.START
            params.x = overlayX(anchor, params.width)
            params.y = overlayY(anchor)
        }
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { AppLogger.debug("Não foi possível reposicionar o overlay: ${it.message}") }
    }

    private fun isDarkTheme(): Boolean = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTOMATIC -> {
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
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

    val plataformaRotulo = decision.platform.label
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
    val informacaoRodape = listOf(plataformaRotulo, criteriosRotulo)
        .filter(String::isNotBlank)
        .joinToString(" | ")
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
