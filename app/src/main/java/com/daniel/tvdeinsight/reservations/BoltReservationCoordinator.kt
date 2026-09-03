package com.daniel.tvdeinsight.reservations

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Integração com a Bolt. Este serviço não desenha UI nem gere o foreground service;
 * apenas lê a árvore, executa o ciclo serial de procura e envia comandos de clique.
 */
class BoltReservationCoordinator(
    private val service: AccessibilityService,
    private val isAuthorized: () -> Boolean
) {
    private val handler = Handler(Looper.getMainLooper())
    private var searchActive = false
    private var reservationInProgress = false
    private var refreshInProgress = false
    // O mesmo intervalo configurado em “Tempo de procura” é usado entre
    // Aceitar -> Confirmar -> resultado -> Fechar, pois a Bolt pode manter a
    // árvore de acessibilidade em transição durante o carregamento.
    private var reservationWaitMillis = 1000L
    private var noEligibleRideSince = 0L
    private var generation = 0L
    private var licenseCheckAt = 0L
    private var licenseValid = false
    private var lastHistoryCaptureAt = 0L
    private var scanCountSincePersist = 0
    private var evaluationInProgress = false
    private val pendingHistoryEvaluations = mutableSetOf<String>()
    private val evaluationExecutor = Executors.newSingleThreadExecutor()
    private val pickupDistanceResolver by lazy { PickupDistanceResolver(service) }
    private val foregroundCheck = Runnable { verifyBoltForeground() }
    private val attempted = mutableMapOf<String, Long>()
    private val scanRunnable = Runnable { scanOnce(generation) }
    private val watchdog = object : Runnable {
        override fun run() {
            if (!searchActive) return
            val root = service.rootInActiveWindow
            val screen = BoltScreenReader.read(root)
            if (!screen.isBoltVisible) {
                stopSearch("Bolt deixou de estar visível (watchdog)")
                return
            }
            if (!reservationInProgress && !refreshInProgress && !screen.isPedidos) {
                stopSearch("separador Pedidos deixou de estar ativo (watchdog)")
                return
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MILLIS)
        }
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AutomationContract.ACTION_PROBE_SCREEN -> publishScreenState()
                AutomationContract.ACTION_START_SEARCH -> startSearch()
                AutomationContract.ACTION_STOP_SEARCH -> stopSearch("comando do utilizador")
            }
        }
    }

    fun connect() {
        DiagnosticLogger.log("Serviço de acessibilidade criado")
        val filter = IntentFilter().apply {
            addAction(AutomationContract.ACTION_PROBE_SCREEN)
            addAction(AutomationContract.ACTION_START_SEARCH)
            addAction(AutomationContract.ACTION_STOP_SEARCH)
        }
        ContextCompat.registerReceiver(
            service,
            commandReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ReservationNotifications.createChannels(service)
        restoreAfterConnect()
    }

    private fun restoreAfterConnect() {
        DiagnosticLogger.log("Serviço de acessibilidade ligado")
        publishScreenState()
        if (AppPreferences.isSearching(service)) {
            // Recuperação após o processo ser recriado pelo Android: só retoma em Pedidos.
            postGuarded(400L) { startSearch() }
        }
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString()
        val root = findBoltRoot()
        val screen = BoltScreenReader.read(root)

        if (!screen.isBoltVisible) {
            if (searchActive) scheduleForegroundCheck(packageName)
            else if (packageName != AutomationContract.BOLT_PACKAGE) publishScreenState(false, false)
            return
        }

        handler.removeCallbacks(foregroundCheck)
        publishScreenState(true, screen.isPedidos)
        // Ao abrir uma oferta, a Bolt deixa de expor scheduledSegment durante
        // alguns instantes. Isso é uma etapa interna da reserva, não uma saída
        // da aplicação. O detalhe/dialogue é reconhecido pela própria árvore.
        // Na lista não é necessário varrer a árvore inteira para procurar
        // diálogos. Essa leitura pesada no mapa/lista era a origem dos atrasos
        // variáveis observados na 1.18. Só inspeciona estados de reserva quando
        // a tela já deixou de ser o separador Pedidos.
        val reservationUi = reservationInProgress || (!screen.isPedidos && isReservationUi(root))
        // O histórico não depende de a busca estar ativa nem de a aba ser
        // Pedidos. A Bolt também apresenta viagens em Aceites; capturamos a
        // árvore nessa aba e o RideHistoryStore elimina duplicados pelo ID.
        if (!searchActive) captureVisibleRides(root)
        if (searchActive && !screen.isPedidos && !reservationUi && !refreshInProgress) {
            stopSearch("saiu do separador Pedidos")
        } else if (searchActive && !reservationUi && !refreshInProgress) {
            scheduleScan(80L)
        }
    }

    /** Durante Aceitar/Confirmar o leitor normal não deve atuar sobre a mesma árvore. */
    fun isInteractionInProgress(): Boolean = searchActive && reservationInProgress

    fun onInterrupt() {
        DiagnosticLogger.log("Serviço de acessibilidade interrompido")
        stopSearch("interrupção do Android")
    }

    fun disconnect() {
        DiagnosticLogger.log("Serviço de acessibilidade destruído")
        handler.removeCallbacks(foregroundCheck)
        evaluationExecutor.shutdownNow()
        stopSearch("destruição do serviço")
        runCatching { service.unregisterReceiver(commandReceiver) }
    }

    private fun startSearch() {
        if (!isLicenseValid(force = true)) {
            DiagnosticLogger.log("Busca recusada: licença inválida ou não ativada")
            AppPreferences.setSearching(service, false)
            return
        }
        val screen = BoltScreenReader.read(findBoltRoot())
        publishScreenState(screen.isBoltVisible, screen.isPedidos)
        if (!screen.isBoltVisible || !screen.isPedidos) {
            DiagnosticLogger.log("Busca recusada: tela atual não é Bolt/Pedidos")
            AppPreferences.setSearching(service, false)
            return
        }
        generation++
        searchActive = true
        reservationInProgress = false
        refreshInProgress = false
        noEligibleRideSince = 0L
        AppPreferences.setSearching(service, true)
        AutomationStateStore.transition(service, AutomationPhase.SEARCHING, "scheduledSegment/Pedidos")
        DiagnosticLogger.log("Busca iniciada diretamente em scheduledSegment/Pedidos")
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MILLIS)
        scheduleScan(0L)
    }

    private fun stopSearch(reason: String) {
        if (!searchActive && !AppPreferences.isSearching(service)) return
        generation++
        searchActive = false
        reservationInProgress = false
        refreshInProgress = false
        noEligibleRideSince = 0L
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(watchdog)
        AppPreferences.setSearching(service, false)
        AutomationStateStore.transition(service, AutomationPhase.STOPPED, reason)
        DiagnosticLogger.log("Busca parada: $reason")
    }

    private fun scheduleScan(delayMillis: Long) {
        if (!searchActive) return
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, delayMillis.coerceAtLeast(0L))
    }

    private fun isLicenseValid(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - licenseCheckAt < LICENSE_CHECK_INTERVAL_MILLIS) return licenseValid
        licenseCheckAt = now
        // A licença unificada já é validada pelo serviço anfitrião. Não depende
        // do estado INICIAR/PARAR da TVDE Insight.
        licenseValid = isAuthorized()
        return licenseValid
    }

    private fun scanOnce(scanGeneration: Long) {
        if (!searchActive || scanGeneration != generation || reservationInProgress || refreshInProgress || evaluationInProgress) return
        if (!isLicenseValid()) {
            stopSearch("licença inválida ou expirada")
            return
        }
        val root = service.rootInActiveWindow
        val screen = BoltScreenReader.read(root)
        if (!screen.isBoltVisible || (!screen.isPedidos && !isReservationUi(root))) {
            stopSearch(if (!screen.isBoltVisible) "Bolt não está em primeiro plano" else "separador Pedidos deixou de estar ativo")
            return
        }

        val settings = AppPreferences.loadSettings(service)
        scanCountSincePersist++
        if (scanCountSincePersist >= 10) {
            scanCountSincePersist = 0
            AutomationStateStore.recordScan(service)
        }
        val now = System.currentTimeMillis()
        attempted.entries.removeIf { now - it.value > ATTEMPT_COOLDOWN_MILLIS }
        val candidates = BoltRideReader.findCandidates(root!!)
        evaluationInProgress = true
        evaluateAndRecord(candidates, settings) { evaluated ->
            evaluationInProgress = false
            if (!searchActive || scanGeneration != generation || reservationInProgress || refreshInProgress) return@evaluateAndRecord

            val eligible = evaluated.firstOrNull { evaluatedRide ->
                evaluatedRide.hit.candidate.fingerprint !in attempted && evaluatedRide.evaluation.accepted
            }
            DiagnosticLogger.log("Scan em Pedidos: cartões=${candidates.size}, elegível=${eligible != null}")

            if (eligible != null) {
                noEligibleRideSince = 0L
                attempted[eligible.hit.candidate.fingerprint] = System.currentTimeMillis()
                if (settings.dailyLimitEnabled && AutomationStateStore.acceptedToday(service) >= settings.maxDailyReservations) {
                    stopSearch("limite diário de ${settings.maxDailyReservations} reservas atingido")
                    return@evaluateAndRecord
                }
                reserve(eligible)
                return@evaluateAndRecord
            }

            if (noEligibleRideSince == 0L) noEligibleRideSince = System.currentTimeMillis()
            val elapsed = System.currentTimeMillis() - noEligibleRideSince
            if (elapsed < settings.searchWaitMillis) {
                scheduleScan(minOf(POLL_INTERVAL_MILLIS, settings.searchWaitMillis - elapsed))
                return@evaluateAndRecord
            }

            noEligibleRideSince = 0L
            refreshFromAccepted(root, settings.refreshDelayMillis, scanGeneration)
        }
    }

    private fun captureVisibleRides(root: AccessibilityNodeInfo?) {
        val now = System.currentTimeMillis()
        if (root == null || now - lastHistoryCaptureAt < HISTORY_CAPTURE_INTERVAL_MILLIS) return
        lastHistoryCaptureAt = now
        val candidates = BoltRideReader.findCandidates(root)
        val settings = AppPreferences.loadSettings(service)
        val pending = candidates.filter { pendingHistoryEvaluations.add(it.candidate.fingerprint) }
        if (pending.isEmpty()) return
        evaluateAndRecord(pending, settings) {
            pending.forEach { pendingHistoryEvaluations.remove(it.candidate.fingerprint) }
            if (pending.isNotEmpty()) {
                DiagnosticLogger.log("Histórico: ${pending.size} viagem(ns) capturada(s) fora da busca")
            }
        }
    }

    private fun evaluateAndRecord(
        candidates: List<RideHit>,
        settings: ReservationSettings,
        onComplete: (List<EvaluatedRide>) -> Unit
    ) {
        val unique = candidates.distinctBy { it.candidate.fingerprint }
        if (unique.isEmpty()) {
            onComplete(emptyList())
            return
        }
        evaluationExecutor.execute {
            val evaluated = unique.map { hit ->
                val pickupDistance = if (settings.homeAddress.isBlank()) {
                    null
                } else {
                    pickupDistanceResolver.distanceKm(settings.homeAddress, hit.candidate.origin)
                }
                val evaluation = RideEvaluator.evaluate(hit.candidate, settings, pickupDistance)
                DiagnosticLogger.log(
                    "Decisão ${hit.candidate.historyId}: ${if (evaluation.accepted) "VERDE" else "VERMELHO"}; " +
                        "recolha=${pickupDistance?.let { "%.2f km".format(java.util.Locale.US, it) } ?: "indisponível"}; " +
                        "motivo=${evaluation.reasons.joinToString("; ").ifBlank { "todos os critérios" }}"
                )
                EvaluatedRide(hit, evaluation, pickupDistance)
            }
            handler.post {
                evaluated.forEach { item ->
                    RideHistoryStore.record(
                        service,
                        item.hit.candidate,
                        item.evaluation,
                        item.pickupDistanceKm,
                        simulated = false
                    )
                }
                onComplete(evaluated)
            }
        }
    }

    private fun refreshFromAccepted(root: AccessibilityNodeInfo, delayMillis: Long, scanGeneration: Long) {
        if (refreshInProgress || !searchActive || scanGeneration != generation) return
        val accepted = AccessibilityNodeUtils.findFirstById(root, BoltScreenReader.ACCEPTED_SEGMENT_ID)
        if (accepted == null || !AccessibilityNodeUtils.click(accepted)) {
            DiagnosticLogger.log("Falha ao clicar acceptedSegment; nova tentativa após ${RETRY_INTERVAL_MILLIS}ms")
            scheduleScan(RETRY_INTERVAL_MILLIS)
            return
        }
        refreshInProgress = true
        AutomationStateStore.transition(service, AutomationPhase.REFRESHING, "Aceites → Pedidos")
        DiagnosticLogger.log("Refresh: scheduledSegment -> procura concluída -> acceptedSegment")
        postGuarded(delayMillis) {
            val current = service.rootInActiveWindow
            val scheduled = AccessibilityNodeUtils.findFirstById(current, BoltScreenReader.SCHEDULED_SEGMENT_ID)
            if (scheduled == null || !AccessibilityNodeUtils.click(scheduled)) {
                DiagnosticLogger.log("Falha ao clicar scheduledSegment depois do refresh")
            } else {
                DiagnosticLogger.log("Refresh concluído: acceptedSegment -> ${delayMillis}ms -> scheduledSegment")
            }
            refreshInProgress = false
            noEligibleRideSince = 0L
            scheduleScan(POLL_INTERVAL_MILLIS)
        }
    }

    private fun reserve(evaluated: EvaluatedRide) {
        val ride = evaluated.hit
        reservationInProgress = true
        AutomationStateStore.transition(service, AutomationPhase.OPENING_RIDE, ride.candidate.historyId)
        reservationWaitMillis = AppPreferences.loadSettings(service).searchWaitMillis
            .coerceIn(100L, 10_000L)
        DiagnosticLogger.log("Tempo de espera da reserva: ${reservationWaitMillis}ms")
        openRideDetails(evaluated, 0)
    }

    private fun openRideDetails(evaluated: EvaluatedRide, targetIndex: Int) {
        val ride = evaluated.hit
        val target = ride.clickTargets.getOrNull(targetIndex)
        if (target == null) {
            reservationInProgress = false
            DiagnosticLogger.log("Nenhum alvo abriu a viagem após ${ride.clickTargets.size} tentativa(s)")
            RideHistoryStore.updateOutcome(
                service,
                ride.candidate.historyId,
                accepted = false,
                reason = "não foi possível abrir a viagem"
            )
            scheduleScan(RETRY_INTERVAL_MILLIS)
            return
        }
        DiagnosticLogger.log("A abrir viagem: tentativa ${targetIndex + 1}/${ride.clickTargets.size}")
        if (!clickRideCard(target)) {
            openRideDetails(evaluated, targetIndex + 1)
            return
        }
        postGuarded(700L) {
            waitForRideDetails(evaluated, targetIndex, 10)
        }
    }

    private fun waitForRideDetails(evaluated: EvaluatedRide, targetIndex: Int, attemptsLeft: Int) {
        val ride = evaluated.hit
        val detail = findBoltRoot()
        if (detail != null && isRideDetailsScreen(detail)) {
            if (recoverError(detail)) {
                finishReservation(false, "erro ao abrir cartão", ride.candidate)
                return
            }
            DiagnosticLogger.log("Tela da viagem aberta na tentativa ${targetIndex + 1}")
            captureOpenedRideRoute(detail, evaluated)
            clickAccept(evaluated, detail, 3)
            return
        }
        if (attemptsLeft > 0) {
            postGuarded(250L) { waitForRideDetails(evaluated, targetIndex, attemptsLeft - 1) }
        } else {
            DiagnosticLogger.log("Alvo ${targetIndex + 1} não abriu a tela da viagem; a tentar o próximo")
            openRideDetails(evaluated, targetIndex + 1)
        }
    }

    private fun clickAccept(evaluated: EvaluatedRide, detail: AccessibilityNodeInfo, attemptsLeft: Int) {
        val ride = evaluated.hit
        AutomationStateStore.transition(service, AutomationPhase.ACCEPTING, "Aceitar ${ride.candidate.historyId}")
        if (clickAction(detail, "Aceitar")) {
            DiagnosticLogger.log("Botão Aceitar pressionado")
            postGuarded(reservationWaitMillis) {
                waitForConfirmation(evaluated, 12)
            }
        } else if (attemptsLeft > 0) {
            postGuarded(reservationWaitMillis) { clickAccept(evaluated, findBoltRoot() ?: detail, attemptsLeft - 1) }
        } else {
            finishReservation(false, "botão Aceitar não encontrado", ride.candidate)
        }
    }

    private fun waitForConfirmation(evaluated: EvaluatedRide, attemptsLeft: Int) {
        val ride = evaluated.hit
        AutomationStateStore.transition(service, AutomationPhase.CONFIRMING, "Confirmar ${ride.candidate.historyId}")
        val confirmation = findBoltRoot()
        if (confirmation != null && recoverError(confirmation)) {
            finishReservation(false, "erro antes da confirmação", ride.candidate)
            return
        }
        // Nesta etapa a Bolt ainda está a pedir confirmação. Nunca interpretar
        // texto residual da tela como sucesso antes de Confirmar ser clicado.
        if (confirmation != null && clickAction(confirmation, "Confirmar", CONFIRM_ACTION_ID)) {
            DiagnosticLogger.log("Botão Confirmar pressionado")
            postGuarded(reservationWaitMillis) {
                waitForReservationResult(evaluated, 12)
            }
            return
        }
        if (attemptsLeft > 0) {
            postGuarded(reservationWaitMillis) { waitForConfirmation(evaluated, attemptsLeft - 1) }
        } else {
            finishReservation(false, "botão Confirmar não encontrado", ride.candidate)
        }
    }

    private fun waitForReservationResult(evaluated: EvaluatedRide, attemptsLeft: Int) {
        val ride = evaluated.hit
        AutomationStateStore.transition(service, AutomationPhase.CLOSING_RESULT, "A aguardar resultado")
        val result = findBoltRoot()
        if (result != null && recoverError(result)) {
            finishReservation(false, "conflito ou erro após confirmar", ride.candidate)
            return
        }
        if (result != null && isReservationConfirmed(result)) {
            waitBeforeClosingAcceptedResult(evaluated, 8)
            return
        }
        if (attemptsLeft > 0) {
            postGuarded(reservationWaitMillis) { waitForReservationResult(evaluated, attemptsLeft - 1) }
        } else {
            finishReservation(false, "confirmação não identificada", ride.candidate)
        }
    }

    private fun waitBeforeClosingAcceptedResult(evaluated: EvaluatedRide, attemptsLeft: Int) {
        postGuarded(reservationWaitMillis) {
            closeAcceptedResult(evaluated, attemptsLeft)
        }
    }

    private fun closeAcceptedResult(evaluated: EvaluatedRide, attemptsLeft: Int) {
        val ride = evaluated.hit
        val result = findBoltRoot()
        if (result != null && isReservationConfirmed(result)) {
            if (closeResultDialog(result, "Pedido aceite")) {
                ReservationNotifications.reservation(service, ride.candidate)
                finishReservation(true, "reserva confirmada", ride.candidate)
            } else if (attemptsLeft > 0) {
                DiagnosticLogger.log("Resultado aceite ainda sem botão Fechar; nova tentativa em ${reservationWaitMillis}ms")
                postGuarded(reservationWaitMillis) {
                    closeAcceptedResult(evaluated, attemptsLeft - 1)
                }
            } else {
                // O resultado já foi confirmado, mas a Bolt não expôs o botão.
                // Voltar evita ficar preso no detalhe e conserva o aceite real.
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                ReservationNotifications.reservation(service, ride.candidate)
                finishReservation(true, "reserva confirmada; Fechar não exposto", ride.candidate)
            }
            return
        }
        if (attemptsLeft > 0) {
            DiagnosticLogger.log("Resultado aceite ainda a carregar; nova leitura em ${reservationWaitMillis}ms")
            postGuarded(reservationWaitMillis) {
                closeAcceptedResult(evaluated, attemptsLeft - 1)
            }
        } else {
            finishReservation(false, "resultado aceite desapareceu antes de Fechar", ride.candidate)
        }
    }

    private fun isRideDetailsScreen(root: AccessibilityNodeInfo): Boolean {
        if (AccessibilityNodeUtils.findText(root, "Aceitar") != null) return true

        // A lista também contém origem/destino. Portanto, rota sozinha não
        // prova que o cartão abriu. O detalhe da Bolt não apresenta os tabs
        // scheduled/accepted e possui o título da oferta (id=title).
        val hasListTabs = AccessibilityNodeUtils.findById(root, BoltScreenReader.SCHEDULED_SEGMENT_ID).isNotEmpty() ||
            AccessibilityNodeUtils.findById(root, BoltScreenReader.ACCEPTED_SEGMENT_ID).isNotEmpty()
        if (hasListTabs) return false
        val title = AccessibilityNodeUtils.findFirstById(root, BoltScreenReader.PEDIDOS_TITLE_ID)
            ?.let(AccessibilityNodeUtils::textOf)
            .orEmpty()
        val normalizedTitle = AccessibilityNodeUtils.normalizeForComparison(title)
        val listTitle = normalizedTitle == "pedidos" ||
            normalizedTitle == "pedidos de agendamento" ||
            normalizedTitle == "aceites"
        val route = RideParser.extractRoute(AccessibilityNodeUtils.leafTexts(root), title)
        return !listTitle && title.isNotBlank() && route.first.isNotBlank() && route.second.isNotBlank()
    }

    /**
     * Estados transitórios em que a busca deve continuar: detalhe da viagem,
     * confirmação, resultado aceite e diálogo de erro/conflito.
     */
    private fun isReservationUi(root: AccessibilityNodeInfo?): Boolean {
        if (root == null || root.packageName?.toString() != AutomationContract.BOLT_PACKAGE) return false
        val dialogTitle = AccessibilityNodeUtils.findFirstById(root, DIALOG_TITLE_ID)
            ?.let(AccessibilityNodeUtils::textOf)
            .orEmpty()
        if (dialogTitle.isNotBlank()) return true
        val hasConfirmAction = AccessibilityNodeUtils.findById(root, CONFIRM_ACTION_ID).any {
            AccessibilityNodeUtils.normalizeForComparison(AccessibilityNodeUtils.textOf(it)) == "confirmar"
        }
        return hasConfirmAction || isRideDetailsScreen(root)
    }

    private fun finishReservation(success: Boolean, reason: String, candidate: RideCandidate? = null) {
        DiagnosticLogger.log("Tentativa de reserva: sucesso=$success ($reason)")
        if (success) {
            AutomationStateStore.recordAccepted(service)
        } else {
            AutomationStateStore.recordRejected(service)
            AutomationStateStore.transition(service, AutomationPhase.ERROR, reason, reason)
        }
        candidate?.let {
            RideHistoryStore.updateOutcome(service, it.historyId, success, reason)
        }
        if (!searchActive) {
            reservationInProgress = false
            return
        }

        noEligibleRideSince = System.currentTimeMillis()
        // Mantém reservationInProgress=true durante o regresso. Assim, o
        // evento "pedidos=false" do detalhe nunca encerra a busca antes de a
        // árvore voltar realmente a scheduledSegment.
        postGuarded(100L) { returnToScheduledAndResume(6) }
    }

    private fun returnToScheduledAndResume(attemptsLeft: Int) {
        if (!searchActive) {
            reservationInProgress = false
            return
        }
        val current = findBoltRoot()
        if (current == null) {
            reservationInProgress = false
            stopSearch("Bolt deixou de estar em primeiro plano durante a reserva")
            return
        }
        val screen = BoltScreenReader.read(current)
        if (screen.isPedidos) {
            reservationInProgress = false
            AutomationStateStore.transition(
                service,
                AutomationPhase.SEARCHING,
                "scheduledSegment disponível"
            )
            DiagnosticLogger.log("Regresso a Pedidos após fechar resultado")
            scheduleScan(POLL_INTERVAL_MILLIS)
            return
        }

        val scheduled = AccessibilityNodeUtils.findFirstById(current, BoltScreenReader.SCHEDULED_SEGMENT_ID)
        if (scheduled != null && AccessibilityNodeUtils.click(scheduled)) {
            DiagnosticLogger.log("scheduledSegment clicado no regresso da reserva")
        } else {
            // Em detalhe/resultado, scheduledSegment não está na árvore. O
            // voltar do serviço reproduz o Fechar da macro e regressa à lista.
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            DiagnosticLogger.log("Regresso por voltar: scheduledSegment ainda não disponível")
        }
        if (attemptsLeft > 0) {
            postGuarded(250L) { returnToScheduledAndResume(attemptsLeft - 1) }
        } else {
            reservationInProgress = false
            DiagnosticLogger.log("Não foi possível confirmar o regresso a Pedidos")
            scheduleScan(RETRY_INTERVAL_MILLIS)
        }
    }

    private fun clickAction(root: AccessibilityNodeInfo, label: String): Boolean =
        clickAction(root, label, *emptyArray())

    private fun clickAction(root: AccessibilityNodeInfo, label: String, vararg preferredIds: String): Boolean {
        val node = preferredIds.asSequence()
            .flatMap { id -> AccessibilityNodeUtils.findById(root, id).asSequence() }
            .firstOrNull { AccessibilityNodeUtils.normalizeForComparison(AccessibilityNodeUtils.textOf(it)) == AccessibilityNodeUtils.normalizeForComparison(label) }
            ?: AccessibilityNodeUtils.findText(root, label)
        val clicked = clickNodeOrGesture(node)
        DiagnosticLogger.log("Clique de ação '$label': $clicked")
        return clicked
    }

    private fun clickNodeOrGesture(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (AccessibilityNodeUtils.click(node)) return true
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun clickRideCard(node: AccessibilityNodeInfo): Boolean {
        if (clickNodeOrGesture(node)) return true
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val path = Path().apply {
            moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        val dispatched = service.dispatchGesture(gesture, null, null)
        DiagnosticLogger.log(
            "Clique de cartão por gesto: enviado=$dispatched, centro=${bounds.centerX()},${bounds.centerY()}"
        )
        return dispatched
    }

    private fun captureOpenedRideRoute(root: AccessibilityNodeInfo, evaluated: EvaluatedRide) {
        val route = RideParser.extractRoute(
            AccessibilityNodeUtils.leafTexts(root),
            AccessibilityNodeUtils.fullText(root)
        )
        if (route.first.isBlank() || route.second.isBlank()) {
            DiagnosticLogger.log("Moradas não encontradas na viagem aberta; não foi usado o texto do diálogo")
            return
        }
        val enriched = evaluated.hit.candidate.copy(origin = route.first, destination = route.second)
        RideHistoryStore.record(
            service,
            enriched,
            evaluated.evaluation,
            evaluated.pickupDistanceKm,
            simulated = false
        )
        DiagnosticLogger.log("Moradas capturadas na viagem aberta: origem=${route.first}, destino=${route.second}")
    }

    private fun recoverError(root: AccessibilityNodeInfo): Boolean {
        val text = AccessibilityNodeUtils.normalizeForComparison(AccessibilityNodeUtils.fullText(root))
        val isError = ERROR_MARKERS.any(text::contains)
        if (!isError) return false
        DiagnosticLogger.log("Erro detetado: $text")
        if (closeResultDialog(root, "erro")) {
            DiagnosticLogger.log("Diálogo de erro fechado pelo botão Fechar")
        } else {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            DiagnosticLogger.log("Botão Fechar não encontrado no erro; usado voltar")
        }
        return true
    }

    private fun closeResultDialog(root: AccessibilityNodeInfo, result: String): Boolean {
        val closed = clickAction(root, "Fechar") || clickAction(root, "OK") || clickAction(root, "Ok")
        if (closed) DiagnosticLogger.log("Diálogo '$result' fechado")
        return closed
    }

    private fun isReservationConfirmed(root: AccessibilityNodeInfo): Boolean {
        val text = AccessibilityNodeUtils.normalizeForComparison(AccessibilityNodeUtils.fullText(root))
        return CONFIRMATION_MARKERS.any(text::contains)
    }

    private fun publishScreenState() {
        val screen = BoltScreenReader.read(findBoltRoot())
        publishScreenState(screen.isBoltVisible, screen.isPedidos)
    }

    private fun publishScreenState(boltVisible: Boolean, pedidosVisible: Boolean) {
        AutomationStateStore.publishScreen(service, boltVisible, pedidosVisible)
    }

    private fun postGuarded(delayMillis: Long, action: () -> Unit) {
        val expectedGeneration = generation
        handler.postDelayed({ if (expectedGeneration == generation) action() }, delayMillis.coerceAtLeast(0L))
    }

    /**
     * O floating control também pode gerar eventos de acessibilidade. Não se deve
     * parar a busca por causa desse evento transitório; confirma-se a janela após
     * uma pequena tolerância e só então se considera que a Bolt saiu do primeiro plano.
     */
    private fun scheduleForegroundCheck(eventPackage: String?) {
        if (!searchActive) return
        handler.removeCallbacks(foregroundCheck)
        val delay = if (eventPackage == service.packageName) 800L else 350L
        handler.postDelayed(foregroundCheck, delay)
        DiagnosticLogger.log("Evento fora da Bolt recebido: package=$eventPackage; confirmação em ${delay}ms")
    }

    private fun verifyBoltForeground() {
        if (!searchActive) return
        val root = findBoltRoot()
        val screen = BoltScreenReader.read(root)
        if (!screen.isBoltVisible) {
            stopSearch("Bolt deixou de estar em primeiro plano")
            publishScreenState(false, false)
            return
        }
        publishScreenState(true, screen.isPedidos)
        if (!screen.isPedidos && !reservationInProgress && !refreshInProgress) {
            stopSearch("saiu do separador Pedidos")
        } else if (!reservationInProgress && !refreshInProgress) {
            scheduleScan(80L)
        }
    }

    /** Usa a janela ativa; permite atravessar a janela não focável do overlay. */
    private fun findBoltRoot(): AccessibilityNodeInfo? {
        val direct = service.rootInActiveWindow
        if (BoltScreenReader.read(direct).isBoltVisible) return direct
        return runCatching {
            service.windows.asSequence()
                .filter { it.isActive || it.isFocused }
                .mapNotNull { it.root }
                .firstOrNull { BoltScreenReader.read(it).isBoltVisible }
        }.getOrNull()
    }

    companion object {
        private val ERROR_MARKERS = listOf(
            "ocorreu um erro", "nao foi possivel", "conflito", "ja tem uma viagem",
            "sem ligacao", "nao ha ligacao", "indisponivel", "pedido aceite"
        ).filterNot { it == "pedido aceite" }
        private val CONFIRMATION_MARKERS = listOf(
            "pedido aceite", "pedido aceito", "viagem reservada", "viagem agendada", "reservado"
        )
        private const val CONFIRM_ACTION_ID = "ee.mtakso.driver:id/actionText"
        private const val DIALOG_TITLE_ID = "ee.mtakso.driver:id/dialog_title"
        private const val ATTEMPT_COOLDOWN_MILLIS = 30_000L
        private const val POLL_INTERVAL_MILLIS = 100L
        private const val RETRY_INTERVAL_MILLIS = 500L
        private const val LICENSE_CHECK_INTERVAL_MILLIS = 30_000L
        private const val HISTORY_CAPTURE_INTERVAL_MILLIS = 250L
        private const val WATCHDOG_INTERVAL_MILLIS = 2500L
    }
}

data class EvaluatedRide(
    val hit: RideHit,
    val evaluation: RideEvaluation,
    val pickupDistanceKm: Double?
)

