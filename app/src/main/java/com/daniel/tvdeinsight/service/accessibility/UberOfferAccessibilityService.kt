package com.daniel.tvdeinsight.service.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.graphics.Rect
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import com.daniel.tvdeinsight.BuildConfig
import com.daniel.tvdeinsight.data.repository.OfferAnalysisStore
import com.daniel.tvdeinsight.data.repository.SettingsRepository
import com.daniel.tvdeinsight.data.screenshot.OfferScreenshotStore
import com.daniel.tvdeinsight.domain.model.RuleSettings
import com.daniel.tvdeinsight.domain.model.TripOffer
import com.daniel.tvdeinsight.domain.usecase.EvaluateOfferUseCase
import com.daniel.tvdeinsight.logging.AppLogger
import com.daniel.tvdeinsight.license.LicenseManager
import com.daniel.tvdeinsight.reservations.AppPreferences
import com.daniel.tvdeinsight.reservations.BoltReservationCoordinator
import com.daniel.tvdeinsight.service.ocr.OcrCaptureGate
import com.daniel.tvdeinsight.service.ocr.UberOfferCardTextExtractor
import com.daniel.tvdeinsight.service.ocr.OpenCvOcrPreprocessor
import com.daniel.tvdeinsight.service.ocr.UberFareValidation
import com.daniel.tvdeinsight.service.notification.BoltNotificationSignal
import com.daniel.tvdeinsight.service.overlay.DecisionOverlayManager
import com.daniel.tvdeinsight.service.overlay.OverlayBounds
import com.daniel.tvdeinsight.service.overlay.OverlayWindowModePolicy
import com.daniel.tvdeinsight.service.overlay.VisibleApplicationWindow
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Android 12 e inferiores: recolhe ofertas Uber exclusivamente pela árvore
 * de acessibilidade. Android 13 e superiores: recolhe Uber exclusivamente
 * pela imagem do display (sem ler a árvore de acessibilidade). A imagem inteira
 * é mantida na resolução nativa para funcionar em 720p, FHD, DeX, tela dividida
 * e 4K. Nunca envia cliques para a Uber.
 */
@AndroidEntryPoint
class UberOfferAccessibilityService : AccessibilityService() {

    private sealed interface ScreenshotRequestResult {
        data object Started : ScreenshotRequestResult
        data object Rejected : ScreenshotRequestResult
        data class RetryAfter(val delayMs: Long) : ScreenshotRequestResult
    }

    @Inject lateinit var uberParser: UberOfferParser
    @Inject lateinit var boltParser: BoltOfferParser
    @Inject lateinit var evaluateOfferUseCase: EvaluateOfferUseCase
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var analysisStore: OfferAnalysisStore
    @Inject lateinit var offerScreenshotStore: OfferScreenshotStore
    @Inject lateinit var overlayManager: DecisionOverlayManager
    @Inject lateinit var licenseManager: LicenseManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var settingsCollectorJob: Job? = null
    private var screenshotCleanupJob: Job? = null
    private var adaptiveOcrJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var textRecognizer: TextRecognizer? = createTextRecognizer()
    private val boltReservationCoordinator by lazy {
        // Reservas Bolt tem um controlo independente do INICIAR/PARAR da TVDE.
        BoltReservationCoordinator(this) { isAnalysisAuthorized }
    }
    private val uberCardTextExtractor = UberOfferCardTextExtractor()
    private val screenshotGate = OcrCaptureGate(SCREENSHOT_INTERVAL_MS)
    private val ocrWarmupInFlight = AtomicBoolean(false)
    private val screenshotSequence = AtomicLong(0)
    private val visionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val nativeFrameInFlight = AtomicBoolean(false)
    private var activeCaptureIsWindow = false
    private var stressToken: String? = null
    private var stressRequestId = NO_ACTIVE_SCREENSHOT
    private var stressReceiverRegistered = false
    private var boltNotificationReceiverRegistered = false
    // Instrumentação temporária e exclusiva da variante debug. Ela não conserva
    // texto de viagens: apenas a assinatura estrutural do evento da Uber.
    private var lastUberAccessibilityDiagnosticAt = 0L
    private val stressReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (!BuildConfig.DEBUG || intent.action != STRESS_ACTION) return
            val token = intent.getStringExtra("token")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) } ?: return
            if (Build.VERSION.SDK_INT < 33 || !isAnalysisAuthorized || !settingsLoaded || !isUberVisibleForOcr()) {
                android.util.Log.i("AccessibilityService", "OCR_STRESS token=$token status=DISABLED")
                return
            }
            if (stressToken != null) {
                android.util.Log.i("AccessibilityService", "OCR_STRESS token=$token status=RETRY")
                return
            }
            when (requestScreenshot("debug stress $token")) {
                ScreenshotRequestResult.Started -> { stressToken = token; stressRequestId = activeScreenshotId }
                else -> android.util.Log.i("AccessibilityService", "OCR_STRESS token=$token status=RETRY")
            }
        }
    }
    private val boltNotificationReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action != BoltNotificationSignal.ACTION_BOLT_NOTIFICATION_POSTED) return
            val signalId = intent.getStringExtra(BoltNotificationSignal.EXTRA_SIGNAL_ID)
                ?.take(MAX_BOLT_NOTIFICATION_SIGNAL_LENGTH)
                .orEmpty()
            if (signalId.isBlank()) return
            val postTimeMs = intent.getLongExtra(BoltNotificationSignal.EXTRA_POST_TIME_MS, 0L)
            scheduleBoltNotificationProbe(signalId, postTimeMs)
        }
    }

    private fun completeStressFrame(requestId: Long, status: String) {
        if (stressRequestId != requestId) return
        stressToken?.let { android.util.Log.i("AccessibilityService", "OCR_STRESS token=$it status=$status") }
        stressToken = null
        stressRequestId = NO_ACTIVE_SCREENSHOT
    }
    private val offerStabilityTracker = OfferStabilityTracker(
        requiredConsecutiveReadings = REQUIRED_CONSECUTIVE_READINGS,
        duplicateWindowMs = DECISION_DUPLICATE_WINDOW_MS
    )
    @Volatile private var currentSettings = RuleSettings()
    @Volatile private var settingsLoaded = false
    @Volatile private var foregroundPackage = ""
    @Volatile private var foregroundPackageUpdatedAt = 0L
    // Em alguns Samsung/Android 16 a janela Uber do painel sobreposto aparece
    // como focada mesmo quando a Launcher, Bolt ou outra app continua por cima.
    // Guardamos a última aplicação real observada para escolher display capture
    // nesses eventos, em vez de capturar a Activity Uber invisível.
    @Volatile private var lastExternalForegroundPackage = ""
    @Volatile private var lastExternalForegroundAt = 0L
    @Volatile private var activeScreenshotId = NO_ACTIVE_SCREENSHOT
    private var activeScreenshotStartedAt = 0L
    private var screenshotTimeoutRunnable: Runnable? = null
    private var ocrRestartRunnable: Runnable? = null
    private var uberWindowOcrRunnable: Runnable? = null
    private var uberBackgroundPanelProbeRunnable: Runnable? = null
    private var incompleteOcrRetryRunnable: Runnable? = null
    /** Latest-wins queue for a semantic Uber trigger received while ML Kit or
     * Android's screenshot gate is busy. It stores no bitmap. */
    private data class PendingUberCapture(
        val reason: String,
        val eventGeneration: Long
    )
    private var pendingUberCapture: PendingUberCapture? = null
    private var pendingUberCaptureRunnable: Runnable? = null
    private var uberEventGeneration = 0L
    /** Once Android accepts a valid capture, its result stays authorized until
     * that request finishes; a wall-clock timeout must not discard valid OCR. */
    private var activeCaptureAuthorization = false
    // A leitura OpenCV pode ser completa enquanto a leitura crua do mesmo
    // frame ainda está a atravessar a animação da Uber. Uma única segunda
    // captura, associada à mesma assinatura, confirma o valor sem polling.
    private var boundedUberConfirmationRunnable: Runnable? = null
    private var boundedUberConfirmationSignature: String? = null
    private var boundedUberConfirmationAttempts = 0
    private var incompleteAccessibilityRetryRunnable: Runnable? = null
    // Em Android 16, a Bolt por vezes emite a mudança de janela antes de o
    // Android disponibilizar a árvore dessa mesma janela. Guardamos no máximo
    // uma recuperação curta, associada ao evento, sem criar polling contínuo.
    private var boltRootRetryRunnable: Runnable? = null
    private var boltRootRetryWindowId = INVALID_WINDOW_ID
    private var boltRootRetryAttempts = 0
    private var boltNotificationProbeRunnable: Runnable? = null
    private var lastBoltNotificationSignalId: String? = null
    private var lastBoltNotificationSignalAt = 0L
    private var hasActiveDecision = false
    private var boltNoOfferCount = 0

    private var lastUberParseAt = 0L
    // Android 12 e inferiores disponibilizam a árvore Uber até em segundo
    // plano. O mapa, porém, também emite muitas alterações de conteúdo. Estes
    // contadores mantêm o caminho barato quando não existe cartão, e só deixam
    // o extrator posicional percorrer a árvore completa para uma oferta real.
    private var weakUberAccessibilityEvents = 0
    private var lastWeakUberAccessibilitySummaryAt = 0L
    private var lastLegacyUberOfferSignature: String? = null
    private var lastLegacyUberOfferHandledAt = 0L
    // Depois de uma oferta completa ser extraída, a Uber continua a emitir
    // eventos de conteúdo para a mesma árvore enquanto o cartão permanece
    // visível. O fingerprint do pré-filtro permite manter a admissão barata,
    // evitando percorrer novamente todos os nós e executar o parser completo.
    private var lastLegacyUberPreflightFingerprint: String? = null
    private var lastLegacyUberPreflightHandledAt = 0L
    /** Estado do cartão atualmente visível; não é uma janela temporal global. */
    private var legacyUberAccessibilityCardActive = false
    private var ignoredUberAccessibilityEvents = 0L
    private var lastIgnoredUberAccessibilitySummaryAt = 0L
    /**
     * TYPE_WINDOW_CONTENT_CHANGED is emitted continuously by the Uber map even
     * when no offer exists. Keep that visual noise from monopolising the only
     * screenshot/OCR slot; a real offer still gets an immediate window-state or
     * semantic-hint capture and the periodic scan remains as a fallback.
     */
    private var lastUberContentEventCaptureAt = 0L
    private var lastUberAccessibilityFastPathAt = 0L
    private var uberAccessibilityFastPathMisses = 0
    private var uberAccessibilityFastPathDisabled = false
    private var lastUberWindowStateAt = 0L
    /**
     * Visual gate for Android 13+: a screenshot is still cheap insurance for
     * an accessibility event that Android drops, but ML Kit must not run again
     * while the same Uber card remains on screen. The gate is deliberately
     * reset when a window event or a no-card frame is observed, so a new offer
     * with the same values is analysed immediately after the old card closes.
     */
    @Volatile private var uberVisualCardActive = false
    @Volatile private var lastUberVisualFingerprint: Long? = null

    private var skippedUberEvents = 0
    private var boltSummaryStartedAt = 0L
    private var ocrNoCardCount = 0
    private var ocrNoCardSummaryStartedAt = 0L
    private var lastOcrNoCardSignature: String? = null
    private var lastOcrNoCardLoggedAt = 0L
    private var invalidUberCardSignature: String? = null
    private var lastInvalidUberCardAt = 0L
    private var suppressedInvalidUberCardCount = 0
    private var incompleteOcrRetryAttempts = 0
    private var incompleteOcrRetryStartedAt = 0L
    private var lastIncompleteAccessibilitySignature: String? = null
    private var lastIncompleteAccessibilityAt = 0L
    private var ocrTimeoutWindowStartedAt = 0L
    private var ocrTimeoutsInWindow = 0
    private var completedOcrCaptures = 0L
    private var totalOcrDurationMs = 0L
    private var lastOcrMemoryReportAt = 0L
    private var lastVisualPrefilterLoggedAt = 0L
    private var lastBatteryConstraintCheckAt = 0L
    private var cachedBatteryConstrained = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Samsung pode chamar novamente onServiceConnected() sem destruir esta
        // instância. Nunca mantenha coletores ou ciclos antigos em paralelo.
        settingsCollectorJob?.cancel()
        screenshotCleanupJob?.cancel()
        adaptiveOcrJob?.cancel()
        resetLegacyUberAccessibilityState("serviço reconectado")
        uberAccessibilityFastPathMisses = 0
        uberAccessibilityFastPathDisabled = false
        // A Samsung pode recriar apenas a instância do AccessibilityService
        // mantendo o processo da aplicação. Remova qualquer janela do host
        // anterior antes de aceitar novos cards; caso contrário, o host antigo
        // e o novo podem ficar simultaneamente sobrepostos.
        overlayManager.removeOverlay()
        // Usa a camada de sobreposição de acessibilidade: fica acima dos
        // painéis de oferta da Uber, inclusive as ofertas Radar "Selecionar".
        overlayManager.attachAccessibilityOverlayContext(this)
        if (BuildConfig.DEBUG && !stressReceiverRegistered) {
            // Export only in debug, and only shell/system (DUMP permission) may invoke it.
            androidx.core.content.ContextCompat.registerReceiver(this, stressReceiver,
                android.content.IntentFilter(STRESS_ACTION), "android.permission.DUMP", mainHandler,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
            stressReceiverRegistered = true
        }
        if (!boltNotificationReceiverRegistered) {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                boltNotificationReceiver,
                android.content.IntentFilter(BoltNotificationSignal.ACTION_BOLT_NOTIFICATION_POSTED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            boltNotificationReceiverRegistered = true
        }
        AppLogger.info("Serviço de Acessibilidade conectado")
        boltReservationCoordinator.connect()
        settingsCollectorJob = serviceScope.launch {
            settingsRepository.settings.collect { settings ->
                currentSettings = settings
                settingsLoaded = true
                if (!settings.isAppRunning || !settings.isUberEnabled) {
                    mainHandler.post {
                        cancelPendingUberOcr()
                        if (!settings.isAppRunning) clearOverlay("monitorização parada")
                    }
                }
                AppLogger.debug(
                    "Configurações carregadas: ativo=${settings.isAppRunning}, " +
                        "uber=${settings.isUberEnabled}, bolt=${settings.isBoltEnabled}"
                )
            }
        }

        screenshotCleanupJob = serviceScope.launch {
            while (isActive) {
                if (settingsLoaded) {
                    val removed = offerScreenshotStore.deleteOlderThan(currentSettings.screenshotRetentionHours)
                    if (removed > 0) AppLogger.info("Capturas vencidas apagadas: $removed")
                }
                delay(SCREENSHOT_CLEANUP_INTERVAL_MS)
            }
        }

        if (usesBitmapOcrForUber) {
            warmUpTextRecognizer()
            adaptiveOcrJob = serviceScope.launch {
                while (isActive) {
                    delay(screenshotPollIntervalMs())
                    if (
                        isAnalysisAuthorized &&
                            currentSettings.isAppRunning &&
                            currentSettings.isUberEnabled &&
                            shouldPollUberOcr()
                    ) {
                        mainHandler.post {
                            requestScreenshot("verificação periódica adaptativa")
                        }
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (hasActiveDecision &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        ) {
            listOf(com.daniel.tvdeinsight.domain.model.OfferPlatform.UBER to UBER_PACKAGE_FRAGMENT,
                com.daniel.tvdeinsight.domain.model.OfferPlatform.BOLT to BOLT_PACKAGE_FRAGMENT).forEach { (platform, pkg) ->
                runCatching { appWindowTarget(pkg) }.getOrNull()?.let {
                    // TYPE_SYSTEM is the suspended Uber offer surface, not a
                    // split-screen/DeX pane. Do not make the TVDE host inherit
                    // that transient rectangle on a normal phone screen.
                    overlayManager.updateAnchor(
                        platform = platform,
                        anchor = it.bounds,
                        displayId = it.displayId,
                        constrainToAnchor = isGenuineSplitScreenOrDexPane(it)
                    )
                }
            }
        }
        if (!isAnalysisAuthorized) {
            clearOverlay("app parada ou licença inválida")
            return
        }
        val reservationsEnabled = AppPreferences.isOverlayVisible(this)
        if (!currentSettings.isAppRunning && !reservationsEnabled) {
            clearOverlay("app parada")
            return
        }

        val sourceNode = event.source
        val eventPackageName = event.packageName?.toString().orEmpty()
        val sourcePackageName = sourceNode?.packageName?.toString().orEmpty()
        val packageName = eventPackageName.ifBlank { sourcePackageName }
        val isWindowStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (isWindowStateChange && packageName.isNotEmpty()) {
            if (isExternalApplicationPackage(packageName)) {
                lastExternalForegroundPackage = packageName
                lastExternalForegroundAt = SystemClock.elapsedRealtime()
            }
            foregroundPackage = packageName
            foregroundPackageUpdatedAt = SystemClock.elapsedRealtime()
        }

        // Em Android 12L e inferiores a Uber pode emitir o evento enquanto
        // outra app é a janela ativa. Por isso o pacote do nó-fonte também é
        // considerado, sem depender exclusivamente da janela em primeiro plano.
        val isUber = eventPackageName.contains(UBER_PACKAGE_FRAGMENT, ignoreCase = true) ||
            sourcePackageName.contains(UBER_PACKAGE_FRAGMENT, ignoreCase = true)
        val isBolt = eventPackageName.contains(BOLT_PACKAGE_FRAGMENT, ignoreCase = true) ||
            sourcePackageName.contains(BOLT_PACKAGE_FRAGMENT, ignoreCase = true)
        if (isUber && isWindowStateChange) {
            // A Uber não expõe o conteúdo do cartão na árvore. Este instante é
            // apenas o gatilho para procurar, por um período muito curto, a
            // APPLICATION_OVERLAY que materializa visualmente a oferta.
            lastUberWindowStateAt = SystemClock.elapsedRealtime()
            uberEventGeneration++
            if (usesBitmapOcrForUber) {
                // A window-state event is the semantic boundary between cards.
                // Never let the previous card's pixels suppress the next one.
                uberVisualCardActive = false
                lastUberVisualFingerprint = null
            }
        }
        if (isUber) {
            logUberAccessibilityDiagnostic(event, sourceNode)
        }
        if (isWindowStateChange) {
            AppLogger.debug(
                "Mudança de janela: pacote=$packageName, uber=$isUber, bolt=$isBolt"
            )
            if (usesBitmapOcrForUber && !isUber && !isUberVisibleForOcr()) {
                // Parar apenas novas leituras. O card já publicado deve seguir
                // o comportamento original: permanecer visível por 10 segundos
                // ou até o utilizador tocar nele.
                cancelPendingUberOcr()
            }
        }

        // A Bolt é sempre lida pela acessibilidade, em todas as versões Android.
        // Nunca participa no fluxo OCR reservado à Uber no Android 13+.
        if (isBolt) {
            boltReservationCoordinator.onAccessibilityEvent(event)
        }
        // Quando a TVDE está parada mas o floating das Reservas está ligado,
        // apenas o motor das Reservas trata os eventos da Bolt.
        if (!currentSettings.isAppRunning) return
        if (isBolt && boltReservationCoordinator.isInteractionInProgress()) {
            // Apenas a sequência curta Abrir → Aceitar → Confirmar bloqueia a
            // análise visual, para um overlay não cobrir o botão da Bolt.
            return
        }
        // A análise normal da Bolt é exclusivamente disparada pelo
        // NotificationListenerService. Eventos de conteúdo/janela da Bolt são
        // ruidosos (mapa, animações e estado online) e não iniciam uma leitura
        // nem uma decisão. Acessibilidade continua a ser usada como fonte
        // única dos dados quando a notificação chega.

        if (isUber && currentSettings.isUberEnabled && !usesBitmapOcrForUber) {
            val now = System.currentTimeMillis()
            val semanticHint = isWindowStateChange || hasUberAccessibilitySemanticHint(event, sourceNode)

            // Eventos do mapa não têm qualquer pista semântica de oferta e são
            // descartados antes de obter a árvore completa. Uma mudança de
            // janela é mantida como candidato porque a Uber pode publicar o
            // cartão numa janela cujo texto ainda não está no evento.
            if (!semanticHint) {
                ignoredUberAccessibilityEvents++
                logIgnoredUberAccessibilityEvents(now)
                return
            }

            // O debounce só protege contra flood de eventos fracos. Uma pista
            // forte de oferta nunca fica bloqueada: uma nova viagem pode chegar
            // imediatamente após o motorista fechar a anterior.
            if (
                !isWindowStateChange &&
                    !hasStrongUberAccessibilitySemanticHint(event, sourceNode) &&
                    now - lastUberParseAt < LEGACY_UBER_PREFLIGHT_DEBOUNCE_MS
            ) {
                skippedUberEvents++
                return
            }
            lastUberParseAt = now

            if (skippedUberEvents > 0) {
                AppLogger.debug("Uber: $skippedUberEvents evento(s) engarrafados ignorados (Prevenção de Flood)")
                skippedUberEvents = 0
            }

            processUberAccessibilityEvent(event, sourceNode)
        }

        // Android 13+ still emits a source node for several background offers.
        // Use it only as a verified fast path when the complete card parses;
        // screenshots remain the fallback for events that expose no usable tree.
        if (isUber && currentSettings.isUberEnabled && usesBitmapOcrForUber) {
            processUberAccessibilityFastPath(event, sourceNode)
        }

        // No Android 13+, uma oferta Uber em segundo plano cria uma janela grande
        // própria. O TYPE_WINDOW_STATE_CHANGED chega antes de a árvore expor texto
        // útil; por isso é um gatilho válido para uma única captura. Alterações de
        // conteúdo continuam a exigir uma pista semântica, pois o mapa atualiza a
        // árvore continuamente mesmo sem oferta.
        if (
            usesBitmapOcrForUber &&
                currentSettings.isUberEnabled &&
                (isWindowStateChange || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) &&
                isUber
        ) {
            scheduleUberWindowOcr(
                packageName = packageName,
                isWindowStateChange = isWindowStateChange,
                hasOfferHint = eventHasUberOfferHint(event, sourceNode)
            )
        }
    }

    private fun consumeBoltRoot(resolvedRoot: ResolvedAccessibilityRoot, now: Long) {
        val rootNode = resolvedRoot.node
        try {
            val offer = runCatching { boltParser.parse(rootNode) }
                .onFailure { AppLogger.warn("Erro ao interpretar card Bolt", it) }
                .getOrNull()
            if (offer != null) {
                resetBoltRootRetry()
                logBoltSummaryIfDue(now, force = true)
                val overlayTarget = accessibilityWindowTarget(rootNode)
                // A Bolt tem um gatilho único: a notificação de nova oferta.
                // Não haverá um segundo evento confiável para confirmar a mesma
                // árvore. O parser só publica depois de validar preço e os dois
                // segmentos da rota; portanto esta leitura já é confirmação
                // suficiente e não pode ficar presa em "aguardando confirmação
                // estável".
            handleOffer(
                    offer = offer,
                    alreadyConfirmedByAccessibility = true,
                    overlayAnchor = overlayTarget?.bounds,
                    overlayAvoidTop = boltNativeDismissControlBottom(rootNode),
                    overlayDisplayId = overlayTarget?.displayId ?: Display.DEFAULT_DISPLAY,
                    overlayConstrainToAnchor = overlayTarget?.let(::isGenuineSplitScreenOrDexPane) ?: false
                )
            } else {
                boltNoOfferCount++
                logBoltSummaryIfDue(now)
                // A notificação pode chegar enquanto a Activity ainda está a
                // preencher o cartão. A árvore existe, mas preço/rotas ainda
                // não estão completos. Reutiliza a mesma recuperação curta
                // usada para transições de janela; isto não é polling e não
                // bloqueia uma notificação nova.
                if (boltRootRetryAttempts < MAX_BOLT_ROOT_RETRIES) {
                    scheduleBoltRootRetry(INVALID_WINDOW_ID)
                } else {
                    AppLogger.debug("Bolt: oferta incompleta após ${MAX_BOLT_ROOT_RETRIES + 1} leituras")
                    boltRootRetryAttempts = 0
                }
            }
        } finally {
            if (resolvedRoot.recycleWhenDone) rootNode.recycle()
        }
    }

    private fun scheduleBoltRootRetry(windowId: Int) {
        if (boltRootRetryRunnable != null) return
        if (boltRootRetryAttempts >= MAX_BOLT_ROOT_RETRIES) {
            AppLogger.debug("Bolt continua sem oferta após ${MAX_BOLT_ROOT_RETRIES + 1} leituras")
            boltRootRetryAttempts = 0
            return
        }
        boltRootRetryAttempts++
        val retryNumber = boltRootRetryAttempts
        boltRootRetryWindowId = windowId
        val retry = Runnable {
            boltRootRetryRunnable = null
            if (!currentSettings.isAppRunning || !currentSettings.isBoltEnabled) return@Runnable
            val root = findPackageRootInWindow(boltRootRetryWindowId, BOLT_PACKAGE_FRAGMENT)
                ?: findVisiblePackageRoot(BOLT_PACKAGE_FRAGMENT)
                ?: run {
                    if (boltRootRetryAttempts < MAX_BOLT_ROOT_RETRIES) {
                        AppLogger.debug("Bolt sem árvore; nova tentativa=${boltRootRetryAttempts + 1}/$MAX_BOLT_ROOT_RETRIES")
                        scheduleBoltRootRetry(boltRootRetryWindowId)
                    } else {
                        AppLogger.debug("Bolt continua sem árvore após ${MAX_BOLT_ROOT_RETRIES + 1} leituras")
                        boltRootRetryAttempts = 0
                    }
                    return@Runnable
                }
            AppLogger.debug("Bolt: árvore recuperada na tentativa $retryNumber após a notificação")
            consumeBoltRoot(ResolvedAccessibilityRoot(root, recycleWhenDone = true), System.currentTimeMillis())
        }
        boltRootRetryRunnable = retry
        mainHandler.postDelayed(retry, BOLT_ROOT_RETRY_DELAY_MS)
    }

    private fun resetBoltRootRetry() {
        boltRootRetryRunnable?.let(mainHandler::removeCallbacks)
        boltRootRetryRunnable = null
        boltRootRetryWindowId = INVALID_WINDOW_ID
        boltRootRetryAttempts = 0
    }

    /**
     * A notificação é o único gatilho da Bolt. Aguarda a Activity renderizar o
     * cartão e faz uma leitura; se a árvore ainda estiver incompleta,
     * A primeira leitura ocorre aos 750 ms. Se a árvore ainda estiver vazia ou
     * incompleta, são permitidas tentativas aos 1000, 1250 e 1500 ms. O ciclo
     * é criado apenas por notificação e não existe polling global.
     */
    private fun scheduleBoltNotificationProbe(signalId: String, postTimeMs: Long) {
        if (!isAnalysisAuthorized || !currentSettings.isAppRunning || !currentSettings.isBoltEnabled) {
            AppLogger.debug("Notificação Bolt ignorada: monitorização Bolt desativada")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (
            signalId == lastBoltNotificationSignalId &&
                now - lastBoltNotificationSignalAt < BOLT_NOTIFICATION_DEDUPLICATE_WINDOW_MS
        ) return

        lastBoltNotificationSignalId = signalId
        lastBoltNotificationSignalAt = now
        boltNotificationProbeRunnable?.let(mainHandler::removeCallbacks)
        // Uma nova notificação representa uma nova oferta, mesmo quando o
        // identificador do Android é reutilizado. Reiniciar o ciclo da oferta
        // mais recente evita que uma leitura antiga publique o cartão errado;
        // o listener já eliminou apenas duplicações com o mesmo postTime.
        resetBoltRootRetry()
        val probe = Runnable {
            boltNotificationProbeRunnable = null
            if (!isAnalysisAuthorized || !currentSettings.isAppRunning || !currentSettings.isBoltEnabled) return@Runnable
            val root = findVisiblePackageRoot(BOLT_PACKAGE_FRAGMENT)
            if (root == null) {
                AppLogger.debug("Gatilho de notificação Bolt recebido; árvore ainda indisponível")
                scheduleBoltRootRetry(INVALID_WINDOW_ID)
                return@Runnable
            }
            AppLogger.info("Gatilho de notificação Bolt acionou leitura do cartão: post=$postTimeMs")
            consumeBoltRoot(ResolvedAccessibilityRoot(root, recycleWhenDone = true), System.currentTimeMillis())
        }
        boltNotificationProbeRunnable = probe
        mainHandler.postDelayed(probe, BOLT_NOTIFICATION_PROBE_DELAY_MS)
    }

    private fun resetBoltNotificationProbe() {
        boltNotificationProbeRunnable?.let(mainHandler::removeCallbacks)
        boltNotificationProbeRunnable = null
        lastBoltNotificationSignalId = null
        lastBoltNotificationSignalAt = 0L
    }

    private fun processUberAccessibilityEvent(
        event: AccessibilityEvent,
        sourceNode: AccessibilityNodeInfo?
    ) {
        // O nó-fonte preserva a árvore da Uber quando a oferta está sobre
        // outra aplicação. A janela ativa, nesse caso, não pertence à Uber.
        val sourceRoot = sourceNode
            ?.takeIf { it.belongsToPackage(UBER_PACKAGE_FRAGMENT) }
            ?.topMostParent()
        if (sourceRoot != null) {
            try {
                processUberAccessibilityTree(sourceRoot)
            } finally {
                if (sourceRoot !== sourceNode) sourceRoot.recycle()
            }
            return
        }

        val activeRoot = rootInActiveWindow
            ?.takeIf { it.belongsToPackage(UBER_PACKAGE_FRAGMENT) }
        if (activeRoot != null) {
            try {
                processUberAccessibilityTree(activeRoot)
            } finally {
                activeRoot.recycle()
            }
            return
        }

        val eventRoot = event.source
            ?.takeIf { it.belongsToPackage(UBER_PACKAGE_FRAGMENT) }
        if (eventRoot != null) {
            try {
                processUberAccessibilityTree(eventRoot)
            } finally {
                eventRoot.recycle()
            }
            return
        }

        AppLogger.debug("Uber sem rootInActiveWindow/source")
    }

    /**
     * Background window screenshots are not guaranteed by Android when Bolt is
     * focused. If Uber exposes a complete accessibility card in its event, use
     * that exact structured data immediately instead of waiting for a display
     * capture that may belong to Bolt, System UI, or the lock screen.
     */
    private fun processUberAccessibilityFastPath(
        event: AccessibilityEvent,
        sourceNode: AccessibilityNodeInfo?
    ) {
        if (uberAccessibilityFastPathDisabled) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !eventHasUberOfferHint(event, sourceNode)
        ) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastUberAccessibilityFastPathAt < UBER_ACCESSIBILITY_FAST_PATH_DEBOUNCE_MS) return
        lastUberAccessibilityFastPathAt = now

        val rootNode = sourceNode
            ?.takeIf { it.belongsToPackage(UBER_PACKAGE_FRAGMENT) }
            ?.topMostParent()
            ?: findVisiblePackageRoot(UBER_PACKAGE_FRAGMENT)
            ?: run {
                recordUberAccessibilityFastPathMiss()
                return
            }
        if (!rootNode.belongsToPackage(UBER_PACKAGE_FRAGMENT)) {
            recordUberAccessibilityFastPathMiss()
            return
        }

        val cardText = runCatching { rootNode.extractUberOfferCardText() }
            .onFailure { AppLogger.warn("Falha ao ler cartão Uber pelo evento de acessibilidade", it) }
            .getOrNull()
            ?: run {
                recordUberAccessibilityFastPathMiss()
                return
            }
        val offer = uberParser.parse(cardText) ?: run {
            recordUberAccessibilityFastPathMiss()
            return
        }
        uberAccessibilityFastPathMisses = 0

        AppLogger.info(
            "Oferta Uber confirmada pela acessibilidade: " +
                "€${offer.price}, recolha=${offer.pickupDistanceKm}, viagem=${offer.tripDistanceKm}"
        )
        val overlayTarget = accessibilityWindowTarget(rootNode)
        handleOffer(
            offer = offer,
            alreadyConfirmedByAccessibility = true,
            overlayAnchor = overlayTarget?.bounds,
            overlayDisplayId = overlayTarget?.displayId ?: Display.DEFAULT_DISPLAY,
            overlayConstrainToAnchor = overlayTarget?.let(::isGenuineSplitScreenOrDexPane) ?: false
        )
    }

    private fun recordUberAccessibilityFastPathMiss() {
        uberAccessibilityFastPathMisses++
        if (uberAccessibilityFastPathMisses < MAX_UBER_ACCESSIBILITY_FAST_PATH_MISSES) return
        uberAccessibilityFastPathDisabled = true
        AppLogger.debug(
            "Uber Android 13+: árvore sem cartão; extração completa desativada nesta sessão, " +
                "mantendo apenas o gatilho para OCR"
        )
    }

    /** Android 12 e inferiores: uma nova leitura da árvore, nunca OCR. */
    private fun processUberAccessibilityTree(rootNode: AccessibilityNodeInfo) {
        val preflight = runCatching { rootNode.inspectUberOfferStructure() }
            .onFailure { AppLogger.warn("Falha no pré-filtro da árvore Uber", it) }
            .getOrNull()
            ?: return

        // Não há preço + dois trechos de uma oferta: é uma atualização normal
        // do mapa, do radar ou de navegação. Não cria Bitmap, não chama ML Kit
        // e tampouco percorre os 600 nós do extrator completo.
        if (!preflight.isCompleteOffer) {
            logWeakUberAccessibilityEvent(preflight)
            if (!preflight.isPartialOffer && legacyUberAccessibilityCardActive) {
                resetLegacyUberAccessibilityState("cartão Uber fechado")
            }
            if (preflight.isPartialOffer) {
                scheduleIncompleteAccessibilityRetry(preflight.fingerprint)
            }
            return
        }

        // A mesma oferta pode gerar dezenas de TYPE_WINDOW_CONTENT_CHANGED
        // durante a animação. Se o pré-filtro não mudou desde a última leitura
        // completa, não repetir a extração posicional nem a avaliação.
        val preflightNow = SystemClock.elapsedRealtime()
        if (
            legacyUberAccessibilityCardActive &&
            preflight.fingerprint == lastLegacyUberPreflightFingerprint &&
                preflightNow >= lastLegacyUberPreflightHandledAt
        ) {
            AppLogger.debug("Oferta Uber duplicada ignorada: mesmo cartão ainda visível")
            return
        }

        // The preflight already collected positioned text while walking the
        // tree. Reuse it; only fall back to the wider traversal for an unusual
        // truncated hierarchy.
        val cardText = runCatching {
            uberCardTextExtractor.extract(preflight.blocks)
                ?: rootNode.extractUberOfferCardText()
        }
            .onFailure { AppLogger.warn("Erro ao recolher texto da árvore Uber", it) }
            .getOrNull()
        val offer = cardText?.let { text ->
            AppLogger.debug("Texto Uber encontrado pela acessibilidade: ${rawTextFingerprint(text)}")
            uberParser.parse(text)
        }
        if (offer != null) {
            resetIncompleteAccessibilityRetry()
            val now = System.currentTimeMillis()
            val signature = offer.accessibilityOfferSignature()
            if (
                signature == lastLegacyUberOfferSignature &&
                    now - lastLegacyUberOfferHandledAt < LEGACY_UBER_OFFER_DUPLICATE_WINDOW_MS
            ) {
                // A árvore atualiza várias vezes o mesmo cartão enquanto ele
                // está visível. Já o publicámos; evitar parse/avaliação repetidos
                // reduz trabalho de CPU e objetos de curta duração.
                lastLegacyUberPreflightFingerprint = preflight.fingerprint
                lastLegacyUberPreflightHandledAt = preflightNow
                return
            }
            lastLegacyUberOfferSignature = signature
            lastLegacyUberOfferHandledAt = now
            lastLegacyUberPreflightFingerprint = preflight.fingerprint
            lastLegacyUberPreflightHandledAt = preflightNow
            legacyUberAccessibilityCardActive = true
            val overlayTarget = accessibilityWindowTarget(rootNode)
            handleOffer(
                offer = offer,
                overlayAnchor = overlayTarget?.bounds,
                overlayDisplayId = overlayTarget?.displayId ?: Display.DEFAULT_DISPLAY,
                overlayConstrainToAnchor = overlayTarget?.let(::isGenuineSplitScreenOrDexPane) ?: false
            )
        } else {
            AppLogger.debug("Cartão Uber ainda incompleto após pré-filtro")
            scheduleIncompleteAccessibilityRetry(preflight.fingerprint)
        }
    }

    private fun logWeakUberAccessibilityEvent(preflight: UberOfferPreflight) {
        weakUberAccessibilityEvents++
        val now = System.currentTimeMillis()
        if (now - lastWeakUberAccessibilitySummaryAt < LEGACY_UBER_WEAK_EVENT_LOG_INTERVAL_MS) return
        AppLogger.debug(
            "Uber acessibilidade: $weakUberAccessibilityEvents atualização(ões) de mapa ignorada(s); " +
                "preço=${preflight.hasFare}, viagem=${preflight.hasTripMarker}, trechos=${preflight.routeSegments}"
        )
        weakUberAccessibilityEvents = 0
        lastWeakUberAccessibilitySummaryAt = now
    }

    private fun resetLegacyUberAccessibilityState(reason: String) {
        incompleteAccessibilityRetryRunnable?.let(mainHandler::removeCallbacks)
        incompleteAccessibilityRetryRunnable = null
        lastIncompleteAccessibilitySignature = null
        lastIncompleteAccessibilityAt = 0L
        lastLegacyUberOfferSignature = null
        lastLegacyUberOfferHandledAt = 0L
        lastLegacyUberPreflightFingerprint = null
        lastLegacyUberPreflightHandledAt = 0L
        legacyUberAccessibilityCardActive = false
        uberVisualCardActive = false
        lastUberVisualFingerprint = null
        lastUberParseAt = 0L
        skippedUberEvents = 0
        offerStabilityTracker.clear(com.daniel.tvdeinsight.domain.model.OfferPlatform.UBER)
        AppLogger.debug("Uber Android 12: estado do cartão limpo ($reason)")
    }

    private fun accessibilityEventText(
        event: AccessibilityEvent,
        sourceNode: AccessibilityNodeInfo?
    ): String = buildString {
        event.text.forEach { append(it).append(' ') }
        append(event.contentDescription ?: "").append(' ')
        append(sourceNode?.text ?: "").append(' ')
        append(sourceNode?.contentDescription ?: "")
    }.lowercase()

    /** Pista barata: não percorre a árvore e não cria Bitmap/OCR. */
    private fun hasUberAccessibilitySemanticHint(
        event: AccessibilityEvent,
        sourceNode: AccessibilityNodeInfo?
    ): Boolean {
        val text = accessibilityEventText(event, sourceNode)
        if (text.isBlank()) return false
        val hasAction = text.contains("aceitar") ||
            text.contains("selecionar") ||
            text.contains("corresponder")
        val hasFare = UBER_FARE_PREFLIGHT_REGEX.containsMatchIn(text)
        val hasRoute = text.contains("viagem") || text.contains("km") || text.contains("min")
        return hasAction || (hasFare && hasRoute)
    }

    private fun hasStrongUberAccessibilitySemanticHint(
        event: AccessibilityEvent,
        sourceNode: AccessibilityNodeInfo?
    ): Boolean {
        val text = accessibilityEventText(event, sourceNode)
        return text.contains("aceitar") ||
            text.contains("selecionar") ||
            text.contains("corresponder") ||
            text.contains("após dedução") ||
            text.contains("apos deducao")
    }

    private fun logIgnoredUberAccessibilityEvents(now: Long) {
        if (now - lastIgnoredUberAccessibilitySummaryAt < LEGACY_UBER_EVENT_LOG_INTERVAL_MS) return
        AppLogger.debug(
            "Uber Android 12: $ignoredUberAccessibilityEvents evento(s) sem pista de oferta ignorado(s)"
        )
        ignoredUberAccessibilityEvents = 0L
        lastIgnoredUberAccessibilitySummaryAt = now
    }

    private fun scheduleIncompleteAccessibilityRetry(cardText: String) {
        if (usesBitmapOcrForUber) return

        val now = System.currentTimeMillis()
        // A árvore pode chegar em duas fases. Mantemos somente uma assinatura
        // não reversível e fazemos uma única confirmação curta; nunca uma
        // sequência de tentativas causada por atualizações do mapa.
        val signature = rawTextFingerprint(cardText)
        if (
            signature == lastIncompleteAccessibilitySignature &&
                now - lastIncompleteAccessibilityAt < INCOMPLETE_ACCESSIBILITY_RETRY_COOLDOWN_MS
        ) return

        lastIncompleteAccessibilitySignature = signature
        lastIncompleteAccessibilityAt = now
        incompleteAccessibilityRetryRunnable?.let(mainHandler::removeCallbacks)
        val retry = Runnable {
            incompleteAccessibilityRetryRunnable = null
            if (!currentSettings.isAppRunning || !currentSettings.isUberEnabled || usesBitmapOcrForUber) return@Runnable

            val rootNode = findVisiblePackageRoot(UBER_PACKAGE_FRAGMENT) ?: return@Runnable
            if (rootNode.belongsToPackage(UBER_PACKAGE_FRAGMENT)) {
                AppLogger.debug("Nova leitura de acessibilidade após card Uber incompleto")
                processUberAccessibilityTree(rootNode)
            }
        }
        incompleteAccessibilityRetryRunnable = retry
        mainHandler.postDelayed(retry, INCOMPLETE_ACCESSIBILITY_RETRY_DELAY_MS)
    }

    private fun resetIncompleteAccessibilityRetry() {
        incompleteAccessibilityRetryRunnable?.let(mainHandler::removeCallbacks)
        incompleteAccessibilityRetryRunnable = null
        lastIncompleteAccessibilitySignature = null
        lastIncompleteAccessibilityAt = 0L
    }

    /** Aguarda a composição inicial do card antes da primeira captura OCR. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun scheduleUberWindowOcr(
        packageName: String,
        isWindowStateChange: Boolean,
        hasOfferHint: Boolean
    ) {
        val now = SystemClock.elapsedRealtime()
        val eventGeneration = uberEventGeneration
        // Uma mudança de estado abre a oportunidade de capturar o painel que a
        // Uber acabou de apresentar. Para eventos de conteúdo, porém, exigimos
        // uma pista do próprio cartão; o mapa emite esses eventos sem parar.
        if (!isWindowStateChange && !hasOfferHint) return
        // Nunca aplicar a janela mínima a TYPE_WINDOW_STATE_CHANGED: cada um
        // desses eventos pode representar uma oferta nova, inclusive outra
        // oferta com o mesmo preço/quilometragem que acabou de substituir a
        // anterior. O limite continua apenas nos eventos de conteúdo, que são
        // ruído contínuo do mapa.
        if (!isWindowStateChange && now - lastUberContentEventCaptureAt < UBER_EVENT_OCR_MIN_INTERVAL_MS) return
        if (isWindowStateChange) {
            uberWindowOcrRunnable?.let(mainHandler::removeCallbacks)
            uberWindowOcrRunnable = null
            uberBackgroundPanelProbeRunnable?.let(mainHandler::removeCallbacks)
            uberBackgroundPanelProbeRunnable = null
        } else if (uberWindowOcrRunnable != null) {
            return
        }
        if (shouldCaptureUberDisplay() && isWindowStateChange) {
            scheduleUberBackgroundPanelProbe(packageName, eventGeneration = eventGeneration)
            return
        }
        val task = Runnable {
            uberWindowOcrRunnable = null
            if (
                currentSettings.isAppRunning &&
                    currentSettings.isUberEnabled &&
                    isUberEventCaptureAllowed(eventGeneration)
            ) {
                when (requestOrQueueUberScreenshot("evento visual Uber: $packageName", eventGeneration)) {
                    ScreenshotRequestResult.Started -> {
                        lastUberContentEventCaptureAt = SystemClock.elapsedRealtime()
                    }
                    else -> Unit
                }
            }
        }
        uberWindowOcrRunnable = task
        mainHandler.postDelayed(task, UBER_OFFER_EVENT_OCR_DELAY_MS)
    }

    /**
     * O evento Uber chega antes da sobreposição estar pronta. Em vez de fazer
     * OCR contínuo sobre a app que está em primeiro plano, observa-se somente a
     * geometria das janelas por até 900 ms e captura-se quando o painel existir.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun scheduleUberBackgroundPanelProbe(
        packageName: String,
        attempt: Int = 0,
        eventGeneration: Long = uberEventGeneration
    ) {
        if (uberBackgroundPanelProbeRunnable != null) return
        val probe = Runnable {
            uberBackgroundPanelProbeRunnable = null
            if (!currentSettings.isAppRunning || !currentSettings.isUberEnabled ||
                !isUberEventCaptureAllowed(eventGeneration)
            ) return@Runnable
            if (backgroundUberOfferPanelTarget() != null) {
                when (requestOrQueueUberScreenshot("painel Uber em segundo plano: $packageName", eventGeneration)) {
                    ScreenshotRequestResult.Started -> lastUberContentEventCaptureAt = SystemClock.elapsedRealtime()
                    else -> Unit
                }
            } else if (attempt + 1 < MAX_UBER_BACKGROUND_PANEL_PROBES) {
                scheduleUberBackgroundPanelProbe(packageName, attempt + 1, eventGeneration)
            } else if (isRecentUberWindowEvent()) {
                // Alguns Samsung/Android 16 não publicam o painel da Uber na
                // lista de janelas de acessibilidade. O próprio evento Uber é
                // a autorização semântica; capturar o display inteiro permite
                // que o pré-filtro visual rejeite mapas sem gastar ML Kit.
                when (requestOrQueueUberScreenshot("fallback display Uber em segundo plano: $packageName", eventGeneration)) {
                    ScreenshotRequestResult.Started -> lastUberContentEventCaptureAt = SystemClock.elapsedRealtime()
                    else -> Unit
                }
            }
        }
        uberBackgroundPanelProbeRunnable = probe
        mainHandler.postDelayed(probe, if (attempt == 0) UBER_BACKGROUND_PANEL_INITIAL_DELAY_MS else UBER_BACKGROUND_PANEL_PROBE_DELAY_MS)
    }

    /**
     * This is only a cheap admission signal. It never extracts, stores, or
     * evaluates the ride from Accessibility data; ML Kit remains the source of
     * truth on Android 13+.
     */
    private fun eventHasUberOfferHint(
        event: AccessibilityEvent,
        sourceNode: AccessibilityNodeInfo?
    ): Boolean {
        val text = buildString {
            event.text.forEach { append(it).append(' ') }
            append(event.contentDescription ?: "").append(' ')
            append(sourceNode?.text ?: "").append(' ')
            append(sourceNode?.contentDescription ?: "")
        }.lowercase()
        if (text.isBlank()) return false
        val offerLabels = listOf(
            "após dedução", "apos deducao", "viagem de", "exclusivo",
            "selecionar", "aceitar", "incluído para embarque", "incluido para embarque"
        )
        return offerLabels.any(text::contains) && (text.contains('€') || text.contains("km"))
    }

    /**
     * Diagnóstico de campo para descobrir se as ofertas Uber expõem uma
     * assinatura de acessibilidade própria. Nunca escreve preço, moradas ou
     * qualquer outro texto da oferta no log. Só existe no APK debug.
     */
    private fun logUberAccessibilityDiagnostic(
        event: AccessibilityEvent,
        sourceNode: AccessibilityNodeInfo?
    ) {
        if (!BuildConfig.DEBUG) return
        val eventType = event.eventType
        if (
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val now = SystemClock.elapsedRealtime()
        if (
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            now - lastUberAccessibilityDiagnosticAt < UBER_DIAGNOSTIC_EVENT_MIN_INTERVAL_MS
        ) return
        if (
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !hasUberAccessibilitySemanticHint(event, sourceNode)
        ) return
        lastUberAccessibilityDiagnosticAt = now

        val directText = accessibilityEventText(event, sourceNode)
        val extractedCardText = sourceNode
            ?.takeIf { it.belongsToPackage(UBER_PACKAGE_FRAGMENT) }
            ?.let { source ->
                val root = source.topMostParent()
                try {
                    runCatching { root.extractUberOfferCardText() }.getOrNull()
                } finally {
                    if (root !== source) root.recycle()
                }
            }
            .orEmpty()
            .lowercase()
        val combined = "$directText $extractedCardText"
        val sourceId = sourceNode?.viewIdResourceName?.takeLast(80).orEmpty()
        val sourceClass = sourceNode?.className?.toString()?.substringAfterLast('.')?.take(60).orEmpty()

        AppLogger.debug(
            "Diag Uber acessibilidade: tipo=${accessibilityEventName(eventType)}, " +
                "janela=${event.windowId}, alteração=${event.contentChangeTypes}, " +
                "classe=${event.className?.toString()?.substringAfterLast('.')?.take(60)}, " +
                "nó=$sourceClass, id=$sourceId, aceitar=${combined.contains("aceitar")}, " +
                "selecionar=${combined.contains("selecionar")}, exclusivo=${combined.contains("exclusivo")}, " +
                "valor=${combined.contains('€')}, percurso=${combined.contains("viagem de")}, " +
                "cartãoCompleto=${hasStrongUberCardSignal(extractedCardText)}"
        )
    }

    private fun accessibilityEventName(eventType: Int): String = when (eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS"
        else -> eventType.toString()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestScreenshot(
        reason: String,
        eventGeneration: Long? = null
    ): ScreenshotRequestResult {
        if (!isDeviceReadyForOcr()) return ScreenshotRequestResult.Rejected
        if (!isUberVisibleForOcr() &&
            (eventGeneration == null || !isUberEventCaptureAllowed(eventGeneration))
        ) return ScreenshotRequestResult.Rejected
        if (textRecognizer == null || ocrWarmupInFlight.get()) {
            return ScreenshotRequestResult.RetryAfter(OCR_RECOGNIZER_BUSY_RETRY_DELAY_MS)
        }
        if (nativeFrameInFlight.get()) {
            return ScreenshotRequestResult.RetryAfter(OCR_BUSY_RETRY_DELAY_MS)
        }

        val now = SystemClock.elapsedRealtime()
        when (val admission = screenshotGate.tryAcquire(now)) {
            OcrCaptureGate.Admission.Allowed -> Unit
            OcrCaptureGate.Admission.Busy ->
                return ScreenshotRequestResult.RetryAfter(OCR_BUSY_RETRY_DELAY_MS)
            is OcrCaptureGate.Admission.TooSoon ->
                return ScreenshotRequestResult.RetryAfter(admission.retryAfterMs)
        }

        val requestId = screenshotSequence.incrementAndGet()
        activeScreenshotId = requestId
        activeScreenshotStartedAt = now
        // The authorization belongs to this request, not to a three-second
        // wall-clock window. ML Kit is allowed to finish the valid frame.
        activeCaptureAuthorization = true
        AppLogger.debug("Solicitando captura Uber: motivo=$reason, id=$requestId")
        armScreenshotWatchdog(requestId)

        mainHandler.post { requestScreenshotPayload(requestId) }
        return ScreenshotRequestResult.Started
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestOrQueueUberScreenshot(
        reason: String,
        eventGeneration: Long
    ): ScreenshotRequestResult {
        val result = requestScreenshot(reason, eventGeneration)
        if (result is ScreenshotRequestResult.RetryAfter) {
            queueLatestUberScreenshot(reason, eventGeneration, result.delayMs)
        }
        return result
    }

    /** Keeps only the newest semantic trigger. No timer can block a later ride. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun queueLatestUberScreenshot(
        reason: String,
        eventGeneration: Long,
        delayMs: Long
    ) {
        pendingUberCapture = PendingUberCapture(reason, eventGeneration)
        pendingUberCaptureRunnable?.let(mainHandler::removeCallbacks)
        val task = Runnable {
            pendingUberCaptureRunnable = null
            val pending = pendingUberCapture ?: return@Runnable
            if (!isUberEventCaptureAllowed(pending.eventGeneration)) {
                pendingUberCapture = null
                AppLogger.debug("Gatilho Uber pendente expirou antes da captura")
                return@Runnable
            }
            when (val result = requestScreenshot(pending.reason, pending.eventGeneration)) {
                ScreenshotRequestResult.Started -> {
                    pendingUberCapture = null
                    lastUberContentEventCaptureAt = SystemClock.elapsedRealtime()
                    AppLogger.debug("Gatilho Uber pendente executado")
                }
                is ScreenshotRequestResult.RetryAfter ->
                    queueLatestUberScreenshot(
                        pending.reason,
                        pending.eventGeneration,
                        result.delayMs
                    )
                ScreenshotRequestResult.Rejected -> pendingUberCapture = null
            }
        }
        pendingUberCaptureRunnable = task
        mainHandler.postDelayed(task, delayMs.coerceAtLeast(OCR_BUSY_RETRY_DELAY_MS))
    }

    private fun isUberEventCaptureAllowed(eventGeneration: Long): Boolean {
        if (!currentSettings.isAppRunning || !currentSettings.isUberEnabled) return false
        if (eventGeneration != uberEventGeneration) return false
        if (isUberVisibleForOcr()) return true
        val age = SystemClock.elapsedRealtime() - lastUberWindowStateAt
        return age in 0..UBER_EVENT_CAPTURE_ADMISSION_MS
    }

    /** Prefer the app window on API 34+; fallback to its display, never the 167px shortcut. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestScreenshotPayload(requestId: Long) {
        if (!isScreenshotActive(requestId)) return
        val target = currentUberCaptureTarget()
        activeCaptureIsWindow = false
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshotResult: ScreenshotResult) {
                processScreenshotResult(requestId, screenshotResult, target)
            }

            override fun onFailure(errorCode: Int) {
                releaseScreenshot(requestId, "falha código=$errorCode")
                if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    scheduleIncompleteOcrRetry(SCREENSHOT_INTERVAL_MS)
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34 && target != null && !target.captureWholeDisplay) {
                // Window capture excludes our own cards, preventing recursive OCR contamination.
                activeCaptureIsWindow = true
                takeScreenshotOfWindow(target.windowId, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) = callback.onSuccess(result)
                    override fun onFailure(errorCode: Int) {
                        if (!isScreenshotActive(requestId)) return
                        activeCaptureIsWindow = false
                        try {
                            takeScreenshot(target.displayId, mainExecutor, callback)
                        } catch (error: Exception) {
                            releaseScreenshot(requestId, "display indisponível no fallback", error)
                        }
                    }
                })
            } else takeScreenshot(target?.displayId ?: Display.DEFAULT_DISPLAY, mainExecutor, callback)
        } catch (error: Throwable) {
            releaseScreenshot(requestId, "exceção ao solicitar captura", error)
        }
    }

    private fun warmUpTextRecognizer() {
        val recognizer = textRecognizer ?: return
        if (!ocrWarmupInFlight.compareAndSet(false, true)) return
        val warmupBitmap = Bitmap.createBitmap(OCR_WARMUP_SIZE_PX, OCR_WARMUP_SIZE_PX, Bitmap.Config.ARGB_8888)
        try {
            recognizer.process(InputImage.fromBitmap(warmupBitmap, 0))
                .addOnSuccessListener { AppLogger.debug("OCR Uber preparado para a primeira oferta") }
                .addOnFailureListener { AppLogger.warn("Falha ao preparar OCR Uber", it) }
                .addOnCompleteListener {
                    warmupBitmap.recycle()
                    ocrWarmupInFlight.set(false)
                }
        } catch (error: Throwable) {
            warmupBitmap.recycle()
            ocrWarmupInFlight.set(false)
            AppLogger.warn("Não foi possível iniciar a preparação do OCR Uber", error)
        }
    }

    private fun armScreenshotWatchdog(requestId: Long) {
        screenshotTimeoutRunnable?.let(mainHandler::removeCallbacks)
        val timeout = Runnable {
            if (activeScreenshotId == requestId && screenshotGate.isActive()) {
                recoverOcrAfterTimeout(requestId)
            }
        }
        screenshotTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, SCREENSHOT_TIMEOUT_MS)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun processScreenshotResult(
        requestId: Long,
        screenshotResult: ScreenshotResult,
        target: AppWindowTarget?
    ) {
        var bitmap: Bitmap? = null
        var handedToOcr = false
        val hardwareBuffer: HardwareBuffer = screenshotResult.hardwareBuffer
        try {
            if (!isScreenshotActive(requestId)) {
                AppLogger.debug("Captura obsoleta ignorada antes do OCR: id=$requestId")
                return
            }

            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.colorSpace)
            try {
                val copied = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                bitmap = copied
                if (bitmap !== copied) copied?.recycle()
            } finally {
                // A cópia ARGB é independente. Libertar explicitamente o wrapper
                // evita acumulação de memória nativa entre milhares de capturas.
                hardwareBitmap?.recycle()
            }
            if (bitmap == null) {
                releaseScreenshot(requestId, "buffer sem bitmap")
                return
            }

            // A janela auxiliar do Uber observada nos registos (167x167) não é
            // um frame do display. Nunca a ampliamos artificialmente para OCR.
            if (bitmap.width < MIN_VALID_DISPLAY_DIMENSION_PX ||
                bitmap.height < MIN_VALID_DISPLAY_DIMENSION_PX
            ) {
                AppLogger.warn(
                    "Captura rejeitada por dimensão insuficiente: " +
                        "${bitmap.width}x${bitmap.height}; esperado display completo"
                )
                releaseScreenshot(requestId, "dimensão insuficiente")
                return
            }

            if (!isCaptureAuthorized(requestId)) {
                releaseScreenshot(requestId, "autorização da captura Uber inválida")
                return
            }

            AppLogger.debug(
                "Captura recebida: id=$requestId, ${bitmap.width}x${bitmap.height}, " +
                    "origem=${if (activeCaptureIsWindow) "janela" else "display"}, display=${target?.displayId ?: 0}"
            )
            processOcrBitmap(requestId, bitmap, target)
            handedToOcr = true
            bitmap = null
        } catch (error: Throwable) {
            AppLogger.warn("Falha ao converter captura para OCR", error)
            releaseScreenshot(requestId, "erro no processamento")
        } finally {
            bitmap?.recycle()
            hardwareBuffer.close()
            if (!handedToOcr) releaseScreenshot(requestId, "captura não entregue ao OCR")
        }
    }

    /** One native frame in flight, including after watchdog timeout; never queue hardware bitmaps. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun processOcrBitmap(requestId: Long, bitmap: Bitmap, target: AppWindowTarget?) {
        val recognizer = textRecognizer
        if (recognizer == null || !nativeFrameInFlight.compareAndSet(false, true)) {
            bitmap.recycle()
            releaseScreenshot(requestId, "OCR ocupado/indisponível")
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        val capturedWindow = activeCaptureIsWindow
        try {
            visionExecutor.execute {
                var frame: Bitmap = bitmap
                var result: com.google.mlkit.vision.text.Text? = null
                var failure: Throwable? = null
                var rejectedSuspiciousFare = false
                var fareVerifiedInFrame = false
                var fareRejectedOffer: TripOffer? = null
                var stableFrameSkipped = false
                var visualFingerprint: Long? = null
                var preparedFrameWidth = 0
                var preparedFrameHeight = 0
                try {
                    // A full-display screenshot is cropped to the application's actual window, not a fixed layout.
                    if (target != null && !capturedWindow) {
                        val roi = Rect(target.bounds)
                        if (roi.intersect(0, 0, bitmap.width, bitmap.height) && !roi.isEmpty) {
                            frame = Bitmap.createBitmap(bitmap, roi.left, roi.top, roi.width(), roi.height())
                        }
                    }
                    // A deliberately permissive, pixel-only admission check avoids
                    // OpenCV/ML Kit work on a plain map. It never decides a ride;
                    // a false positive merely reaches the normal strict pipeline.
                    visualFingerprint = uberVisualFingerprint(frame, target, capturedWindow)
                    if (
                        uberVisualCardActive &&
                            visualFingerprint == lastUberVisualFingerprint
                    ) {
                        // This is the same visible card, not a global time lock.
                        // A different card (or a card closed and reopened) gets a
                        // different event/fingerprint and is processed immediately.
                        stableFrameSkipped = true
                        AppLogger.debug(
                            "OCR Uber ignorado: cartão visível sem alteração, " +
                                "fingerprint=${visualFingerprint.toString(16)}"
                        )
                    } else if (!OpenCvOcrPreprocessor.hasLikelyOfferPanel(frame)) {
                        uberVisualCardActive = false
                        lastUberVisualFingerprint = null
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastVisualPrefilterLoggedAt >= OCR_PREFILTER_LOG_INTERVAL_MS) {
                            lastVisualPrefilterLoggedAt = now
                            AppLogger.debug("OCR Uber ignorado: pré-filtro visual sem painel")
                        }
                    } else {
                        // O evento de acessibilidade já confirmou que a Uber
                        // apresentou uma janela nova. Limitamos a análise a duas
                        // leituras do MESMO frame: OpenCV (cartão completo) e a
                        // imagem original. Não há varrimentos ou retries visuais
                        // em cadeia que possam atrasar uma oferta real.
                        OpenCvOcrPreprocessor.prepare(
                            frame,
                            bottomHalf = false,
                            binarize = true
                        ).use { prepared ->
                            preparedFrameWidth = prepared.bitmap.width
                            preparedFrameHeight = prepared.bitmap.height
                            val passStart = SystemClock.elapsedRealtime()
                            result = com.google.android.gms.tasks.Tasks.await(
                                recognizer.process(InputImage.fromBitmap(prepared.bitmap, 0))
                            )
                            android.util.Log.d(
                                "OpenCV",
                                "id=$requestId pass=1/$MAX_OCR_READS_PER_OFFER ms=${SystemClock.elapsedRealtime() - passStart}"
                            )
                        }
                        val candidate = result?.let(uberCardTextExtractor::extractCard)
                        val offer = candidate?.let { uberParser.parse(it.text) }
                        if (offer != null && isScreenshotActive(requestId)) {
                            if (UberFareValidation.requiresIndependentRead(offer.price)) {
                                    // The OpenCV pass can occasionally merge a nearby glyph with a
                                    // fare. Every monetary value is therefore confirmed against an
                                    // independent recognition of the untouched software bitmap: no
                                    // upper price limit is imposed on a legitimate long trip.
                                    val independentFrame = cropUberCardForIndependentRead(
                                        source = frame,
                                        bounds = candidate.bounds,
                                        recognizedWidth = preparedFrameWidth,
                                        recognizedHeight = preparedFrameHeight
                                    )
                                    val originalText = try {
                                        val originalPassStart = SystemClock.elapsedRealtime()
                                        com.google.android.gms.tasks.Tasks.await(
                                            recognizer.process(InputImage.fromBitmap(independentFrame, 0))
                                        ).also {
                                            android.util.Log.d(
                                                "OpenCV",
                                                "id=$requestId pass=2/$MAX_OCR_READS_PER_OFFER " +
                                                    "roi=${independentFrame.width}x${independentFrame.height} " +
                                                    "ms=${SystemClock.elapsedRealtime() - originalPassStart}"
                                            )
                                        }
                                    } finally {
                                        if (independentFrame !== frame) independentFrame.recycle()
                                    }
                                    val originalOffer = uberCardTextExtractor.extractCard(originalText)
                                        ?.let { uberParser.parse(it.text) }
                                    // The untouched full-display pass can see
                                    // the TVDE overlay that was published for a
                                    // previous offer. Only compare fares when
                                    // both OCR candidates describe the same
                                    // native Uber card; a different route is
                                    // not evidence of a wrong fare.
                                    val sameCardIndependentFare = originalOffer
                                        ?.takeIf { areSameUberOffer(offer, it) }
                                        ?.price
                                    if (originalOffer != null && sameCardIndependentFare == null) {
                                        AppLogger.debug(
                                            "Leitura Uber independente pertence a outro cartão; " +
                                                "não rejeita a oferta atual"
                                        )
                                    }
                                    when (
                                        UberFareValidation.resolveSuspiciousFare(
                                            primaryFare = offer.price,
                                            independentFare = sameCardIndependentFare
                                        )
                                    ) {
                                        UberFareValidation.Resolution.USE_PRIMARY -> {
                                            fareVerifiedInFrame = true
                                            AppLogger.info(
                                                "Preço Uber confirmado no mesmo frame por duas leituras: €${offer.price}"
                                            )
                                        }
                                        UberFareValidation.Resolution.AWAIT_SECOND_FRAME -> {
                                            // O pré-processamento já encontrou um cartão completo;
                                            // a imagem sem tratamento apenas não conseguiu ler o
                                            // preço. Guardamos a primeira leitura como candidata e
                                            // fazemos uma única confirmação temporal, em vez de a
                                            // descartar como se os preços divergissem.
                                            AppLogger.debug(
                                                "Preço Uber sem segunda leitura no mesmo frame: " +
                                                    "€${offer.price}; aguarda confirmação temporal"
                                            )
                                        }
                                        UberFareValidation.Resolution.REJECT -> {
                                            rejectedSuspiciousFare = true
                                            fareRejectedOffer = offer
                                            result = null
                                            AppLogger.warn(
                                                "Preço Uber divergente; card descartado para evitar valor incorreto: " +
                                                    "OCR=${offer.price}, original=${originalOffer?.price}"
                                            )
                                        }
                                    }
                            }
                        } else if (result != null) {
                            AppLogger.debug("OCR Uber: primeira leitura sem cartão completo; aguardando próximo evento")
                        }
                    }
                } catch (error: Throwable) {
                    failure = error
                }
                val output = result
                val error = failure
                val fareVerified = fareVerifiedInFrame
                val rejectedOffer = fareRejectedOffer
                mainHandler.post {
                    try {
                        if (error != null) {
                            AppLogger.warn("Falha no pipeline OpenCV/MLKit: id=$requestId", error)
                        } else if (rejectedSuspiciousFare) {
                            // Nunca publicamos preços que divergem no mesmo frame. Há ainda
                            // uma única captura posterior para distinguir uma transição visual
                            // de uma leitura realmente incorreta; não existe polling contínuo.
                            AppLogger.debug("Oferta Uber aguarda nova leitura após divergência de preço")
                        } else if (output != null) {
                            consumeOcrResult(
                                requestId,
                                frame,
                                output,
                                target,
                                fareVerified,
                                visualFingerprint
                            )
                        }
                    } finally {
                        if (frame !== bitmap) frame.recycle()
                        bitmap.recycle()
                        nativeFrameInFlight.set(false)
                        recordOcrCompletion(SystemClock.elapsedRealtime() - startedAt)
                        releaseScreenshot(requestId, if (error == null) "OCR concluído" else "OCR falhou")
                        if (error == null) {
                            rejectedOffer?.let(::scheduleBoundedUberConfirmation)
                        }
                        completeStressFrame(
                            requestId,
                            when {
                                error != null -> "ERROR"
                                stableFrameSkipped -> "UNCHANGED"
                                output != null -> "OK"
                                else -> "ERROR"
                            }
                        )
                    }
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            bitmap.recycle()
            nativeFrameInFlight.set(false)
            releaseScreenshot(requestId, "serviço terminado", error)
        }
    }

    private fun consumeOcrResult(
        requestId: Long, bitmap: Bitmap, visionText: com.google.mlkit.vision.text.Text,
        target: AppWindowTarget?,
        fareVerifiedInFrame: Boolean,
        visualFingerprint: Long?
    ) {
        if (!isScreenshotActive(requestId)) {
            AppLogger.debug("Resultado OCR obsoleto ignorado: id=$requestId")
            return
        }
        if (
            !currentSettings.isAppRunning ||
                !currentSettings.isUberEnabled ||
                !isCaptureAuthorized(requestId)
        ) {
            AppLogger.debug("Resultado OCR sem autorização ativa ignorado: id=$requestId")
            return
        }

        val extractedCard = uberCardTextExtractor.extractCard(visionText)
        if (extractedCard == null) {
            // The card disappeared (or the transition frame has no card).
            // Clear the visual gate so an identical-looking future offer is
            // not mistaken for the previous one.
            uberVisualCardActive = false
            lastUberVisualFingerprint = null
            val rawText = visionText.text
            recordOcrWithoutUberCard(requestId, rawText)
            // A tela inicial da Uber não é um cartão incompleto. Repetir
            // OCR nesse estado criava rajadas permanentes de capturas.
            if (isUberScheduledRequest(rawText)) {
                // Os pedidos de agendamento não são viagens normais: não têm os
                // campos de recolha/destino necessários aos critérios. Foram
                // explicitamente excluídos do fluxo de análise; sobretudo, não
                // podem acionar a rajada de novas tentativas OCR.
                resetIncompleteOcrRetry()
                AppLogger.debug("Pedido de agendamento Uber ignorado")
            } else if (hasStrongUberCardSignal(rawText) || isRecentUberWindowEvent()) {
                // A primeira captura pode ocorrer durante a animação do painel:
                // nesse instante a árvore/OCR ainda não contém os campos do
                // cartão, mesmo quando já existe uma oferta visível no display.
                // Correlacionamos a confirmação com o evento de janela recente,
                // em vez de usar um temporizador global. Assim uma nova oferta
                // (novo evento/fingerprint) nunca fica bloqueada pela anterior,
                // enquanto telas normais da Uber continuam sem polling/OCR extra.
                val trigger = if (hasStrongUberCardSignal(rawText)) {
                    "sinal semântico"
                } else {
                    "evento de janela recente"
                }
                AppLogger.debug(
                    "OCR Uber sem card após $trigger; agenda a única confirmação visual permitida"
                )
                scheduleBoundedUberConfirmationForIncompleteCard()
            }
            return
        }

        val cardText = extractedCard.text
        val fields = com.daniel.tvdeinsight.service.ocr.UberVisionFields.parse(cardText)
        android.util.Log.d("Regex", "id=$requestId structuredFields=${fields != null}")
        val offer = uberParser.parse(cardText)?.copy(category = extractedCard.category)
        if (offer == null) {
            uberVisualCardActive = false
            lastUberVisualFingerprint = null
            recordInvalidUberCard(cardText)
            scheduleBoundedUberConfirmationForIncompleteCard()
        } else if (!fareVerifiedInFrame) {
            // A segunda leitura do frame original foi indisponível. Mantemos
            // esta oferta apenas como primeira leitura estável e fazemos uma
            // única captura posterior. Dois frames OpenCV iguais publicam o
            // cartão; preços diferentes jamais são publicados.
            AppLogger.warn("Card Uber completo sem segunda leitura; aguarda confirmação temporal")
            uberVisualCardActive = true
            lastUberVisualFingerprint = visualFingerprint
            handleOffer(
                offer = offer,
                ocrScreenshot = bitmap,
                overlayAnchor = target?.bounds,
                overlayDisplayId = target?.displayId ?: Display.DEFAULT_DISPLAY,
                overlayConstrainToAnchor = target?.let(::isGenuineSplitScreenOrDexPane) ?: false
            )
            scheduleBoundedUberConfirmation(offer)
        } else {
            uberVisualCardActive = true
            lastUberVisualFingerprint = visualFingerprint
            resetBoundedUberConfirmation()
            lastOcrNoCardSignature = null
            lastOcrNoCardLoggedAt = 0L
            logOcrNoCardSummaryIfDue(System.currentTimeMillis(), force = true)
            AppLogger.info(
                "Card Uber detetado por OCR: categoria=${offer.category.orEmpty()}, " +
                    "€=${offer.price}, ${rawTextFingerprint(cardText)}"
            )
            // A confirmação do preço já ocorreu no worker usando duas leituras
            // independentes do MESMO frame: OpenCV + bitmap original. Pedir uma
            // segunda captura depois disso atrasava o card e podia perdê-lo quando
            // o pedido desaparecia ou quando uma verificação periódica ocupava a
            // única fila de captura.
            handleOffer(
                offer = offer,
                alreadyConfirmedByOcr = true,
                ocrScreenshot = bitmap,
                overlayAnchor = target?.bounds,
                overlayDisplayId = target?.displayId ?: Display.DEFAULT_DISPLAY,
                overlayConstrainToAnchor = target?.let(::isGenuineSplitScreenOrDexPane) ?: false
            )
        }
    }

    /**
     * The primary pass gives us the card geometry. Confirming against this
     * bounded region removes the launcher/map and old TVDE overlays from the
     * independent pass, cutting pixels and improving fare recognition.
     */
    private fun cropUberCardForIndependentRead(
        source: Bitmap,
        bounds: UberOfferCardTextExtractor.OcrBounds?,
        recognizedWidth: Int,
        recognizedHeight: Int
    ): Bitmap {
        if (bounds == null || recognizedWidth <= 0 || recognizedHeight <= 0) return source
        val scaleX = source.width.toFloat() / recognizedWidth
        val scaleY = source.height.toFloat() / recognizedHeight
        val marginX = recognizedWidth * UBER_CARD_ROI_HORIZONTAL_MARGIN_RATIO
        val marginTop = recognizedHeight * UBER_CARD_ROI_TOP_MARGIN_RATIO
        val marginBottom = recognizedHeight * UBER_CARD_ROI_BOTTOM_MARGIN_RATIO
        val left = ((bounds.left - marginX) * scaleX).toInt().coerceIn(0, source.width - 1)
        val top = ((bounds.top - marginTop) * scaleY).toInt().coerceIn(0, source.height - 1)
        val right = ((bounds.right + marginX) * scaleX).toInt().coerceIn(left + 1, source.width)
        val bottom = ((bounds.bottom + marginBottom) * scaleY).toInt().coerceIn(top + 1, source.height)
        if (right - left < MIN_UBER_CARD_ROI_DIMENSION_PX ||
            bottom - top < MIN_UBER_CARD_ROI_DIMENSION_PX
        ) return source
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun screenshotPollIntervalMs(): Long {
        val base = if (isUberInForeground()) {
            UBER_FOREGROUND_SCREENSHOT_POLL_INTERVAL_MS
        } else {
            UBER_BACKGROUND_SCREENSHOT_POLL_INTERVAL_MS
        }
        return if (isBatteryConstrained()) base * 2 else base
    }

    /**
     * Em DeX e em tela dividida a Uber pode estar visível sem ser a janela ativa.
     * Para OCR usamos a visibilidade real de uma janela grande da Uber, nunca uma
     * janela auxiliar como a bolha 167x167 observada nos registos.
     */
    private fun shouldPollUberOcr(): Boolean {
        // Em segundo plano, cada oferta é acionada pelo evento de
        // acessibilidade da própria Uber. Não voltamos a capturar o mesmo
        // painel por polling: isso podia reler uma oferta já tratada (ou o
        // cartão do TVDE sobreposto) e publicar uma análise duplicada.
        // Mantemos o polling apenas quando a Uber é a aplicação ativa, como
        // rede de segurança para um evento de janela perdido no primeiro plano.
        return isDeviceReadyForOcr() && isUberInForeground()
    }

    /** Never capture while the user cannot see the device. */
    private fun isDeviceReadyForOcr(): Boolean {
        val power = getSystemService(android.os.PowerManager::class.java)
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        return power?.isInteractive != false && keyguard?.isKeyguardLocked != true
    }

    /** Low battery without charging keeps event-triggered OCR responsive but slows polling. */
    private fun isBatteryConstrained(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBatteryConstraintCheckAt < BATTERY_STATE_CACHE_MS) {
            return cachedBatteryConstrained
        }
        val battery = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?: return cachedBatteryConstrained
        val level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val status = battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        cachedBatteryConstrained = !charging && level >= 0 && scale > 0 &&
            level * 100 / scale <= LOW_BATTERY_PERCENT
        lastBatteryConstraintCheckAt = now
        return cachedBatteryConstrained
    }

    /**
     * Correlates two OCR candidates without using the fare itself. This keeps
     * the independent fare guard useful while preventing a previous TVDE card
     * (or a second native panel) from being mistaken for the current offer.
     */
    private fun areSameUberOffer(primary: TripOffer, independent: TripOffer): Boolean {
        if (primary.platform != independent.platform) return false
        val categoryA = primary.category?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val categoryB = independent.category?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (categoryA.isNotBlank() && categoryB.isNotBlank() && categoryA != categoryB) return false

        fun close(a: Double?, b: Double?, absoluteTolerance: Double): Boolean {
            if (a == null || b == null || !a.isFinite() || !b.isFinite()) return true
            return kotlin.math.abs(a - b) <= maxOf(absoluteTolerance, maxOf(a, b) * 0.08)
        }

        val routePairs = listOf(
            primary.pickupDistanceKm to independent.pickupDistanceKm,
            primary.pickupDurationMinutes to independent.pickupDurationMinutes,
            primary.tripDistanceKm to independent.tripDistanceKm,
            primary.tripDurationMinutes to independent.tripDurationMinutes
        )
        val comparable = routePairs.count { (a, b) -> a != null && b != null }
        if (comparable < 2) return false
        return routePairs.all { (a, b) -> close(a, b, absoluteTolerance = 0.25) }
    }

    /**
     * Computes a tiny, allocation-free fingerprint of the lower part of the
     * captured Uber surface. The map above the card is intentionally ignored:
     * GPS animation must not force ML Kit to reread an unchanged offer. The
     * target/window identity is included so a display capture and an app-window
     * capture can never suppress one another accidentally.
     */
    private fun uberVisualFingerprint(
        bitmap: Bitmap,
        target: AppWindowTarget?,
        capturedWindow: Boolean
    ): Long {
        var hash = -0x340d631b8c467a5bL // FNV-1a offset basis
        hash = (hash xor bitmap.width.toLong()) * 0x100000001b3L
        hash = (hash xor bitmap.height.toLong()) * 0x100000001b3L
        hash = (hash xor (if (capturedWindow) 1L else 0L)) * 0x100000001b3L
        hash = (hash xor (target?.windowId ?: INVALID_WINDOW_ID).toLong()) * 0x100000001b3L

        val columns = 24
        val rows = 18
        val top = (bitmap.height * 0.30f).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = (bitmap.height * 0.96f).toInt().coerceIn(top + 1, bitmap.height)
        for (row in 0 until rows) {
            val y = top + ((row + 1) * (bottom - top) / (rows + 1))
            for (column in 0 until columns) {
                val x = ((column + 1) * bitmap.width / (columns + 1))
                    .coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(x, y)
                val red = pixel ushr 16 and 0xff
                val green = pixel ushr 8 and 0xff
                val blue = pixel and 0xff
                // Four-bit luminance/chroma bins tolerate sub-pixel animation
                // while still changing for text, buttons, fares and routes.
                val luminance = (red * 77 + green * 150 + blue * 29) ushr 8
                val chroma = ((maxOf(red, green, blue) - minOf(red, green, blue)) ushr 4)
                val sample = ((luminance ushr 4) shl 4) or chroma
                hash = (hash xor sample.toLong()) * 0x100000001b3L
            }
        }
        return hash
    }

    /**
     * Usa a última mudança de janela enquanto ainda é recente e, depois, consulta
     * a raiz ativa. Nunca mantém um pacote Uber indefinidamente como fallback.
     */
    private fun currentForegroundPackage(): String {
        // A non-focusable TVDE card is not the foreground application.
        val focusedRoot = rootInActiveWindow
        if (focusedRoot != null) {
            try {
                val focusedPackage = focusedRoot.packageName?.toString().orEmpty()
                if (focusedPackage.isNotBlank()) return focusedPackage
            } finally {
                @Suppress("DEPRECATION")
                focusedRoot.recycle()
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (
            foregroundPackage.isNotBlank() &&
                now - foregroundPackageUpdatedAt <= FOREGROUND_EVENT_FRESHNESS_MS
        ) {
            return foregroundPackage
        }
        return ""
    }

    /** Recolhe uma segunda imagem após uma leitura parcial, sem aceitar dados incompletos. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun scheduleIncompleteOcrRetry(delayMs: Long = INCOMPLETE_OCR_RETRY_DELAY_MS) {
        val now = System.currentTimeMillis()
        if (now - incompleteOcrRetryStartedAt > INCOMPLETE_OCR_RETRY_WINDOW_MS) {
            incompleteOcrRetryStartedAt = now
            incompleteOcrRetryAttempts = 0
        }
        if (incompleteOcrRetryAttempts >= MAX_INCOMPLETE_OCR_RETRIES) return
        if (incompleteOcrRetryRunnable != null) return

        val retry = Runnable {
            incompleteOcrRetryRunnable = null
            if (currentSettings.isAppRunning && currentSettings.isUberEnabled) {
                val result = requestScreenshot("nova tentativa após card Uber incompleto")
                if (result == ScreenshotRequestResult.Started) {
                    incompleteOcrRetryAttempts += 1
                } else if (result is ScreenshotRequestResult.RetryAfter) {
                    scheduleIncompleteOcrRetry(
                        result.delayMs.coerceAtLeast(OCR_BUSY_RETRY_DELAY_MS)
                    )
                }
            }
        }
        incompleteOcrRetryRunnable = retry
        mainHandler.postDelayed(retry, delayMs)
    }

    private fun resetIncompleteOcrRetry() {
        incompleteOcrRetryRunnable?.let(mainHandler::removeCallbacks)
        incompleteOcrRetryRunnable = null
        incompleteOcrRetryAttempts = 0
        incompleteOcrRetryStartedAt = 0L
    }

    /**
     * Uma oferta em segundo plano tem no máximo uma captura de confirmação
     * depois da primeira. O atraso deixa a animação do painel terminar sem
     * reintroduzir o polling contínuo que ocupava a fila de screenshots.
     */
    private fun scheduleBoundedUberConfirmation(offer: TripOffer) {
        scheduleBoundedUberConfirmation(offer.stabilitySignature())
    }

    private fun scheduleBoundedUberConfirmationForIncompleteCard() {
        // O instante da mudança de janela associa a nova imagem ao mesmo painel
        // sem conservar texto, endereço ou outro conteúdo da oferta.
        scheduleBoundedUberConfirmation("incomplete:$lastUberWindowStateAt")
    }

    @android.annotation.SuppressLint("NewApi")
    private fun scheduleBoundedUberConfirmation(signature: String, delayMs: Long = UBER_CONFIRMATION_CAPTURE_DELAY_MS) {
        if (signature.isBlank()) return
        if (signature != boundedUberConfirmationSignature) {
            boundedUberConfirmationRunnable?.let(mainHandler::removeCallbacks)
            boundedUberConfirmationRunnable = null
            boundedUberConfirmationSignature = signature
            boundedUberConfirmationAttempts = 0
        }
        if (boundedUberConfirmationAttempts >= MAX_UBER_CONFIRMATION_CAPTURES || boundedUberConfirmationRunnable != null) {
            return
        }

        val confirmation = Runnable {
            boundedUberConfirmationRunnable = null
            if (
                !currentSettings.isAppRunning ||
                    !currentSettings.isUberEnabled ||
                    !isUberVisibleForOcr()
            ) return@Runnable

            when (val request = requestScreenshot("confirmação temporal Uber")) {
                ScreenshotRequestResult.Started -> {
                    boundedUberConfirmationAttempts++
                    lastUberContentEventCaptureAt = SystemClock.elapsedRealtime()
                }
                is ScreenshotRequestResult.RetryAfter -> {
                    scheduleBoundedUberConfirmation(signature, request.delayMs.coerceAtLeast(OCR_BUSY_RETRY_DELAY_MS))
                }
                ScreenshotRequestResult.Rejected -> Unit
            }
        }
        boundedUberConfirmationRunnable = confirmation
        mainHandler.postDelayed(confirmation, delayMs)
    }

    private fun resetBoundedUberConfirmation() {
        boundedUberConfirmationRunnable?.let(mainHandler::removeCallbacks)
        boundedUberConfirmationRunnable = null
        boundedUberConfirmationSignature = null
        boundedUberConfirmationAttempts = 0
    }

    private fun recordOcrWithoutUberCard(requestId: Long, rawText: String) {
        val now = System.currentTimeMillis()
        // Nunca escrevemos no log texto cru de uma janela que não seja um cartão
        // Uber confirmado; o OCR pode ver conteúdo de outras aplicações.
        val signature = rawTextFingerprint(rawText)
        if (
            signature != lastOcrNoCardSignature ||
                now - lastOcrNoCardLoggedAt >= OCR_NO_CARD_DETAIL_COOLDOWN_MS
        ) {
            AppLogger.debug(
                "OCR Uber sem card completo: captura=$requestId, " +
                    "possívelOferta=${hasStrongUberCardSignal(rawText)}, $signature"
            )
            lastOcrNoCardSignature = signature
            lastOcrNoCardLoggedAt = now
        }
        ocrNoCardCount++
        logOcrNoCardSummaryIfDue(now, lastRequestId = requestId)
    }

    private fun logOcrNoCardSummaryIfDue(
        now: Long,
        force: Boolean = false,
        lastRequestId: Long? = null
    ) {
        if (ocrNoCardCount == 0) return
        if (ocrNoCardSummaryStartedAt == 0L) {
            ocrNoCardSummaryStartedAt = now
        }
        if (!force && now - ocrNoCardSummaryStartedAt < OCR_NO_CARD_LOG_SUMMARY_INTERVAL_MS) return

        val elapsedSeconds = (now - ocrNoCardSummaryStartedAt) / 1_000
        val idSuffix = lastRequestId?.let { ", última captura=$it" }.orEmpty()
        AppLogger.debug(
            "OCR Uber sem card: $ocrNoCardCount captura(s) em $elapsedSeconds s$idSuffix"
        )
        ocrNoCardCount = 0
        ocrNoCardSummaryStartedAt = now
    }

    private fun recordInvalidUberCard(cardText: String) {
        val now = System.currentTimeMillis()
        val signature = rawTextFingerprint(cardText)
        if (
            signature == invalidUberCardSignature &&
                now - lastInvalidUberCardAt < INVALID_UBER_CARD_COOLDOWN_MS
        ) {
            suppressedInvalidUberCardCount++
            return
        }

        if (suppressedInvalidUberCardCount > 0) {
            AppLogger.debug(
                "OCR Uber: $suppressedInvalidUberCardCount leitura(s) inválida(s) repetida(s) suprimida(s)"
            )
        }
        invalidUberCardSignature = signature
        lastInvalidUberCardAt = now
        suppressedInvalidUberCardCount = 0
        AppLogger.debug("Card Uber ainda incompleto; aguarda próximo evento de acessibilidade")
    }

    /**
     * Impede que a tela inicial da Uber (por exemplo, “Ofertas 6,40 €”) dispare
     * uma rajada de OCR. O cartão real contém a dedução/taxa e dados de percurso;
     * um único valor em euros não é uma oferta de viagem.
     */
    private fun hasStrongUberCardSignal(text: String): Boolean {
        val normalized = text.lowercase()
        val hasFare = normalized.contains('€')
        val hasServiceFee = normalized.contains("após dedução") ||
            normalized.contains("apos deducao") ||
            normalized.contains("taxa de serviço") ||
            normalized.contains("taxa de servico")
        val hasRoute = normalized.contains("viagem de") ||
            (normalized.contains("minuto") && normalized.contains("km"))
        return hasFare && hasServiceFee && hasRoute
    }

    private fun rawTextFingerprint(text: String): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return "texto=vazio"
        return "texto=${normalized.length} caracteres, assinatura=${normalized.hashCode().toUInt().toString(16)}"
    }

    private fun isUberScheduledRequest(text: String): Boolean {
        val normalized = text.lowercase()
        return (
            normalized.contains("solicitação de agendamento") ||
                normalized.contains("solicitacao de agendamento")
            ) && normalized.contains("ver viagem")
    }

    private fun logBoltSummaryIfDue(now: Long, force: Boolean = false) {
        if (boltNoOfferCount == 0) return
        if (boltSummaryStartedAt == 0L) {
            boltSummaryStartedAt = now
        }
        if (!force && now - boltSummaryStartedAt < BOLT_LOG_SUMMARY_INTERVAL_MS) return

        val elapsedSeconds = (now - boltSummaryStartedAt) / 1_000
        AppLogger.debug(
            "Bolt sem oferta: $boltNoOfferCount leitura(s) acionada(s) por notificação " +
                "em $elapsedSeconds s"
        )
        boltNoOfferCount = 0
        boltSummaryStartedAt = now
    }

    private fun isUberInForeground(): Boolean {
        // A recent Launcher/Bolt/other-app event is stronger evidence of the
        // visible foreground than the stale focused Uber Activity reported by
        // Android while its offer overlay is being animated.
        if (hasRecentExternalForeground()) return false
        // rootInActiveWindow pode ser a APPLICATION_OVERLAY da própria Uber
        // quando ela está por cima de Bolt, Waze ou do Launcher. Isso não torna
        // a Activity Uber a aplicação em primeiro plano. Em Android 16 a Activity
        // de base continua marcada como active mesmo com o Launcher focado; só
        // uma janela de aplicação realmente focada pode autorizar o polling.
        return packageHasForegroundApplicationWindow(UBER_PACKAGE_FRAGMENT)
    }

    private fun isExternalApplicationPackage(packageName: String): Boolean {
        val normalized = packageName.lowercase()
        if (normalized.contains(UBER_PACKAGE_FRAGMENT)) return false
        if (normalized.startsWith("com.daniel.tvdeinsight")) return false
        return normalized != "com.android.systemui" &&
            normalized != "android" &&
            normalized != "com.android.settings"
    }

    private fun hasRecentExternalForeground(): Boolean {
        if (lastExternalForegroundPackage.isBlank()) return false
        return SystemClock.elapsedRealtime() - lastExternalForegroundAt in 0..UBER_EXTERNAL_FOREGROUND_VALIDITY_MS
    }

    private fun shouldCaptureUberDisplay(): Boolean = !isUberInForeground()

    @Suppress("DEPRECATION")
    private fun packageHasForegroundApplicationWindow(packageFragment: String): Boolean = runCatching {
        val displays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowsOnAllDisplays else null
        val groups = if (displays != null) {
            (0 until displays.size()).map { displays.keyAt(it) to displays.valueAt(it) }
        } else {
            listOf(Display.DEFAULT_DISPLAY to windows)
        }
        for ((_, displayWindows) in groups) {
            for (window in displayWindows) {
                try {
                    if (
                        window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                            !window.isFocused
                    ) continue
                    val root = window.root ?: continue
                    try {
                        if (root.belongsToPackage(packageFragment)) return@runCatching true
                    } finally {
                        root.recycle()
                    }
                } finally {
                    window.recycle()
                }
            }
        }
        false
    }.getOrDefault(false)

    private fun isUberVisibleForOcr(): Boolean {
        if (!currentSettings.isAppRunning || !currentSettings.isUberEnabled) return false

        // O próprio TVDE não é alvo de OCR, mas a sua Activity pode estar em
        // primeiro plano enquanto uma oferta Uber é apresentada por cima dela.
        // Não usar o pacote focado como veto: a autorização continua dependente
        // de uma janela/evento recente da Uber e o pré-filtro visual rejeita o
        // ecrã normal do TVDE sem executar o parser completo.
        if (isUberInForeground()) return true
        return backgroundUberOfferPanelTarget() != null || isRecentUberWindowEvent()
    }

    private fun isRecentUberWindowEvent(): Boolean {
        val elapsed = SystemClock.elapsedRealtime() - lastUberWindowStateAt
        return elapsed in 0..UBER_BACKGROUND_EVENT_VALIDITY_MS
    }

    /**
     * O owner package das APPLICATION_OVERLAY não é disponibilizado pela API de
     * acessibilidade. A correlação temporal com o evento Uber é indispensável:
     * uma janela do sistema isolada nunca autoriza OCR de WhatsApp, Waze etc.
     */
    private fun backgroundUberOfferPanelTarget(): AppWindowTarget? {
        if (isUberInForeground()) return null
        val now = SystemClock.elapsedRealtime()
        if (!isRecentUberWindowEvent()) return null
        return runCatching {
            largeSystemOverlayTarget(
                minimumWidthPx = MIN_UBER_OFFER_WINDOW_WIDTH_PX,
                minimumHeightPx = MIN_UBER_OFFER_WINDOW_HEIGHT_PX
            )
        }.getOrNull()
    }

    /**
     * A Activity invisível da Uber não é uma origem de imagem válida quando a
     * Bolt/Waze/WhatsApp está ativa. O cartão sobreposto deve ser capturado pelo
     * display e o OCR será recortado aos limites desse cartão.
     */
    private fun currentUberCaptureTarget(): AppWindowTarget? = when {
        !shouldCaptureUberDisplay() -> runCatching { appWindowTarget(UBER_PACKAGE_FRAGMENT) }.getOrNull()
        else -> backgroundUberOfferPanelTarget()
    }

    /** A janela pequena de uma oferta nunca é, sozinha, prova de split-screen. */
    @Suppress("DEPRECATION")
    private fun isGenuineSplitScreenOrDexPane(target: AppWindowTarget): Boolean {
        if (target.captureWholeDisplay) return false
        val display = getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(target.displayId) ?: return false
        val size = android.graphics.Point().also(display::getRealSize)
        if (size.x <= 0 || size.y <= 0) return false

        val applicationWindows = mutableListOf<VisibleApplicationWindow>()
        val displays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowsOnAllDisplays else null
        val groups = if (displays != null) {
            (0 until displays.size()).map { displays.keyAt(it) to displays.valueAt(it) }
        } else {
            listOf(Display.DEFAULT_DISPLAY to windows)
        }
        groups.forEach { (displayId, displayWindows) ->
            displayWindows.forEach { window ->
                try {
                    if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                        return@forEach
                    }
                    val bounds = Rect().also(window::getBoundsInScreen)
                    if (!bounds.isEmpty) {
                        applicationWindows += VisibleApplicationWindow(
                            windowId = window.id,
                            displayId = displayId,
                            bounds = OverlayBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
                        )
                    }
                } finally {
                    window.recycle()
                }
            }
        }
        val desktopMode = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_TYPE_MASK ==
            android.content.res.Configuration.UI_MODE_TYPE_DESK
        return OverlayWindowModePolicy.shouldConstrainToPane(
            targetWindowId = target.windowId,
            targetDisplayId = target.displayId,
            targetCapturesWholeDisplay = target.captureWholeDisplay,
            display = OverlayBounds(0, 0, size.x, size.y),
            visibleApplicationWindows = applicationWindows,
            desktopMode = desktopMode
        )
    }

    /**
     * Resolve uma árvore de acordo com a janela que emitiu o evento. Isto evita
     * ler a janela ativa errada quando a Bolt e uma app de navegação/launcher
     * alternam o foco durante a animação de uma oferta.
     */
    private fun resolvePackageRoot(
        event: AccessibilityEvent,
        packageFragment: String
    ): ResolvedAccessibilityRoot? {
        event.source
            ?.takeIf { it.belongsToPackage(packageFragment) }
            ?.topMostParent()
            ?.let { return ResolvedAccessibilityRoot(it, recycleWhenDone = false) }

        findPackageRootInWindow(event.windowId, packageFragment)
            ?.let { return ResolvedAccessibilityRoot(it, recycleWhenDone = true) }

        rootInActiveWindow?.let { activeRoot ->
            if (activeRoot.belongsToPackage(packageFragment)) {
                return ResolvedAccessibilityRoot(activeRoot, recycleWhenDone = true)
            }
            activeRoot.recycle()
        }

        findVisiblePackageRoot(packageFragment)
            ?.let { return ResolvedAccessibilityRoot(it, recycleWhenDone = true) }
        return null
    }

    /** Obtém a raiz da aplicação pedida mesmo quando outra janela está ativa. */
    @Suppress("DEPRECATION")
    private fun findVisiblePackageRoot(packageFragment: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { activeRoot ->
            if (activeRoot.belongsToPackage(packageFragment)) return activeRoot
            activeRoot.recycle()
        }
        return findPackageRootInWindows(packageFragment)
    }

    @Suppress("DEPRECATION")
    private fun findPackageRootInWindow(
        windowId: Int,
        packageFragment: String
    ): AccessibilityNodeInfo? =
        if (windowId == INVALID_WINDOW_ID) null else findPackageRootInWindows(packageFragment, windowId)

    @Suppress("DEPRECATION")
    private fun findPackageRootInWindows(
        packageFragment: String,
        requiredWindowId: Int? = null
    ): AccessibilityNodeInfo? = runCatching {
        val displays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowsOnAllDisplays else null
        val groups = if (displays != null) {
            (0 until displays.size()).map { displays.valueAt(it) }
        } else {
            listOf(windows)
        }
        groups.forEach { displayWindows ->
            displayWindows.forEach { window ->
                try {
                    if (requiredWindowId != null && window.id != requiredWindowId) return@forEach
                    val root = window.root ?: return@forEach
                    if (root.belongsToPackage(packageFragment)) return@runCatching root
                    root.recycle()
                } finally {
                    window.recycle()
                }
            }
        }
        null
    }.getOrNull()

    private fun isScreenshotActive(requestId: Long): Boolean =
        activeScreenshotId == requestId && screenshotGate.isActive()

    private fun isCaptureAuthorized(requestId: Long): Boolean =
        isScreenshotActive(requestId) &&
            activeCaptureAuthorization &&
            currentSettings.isAppRunning &&
            currentSettings.isUberEnabled

    private fun releaseScreenshot(requestId: Long, reason: String, error: Throwable? = null) {
        if (activeScreenshotId != requestId) return

        screenshotTimeoutRunnable?.let(mainHandler::removeCallbacks)
        screenshotTimeoutRunnable = null
        val elapsedMs = (SystemClock.elapsedRealtime() - activeScreenshotStartedAt).coerceAtLeast(0L)
        activeScreenshotId = NO_ACTIVE_SCREENSHOT
        activeScreenshotStartedAt = 0L
        activeCaptureAuthorization = false
        screenshotGate.release()
        if (!nativeFrameInFlight.get()) completeStressFrame(requestId, if (reason == "OCR concluído") "OK" else "ERROR")
        if (error == null) AppLogger.debug("Captura liberada: id=$requestId, $reason, duração=${elapsedMs}ms")
        else AppLogger.warn("Captura liberada: id=$requestId, $reason", error)
    }

    private fun createTextRecognizer(): TextRecognizer? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } else {
            null
        }

    /**
     * Um timeout não pode simplesmente abrir a fila para outra imagem enquanto o
     * ML Kit ainda mantém o bitmap anterior. O reconhecedor é fechado, há uma
     * pequena pausa e só então uma instância limpa volta a aceitar capturas.
     */
    private fun recoverOcrAfterTimeout(requestId: Long) {
        if (!isScreenshotActive(requestId)) return
        completeStressFrame(requestId, "TIMEOUT")

        val now = System.currentTimeMillis()
        if (now - ocrTimeoutWindowStartedAt > OCR_TIMEOUT_BURST_WINDOW_MS) {
            ocrTimeoutWindowStartedAt = now
            ocrTimeoutsInWindow = 0
        }
        ocrTimeoutsInWindow += 1
        val restartDelay = if (ocrTimeoutsInWindow >= MAX_OCR_TIMEOUTS_PER_WINDOW) {
            OCR_TIMEOUT_CIRCUIT_BREAKER_MS
        } else {
            OCR_RECOGNIZER_RESTART_DELAY_MS
        }

        AppLogger.warn(
            "OCR excedeu o limite: id=$requestId; reconhecedor será reiniciado em ${restartDelay}ms"
        )
        textRecognizer?.let { recognizer ->
            runCatching(recognizer::close)
                .onFailure { AppLogger.warn("Falha ao fechar reconhecedor OCR em timeout", it) }
        }
        textRecognizer = null
        activeScreenshotId = NO_ACTIVE_SCREENSHOT
        activeScreenshotStartedAt = 0L
        activeCaptureAuthorization = false
        screenshotGate.release()
        screenshotTimeoutRunnable = null
        scheduleTextRecognizerRestart(restartDelay, "timeout da captura $requestId")
    }

    private fun scheduleTextRecognizerRestart(delayMs: Long, reason: String) {
        if (!usesBitmapOcrForUber) return
        ocrRestartRunnable?.let(mainHandler::removeCallbacks)
        val restart = Runnable {
            ocrRestartRunnable = null
            if (textRecognizer == null) {
                textRecognizer = createTextRecognizer()
                warmUpTextRecognizer()
                AppLogger.info("OCR Uber reiniciado: $reason")
            }
        }
        ocrRestartRunnable = restart
        mainHandler.postDelayed(restart, delayMs)
    }

    private fun recordOcrCompletion(durationMs: Long) {
        completedOcrCaptures += 1
        totalOcrDurationMs += durationMs.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        if (now - lastOcrMemoryReportAt < OCR_MEMORY_REPORT_INTERVAL_MS) return

        val runtime = Runtime.getRuntime()
        val javaUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MIB
        val javaMaxMb = runtime.maxMemory() / BYTES_PER_MIB
        val nativeUsedMb = Debug.getNativeHeapAllocatedSize() / BYTES_PER_MIB
        val averageDuration = if (completedOcrCaptures > 0) {
            totalOcrDurationMs / completedOcrCaptures
        } else {
            0L
        }
        AppLogger.info(
            "Saúde OCR: capturas=$completedOcrCaptures, média=${averageDuration}ms, " +
                "heapJava=${javaUsedMb}/${javaMaxMb}MiB, heapNativo=${nativeUsedMb}MiB, " +
                "timeoutsJanela=$ocrTimeoutsInWindow"
        )
        completedOcrCaptures = 0L
        totalOcrDurationMs = 0L
        lastOcrMemoryReportAt = now
    }

    private fun handleOffer(
        offer: TripOffer,
        alreadyConfirmedByOcr: Boolean = false,
        alreadyConfirmedByAccessibility: Boolean = false,
        /** A captura que acabou de confirmar a oferta Uber por OCR. */
        ocrScreenshot: Bitmap? = null,
        overlayAnchor: Rect? = null,
        overlayAvoidTop: Int? = null,
        overlayDisplayId: Int = Display.DEFAULT_DISPLAY,
        overlayConstrainToAnchor: Boolean = true
    ): Boolean {
        if (!isAnalysisAuthorized) {
            AppLogger.debug("Oferta ignorada: licença inválida")
            return false
        }
        AppLogger.debug(
            "Oferta recebida: ${offer.platform} preço=${offer.price}, " +
                "km=${offer.distanceKm}, min=${offer.durationMinutes}"
        )
        if (offer.price <= 0.5 || offer.distanceKm < 0.1 || offer.durationMinutes < 1) {
            AppLogger.debug("Oferta ignorada por dados inválidos")
            return false
        }

        if (
            alreadyConfirmedByOcr ||
                alreadyConfirmedByAccessibility ||
                usesImmediateAccessibilityPublication(Build.VERSION.SDK_INT)
        ) {
            // Tanto a confirmação OCR como um cartão completo da árvore de
            // acessibilidade são fontes validadas. Ainda passam sempre pela
            // mesma deduplicação, impedindo dois históricos para uma oferta.
            if (!offerStabilityTracker.shouldPublishImmediately(offer, System.currentTimeMillis())) {
                AppLogger.debug("Oferta confirmada duplicada ignorada: ${offer.platform}")
                return false
            }
        } else {
            val now = System.currentTimeMillis()
            if (!offerStabilityTracker.shouldPublish(offer, now)) {
                val awaitingConfirmation = offerStabilityTracker.isAwaitingConfirmation(offer)
                AppLogger.debug("Oferta aguardando confirmação estável: ${offer.platform}")
                return awaitingConfirmation
            }
        }

        val decision = evaluateOfferUseCase(offer, currentSettings)
        val grossValuePerKm = if (offer.distanceKm > 0.0) offer.price / offer.distanceKm else 0.0
        if (grossValuePerKm <= 0.05 || decision.valorPorHora <= 0.5) {
            AppLogger.warn("Decisão ignorada por métricas inválidas")
            return false
        }

        val liveTarget = if (offer.platform == com.daniel.tvdeinsight.domain.model.OfferPlatform.UBER) {
            currentUberCaptureTarget()
        } else {
            runCatching { appWindowTarget(BOLT_PACKAGE_FRAGMENT) }.getOrNull()
        }
        val historyEntryId = analysisStore.publish(offer, decision)
        captureOfferScreenshotIfEnabled(historyEntryId, ocrScreenshot, liveTarget?.displayId ?: overlayDisplayId)
        // Mesmo quando a oferta analisada é Uber, uma oferta Bolt pode estar
        // visível por baixo/sobreposta no display. Protege o botão nativo da
        // Bolt também nesse caso; se não houver botão, o valor fica nulo e o
        // posicionamento normal é preservado.
        val boltDismissBottom = overlayAvoidTop ?: runCatching {
            val boltRoot = findVisiblePackageRoot(BOLT_PACKAGE_FRAGMENT)
            try {
                boltRoot?.let(::boltNativeDismissControlBottom)
            } finally {
                boltRoot?.recycle()
            }
        }.getOrNull()
        overlayManager.showDecision(
            decision = decision,
            anchor = liveTarget?.bounds ?: overlayAnchor,
            displayId = liveTarget?.displayId ?: overlayDisplayId,
            avoidTopPx = boltDismissBottom,
            // In normal phone mode a suspended Uber offer is a TYPE_SYSTEM
            // surface; its rectangle must not be mistaken for a split pane.
            // Real split-screen/DeX targets remain constrained to their pane.
            constrainToAnchor = liveTarget?.let(::isGenuineSplitScreenOrDexPane)
                ?: overlayConstrainToAnchor
        )
        hasActiveDecision = true
        val criteriaForLog = decision.criterionDecisions.entries.joinToString { (criterion, result) ->
            "${criterion.name}=$result"
        }
        AppLogger.info(
            "Decisão publicada: plataforma=${offer.platform}, tipo=${decision.type}, " +
                "€/km=${decision.valorPorKm}, €/h=${decision.valorPorHora}, destinoKm=${offer.tripDistanceKm}, " +
                "limiteViagemLonga=${currentSettings.longTripMinimumKm}, critérios=$criteriaForLog"
        )
        return false
    }

    /** Guarda a imagem OCR da Uber ou cria uma captura para a oferta Bolt/legada. */
    private fun captureOfferScreenshotIfEnabled(historyEntryId: Long, ocrScreenshot: Bitmap? = null, displayId: Int = Display.DEFAULT_DISPLAY) {
        if (!currentSettings.isOfferScreenshotCaptureEnabled) return
        val foreground = currentForegroundPackage()
        if (foreground.startsWith("com.daniel.tvdeinsight") || foreground.startsWith("com.whatsapp")) return

        // Na Uber recente, esta imagem acabou de ser recebida pelo OCR. Pedir
        // uma segunda captura enquanto a primeira ainda está ativa faz o Android
        // devolver ERROR_TAKE_SCREENSHOT (código 3), como visto no registo.
        ocrScreenshot?.let { screenshot ->
            val privateCopy = runCatching { screenshot.copy(Bitmap.Config.ARGB_8888, false) }
                .onFailure { AppLogger.warn("Não foi possível copiar a captura OCR da oferta", it) }
                .getOrNull()
            if (privateCopy != null) {
                saveOfferScreenshot(historyEntryId, privateCopy)
                return
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            AppLogger.warn("Captura da oferta ignorada: requer Android 11 ou superior")
            return
        }
        requestOfferScreenshot(historyEntryId, displayId)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestOfferScreenshot(historyEntryId: Long, displayId: Int) {
        try {
            takeScreenshot(
                displayId,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        persistOfferScreenshot(historyEntryId, screenshotResult)
                    }

                    override fun onFailure(errorCode: Int) {
                        AppLogger.warn("Não foi possível capturar a oferta: código=$errorCode")
                    }
                }
            )
        } catch (error: Throwable) {
            AppLogger.warn("Não foi possível solicitar a captura da oferta", error)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun persistOfferScreenshot(historyEntryId: Long, screenshotResult: ScreenshotResult) {
        val hardwareBuffer = screenshotResult.hardwareBuffer
        var bitmap: Bitmap? = null
        try {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.colorSpace)
            try {
                bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                hardwareBitmap?.recycle()
            }
        } catch (error: Throwable) {
            AppLogger.warn("Não foi possível preparar a captura da oferta", error)
        } finally {
            hardwareBuffer.close()
        }

        val capture = bitmap ?: return
        saveOfferScreenshot(historyEntryId, capture)
    }

    private fun saveOfferScreenshot(historyEntryId: Long, capture: Bitmap) {
        // Finish the durable write even when the accessibility service is disconnected.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fileName = offerScreenshotStore.save(historyEntryId, capture)
                if (fileName == null) {
                    AppLogger.warn("Não foi possível guardar a captura da oferta")
                } else {
                    analysisStore.attachScreenshot(historyEntryId, fileName)
                    AppLogger.debug("Captura associada à oferta: id=$historyEntryId")
                }
            } finally {
                capture.recycle()
            }
        }
    }

    private fun clearOverlay(reason: String) {
        val now = System.currentTimeMillis()
        logBoltSummaryIfDue(now, force = true)
        logOcrNoCardSummaryIfDue(now, force = true)
        val hadActiveState = hasActiveDecision
        offerStabilityTracker.clear()
        cancelPendingUberOcr()
        uberVisualCardActive = false
        lastUberVisualFingerprint = null
        hasActiveDecision = false
        overlayManager.removeOverlay()
        if (hadActiveState) AppLogger.debug("Overlay limpo: $reason")
    }

    /** Cancela apenas trabalho OCR futuro, preservando a deduplicação publicada. */
    private fun cancelPendingUberOcr() {
        uberWindowOcrRunnable?.let(mainHandler::removeCallbacks)
        uberWindowOcrRunnable = null
        uberBackgroundPanelProbeRunnable?.let(mainHandler::removeCallbacks)
        uberBackgroundPanelProbeRunnable = null
        pendingUberCaptureRunnable?.let(mainHandler::removeCallbacks)
        pendingUberCaptureRunnable = null
        pendingUberCapture = null
        resetIncompleteOcrRetry()
        resetBoundedUberConfirmation()
        resetIncompleteAccessibilityRetry()
    }

    private val usesBitmapOcrForUber: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private val isAnalysisAuthorized: Boolean
        get() = BuildConfig.IS_ADMIN_APP || licenseManager.state.value.isValid

    private fun AccessibilityNodeInfo.belongsToPackage(packageFragment: String): Boolean =
        packageName?.toString()?.contains(packageFragment, ignoreCase = true) == true

    /**
     * Finds the native Bolt dismiss action so our decision card never covers
     * the driver's Recusar/Rejeitar button. The result is only a placement
     * hint; no node is clicked and all temporary node wrappers are recycled.
     */
    private fun boltNativeDismissControlBottom(rootNode: AccessibilityNodeInfo): Int? {
        val labels = setOf("recusar", "rejeitar")
        // Android's text lookup can be case-sensitive on OEM accessibility
        // implementations; query both the native title case and uppercase.
        val matches = listOf("Recusar", "Rejeitar", "RECUSAR", "REJEITAR")
            .flatMap { label ->
            runCatching { rootNode.findAccessibilityNodeInfosByText(label) }.getOrDefault(emptyList())
            }
            .distinct()
        return try {
            matches.asSequence()
                .mapNotNull { node ->
                    val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                        .joinToString(" ")
                        .trim()
                        .lowercase(java.util.Locale.ROOT)
                    // "Rejeitar não afeta a taxa de aceitação" belongs to the
                    // offer body, not to the dismiss action.  It previously
                    // won maxOrNull() and moved the entire decision stack into
                    // the middle of the native Bolt card.  Only an exact
                    // action label can reserve vertical space.
                    if (text !in labels) return@mapNotNull null
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    bounds.takeIf { !it.isEmpty && it.width() >= 40 && it.height() >= 20 }
                        ?.bottom
                }
                .maxOrNull()
        } finally {
            matches.forEach { it.recycle() }
        }
    }

    /**
     * Returns both the real window rectangle and its kind. TYPE_APPLICATION is
     * an actual app pane and may constrain split-screen/DeX. TYPE_SYSTEM is a
     * suspended offer surface and must never be used as the TVDE layout pane.
     */
    @Suppress("DEPRECATION")
    private fun accessibilityWindowTarget(node: AccessibilityNodeInfo): AppWindowTarget? =
        runCatching {
            val window = node.window ?: return@runCatching null
            try {
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                if (bounds.isEmpty) return@runCatching null
                AppWindowTarget(
                    bounds = bounds,
                    displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        window.displayId
                    } else {
                        Display.DEFAULT_DISPLAY
                    },
                    windowId = window.id,
                    captureWholeDisplay = window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                )
            } finally {
                window.recycle()
            }
        }.getOrNull()

    private fun AccessibilityNodeInfo.topMostParent(): AccessibilityNodeInfo {
        var currentNode = this
        repeat(MAX_ACCESSIBILITY_PARENT_DEPTH) {
            val parentNode = currentNode.parent ?: return currentNode
            // parent() devolve uma nova instância. Reciclar as cópias
            // intermediárias evita acumulação nativa durante eventos repetidos.
            if (currentNode !== this) currentNode.recycle()
            currentNode = parentNode
        }
        return currentNode
    }

    /**
     * Inspeção curta, sem criar a lista de blocos posicionados usada pelo
     * parser final. Uma oferta Uber verdadeira tem valor, a marca "Viagem" e
     * os dois trechos tempo/distância. A combinação evita confundir o mapa,
     * o radar ou notificações com uma oferta.
     */
    private fun AccessibilityNodeInfo.inspectUberOfferStructure(): UberOfferPreflight {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(this)
        val text = StringBuilder(MAX_UBER_PREFLIGHT_TEXT_CHARS)
        val positionedBlocks = mutableListOf<UberOfferCardTextExtractor.OcrBlock>()
        val unpositionedBlocks = mutableListOf<UberOfferCardTextExtractor.OcrBlock>()
        val bounds = Rect()
        var textSequence = 0
        var inspectedNodes = 0
        while (
            nodes.isNotEmpty() &&
                inspectedNodes < MAX_UBER_PREFLIGHT_NODES &&
                text.length < MAX_UBER_PREFLIGHT_TEXT_CHARS
        ) {
            val node = nodes.removeFirst()
            try {
                val values = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .toList()
                if (values.isNotEmpty()) node.getBoundsInScreen(bounds)
                values.forEach { value ->
                        if (text.length < MAX_UBER_PREFLIGHT_TEXT_CHARS) {
                            text.append(value.take(MAX_UBER_PREFLIGHT_VALUE_CHARS)).append('\n')
                        }
                        if (!bounds.isEmpty) {
                            positionedBlocks += UberOfferCardTextExtractor.OcrBlock(
                                value, bounds.top, bounds.bottom, bounds.left, bounds.right
                            )
                        } else {
                            val top = textSequence * FALLBACK_TEXT_LINE_HEIGHT_PX
                            unpositionedBlocks += UberOfferCardTextExtractor.OcrBlock(
                                value, top, top + FALLBACK_TEXT_LINE_HEIGHT_PX
                            )
                        }
                        textSequence++
                }
                repeat(node.childCount) { index ->
                    node.getChild(index)?.let(nodes::addLast)
                }
            } finally {
                // getChild() cria instâncias de AccessibilityNodeInfo. O root
                // pertence ao chamador e continua a ser usado pelo extrator;
                // os descendentes desta inspeção curta já não são necessários.
                if (node !== this) node.recycle()
            }
            inspectedNodes++
        }
        while (nodes.isNotEmpty()) nodes.removeFirst().recycle()

        val normalized = text.toString()
            .lowercase()
            .replace('\u00a0', ' ')
            .replace(Regex("\\s+"), " ")
        val routeSegments = UBER_ROUTE_SEGMENT_PREFLIGHT_REGEX.findAll(normalized).count()
        val hasFare = UBER_FARE_PREFLIGHT_REGEX.containsMatchIn(normalized)
        val hasTripMarker = UBER_TRIP_PREFLIGHT_REGEX.containsMatchIn(normalized)
        val hasServiceFee = UBER_SERVICE_FEE_PREFLIGHT_REGEX.containsMatchIn(normalized)
        return UberOfferPreflight(
            hasFare = hasFare,
            hasTripMarker = hasTripMarker,
            routeSegments = routeSegments,
            isCompleteOffer = hasFare && hasTripMarker && routeSegments >= 2,
            isPartialOffer = hasFare && (hasTripMarker || hasServiceFee || routeSegments > 0),
            fingerprint = rawTextFingerprint(normalized),
            blocks = positionedBlocks.ifEmpty { unpositionedBlocks }
        )
    }

    private fun TripOffer.accessibilityOfferSignature(): String = listOf(
        platform.name,
        price.toString(),
        pickupDistanceKm?.toString().orEmpty(),
        pickupDurationMinutes?.toString().orEmpty(),
        tripDistanceKm?.toString().orEmpty(),
        tripDurationMinutes?.toString().orEmpty(),
        category.orEmpty()
    ).joinToString("|")

    private data class UberOfferPreflight(
        val hasFare: Boolean,
        val hasTripMarker: Boolean,
        val routeSegments: Int,
        val isCompleteOffer: Boolean,
        val isPartialOffer: Boolean,
        val fingerprint: String,
        val blocks: List<UberOfferCardTextExtractor.OcrBlock>
    )

    private data class ResolvedAccessibilityRoot(
        val node: AccessibilityNodeInfo,
        val recycleWhenDone: Boolean
    )

    override fun onInterrupt() {
        completeStressFrame(stressRequestId, "INTERRUPTED")
        releaseScreenshot(activeScreenshotId, "serviço interrompido")
        resetLegacyUberAccessibilityState("serviço interrompido")
        AppLogger.warn("Serviço de Acessibilidade interrompido")
        clearOverlay("serviço interrompido")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val isMemoryPressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        // TRIM_MEMORY_UI_HIDDEN não significa falta de memória. É o estado
        // normal enquanto o motorista está na Uber e não deve pausar o OCR.
        if (!usesBitmapOcrForUber || !isMemoryPressure) return
        mainHandler.post {
            AppLogger.warn("Pressão de memória Android no OCR: nível=$level")
            textRecognizer?.let { runCatching(it::close) }
            textRecognizer = null
            scheduleTextRecognizerRestart(OCR_MEMORY_PRESSURE_PAUSE_MS, "pressão de memória nível $level")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!usesBitmapOcrForUber) return
        mainHandler.post {
            AppLogger.warn("Memória crítica comunicada pelo Android; OCR será reiniciado")
            textRecognizer?.let { runCatching(it::close) }
            textRecognizer = null
            scheduleTextRecognizerRestart(OCR_MEMORY_PRESSURE_PAUSE_MS, "memória crítica")
        }
    }

    override fun onDestroy() {
        // Invalidate results before disposing UI: a late ML Kit completion must not resurrect a card.
        releaseScreenshot(activeScreenshotId, "serviço destruído")
        if (stressReceiverRegistered) {
            unregisterReceiver(stressReceiver)
            stressReceiverRegistered = false
        }
        if (boltNotificationReceiverRegistered) {
            unregisterReceiver(boltNotificationReceiver)
            boltNotificationReceiverRegistered = false
        }
        completeStressFrame(stressRequestId, "DESTROYED")
        resetLegacyUberAccessibilityState("serviço destruído")
        settingsCollectorJob?.cancel()
        screenshotCleanupJob?.cancel()
        adaptiveOcrJob?.cancel()
        visionExecutor.shutdown()
        AppLogger.info("Serviço de Acessibilidade destruído")
        boltReservationCoordinator.disconnect()
        clearOverlay("serviço destruído")
        overlayManager.detachAccessibilityOverlayContext(this)
        screenshotTimeoutRunnable?.let(mainHandler::removeCallbacks)
        ocrRestartRunnable?.let(mainHandler::removeCallbacks)
        resetBoltRootRetry()
        resetBoltNotificationProbe()
        textRecognizer?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun AccessibilityNodeInfo.extractUberOfferCardText(): String? {
        val blocks = mutableListOf<UberOfferCardTextExtractor.OcrBlock>()
        val unpositionedBlocks = mutableListOf<UberOfferCardTextExtractor.OcrBlock>()
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(this)
        val bounds = android.graphics.Rect()
        var textSequence = 0
        while (nodes.isNotEmpty() && textSequence < MAX_TEXT_NODES) {
            val node = nodes.removeFirst()
            try {
                val values = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                if (values.isNotEmpty()) {
                    node.getBoundsInScreen(bounds)
                    values.forEach { text ->
                        if (!bounds.isEmpty) {
                            blocks += UberOfferCardTextExtractor.OcrBlock(
                                text,
                                bounds.top,
                                bounds.bottom,
                                bounds.left,
                                bounds.right
                            )
                        } else {
                            val top = textSequence * FALLBACK_TEXT_LINE_HEIGHT_PX
                            unpositionedBlocks += UberOfferCardTextExtractor.OcrBlock(
                                text = text,
                                top = top,
                                bottom = top + FALLBACK_TEXT_LINE_HEIGHT_PX
                            )
                        }
                        textSequence++
                    }
                }
                repeat(node.childCount) { index -> node.getChild(index)?.let(nodes::addLast) }
            } finally {
                if (node !== this) node.recycle()
            }
        }
        while (nodes.isNotEmpty()) nodes.removeFirst().recycle()
        return uberCardTextExtractor.extract(blocks)
            ?: uberCardTextExtractor.extract(unpositionedBlocks)
    }

    private companion object {
        const val STRESS_ACTION = "com.mqzsolutions.app.STRESS_TEST_OCR"
        const val UBER_PACKAGE_FRAGMENT = "uber"
        const val BOLT_PACKAGE_FRAGMENT = "mtakso"
        val UBER_FARE_PREFLIGHT_REGEX = Regex(
            "(?:€|â‚¬|eur)\\s*\\d{1,5}[,.]\\d{2}|\\d{1,5}[,.]\\d{2}\\s*(?:€|â‚¬|eur)",
            RegexOption.IGNORE_CASE
        )
        val UBER_TRIP_PREFLIGHT_REGEX = Regex("\\bviag\\w*", RegexOption.IGNORE_CASE)
        val UBER_SERVICE_FEE_PREFLIGHT_REGEX = Regex(
            "(?:dedu[cç][aã]o|taxa\\s+de\\s+servi[cç]o)",
            RegexOption.IGNORE_CASE
        )
        val UBER_ROUTE_SEGMENT_PREFLIGHT_REGEX = Regex(
            "\\d+\\s*(?:h(?:oras?)?|min(?:uto)?s?)\\s*[^\\d]{0,32}\\d+[,.]?\\d*\\s*(?:km|quil(?:[oô]metros?)?)",
            RegexOption.IGNORE_CASE
        )
        const val MAX_TEXT_NODES = 600
        const val MAX_OCR_READS_PER_OFFER = 2
        const val MAX_UBER_PREFLIGHT_NODES = 140
        const val MAX_UBER_PREFLIGHT_TEXT_CHARS = 4_096
        const val MAX_UBER_PREFLIGHT_VALUE_CHARS = 240
        const val MAX_ACCESSIBILITY_PARENT_DEPTH = 32
        const val FALLBACK_TEXT_LINE_HEIGHT_PX = 48
        const val REQUIRED_CONSECUTIVE_READINGS = 2
        const val SCREENSHOT_INTERVAL_MS = 750L
        const val UBER_FOREGROUND_SCREENSHOT_POLL_INTERVAL_MS = 2_000L
        const val UBER_BACKGROUND_SCREENSHOT_POLL_INTERVAL_MS = 2_000L
        const val FOREGROUND_EVENT_FRESHNESS_MS = 1_500L
        const val UBER_OFFER_EVENT_OCR_DELAY_MS = 220L
        const val UBER_CONTENT_EVENT_OCR_DELAY_MS = 350L
        const val UBER_CONTENT_EVENT_OCR_MIN_INTERVAL_MS = 2_000L
        const val UBER_EVENT_OCR_MIN_INTERVAL_MS = 1_000L
        const val UBER_BACKGROUND_PANEL_INITIAL_DELAY_MS = 120L
        const val UBER_BACKGROUND_PANEL_PROBE_DELAY_MS = 120L
        const val MAX_UBER_BACKGROUND_PANEL_PROBES = 8
        const val UBER_BACKGROUND_EVENT_VALIDITY_MS = 3_000L
        const val UBER_EVENT_CAPTURE_ADMISSION_MS = 5_000L
        const val UBER_EXTERNAL_FOREGROUND_VALIDITY_MS = 3_000L
        const val UBER_CONFIRMATION_CAPTURE_DELAY_MS = 320L
        const val MAX_UBER_CONFIRMATION_CAPTURES = 1
        const val UBER_DIAGNOSTIC_EVENT_MIN_INTERVAL_MS = 250L
        const val UBER_ACCESSIBILITY_FAST_PATH_DEBOUNCE_MS = 250L
        const val MAX_UBER_ACCESSIBILITY_FAST_PATH_MISSES = 6
        const val INCOMPLETE_OCR_RETRY_DELAY_MS = 300L
        const val INCOMPLETE_OCR_RETRY_WINDOW_MS = 4_000L
        // Apenas para um erro de captura devolvido pelo Android; um cartão
        // parcial nunca aciona uma sequência extra de OCR.
        const val MAX_INCOMPLETE_OCR_RETRIES = 1
        const val OCR_BUSY_RETRY_DELAY_MS = 80L
        const val OCR_RECOGNIZER_BUSY_RETRY_DELAY_MS = 500L
        const val OCR_WARMUP_SIZE_PX = 32
        const val INCOMPLETE_ACCESSIBILITY_RETRY_DELAY_MS = 350L
        const val INCOMPLETE_ACCESSIBILITY_RETRY_COOLDOWN_MS = 2_500L
        const val LEGACY_UBER_PREFLIGHT_DEBOUNCE_MS = 300L
        const val LEGACY_UBER_EVENT_LOG_INTERVAL_MS = 30_000L
        const val LEGACY_UBER_WEAK_EVENT_LOG_INTERVAL_MS = 30_000L
        const val LEGACY_UBER_OFFER_DUPLICATE_WINDOW_MS = 15_000L
        const val SCREENSHOT_TIMEOUT_MS = 6_000L
        const val OCR_RECOGNIZER_RESTART_DELAY_MS = 1_000L
        const val OCR_TIMEOUT_BURST_WINDOW_MS = 60_000L
        const val MAX_OCR_TIMEOUTS_PER_WINDOW = 2
        const val OCR_TIMEOUT_CIRCUIT_BREAKER_MS = 30_000L
        const val OCR_MEMORY_PRESSURE_PAUSE_MS = 10_000L
        const val OCR_MEMORY_REPORT_INTERVAL_MS = 5 * 60_000L
        const val SCREENSHOT_CLEANUP_INTERVAL_MS = 60 * 60_000L
        const val BYTES_PER_MIB = 1_048_576L
        // Uma leitura normal aos 750 ms e três recuperações condicionais aos
        // 1000, 1250 e 1500 ms após a notificação.
        const val BOLT_ROOT_RETRY_DELAY_MS = 250L
        const val MAX_BOLT_ROOT_RETRIES = 3
        const val BOLT_NOTIFICATION_PROBE_DELAY_MS = 750L
        const val BOLT_NOTIFICATION_DEDUPLICATE_WINDOW_MS = 10_000L
        const val MAX_BOLT_NOTIFICATION_SIGNAL_LENGTH = 256
        const val BOLT_LOG_SUMMARY_INTERVAL_MS = 5_000L
        const val OCR_NO_CARD_LOG_SUMMARY_INTERVAL_MS = 5_000L
        const val OCR_NO_CARD_DETAIL_COOLDOWN_MS = 5_000L
        const val OCR_PREFILTER_LOG_INTERVAL_MS = 30_000L
        const val INVALID_UBER_CARD_COOLDOWN_MS = 5_000L
        const val DECISION_DUPLICATE_WINDOW_MS = 20_000L
        const val MIN_VALID_DISPLAY_DIMENSION_PX = 240
        const val MIN_UBER_CARD_ROI_DIMENSION_PX = 160
        const val UBER_CARD_ROI_HORIZONTAL_MARGIN_RATIO = 0.04f
        const val UBER_CARD_ROI_TOP_MARGIN_RATIO = 0.06f
        const val UBER_CARD_ROI_BOTTOM_MARGIN_RATIO = 0.08f
        const val MIN_UBER_OFFER_WINDOW_WIDTH_PX = 480
        const val MIN_UBER_OFFER_WINDOW_HEIGHT_PX = 480
        const val LOW_BATTERY_PERCENT = 15
        const val BATTERY_STATE_CACHE_MS = 30_000L
        const val NO_ACTIVE_SCREENSHOT = 0L
        const val INVALID_WINDOW_ID = -1
    }
}
