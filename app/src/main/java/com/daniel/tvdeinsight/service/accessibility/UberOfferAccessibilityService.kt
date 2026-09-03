package com.daniel.tvdeinsight.service.accessibility

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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import com.daniel.tvdeinsight.service.ocr.OcrOfferConfirmationTracker
import com.daniel.tvdeinsight.service.ocr.UberOfferCardTextExtractor
import com.daniel.tvdeinsight.service.ocr.OcrBitmapPreprocessor
import com.daniel.tvdeinsight.service.overlay.DecisionOverlayManager
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private val offerStabilityTracker = OfferStabilityTracker(
        requiredConsecutiveReadings = REQUIRED_CONSECUTIVE_READINGS,
        duplicateWindowMs = DECISION_DUPLICATE_WINDOW_MS
    )
    private val ocrOfferConfirmationTracker = OcrOfferConfirmationTracker(
        confirmationWindowMs = OCR_CONFIRMATION_WINDOW_MS,
        duplicateWindowMs = DECISION_DUPLICATE_WINDOW_MS
    )

    @Volatile private var currentSettings = RuleSettings()
    @Volatile private var settingsLoaded = false
    @Volatile private var foregroundPackage = ""
    @Volatile private var foregroundPackageUpdatedAt = 0L
    @Volatile private var activeScreenshotId = NO_ACTIVE_SCREENSHOT
    private var activeScreenshotStartedAt = 0L
    private var screenshotTimeoutRunnable: Runnable? = null
    private var ocrRestartRunnable: Runnable? = null
    private var ocrConfirmationRunnable: Runnable? = null
    private var uberWindowOcrRunnable: Runnable? = null
    private var incompleteOcrRetryRunnable: Runnable? = null
    private var incompleteAccessibilityRetryRunnable: Runnable? = null
    private var scheduledOcrConfirmationSignature: String? = null
    private var pendingOcrCategory: String? = null
    private var hasActiveDecision = false
    private var lastBoltParseAt = 0L
    private var skippedBoltEvents = 0
    private var boltNoOfferCount = 0
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLogger.info("Serviço de Acessibilidade conectado")
        boltReservationCoordinator.connect()
        serviceScope.launch {
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

        serviceScope.launch {
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
            serviceScope.launch {
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
        if (isWindowStateChange) {
            AppLogger.debug(
                "Mudança de janela: pacote=$packageName, uber=$isUber, bolt=$isBolt"
            )
            if (usesBitmapOcrForUber && !isUberVisibleForOcr()) {
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
        } else if (isBolt && currentSettings.isBoltEnabled) {
            processBoltEvent(event)
        }

        if (isUber && currentSettings.isUberEnabled && !usesBitmapOcrForUber) {
            processUberAccessibilityEvent(event, sourceNode)
        }

        // No Android 13+, os eventos apenas antecipam a captura; nenhum texto da
        // árvore da Uber é utilizado. O gatilho por conteúdo reduz a espera até ao
        // próximo ciclo periódico quando o card aparece na janela já aberta.
        if (
            usesBitmapOcrForUber &&
                currentSettings.isUberEnabled &&
                (isWindowStateChange || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) &&
                isUber
        ) {
            scheduleUberWindowOcr(packageName)
        }
    }

    private fun processBoltEvent(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        val isWindowStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!isWindowStateChange && now - lastBoltParseAt < BOLT_PARSE_DEBOUNCE_MS) {
            skippedBoltEvents++
            logBoltSummaryIfDue(now)
            return
        }
        lastBoltParseAt = now
        // Em DeX/tela dividida a janela ativa pode ser a Uber/Waze, mesmo
        // quando o evento acabou de ser emitido pela Bolt. O nó-fonte é a
        // referência mais fiável; só usamos a janela ativa se ela também for
        // da Bolt e, por fim, procuramos uma janela Bolt visível.
        val rootNode = event.source
            ?.takeIf { it.belongsToPackage(BOLT_PACKAGE_FRAGMENT) }
            ?.topMostParent()
            ?: rootInActiveWindow
                ?.takeIf { it.belongsToPackage(BOLT_PACKAGE_FRAGMENT) }
            ?: findVisiblePackageRoot(BOLT_PACKAGE_FRAGMENT)
            ?: run {
            AppLogger.debug("Bolt sem rootInActiveWindow/source")
            return
        }
        val offer = runCatching { boltParser.parse(rootNode) }
            .onFailure { AppLogger.warn("Erro ao interpretar card Bolt", it) }
            .getOrNull()
        if (offer != null) {
            logBoltSummaryIfDue(now, force = true)
            handleOffer(offer, overlayAnchor = accessibilityWindowBounds(rootNode))
        } else {
            boltNoOfferCount++
            logBoltSummaryIfDue(now)
        }
    }

    private fun processUberAccessibilityEvent(
        event: AccessibilityEvent,
        sourceNode: AccessibilityNodeInfo?
    ) {
        // O nó-fonte preserva a árvore da Uber quando a oferta está sobre
        // outra aplicação. A janela ativa, nesse caso, não pertence à Uber.
        val rootNode = sourceNode
            ?.takeIf { it.belongsToPackage(UBER_PACKAGE_FRAGMENT) }
            ?.topMostParent()
            ?: rootInActiveWindow
                ?.takeIf { it.belongsToPackage(UBER_PACKAGE_FRAGMENT) }
            ?: event.source
                ?.takeIf { it.belongsToPackage(UBER_PACKAGE_FRAGMENT) }
            ?: run {
            AppLogger.debug("Uber sem rootInActiveWindow/source")
            return
        }
        processUberAccessibilityTree(rootNode)
    }

    /** Android 12 e inferiores: uma nova leitura da árvore, nunca OCR. */
    private fun processUberAccessibilityTree(rootNode: AccessibilityNodeInfo) {
        val cardText = runCatching { rootNode.extractUberOfferCardText() }
            .onFailure { AppLogger.warn("Erro ao recolher texto da árvore Uber", it) }
            .getOrNull()
        val offer = cardText?.let { text ->
            AppLogger.debug("Texto Uber encontrado pela acessibilidade: ${text.compactForLog()}")
            uberParser.parse(text)
        }
        if (offer != null) {
            resetIncompleteAccessibilityRetry()
            handleOffer(offer, overlayAnchor = accessibilityWindowBounds(rootNode))
        } else {
            AppLogger.debug("Nenhuma oferta Uber válida na árvore")
            cardText?.let(::scheduleIncompleteAccessibilityRetry)
        }
    }

    private fun scheduleIncompleteAccessibilityRetry(cardText: String) {
        if (usesBitmapOcrForUber) return

        val now = System.currentTimeMillis()
        val signature = cardText.compactForLog()
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
    private fun scheduleUberWindowOcr(packageName: String) {
        // Leading edge: eventos sucessivos não adiam indefinidamente a leitura.
        if (uberWindowOcrRunnable != null) return
        val task = Runnable {
            uberWindowOcrRunnable = null
            if (
                currentSettings.isAppRunning &&
                    currentSettings.isUberEnabled &&
                    isUberVisibleForOcr()
            ) {
                requestScreenshot("evento visual Uber: $packageName")
            }
        }
        uberWindowOcrRunnable = task
        mainHandler.postDelayed(task, UBER_EVENT_OCR_DELAY_MS)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestScreenshot(reason: String): ScreenshotRequestResult {
        if (!isUberVisibleForOcr()) return ScreenshotRequestResult.Rejected
        if (textRecognizer == null || ocrWarmupInFlight.get()) {
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
        // Não selecionar uma janela Uber: no Android 13/14 o serviço pode
        // devolver uma janela auxiliar de 167x167. O display completo é a única
        // fonte consistente para DeX, tela dividida e múltiplos cards.
        AppLogger.debug("Solicitando captura Uber: origem=display, motivo=$reason, id=$requestId")
        armScreenshotWatchdog(requestId)

        mainHandler.post { requestScreenshotPayload(requestId) }
        return ScreenshotRequestResult.Started
    }

    /** Captura sempre o display completo; não depende de limites de janela. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestScreenshotPayload(requestId: Long) {
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshotResult: ScreenshotResult) {
                processScreenshotResult(requestId, screenshotResult)
            }

            override fun onFailure(errorCode: Int) {
                releaseScreenshot(requestId, "falha código=$errorCode")
                if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    scheduleIncompleteOcrRetry(SCREENSHOT_INTERVAL_MS)
                }
            }
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
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
        screenshotResult: ScreenshotResult
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
                scheduleIncompleteOcrRetry()
                return
            }

            if (!isUberVisibleForOcr()) {
                releaseScreenshot(requestId, "Uber deixou de estar visível")
                return
            }

            AppLogger.debug(
                "Captura recebida: id=$requestId, ${bitmap.width}x${bitmap.height}, " +
                    "origem=display"
            )
            processOcrBitmap(requestId, bitmap)
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

    @RequiresApi(Build.VERSION_CODES.R)
    private fun processOcrBitmap(
        requestId: Long,
        bitmap: Bitmap
    ) {
        val recognizer = textRecognizer ?: run {
            bitmap.recycle()
            releaseScreenshot(requestId, "OCR indisponível nesta versão Android")
            return
        }
        val ocrStartedAt = SystemClock.elapsedRealtime()
        var bitmapForOcr = bitmap
        var preparedScale = 1f
        try {
            val prepared = OcrBitmapPreprocessor.prepare(bitmap)
            bitmapForOcr = prepared.bitmap
            preparedScale = prepared.scale
            if (bitmapForOcr !== bitmap) {
                bitmap.recycle()
                AppLogger.debug(
                    "Captura reduzida proporcionalmente antes do OCR: id=$requestId, " +
                        "${bitmapForOcr.width}x${bitmapForOcr.height}, escala=$preparedScale"
                )
            }
            recognizer.process(InputImage.fromBitmap(bitmapForOcr, 0))
                .addOnSuccessListener { visionText ->
                    if (!isScreenshotActive(requestId)) {
                        AppLogger.debug("Resultado OCR obsoleto ignorado: id=$requestId")
                        return@addOnSuccessListener
                    }
                    if (
                        !currentSettings.isAppRunning ||
                            !currentSettings.isUberEnabled ||
                            !isUberVisibleForOcr()
                    ) return@addOnSuccessListener

                    val extractedCard = uberCardTextExtractor.extractCard(visionText)
                    if (extractedCard == null) {
                        val rawText = visionText.text
                        recordOcrWithoutUberCard(requestId, rawText)
                        // A tela inicial da Uber não é um cartão incompleto. Repetir
                        // OCR nesse estado criava rajadas permanentes de capturas.
                        if (uberCardTextExtractor.resemblesOffer(rawText)) {
                            scheduleIncompleteOcrRetry()
                        }
                        return@addOnSuccessListener
                    }

                    val cardText = extractedCard.text
                    val offer = uberParser.parse(cardText)?.copy(category = extractedCard.category)
                    if (offer == null) {
                        recordInvalidUberCard(cardText)
                    } else {
                        resetIncompleteOcrRetry()
                        lastOcrNoCardSignature = null
                        lastOcrNoCardLoggedAt = 0L
                        logOcrNoCardSummaryIfDue(System.currentTimeMillis(), force = true)
                        AppLogger.info(
                            "Card Uber detetado por OCR: categoria=${offer.category.orEmpty()}, " +
                                cardText.compactForLog()
                        )
                        when (
                            ocrOfferConfirmationTracker.observe(
                                offer = offer,
                                nowMs = System.currentTimeMillis()
                            )
                        ) {
                            OcrOfferConfirmationTracker.Result.AWAITING_CONFIRMATION -> {
                                pendingOcrCategory = offer.category
                                AppLogger.debug("Oferta Uber OCR aguardando confirmação 2/2")
                                scheduleOcrSecondReading(offer)
                            }
                            OcrOfferConfirmationTracker.Result.CONFIRMED -> {
                                val confirmedOffer = offer.copy(
                                    category = pendingOcrCategory ?: offer.category
                                )
                                pendingOcrCategory = null
                                AppLogger.debug("Oferta Uber OCR confirmada em duas leituras coincidentes")
                                handleOffer(
                                    offer = confirmedOffer,
                                    alreadyConfirmedByOcr = true,
                                    ocrScreenshot = bitmapForOcr
                                )
                            }
                            OcrOfferConfirmationTracker.Result.MISMATCH -> {
                                AppLogger.warn(
                                    "Confirmação OCR divergente descartada; mantendo a primeira leitura completa"
                                )
                                scheduleOcrSecondReading(offer)
                            }
                            OcrOfferConfirmationTracker.Result.DUPLICATE -> {
                                AppLogger.debug("Oferta Uber OCR duplicada ignorada")
                            }
                        }
                    }
                }
                .addOnFailureListener { error ->
                    AppLogger.warn("Falha no OCR da captura: id=$requestId", error)
                    scheduleIncompleteOcrRetry()
                }
                .addOnCompleteListener {
                    bitmapForOcr.recycle()
                    recordOcrCompletion(SystemClock.elapsedRealtime() - ocrStartedAt)
                    releaseScreenshot(requestId, "OCR concluído")
                }
        } catch (error: Throwable) {
            bitmapForOcr.takeIf { !it.isRecycled }?.recycle()
            if (bitmapForOcr !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            releaseScreenshot(requestId, "não foi possível iniciar OCR", error)
        }
    }

    private fun screenshotPollIntervalMs(): Long =
        if (isUberVisibleForOcr()) {
            UBER_FOREGROUND_SCREENSHOT_POLL_INTERVAL_MS
        } else {
            UBER_BACKGROUND_SCREENSHOT_POLL_INTERVAL_MS
        }

    /**
     * Em DeX e em tela dividida a Uber pode estar visível sem ser a janela ativa.
     * Para OCR usamos a visibilidade real de uma janela grande da Uber, nunca uma
     * janela auxiliar como a bolha 167x167 observada nos registos.
     */
    private fun shouldPollUberOcr(): Boolean = isUberVisibleForOcr()

    /**
     * Usa a última mudança de janela enquanto ainda é recente e, depois, consulta
     * a raiz ativa. Nunca mantém um pacote Uber indefinidamente como fallback.
     */
    private fun currentForegroundPackage(): String {
        val now = SystemClock.elapsedRealtime()
        if (
            foregroundPackage.isNotBlank() &&
                now - foregroundPackageUpdatedAt <= FOREGROUND_EVENT_FRESHNESS_MS
        ) {
            return foregroundPackage
        }
        val activePackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (activePackage.isNotBlank()) return activePackage
        return ""
    }

    @RequiresApi(Build.VERSION_CODES.R)
    /** Segunda leitura OCR do mesmo cartão, 350 ms após a primeira leitura completa. */
    private fun scheduleOcrSecondReading(
        offer: TripOffer,
        delayMs: Long = OCR_SECOND_READING_DELAY_MS,
        busyRetries: Int = 0
    ) {
        val signature = offer.stabilitySignature()
        if (scheduledOcrConfirmationSignature == signature) return

        ocrConfirmationRunnable?.let(mainHandler::removeCallbacks)
        scheduledOcrConfirmationSignature = signature
        val confirmation = Runnable {
            ocrConfirmationRunnable = null
            if (currentSettings.isAppRunning && currentSettings.isUberEnabled) {
                val result = requestScreenshot("confirmação OCR 2/2 do card Uber")
                val retryAfterMs = (result as? ScreenshotRequestResult.RetryAfter)?.delayMs
                if (retryAfterMs != null && busyRetries < MAX_OCR_CONFIRMATION_BUSY_RETRIES) {
                    scheduledOcrConfirmationSignature = null
                    scheduleOcrSecondReading(
                        offer,
                        retryAfterMs.coerceAtLeast(OCR_BUSY_RETRY_DELAY_MS),
                        busyRetries + 1
                    )
                    return@Runnable
                }
                if (result != ScreenshotRequestResult.Started) {
                    AppLogger.warn("Confirmação OCR 2/2 não iniciou porque a fila permaneceu ocupada")
                }
            }
            scheduledOcrConfirmationSignature = null
        }
        ocrConfirmationRunnable = confirmation
        mainHandler.postDelayed(confirmation, delayMs)
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

    private fun recordOcrWithoutUberCard(requestId: Long, rawText: String) {
        val now = System.currentTimeMillis()
        val signature = rawText.compactForLog().ifBlank { "<sem texto reconhecido>" }
        if (
            signature != lastOcrNoCardSignature ||
                now - lastOcrNoCardLoggedAt >= OCR_NO_CARD_DETAIL_COOLDOWN_MS
        ) {
            AppLogger.debug(
                "OCR Uber sem card completo: captura=$requestId, " +
                    "possívelOferta=${uberCardTextExtractor.resemblesOffer(rawText)}, texto=$signature"
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
        val signature = cardText.compactForLog()
        if (
            signature == invalidUberCardSignature &&
                now - lastInvalidUberCardAt < INVALID_UBER_CARD_COOLDOWN_MS
        ) {
            suppressedInvalidUberCardCount++
            if (usesBitmapOcrForUber) scheduleIncompleteOcrRetry()
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
        AppLogger.debug("Card Uber ainda incompleto; será repetida a leitura OCR")
        if (usesBitmapOcrForUber) scheduleIncompleteOcrRetry()
    }

    private fun logBoltSummaryIfDue(now: Long, force: Boolean = false) {
        if (skippedBoltEvents == 0 && boltNoOfferCount == 0) return
        if (boltSummaryStartedAt == 0L) {
            boltSummaryStartedAt = now
        }
        if (!force && now - boltSummaryStartedAt < BOLT_LOG_SUMMARY_INTERVAL_MS) return

        val elapsedSeconds = (now - boltSummaryStartedAt) / 1_000
        AppLogger.debug(
            "Bolt sem oferta: $boltNoOfferCount análise(s) sem card e " +
                "$skippedBoltEvents evento(s) agrupado(s) em $elapsedSeconds s"
        )
        skippedBoltEvents = 0
        boltNoOfferCount = 0
        boltSummaryStartedAt = now
    }

    private fun isUberInForeground(): Boolean {
        return currentForegroundPackage().contains(UBER_PACKAGE_FRAGMENT, ignoreCase = true)
    }

    private fun isUberVisibleForOcr(): Boolean {
        if (isUberInForeground()) return true
        return runCatching {
            windows.asSequence().any { window ->
                val root = runCatching { window.root }.getOrNull() ?: return@any false
                if (!root.belongsToPackage(UBER_PACKAGE_FRAGMENT)) return@any false
                val bounds = Rect()
                root.getBoundsInScreen(bounds)
                bounds.width() >= MIN_VALID_DISPLAY_DIMENSION_PX &&
                    bounds.height() >= MIN_VALID_DISPLAY_DIMENSION_PX
            }
        }.getOrDefault(false)
    }

    /** Obtém a raiz da aplicação pedida mesmo quando outra janela está ativa. */
    private fun findVisiblePackageRoot(packageFragment: String): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
            ?.takeIf { it.belongsToPackage(packageFragment) }
        if (activeRoot != null) return activeRoot

        return runCatching {
            windows.asSequence()
                .mapNotNull { window -> runCatching { window.root }.getOrNull() }
                .firstOrNull { it.belongsToPackage(packageFragment) }
        }.getOrNull()
    }

    private fun isScreenshotActive(requestId: Long): Boolean =
        activeScreenshotId == requestId && screenshotGate.isActive()

    private fun releaseScreenshot(requestId: Long, reason: String, error: Throwable? = null) {
        if (activeScreenshotId != requestId) return

        screenshotTimeoutRunnable?.let(mainHandler::removeCallbacks)
        screenshotTimeoutRunnable = null
        val elapsedMs = (SystemClock.elapsedRealtime() - activeScreenshotStartedAt).coerceAtLeast(0L)
        activeScreenshotId = NO_ACTIVE_SCREENSHOT
        activeScreenshotStartedAt = 0L
        screenshotGate.release()
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
        /** A captura que acabou de confirmar a oferta Uber por OCR. */
        ocrScreenshot: Bitmap? = null,
        overlayAnchor: Rect? = null
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

        if (alreadyConfirmedByOcr) {
            // A confirmação estrita da Uber OCR já ocorreu imediatamente antes.
        } else if (usesImmediateAccessibilityPublication(Build.VERSION.SDK_INT)) {
            // Android 12L e inferiores: Uber e Bolt são lidas por nós de
            // acessibilidade. Uma oferta válida é publicada neste evento,
            // mas leituras repetidas do mesmo card não voltam ao histórico.
            if (!offerStabilityTracker.shouldPublishImmediately(offer, System.currentTimeMillis())) {
                AppLogger.debug("Oferta de acessibilidade duplicada ignorada: ${offer.platform}")
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

        val historyEntryId = analysisStore.publish(offer, decision)
        captureOfferScreenshotIfEnabled(historyEntryId, ocrScreenshot)
        overlayManager.showDecision(decision, overlayAnchor)
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
    private fun captureOfferScreenshotIfEnabled(historyEntryId: Long, ocrScreenshot: Bitmap? = null) {
        if (!currentSettings.isOfferScreenshotCaptureEnabled) return

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
        requestOfferScreenshot(historyEntryId)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestOfferScreenshot(historyEntryId: Long) {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
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
        serviceScope.launch(Dispatchers.IO) {
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
        ocrOfferConfirmationTracker.clear()
        cancelPendingUberOcr()
        hasActiveDecision = false
        overlayManager.removeOverlay()
        if (hadActiveState) AppLogger.debug("Overlay limpo: $reason")
    }

    /** Cancela apenas trabalho OCR futuro, preservando a deduplicação publicada. */
    private fun cancelPendingUberOcr() {
        ocrOfferConfirmationTracker.clearPending()
        ocrConfirmationRunnable?.let(mainHandler::removeCallbacks)
        ocrConfirmationRunnable = null
        uberWindowOcrRunnable?.let(mainHandler::removeCallbacks)
        uberWindowOcrRunnable = null
        resetIncompleteOcrRetry()
        resetIncompleteAccessibilityRetry()
        scheduledOcrConfirmationSignature = null
        pendingOcrCategory = null
    }

    private fun String.compactForLog(): String = replace(Regex("\\s+"), " ").take(MAX_LOG_TEXT_LENGTH)

    private val usesBitmapOcrForUber: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private val isAnalysisAuthorized: Boolean
        get() = BuildConfig.IS_ADMIN_APP || licenseManager.state.value.isValid

    private fun AccessibilityNodeInfo.belongsToPackage(packageFragment: String): Boolean =
        packageName?.toString()?.contains(packageFragment, ignoreCase = true) == true

    /** Limites da janela a que o nó pertence, usados para ancorar o card no DeX. */
    private fun accessibilityWindowBounds(node: AccessibilityNodeInfo): Rect? =
        runCatching {
            val window = node.window ?: return@runCatching null
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            bounds.takeUnless { it.isEmpty }
        }.getOrNull()

    private fun AccessibilityNodeInfo.topMostParent(): AccessibilityNodeInfo {
        var currentNode = this
        repeat(MAX_ACCESSIBILITY_PARENT_DEPTH) {
            val parentNode = currentNode.parent ?: return currentNode
            currentNode = parentNode
        }
        return currentNode
    }

    override fun onInterrupt() {
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
        AppLogger.info("Serviço de Acessibilidade destruído")
        boltReservationCoordinator.disconnect()
        clearOverlay("serviço destruído")
        screenshotTimeoutRunnable?.let(mainHandler::removeCallbacks)
        ocrRestartRunnable?.let(mainHandler::removeCallbacks)
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
        }
        return uberCardTextExtractor.extract(blocks)
            ?: uberCardTextExtractor.extract(unpositionedBlocks)
    }

    private companion object {
        const val UBER_PACKAGE_FRAGMENT = "uber"
        const val BOLT_PACKAGE_FRAGMENT = "mtakso"
        const val MAX_TEXT_NODES = 600
        const val MAX_ACCESSIBILITY_PARENT_DEPTH = 32
        const val FALLBACK_TEXT_LINE_HEIGHT_PX = 48
        const val MAX_LOG_TEXT_LENGTH = 700
        const val REQUIRED_CONSECUTIVE_READINGS = 2
        const val SCREENSHOT_INTERVAL_MS = 750L
        const val UBER_FOREGROUND_SCREENSHOT_POLL_INTERVAL_MS = 2_000L
        const val UBER_BACKGROUND_SCREENSHOT_POLL_INTERVAL_MS = 2_000L
        const val FOREGROUND_EVENT_FRESHNESS_MS = 1_500L
        const val OCR_SECOND_READING_DELAY_MS = 350L
        const val OCR_CONFIRMATION_WINDOW_MS = 4_000L
        const val UBER_EVENT_OCR_DELAY_MS = 350L
        const val INCOMPLETE_OCR_RETRY_DELAY_MS = 300L
        const val INCOMPLETE_OCR_RETRY_WINDOW_MS = 4_000L
        const val MAX_INCOMPLETE_OCR_RETRIES = 4
        const val OCR_BUSY_RETRY_DELAY_MS = 80L
        const val MAX_OCR_CONFIRMATION_BUSY_RETRIES = 8
        const val OCR_WARMUP_SIZE_PX = 32
        const val INCOMPLETE_ACCESSIBILITY_RETRY_DELAY_MS = 350L
        const val INCOMPLETE_ACCESSIBILITY_RETRY_COOLDOWN_MS = 2_500L
        const val SCREENSHOT_TIMEOUT_MS = 6_000L
        const val OCR_RECOGNIZER_RESTART_DELAY_MS = 1_000L
        const val OCR_TIMEOUT_BURST_WINDOW_MS = 60_000L
        const val MAX_OCR_TIMEOUTS_PER_WINDOW = 2
        const val OCR_TIMEOUT_CIRCUIT_BREAKER_MS = 30_000L
        const val OCR_MEMORY_PRESSURE_PAUSE_MS = 10_000L
        const val OCR_MEMORY_REPORT_INTERVAL_MS = 5 * 60_000L
        const val SCREENSHOT_CLEANUP_INTERVAL_MS = 60 * 60_000L
        const val BYTES_PER_MIB = 1_048_576L
        const val BOLT_PARSE_DEBOUNCE_MS = 300L
        const val BOLT_LOG_SUMMARY_INTERVAL_MS = 5_000L
        const val OCR_NO_CARD_LOG_SUMMARY_INTERVAL_MS = 5_000L
        const val OCR_NO_CARD_DETAIL_COOLDOWN_MS = 5_000L
        const val INVALID_UBER_CARD_COOLDOWN_MS = 5_000L
        const val DECISION_DUPLICATE_WINDOW_MS = 20_000L
        const val MIN_VALID_DISPLAY_DIMENSION_PX = 320
        const val NO_ACTIVE_SCREENSHOT = 0L
    }
}
