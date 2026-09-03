package com.example.cameraseguranca.service

import android.Manifest
import android.animation.ValueAnimator
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import com.example.cameraseguranca.PanicRecordingActivity
import com.daniel.tvdeinsight.R
import com.example.cameraseguranca.data.SettingsRepository
import com.example.cameraseguranca.data.TriggerMode
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
 * Exibe o controlo circular flutuante. Quando as permissões já foram concedidas, o
 * gesto configurado inicia ou finaliza a foreground service. A gravação continua
 * sempre identificada pela notificação e pelos indicadores do Android.
 *
 * Um arraste normal reposiciona o botão. Ao soltar, ele é encaixado por inteiro na
 * borda esquerda ou direita mais próxima e sua altura é salva para a próxima abertura.
 */
class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var positionPreferences: SharedPreferences
    private var rootView: FrameLayout? = null
    private lateinit var bubble: ImageButton
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var pulseAnimator: ValueAnimator? = null
    private var baseVisualScale = 1f

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var triggerMode = TriggerMode.TRIPLE_TAP
    private var audioEnabled = false
    private var recordingActive = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var isDragging = false
    private var doubleTapSwipeCandidate = false
    private var gestureConfirmed = false
    private var lastTapUpAt = 0L
    private var consecutiveTapCount = 0

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == RecordingService.ACTION_RECORDING_STATE_CHANGED) {
                recordingActive = intent.getBooleanExtra(
                    RecordingService.EXTRA_RECORDING_ACTIVE,
                    false
                )
                renderBubble()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        positionPreferences = getSharedPreferences(POSITION_PREFERENCES, MODE_PRIVATE)
        recordingActive = getSharedPreferences(
            RecordingService.RECORDING_STATE_PREFERENCES,
            MODE_PRIVATE
        ).getBoolean(RecordingService.KEY_RECORDING_ACTIVE, false)
        ContextCompat.registerReceiver(
            this,
            recordingStateReceiver,
            IntentFilter(RecordingService.ACTION_RECORDING_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // A alteração feita na tela de Configurações passa a valer sem reiniciar o atalho.
        serviceScope.launch {
            SettingsRepository(applicationContext).settings.collectLatest { settings ->
                triggerMode = settings.triggerMode
                audioEnabled = settings.audioEnabled
                clearTapSequence()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (rootView == null) addBubble()
        // O atalho é um auxílio visual; o Android ainda pode encerrar este serviço.
        return START_NOT_STICKY
    }

    private fun addBubble() {
        // A composição segue o floating de Reservas: área de toque de 56 dp,
        // botão interno de 52 dp e aro circular que não corta o PNG da marca.
        val root = FrameLayout(this)
        val control = ImageButton(this).apply {
            setPadding(CONTROL_INSET_DP.dp, CONTROL_INSET_DP.dp, CONTROL_INSET_DP.dp, CONTROL_INSET_DP.dp)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setOnTouchListener(::onBubbleTouch)
        }
        root.addView(
            control,
            FrameLayout.LayoutParams(CONTROL_BUTTON_SIZE_DP.dp, CONTROL_BUTTON_SIZE_DP.dp).apply {
                gravity = Gravity.CENTER
            }
        )
        bubble = control

        layoutParams = WindowManager.LayoutParams(
            CONTROL_SIZE_DP.dp,
            CONTROL_SIZE_DP.dp,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            // START/TOP torna x e y coordenadas previsíveis para o encaixe nas bordas.
            gravity = Gravity.TOP or Gravity.START
        }
        restorePersistedPosition()
        renderBubble()

        runCatching { windowManager.addView(root, layoutParams) }
            .onFailure { stopSelf() }
            .onSuccess { rootView = root }
    }

    /** Mantém os mesmos estados visuais do floating circular das Reservas Bolt. */
    private fun renderBubble() {
        if (!::bubble.isInitialized) return
        bubble.apply {
            setImageResource(if (recordingActive) R.drawable.tvdeinsight_on else R.drawable.tvdeinsight_off)
            // A arte desligada tem uma margem externa maior. Esta correção mantém
            // o mesmo diâmetro percebido do estado verde de Reservas.
            baseVisualScale = if (recordingActive) 1.0f else 1.10f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (recordingActive) COLOR_ACTIVE else COLOR_IDLE)
                setStroke(
                    CONTROL_STROKE_DP.dp,
                    if (recordingActive) COLOR_ACTIVE_STROKE else COLOR_IDLE_STROKE
                )
            }
            contentDescription = if (recordingActive) "Parar gravação" else "Iniciar gravação"
        }
        startPulse()
    }

    /** Pulsação discreta e contínua, exatamente como o controlo das Reservas. */
    private fun startPulse() {
        pulseAnimator?.cancel()
        if (!::bubble.isInitialized) return
        pulseAnimator = ValueAnimator.ofFloat(0.94f, 1.0f).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                bubble.scaleX = baseVisualScale * scale
                bubble.scaleY = baseVisualScale * scale
            }
            start()
        }
    }

    private fun onBubbleTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onPointerDown(event)
            MotionEvent.ACTION_MOVE -> onPointerMove(event)
            MotionEvent.ACTION_UP -> onPointerUp(event)
            MotionEvent.ACTION_CANCEL -> onPointerCancelled()
        }
        return true
    }

    private fun onPointerDown(event: MotionEvent) {
        downRawX = event.rawX
        downRawY = event.rawY
        downWindowX = layoutParams.x
        downWindowY = layoutParams.y
        isDragging = false
        gestureConfirmed = false

        val isQuickSecondTap = event.eventTime - lastTapUpAt <= MULTI_TAP_WINDOW_MS
        doubleTapSwipeCandidate = triggerMode == TriggerMode.DOUBLE_TAP_AND_SWIPE && isQuickSecondTap
    }

    private fun onPointerMove(event: MotionEvent) {
        val deltaX = event.rawX - downRawX
        val deltaY = event.rawY - downRawY
        val movedEnough = abs(deltaX) >= MOVE_THRESHOLD_DP.dp || abs(deltaY) >= MOVE_THRESHOLD_DP.dp

        if (!movedEnough || gestureConfirmed) return

        if (doubleTapSwipeCandidate) {
            val isHorizontalSwipe = abs(deltaX) >= SWIPE_THRESHOLD_DP.dp && abs(deltaX) > abs(deltaY)
            if (isHorizontalSwipe) {
                gestureConfirmed = true
                doubleTapSwipeCandidate = false
                bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                handleConfirmedGesture()
                clearTapSequence()
            }
            // No modo "2 toques + arrastar", o segundo toque reserva o arraste horizontal
            // para a confirmação. Um arraste comum continua possível com um único toque.
            return
        }

        isDragging = true
        clearTapSequence()
        moveBubbleTo(
            x = downWindowX + deltaX.roundToInt(),
            y = downWindowY + deltaY.roundToInt()
        )
    }

    private fun onPointerUp(event: MotionEvent) {
        if (isDragging) {
            snapToNearestEdge()
        } else if (!gestureConfirmed) {
            registerTap(event.eventTime)
        }

        isDragging = false
        doubleTapSwipeCandidate = false
        gestureConfirmed = false
    }

    private fun onPointerCancelled() {
        if (isDragging) snapToNearestEdge()
        isDragging = false
        doubleTapSwipeCandidate = false
        gestureConfirmed = false
    }

    private fun registerTap(eventTime: Long) {
        when (triggerMode) {
            TriggerMode.TRIPLE_TAP -> {
                consecutiveTapCount = if (eventTime - lastTapUpAt <= MULTI_TAP_WINDOW_MS) {
                    consecutiveTapCount + 1
                } else {
                    1
                }
                lastTapUpAt = eventTime

                if (consecutiveTapCount >= REQUIRED_TAP_COUNT) {
                    bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    handleConfirmedGesture()
                    clearTapSequence()
                }
            }

            TriggerMode.DOUBLE_TAP_AND_SWIPE -> {
                // O primeiro toque arma uma janela curta. O segundo só confirma ao ser
                // seguido por um arraste horizontal de distância suficiente.
                lastTapUpAt = if (doubleTapSwipeCandidate) 0L else eventTime
                consecutiveTapCount = 0
            }
        }
    }

    private fun moveBubbleTo(x: Int, y: Int) {
        val bounds = overlayBounds()
        layoutParams.x = x.coerceIn(0, bounds.maxX)
        layoutParams.y = y.coerceIn(bounds.minY, bounds.maxY)
        updateBubbleLayout()
    }

    private fun snapToNearestEdge() {
        val bounds = overlayBounds()
        val isCloserToLeft = layoutParams.x + CONTROL_SIZE_DP.dp / 2 <= bounds.maxX / 2
        layoutParams.x = if (isCloserToLeft) 0 else bounds.maxX
        layoutParams.y = layoutParams.y.coerceIn(bounds.minY, bounds.maxY)
        updateBubbleLayout()
        persistPosition(bounds)
    }

    private fun restorePersistedPosition() {
        val bounds = overlayBounds()
        val isOnLeftEdge = positionPreferences.getBoolean(KEY_EDGE_LEFT, false)
        val verticalRatio = positionPreferences.getFloat(KEY_VERTICAL_RATIO, DEFAULT_VERTICAL_RATIO)
            .coerceIn(0f, 1f)
        layoutParams.x = if (isOnLeftEdge) 0 else bounds.maxX
        layoutParams.y = bounds.minY + ((bounds.maxY - bounds.minY) * verticalRatio).roundToInt()
    }

    private fun persistPosition(bounds: OverlayBounds) {
        val verticalRange = bounds.maxY - bounds.minY
        val verticalRatio = if (verticalRange == 0) {
            DEFAULT_VERTICAL_RATIO
        } else {
            ((layoutParams.y - bounds.minY).toFloat() / verticalRange).coerceIn(0f, 1f)
        }
        positionPreferences.edit()
            .putBoolean(KEY_EDGE_LEFT, layoutParams.x == 0)
            .putFloat(KEY_VERTICAL_RATIO, verticalRatio)
            .apply()
    }

    private fun overlayBounds(): OverlayBounds {
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
        }

        val size = CONTROL_SIZE_DP.dp
        val minY = VERTICAL_INSET_DP.dp
        val maxX = (width - size).coerceAtLeast(0)
        val maxY = (height - size - VERTICAL_INSET_DP.dp).coerceAtLeast(minY)
        return OverlayBounds(maxX = maxX, minY = minY, maxY = maxY)
    }

    private fun updateBubbleLayout() {
        rootView?.takeIf { it.isAttachedToWindow }?.let { root ->
            runCatching { windowManager.updateViewLayout(root, layoutParams) }
        }
    }

    private fun clearTapSequence() {
        lastTapUpAt = 0L
        consecutiveTapCount = 0
    }

    private fun handleConfirmedGesture() {
        if (recordingActive) {
            // Parar finaliza o MP4 e libera a câmera. O receptor existente controla
            // a sessão já ativa, sem iniciar uma nova captura pelo overlay.
            sendBroadcast(
                Intent(RecordingService.ACTION_STOP)
                    .setPackage(packageName)
            )
        } else {
            beginRecordingFromConfiguredGesture()
        }
    }

    private fun beginRecordingFromConfiguredGesture() {
        serviceScope.launch {
            // Lê a configuração atual antes de verificar o microfone, evitando usar um
            // valor antigo quando o controle é acionado logo após abrir o app.
            audioEnabled = SettingsRepository(applicationContext).settings.first().audioEnabled
            if (hasRecordingPermissions()) {
                startRecordingDirectly()
            } else {
                openPermissionsScreen()
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
            Toast.makeText(
                this,
                "O Android não permitiu iniciar a gravação. Abra o app e verifique as permissões.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openPermissionsScreen() {
        startActivity(
            Intent(this, PanicRecordingActivity::class.java)
                .putExtra(PanicRecordingActivity.EXTRA_PERMISSION_ONLY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::bubble.isInitialized) snapToNearestEdge()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        pulseAnimator?.cancel()
        pulseAnimator = null
        runCatching { unregisterReceiver(recordingStateReceiver) }
        rootView?.let { root -> runCatching { windowManager.removeViewImmediate(root) } }
        rootView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private data class OverlayBounds(
        val maxX: Int,
        val minY: Int,
        val maxY: Int
    )

    private companion object {
        const val CONTROL_SIZE_DP = 56
        const val CONTROL_BUTTON_SIZE_DP = 52
        const val CONTROL_INSET_DP = 4
        const val CONTROL_STROKE_DP = 2
        const val VERTICAL_INSET_DP = 24
        const val MOVE_THRESHOLD_DP = 8
        const val SWIPE_THRESHOLD_DP = 72
        const val MULTI_TAP_WINDOW_MS = 350L
        const val REQUIRED_TAP_COUNT = 3
        const val PULSE_DURATION_MS = 1200L
        val COLOR_ACTIVE = 0xFF2E7D32.toInt()
        val COLOR_ACTIVE_STROKE = 0xFF81C784.toInt()
        val COLOR_IDLE = 0xFFC62828.toInt()
        val COLOR_IDLE_STROKE = 0xFFFF8A80.toInt()
        const val POSITION_PREFERENCES = "overlay_position"
        const val KEY_EDGE_LEFT = "edge_left"
        const val KEY_VERTICAL_RATIO = "vertical_ratio"
        const val DEFAULT_VERTICAL_RATIO = 0.5f
    }
}
