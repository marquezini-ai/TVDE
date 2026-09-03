package com.daniel.tvdeinsight.reservations

import android.Manifest
import android.animation.ValueAnimator
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.daniel.tvdeinsight.R
import com.example.cameraseguranca.PanicRecordingActivity
import com.example.cameraseguranca.data.SettingsRepository
import com.example.cameraseguranca.data.TriggerMode
import com.example.cameraseguranca.service.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Única superfície flutuante da aplicação.
 *
 * Reservas e Gravação participam do mesmo botão PNG, na mesma posição e com a
 * mesma pulsação. Um toque simples conserva a automação da Bolt; os gestos
 * configurados de Gravação alternam a captura sem criar um segundo overlay.
 */
class FloatingControlService : Service() {
    private lateinit var windowManager: WindowManager
    private val handler = Handler(android.os.Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var rootView: FrameLayout? = null
    private var button: ImageButton? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenProbePending = false
    private var probeTimeout: Runnable? = null
    private var pendingReservationClick: Runnable? = null
    private var pulseAnimator: ValueAnimator? = null
    private var baseVisualScale = 1f
    private var recordingActive = false
    private var triggerMode = TriggerMode.TRIPLE_TAP
    private var audioEnabled = false
    private var lastTapUpAt = 0L
    private var consecutiveTapCount = 0

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AutomationContract.ACTION_OVERLAY_STATE -> renderState()
                AutomationContract.ACTION_SCREEN_STATE -> handleScreenState(intent)
                RecordingService.ACTION_RECORDING_STATE_CHANGED -> {
                    recordingActive = intent.getBooleanExtra(
                        RecordingService.EXTRA_RECORDING_ACTIVE,
                        false
                    )
                    renderState()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.log("FloatingControlService criado")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        recordingActive = getSharedPreferences(
            RecordingService.RECORDING_STATE_PREFERENCES,
            MODE_PRIVATE
        ).getBoolean(RecordingService.KEY_RECORDING_ACTIVE, false)
        registerStateReceiver()
        observeRecordingSettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AppPreferences.isAnyOverlayVisible(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        showOverlay()
        renderState()
        // O controlo flutuante não é uma tarefa em curso: mantê-lo como serviço
        // foreground faria o Android apresentar uma notificação logo ao ligar a
        // opção “Gravação”. A gravação real continua no seu próprio foreground
        // service quando for iniciada pelo gesto configurado.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DiagnosticLogger.log("FloatingControlService destruído")
        probeTimeout?.let(handler::removeCallbacks)
        pendingReservationClick?.let(handler::removeCallbacks)
        serviceScope.cancel()
        pulseAnimator?.cancel()
        pulseAnimator = null
        sendBroadcast(AutomationContract.commandIntent(this, AutomationContract.ACTION_STOP_SEARCH))
        runCatching { unregisterReceiver(stateReceiver) }
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        AppPreferences.setSearching(this, false)
        // Quando o processo é encerrado pelo sistema, conserva a intenção de
        // manter o floating para que START_STICKY o possa reconstruir. O botão
        // da app já grava false antes de pedir a paragem manual.
        if (!AppPreferences.isAnyOverlayVisible(this)) {
            AppPreferences.setOverlayVisible(this, false)
        } else {
            DiagnosticLogger.log("Floating destruído pelo sistema; overlay marcado para recuperação")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun registerStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(AutomationContract.ACTION_OVERLAY_STATE)
            addAction(AutomationContract.ACTION_SCREEN_STATE)
            addAction(RecordingService.ACTION_RECORDING_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun showOverlay() {
        if (rootView != null) return
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            DiagnosticLogger.log("Permissão de overlay ausente")
            stopSelf()
            return
        }
        // Mantém o mesmo diâmetro visual do floating circular da Uber (56 dp).
        // A margem interna conserva o toque e impede que o aro seja cortado.
        val size = dp(CONTROL_SIZE_DP)
        val buttonSize = dp(CONTROL_BUTTON_SIZE_DP)
        val root = FrameLayout(this)
        val control = ImageButton(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setOnTouchListener(DragTouchListener())
        }
        root.addView(control, FrameLayout.LayoutParams(buttonSize, buttonSize).apply { gravity = Gravity.CENTER })
        val saved = AppPreferences.getOverlayPosition(this)
        val windowParams = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = saved?.first ?: dp(16)
            y = saved?.second ?: dp(220)
        }
        clamp(windowParams, size, size)
        runCatching { windowManager.addView(root, windowParams) }
            .onFailure { DiagnosticLogger.log("Falha a criar overlay", it); stopSelf() }
            .onSuccess {
                rootView = root
                button = control
                params = windowParams
                renderState()
            }
    }

    private fun renderState() {
        val searching = AppPreferences.isSearching(this)
        val active = searching || recordingActive
        button?.apply {
            // Os ficheiros fornecidos definem os estados visuais: off/vermelho e on/verde.
            setImageResource(if (active) R.drawable.tvdeinsight_on else R.drawable.tvdeinsight_off)
            // A arte vermelha tem uma margem externa maior; esta escala iguala-a ao estado verde.
            baseVisualScale = if (active) 1.0f else 1.10f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (active) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
                setStroke(dp(2), if (active) 0xFF81C784.toInt() else 0xFFFF8A80.toInt())
            }
            contentDescription = when {
                searching && recordingActive -> "Busca e gravação ativas"
                recordingActive -> "Gravação ativa"
                searching -> "Pausar procura"
                AppPreferences.isRecordingOverlayVisible(this@FloatingControlService) ->
                    "Controlo de gravação"
                else -> "Iniciar procura"
            }
        }
        startPulse()
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        val target = button ?: return
        pulseAnimator = ValueAnimator.ofFloat(0.94f, 1.0f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                target.scaleX = baseVisualScale * scale
                target.scaleY = baseVisualScale * scale
            }
            start()
        }
    }

    private fun observeRecordingSettings() {
        serviceScope.launch {
            SettingsRepository(applicationContext).settings.collectLatest { settings ->
                triggerMode = settings.triggerMode
                audioEnabled = settings.audioEnabled
                clearRecordingGesture()
            }
        }
    }

    /**
     * Um toque simples só tem efeito depois de a tela Pedidos da Bolt ser
     * confirmada. Quando Gravação está armada, aguardamos a janela curta do
     * gesto para não iniciar a procura no primeiro toque de um triplo toque.
     */
    private fun onTap(eventTime: Long) {
        if (!AppPreferences.isRecordingOverlayVisible(this)) {
            onReservationClick()
            return
        }

        when (triggerMode) {
            TriggerMode.TRIPLE_TAP -> {
                consecutiveTapCount = if (eventTime - lastTapUpAt <= MULTI_TAP_WINDOW_MS) {
                    consecutiveTapCount + 1
                } else {
                    1
                }
                lastTapUpAt = eventTime
                if (consecutiveTapCount >= REQUIRED_TAP_COUNT) {
                    cancelPendingReservationClick()
                    button?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    handleRecordingGesture()
                    clearRecordingGesture()
                } else {
                    scheduleReservationClick()
                }
            }

            TriggerMode.DOUBLE_TAP_AND_SWIPE -> {
                // O primeiro toque apenas arma o gesto e, se Reservas estiver
                // ativa, deixa o clique simples pronto para o fim da janela.
                lastTapUpAt = eventTime
                consecutiveTapCount = 0
                scheduleReservationClick()
            }
        }
    }

    private fun scheduleReservationClick() {
        if (!AppPreferences.isOverlayVisible(this)) return
        cancelPendingReservationClick()
        pendingReservationClick = Runnable {
            pendingReservationClick = null
            onReservationClick()
        }.also { handler.postDelayed(it, MULTI_TAP_WINDOW_MS) }
    }

    private fun cancelPendingReservationClick() {
        pendingReservationClick?.let(handler::removeCallbacks)
        pendingReservationClick = null
    }

    private fun clearRecordingGesture() {
        lastTapUpAt = 0L
        consecutiveTapCount = 0
    }

    private fun handleRecordingGesture() {
        if (recordingActive) {
            sendBroadcast(
                Intent(RecordingService.ACTION_STOP).setPackage(packageName)
            )
            return
        }

        serviceScope.launch {
            // A configuração pode ter acabado de ser alterada. Lê-la novamente
            // antes de validar o microfone para não iniciar com um estado antigo.
            audioEnabled = SettingsRepository(applicationContext).settings.first().audioEnabled
            if (hasRecordingPermissions()) {
                startRecordingDirectly()
            } else {
                openRecordingPermissions()
            }
        }
    }

    private fun hasRecordingPermissions(): Boolean {
        val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        return hasPermission(Manifest.permission.CAMERA) &&
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            (!audioEnabled || hasPermission(Manifest.permission.RECORD_AUDIO)) &&
            notificationsAllowed
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startRecordingDirectly() {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
            )
        }.onFailure {
            toast("O Android não permitiu iniciar a gravação. Abra o app e verifique as permissões.")
        }
    }

    private fun openRecordingPermissions() {
        startActivity(
            Intent(this, PanicRecordingActivity::class.java)
                .putExtra(PanicRecordingActivity.EXTRA_PERMISSION_ONLY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun onReservationClick() {
        if (!AppPreferences.isOverlayVisible(this)) return
        // Um clique fora de Bolt > Pedidos é intencionalmente silencioso. Mesmo
        // para pausar uma procura ativa, a confirmação da tela vem primeiro.
        DiagnosticLogger.log("Toque simples; a confirmar se Bolt/Pedidos está visível")
        screenProbePending = true
        sendBroadcast(AutomationContract.commandIntent(this, AutomationContract.ACTION_PROBE_SCREEN))
        probeTimeout?.let(handler::removeCallbacks)
        probeTimeout = Runnable {
            if (screenProbePending) {
                screenProbePending = false
                DiagnosticLogger.log("Toque simples ignorado: sem confirmação de Bolt/Pedidos")
            }
        }
        handler.postDelayed(probeTimeout!!, 1200L)
    }

    private fun handleScreenState(intent: Intent) {
        if (!screenProbePending) return
        screenProbePending = false
        probeTimeout?.let(handler::removeCallbacks)
        val bolt = intent.getBooleanExtra(AutomationContract.EXTRA_BOLT_VISIBLE, false)
        val pedidos = intent.getBooleanExtra(AutomationContract.EXTRA_PEDIDOS_VISIBLE, false)
        if (bolt && pedidos) {
            if (AppPreferences.isSearching(this)) {
                DiagnosticLogger.log("Probe confirmou Bolt/Pedidos; a pausar procura")
                AppPreferences.setSearching(this, false)
                sendBroadcast(AutomationContract.commandIntent(this, AutomationContract.ACTION_STOP_SEARCH))
                toast("Procura pausada.")
            } else {
                DiagnosticLogger.log("Probe confirmou Bolt/Pedidos; a iniciar procura")
                sendBroadcast(AutomationContract.commandIntent(this, AutomationContract.ACTION_START_SEARCH))
            }
        } else {
            DiagnosticLogger.log("Toque simples ignorado fora de Bolt/Pedidos: bolt=$bolt, pedidos=$pedidos")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun clamp(value: WindowManager.LayoutParams, width: Int, height: Int) {
        val metrics = resources.displayMetrics
        value.x = value.x.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        value.y = value.y.coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class DragTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var doubleTapSwipeCandidate = false
        private var gestureConfirmed = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val current = params ?: return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startX = current.x; startY = current.y
                    moved = false
                    gestureConfirmed = false
                    doubleTapSwipeCandidate = AppPreferences.isRecordingOverlayVisible(this@FloatingControlService) &&
                        triggerMode == TriggerMode.DOUBLE_TAP_AND_SWIPE &&
                        event.eventTime - lastTapUpAt <= MULTI_TAP_WINDOW_MS
                    if (doubleTapSwipeCandidate) cancelPendingReservationClick()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    val movedEnough = abs(dx) > dp(MOVE_THRESHOLD_DP) || abs(dy) > dp(MOVE_THRESHOLD_DP)
                    if (!movedEnough || gestureConfirmed) return true

                    if (doubleTapSwipeCandidate) {
                        val horizontalSwipe = abs(dx) >= dp(SWIPE_THRESHOLD_DP) && abs(dx) > abs(dy)
                        if (horizontalSwipe) {
                            gestureConfirmed = true
                            doubleTapSwipeCandidate = false
                            cancelPendingReservationClick()
                            button?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            handleRecordingGesture()
                            clearRecordingGesture()
                        }
                        // No modo de dois toques + arrastar, o segundo toque não
                        // reposiciona o botão enquanto aguarda o gesto horizontal.
                        return true
                    }

                    moved = true
                    clearRecordingGesture()
                    cancelPendingReservationClick()
                    current.x = startX + dx; current.y = startY + dy
                    clamp(current, rootView?.width ?: dp(CONTROL_SIZE_DP), rootView?.height ?: dp(CONTROL_SIZE_DP))
                    runCatching { rootView?.let { windowManager.updateViewLayout(it, current) } }
                }
                MotionEvent.ACTION_UP -> {
                    val width = rootView?.width ?: dp(92)
                    val height = rootView?.height ?: dp(92)
                    clamp(current, width, height)
                    val maxX = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
                    current.x = if (current.x <= maxX / 2) 0 else maxX
                    AppPreferences.saveOverlayPosition(this@FloatingControlService, current.x, current.y)
                    runCatching { rootView?.let { windowManager.updateViewLayout(it, current) } }
                    if (!moved && !gestureConfirmed) {
                        if (doubleTapSwipeCandidate) {
                            // Um segundo toque sem o arraste não é um gesto e
                            // também não deve reiniciar a procura acidentalmente.
                            clearRecordingGesture()
                        } else {
                            onTap(event.eventTime)
                        }
                    }
                    moved = false
                    doubleTapSwipeCandidate = false
                    gestureConfirmed = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        clamp(current, rootView?.width ?: dp(CONTROL_SIZE_DP), rootView?.height ?: dp(CONTROL_SIZE_DP))
                        runCatching { rootView?.let { windowManager.updateViewLayout(it, current) } }
                    }
                    moved = false
                    doubleTapSwipeCandidate = false
                    gestureConfirmed = false
                }
            }
            return true
        }
    }

    companion object {
        const val CONTROL_SIZE_DP = 56
        const val CONTROL_BUTTON_SIZE_DP = 52
        const val MOVE_THRESHOLD_DP = 6
        const val SWIPE_THRESHOLD_DP = 72
        const val MULTI_TAP_WINDOW_MS = 350L
        const val REQUIRED_TAP_COUNT = 3

        /** Inicia ou remove o único serviço conforme as duas funcionalidades. */
        fun sync(context: Context) {
            val appContext = context.applicationContext
            val shouldShow = AppPreferences.isAnyOverlayVisible(appContext) &&
                Settings.canDrawOverlays(appContext)
            if (!shouldShow) {
                appContext.stopService(Intent(appContext, FloatingControlService::class.java))
                return
            }
            val intent = Intent(appContext, FloatingControlService::class.java)
            // O floating é iniciado pela escolha explícita do utilizador dentro
            // da app. Não é um foreground service, para não criar notificação.
            appContext.startService(intent)
        }
    }
}
