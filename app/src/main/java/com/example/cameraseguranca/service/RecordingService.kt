package com.example.cameraseguranca.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.effects.Frame
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.cameraseguranca.CameraSafetyDependencies
import com.example.cameraseguranca.PanicRecordingActivity
import com.daniel.tvdeinsight.R
import com.example.cameraseguranca.data.CameraLens
import com.example.cameraseguranca.data.RecordingSettings
import com.example.cameraseguranca.data.RecordingStorage
import com.example.cameraseguranca.data.RollingRecordingFinalizer
import com.example.cameraseguranca.data.VideoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Usa VideoCapture sem Preview. A falta de preview não remove a notificação nem o
 * indicador de câmera do sistema: ambos continuam obrigatórios e intencionais.
 */
class RecordingService : LifecycleService() {
    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor by lazy { ContextCompat.getMainExecutor(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var watermarkEffect: OverlayEffect? = null
    private var activeSettings: RecordingSettings? = null

    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    @Volatile private var latestLocation: Location? = null
    @Volatile private var latestLocationElapsed = 0L

    private val movingBrandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        // Marca deliberadamente sutil: cerca de 15% de opacidade.
        color = Color.argb(38, 255, 255, 255)
        setShadowLayer(2f, 1f, 1f, Color.argb(28, 0, 0, 0))
    }
    private val footerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.WHITE
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    private val footerPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(78, 5, 12, 28)
    }

    private val telemetryLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestLocation = Location(location)
            latestLocationElapsed = SystemClock.elapsedRealtime()
        }
    }

    private var sessionActive = false
    private var sessionStarting = false
    private var sessionClosing = false
    private var sessionPaused = false
    private var isForeground = false
    private var segmentIndex = 0
    private var currentRecordingToken = 0L
    private var shouldRotateSegment = false
    private var currentChunkFile: java.io.File? = null
    private val sessionChunks = ArrayDeque<java.io.File>()
    private var stopRequested = false
    private var finalizationInProgress = false

    private val overlayControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_STOP -> stopSession()
                ACTION_TOGGLE_PAUSE -> togglePause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        publishRecordingState(active = false)
        ContextCompat.registerReceiver(
            this,
            overlayControlReceiver,
            IntentFilter().apply {
                addAction(ACTION_STOP)
                addAction(ACTION_TOGGLE_PAUSE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> beginRecording()
            ACTION_STOP -> stopSession()
            ACTION_TOGGLE_PAUSE -> togglePause()
        }
        // O Android não deve reiniciar uma gravação sem outra ação explícita do usuário.
        return START_NOT_STICKY
    }

    private fun beginRecording() {
        if (sessionActive || sessionStarting || sessionClosing) return
        if (!hasCameraPermission()) {
            fail("A permissão de câmera não foi concedida.")
            return
        }
        if (!hasPreciseLocationPermission()) {
            fail("A permissão de localização precisa é necessária para a marca d’água GPS.")
            return
        }

        sessionStarting = true
        serviceScope.launch {
            runCatching {
                CameraSafetyDependencies.settingsRepository(applicationContext).settings.first()
            }.onSuccess { settings ->
                if (settings.audioEnabled && !hasAudioPermission()) {
                    fail("A gravação com áudio exige a permissão de microfone.")
                    return@onSuccess
                }
                if (enterForeground(audioEnabled = settings.audioEnabled)) {
                    startSession(settings)
                }
            }.onFailure { error ->
                fail("Não foi possível carregar as configurações de gravação.", error)
            }
        }
    }

    private fun startSession(settings: RecordingSettings) {
        if (sessionClosing) return
        activeSettings = settings
        sessionActive = true
        sessionStarting = false
        sessionPaused = false
        stopRequested = false
        finalizationInProgress = false
        sessionChunks.clear()
        currentChunkFile = null
        publishRecordingState(active = true, paused = false)
        segmentIndex = 0
        // A gravação circular não termina sozinha. O valor escolhido em
        // "Looping" define quantos minutos recentes permanecem.
        // Podar logo no arranque também limpa excedentes deixados por uma
        // sessão anterior, antes de criar o primeiro segmento novo.
        pruneCircularBufferAsync()

        val future = ProcessCameraProvider.getInstance(applicationContext)
        future.addListener({
            val provider = try {
                future.get()
            } catch (error: Exception) {
                fail("Não foi possível inicializar a câmera.", error)
                return@addListener
            }

            if (!sessionActive) {
                provider.unbindAll()
                closeSession()
                return@addListener
            }

            try {
                val selector = when (settings.lens) {
                    CameraLens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                    CameraLens.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                }
                check(provider.hasCamera(selector)) {
                    "A lente selecionada não está disponível neste aparelho."
                }

                provider.unbindAll()
                val recorder = Recorder.Builder()
                    .setQualitySelector(settings.quality.toQualitySelector())
                    .build()
                val capture = VideoCapture.Builder(recorder)
                    // A gravação é sempre entregue em portrait. A rotação efetiva
                    // continua disponível no Frame para posicionar a marca d’água
                    // com a mesma orientação do MP4 final.
                    .setTargetRotation(Surface.ROTATION_0)
                    .setTargetFrameRate(Range(settings.fps.value, settings.fps.value))
                    .build()

                cameraProvider = provider
                videoCapture = capture
                watermarkEffect = createWatermarkEffect()
                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(capture)
                    .addEffect(requireNotNull(watermarkEffect))
                    .build()
                provider.bindToLifecycle(this, selector, useCaseGroup)
                startLocationUpdates()
                startNextSegment()
            } catch (error: Exception) {
                fail("Não foi possível abrir a câmera.", error)
            }
        }, cameraExecutor)
    }

    private fun createWatermarkEffect(): OverlayEffect {
        return OverlayEffect(
            CameraEffect.VIDEO_CAPTURE,
            0,
            handler,
            { error -> Log.e(TAG, "Falha ao renderizar a marca d’água.", error) }
        ).also { effect ->
            effect.setOnDrawListener { frame ->
                runCatching {
                    drawWatermark(frame)
                    true
                }.getOrElse { error ->
                    Log.e(TAG, "Falha ao desenhar a marca d’água.", error)
                    false
                }
            }
        }
    }

    /**
     * O buffer da câmara pode chegar em landscape mesmo quando o MP4 final é portrait.
     * O CameraX recorta, roda e só depois espelha o quadro; desenhamos no recorte efetivo
     * com a transformação inversa para a marca ficar horizontal no ficheiro final.
     */
    private fun drawWatermark(frame: Frame) {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val crop = frame.cropRect
        val sourceWidth = crop.width().toFloat()
        val sourceHeight = crop.height().toFloat()
        val rotation = ((frame.rotationDegrees % 360) + 360) % 360
        val outputWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
        val outputHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight

        canvas.save()
        // Só a área do crop chega ao utilizador; o rodapé não pode ser desenhado fora dela.
        canvas.clipRect(crop)
        canvas.translate(crop.left.toFloat(), crop.top.toFloat())
        when (rotation) {
            90 -> {
                canvas.translate(0f, sourceHeight)
                canvas.rotate(-90f)
            }
            180 -> {
                canvas.translate(sourceWidth, sourceHeight)
                canvas.rotate(180f)
            }
            270 -> {
                canvas.translate(sourceWidth, 0f)
                canvas.rotate(90f)
            }
        }
        // O espelhamento acontece depois da rotação no pipeline CameraX. Aplicar a
        // inversa aqui mantém data, velocidade e GPS legíveis na câmara frontal.
        if (frame.isMirroring) {
            canvas.translate(outputWidth, 0f)
            canvas.scale(-1f, 1f)
        }
        drawWatermarkInOutputOrientation(canvas, outputWidth, outputHeight)
        canvas.restore()
    }

    private fun drawWatermarkInOutputOrientation(canvas: Canvas, width: Float, height: Float) {

        val shortSide = min(width, height).toFloat()
        val margin = max(12f, shortSide * 0.03f)
        val footerTop = drawFooter(canvas, width, height, shortSide, margin)

        movingBrandPaint.textSize = max(24f, shortSide * 0.065f)
        val brandWidth = movingBrandPaint.measureText(BRAND_TEXT)
        val brandHeight = movingBrandPaint.fontMetrics.descent - movingBrandPaint.fontMetrics.ascent
        val phase = SystemClock.elapsedRealtime() / 4_500f
        val xProgress = ((sin(phase) + 1f) / 2f)
        val yProgress = ((sin(phase * 0.73f + 1.2f) + 1f) / 2f)
        val maxX = max(margin, width - brandWidth - margin)
        val maxY = max(margin, footerTop - brandHeight - margin)
        val brandX = margin + (maxX - margin) * xProgress
        val brandY = margin - movingBrandPaint.fontMetrics.ascent + (maxY - margin) * yProgress
        canvas.drawText(BRAND_TEXT, brandX, brandY, movingBrandPaint)
    }

    /** Desenha dados completos no rodapé; cada bloco inteiro muda de linha se não couber. */
    private fun drawFooter(
        canvas: Canvas,
        width: Float,
        height: Float,
        shortSide: Float,
        margin: Float
    ): Float {
        val footerGroups = footerGroups()
        val availableWidth = width - margin * 2f
        var textSize = max(13f, shortSide * 0.027f)
        footerTextPaint.textSize = textSize
        var lines = footerLines(footerGroups, availableWidth)
        // Reduz somente o tamanho da fonte: nunca parte data, GPS ou velocidade ao meio.
        while (
            (lines.size > 2 || lines.any { footerTextPaint.measureText(it) > availableWidth }) &&
                textSize > MIN_FOOTER_TEXT_SIZE
        ) {
            textSize -= 1f
            footerTextPaint.textSize = textSize
            lines = footerLines(footerGroups, availableWidth)
        }

        val padding = textSize * 0.46f
        val lineHeight = textSize * 1.30f
        val panelHeight = lines.size * lineHeight + padding * 2f
        val panelTop = height - margin - panelHeight

        canvas.drawRoundRect(
            margin,
            panelTop,
            width - margin,
            height - margin,
            textSize * 0.34f,
            textSize * 0.34f,
            footerPanelPaint
        )
        val baselineStart = panelTop + padding - footerTextPaint.fontMetrics.ascent
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, margin + padding, baselineStart + index * lineHeight, footerTextPaint)
        }
        return panelTop
    }

    private fun footerGroups(): List<String> {
        val timestamp = SimpleDateFormat(
            "dd/MM/yyyy HH:mm:ss",
            Locale.getDefault()
        ).format(Date())
        val location = latestLocation
        if (location == null || SystemClock.elapsedRealtime() - latestLocationElapsed > LOCATION_STALE_MS) {
            return listOf(
                "DATA/HORA: $timestamp",
                "GPS: aguardando sinal",
                "VEL: aguardando sinal"
            )
        }

        val coordinates = String.format(Locale.US, "%.6f, %.6f", location.latitude, location.longitude)
        val speed = if (location.hasSpeed()) {
            String.format(Locale.US, "%.1f km/h", location.speed * 3.6f)
        } else {
            "indisponível"
        }
        return listOf(
            "DATA/HORA: $timestamp",
            "GPS: $coordinates",
            "VEL: $speed"
        )
    }

    /** Mantém cada informação inteira e escolhe a melhor distribuição em até duas linhas. */
    private fun footerLines(groups: List<String>, availableWidth: Float): List<String> {
        val (dateTime, gps, speed) = groups
        fun joinIfFits(first: String, second: String): String? {
            val joined = "$first$FOOTER_SEPARATOR$second"
            return joined.takeIf { footerTextPaint.measureText(it) <= availableWidth }
        }

        val allInOneLine = "$dateTime$FOOTER_SEPARATOR$gps$FOOTER_SEPARATOR$speed"
        if (footerTextPaint.measureText(allInOneLine) <= availableWidth) return listOf(allInOneLine)
        // Prioriza DATA/HORA + velocidade juntos; GPS inteiro ocupa a segunda linha.
        joinIfFits(dateTime, speed)?.let { return listOf(it, gps) }
        // Em telas estreitas, tenta DATA/HORA e GPS juntos, mantendo velocidade inteira abaixo.
        joinIfFits(dateTime, gps)?.let { return listOf(it, speed) }
        // O laço de redução da fonte garante que esta opção caiba antes do desenho.
        return listOf(dateTime, gps, speed)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasPreciseLocationPermission()) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false) }
        providers.forEach { provider ->
            try {
                locationManager.getLastKnownLocation(provider)?.let(telemetryLocationListener::onLocationChanged)
                locationManager.requestLocationUpdates(
                    provider,
                    LOCATION_UPDATE_INTERVAL_MS,
                    0f,
                    telemetryLocationListener,
                    Looper.getMainLooper()
                )
            } catch (error: SecurityException) {
                // A permissão pode ser revogada enquanto a gravação decorre.
                Log.w(TAG, "Permissão de localização revogada para $provider.", error)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Não foi possível solicitar localização de $provider.", error)
            }
        }
    }

    private fun stopLocationUpdates() {
        runCatching { locationManager.removeUpdates(telemetryLocationListener) }
    }

    @SuppressLint("MissingPermission")
    private fun startNextSegment() {
        val settings = activeSettings ?: return closeSession()
        val capture = videoCapture ?: return fail("VideoCapture indisponível.")

        // Segmentos de um minuto tornam a janela precisa sem criar milhares de
        // ficheiros; a poda mantém a janela escolhida mais o segmento atual.
        val durationForFile = CIRCULAR_SEGMENT_MILLIS
        shouldRotateSegment = true

        val outputFile = try {
            // A limpeza também roda antes de uma sessão, usando a preferência escolhida.
            RecordingStorage.deleteExpired(this, settings.autoDeleteInterval)
            RecordingStorage.newOutputFile(this, settings.storageLocation, segmentIndex++)
        } catch (error: Exception) {
            fail("Não foi possível preparar o armazenamento privado.", error)
            return
        }
        currentChunkFile = outputFile
        val outputBuilder = FileOutputOptions.Builder(outputFile)
        outputBuilder.setDurationLimitMillis(durationForFile)

        val token = ++currentRecordingToken
        recording = try {
            val pendingRecording = capture.output
                .prepareRecording(this, outputBuilder.build())
                .let { pending ->
                    if (settings.audioEnabled && hasAudioPermission()) pending.withAudioEnabled() else pending
                }
            pendingRecording
                .start(cameraExecutor) { event ->
                    if (token != currentRecordingToken) return@start
                    when (event) {
                        is VideoRecordEvent.Pause -> {
                            sessionPaused = true
                            publishRecordingState(active = true, paused = true)
                        }

                        is VideoRecordEvent.Resume -> {
                            sessionPaused = false
                            publishRecordingState(active = true, paused = false)
                        }

                        is VideoRecordEvent.Finalize -> onRecordingFinalized(event)
                    }
                }
        } catch (error: Exception) {
            fail("Não foi possível iniciar a gravação.", error)
            null
        }
    }

    private fun onRecordingFinalized(event: VideoRecordEvent.Finalize) {
        recording = null
        sessionPaused = false
        currentChunkFile?.takeIf { it.isFile && it.length() > 0L }?.let(::registerChunk)
        currentChunkFile = null
        val rotate = shouldRotateSegment
        shouldRotateSegment = false
        val durationLimitReached = event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
        val canContinue = sessionActive && rotate && durationLimitReached

        if (canContinue) {
            pruneCircularBufferAsync()
            startNextSegment()
            return
        }

        if (event.hasError() && !durationLimitReached) {
            Log.e(TAG, "Finalização da gravação com erro=${event.error}", event.cause)
        } else {
            Log.i(TAG, "Vídeo salvo em ${event.outputResults.outputUri}")
        }
        if (stopRequested && !event.hasError()) {
            finalizeSessionAsync()
        } else {
            closeSession()
        }
    }

    private fun togglePause() {
        if (!sessionActive || sessionClosing) return
        val activeRecording = recording ?: return
        sessionPaused = !sessionPaused
        publishRecordingState(active = true, paused = sessionPaused)
        if (sessionPaused) {
            activeRecording.pause()
        } else {
            activeRecording.resume()
        }
    }

    private fun stopSession() {
        if (sessionClosing) return
        sessionStarting = false
        stopRequested = true
        sessionActive = false
        sessionPaused = false
        publishRecordingState(active = false, paused = false)
        shouldRotateSegment = false
        // stop() finaliza corretamente o MP4 antes de liberar a câmera.
        recording?.stop() ?: finalizeSessionAsync()
    }

    private fun registerChunk(file: java.io.File) {
        sessionChunks.addLast(file)
        val settings = activeSettings ?: return
        val maxChunks = ceil(
            circularWindowMillis(settings).toDouble() / CIRCULAR_SEGMENT_MILLIS
        ).toInt().coerceAtLeast(1) + 1
        while (sessionChunks.size > maxChunks) {
            sessionChunks.removeFirst().delete()
        }
    }

    private fun finalizeSessionAsync() {
        if (finalizationInProgress) return
        val settings = activeSettings ?: return closeSession()
        val chunks = sessionChunks.toList()
        if (chunks.isEmpty()) return closeSession()

        finalizationInProgress = true
        serviceScope.launch {
            runCatching {
                val finalFile = RecordingStorage.newFinalOutputFile(
                    this@RecordingService,
                    settings.storageLocation
                )
                RollingRecordingFinalizer(this@RecordingService).createFinalMp4(
                    chunks = chunks,
                    windowMs = circularWindowMillis(settings),
                    outputFile = finalFile,
                    includeAudio = settings.audioEnabled
                )
            }.onSuccess { finalFile ->
                chunks.forEach { chunk ->
                    if (chunk.absolutePath != finalFile.absolutePath) chunk.delete()
                }
                Log.i(TAG, "Gravação circular finalizada em ${finalFile.absolutePath}")
            }.onFailure { error ->
                Log.e(TAG, "Não foi possível montar a gravação final.", error)
            }
            finalizationInProgress = false
            closeSession()
        }
    }

    private fun pruneCircularBufferAsync() {
        val settings = activeSettings ?: return
        val maxSegments = ceil(
            circularWindowMillis(settings).toDouble() / CIRCULAR_SEGMENT_MILLIS
        ).toInt().coerceAtLeast(1) + 1
        serviceScope.launch(Dispatchers.IO) {
            val removed = runCatching {
                RecordingStorage.pruneCircularSegments(
                    context = this@RecordingService,
                    requestedLocation = settings.storageLocation,
                    maxSegments = maxSegments
                )
            }.getOrElse { error ->
                Log.w(TAG, "Não foi possível podar a janela circular.", error)
                0
            }
            if (removed > 0) {
                Log.i(TAG, "Looping: $removed segmento(s) temporário(s) antigo(s) removido(s)")
            }
        }
    }

    private fun circularWindowMillis(settings: RecordingSettings): Long =
        settings.segment.milliseconds ?: DEFAULT_CIRCULAR_WINDOW_MILLIS

    private fun enterForeground(audioEnabled: Boolean): Boolean = try {
        val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            if (audioEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            serviceTypes
        )
        isForeground = true
        true
    } catch (error: SecurityException) {
        fail("O Android bloqueou o serviço de câmera. Inicie-o pela tela visível do app.", error)
        false
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_camera_protection)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Serviço de gravação",
            NotificationManager.IMPORTANCE_NONE
        ).apply {
            description = "Mantém a gravação em execução sem apresentar notificações."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasPreciseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun fail(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
        closeSession()
    }

    /** Permite que o overlay pause/retome sem acessar a câmera diretamente. */
    private fun publishRecordingState(active: Boolean, paused: Boolean = sessionPaused) {
        getSharedPreferences(RECORDING_STATE_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RECORDING_ACTIVE, active)
            .putBoolean(KEY_RECORDING_PAUSED, paused)
            .apply()
        sendBroadcast(
            Intent(ACTION_RECORDING_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_RECORDING_ACTIVE, active)
                .putExtra(EXTRA_RECORDING_PAUSED, paused)
        )
        if (isForeground) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun closeSession() {
        if (sessionClosing) return
        sessionClosing = true
        sessionStarting = false
        sessionActive = false
        sessionPaused = false
        publishRecordingState(active = false, paused = false)
        ++currentRecordingToken

        stopLocationUpdates()
        runCatching { cameraProvider?.unbindAll() }
        runCatching { watermarkEffect?.close() }
        cameraProvider = null
        videoCapture = null
        recording = null
        watermarkEffect = null
        activeSettings = null

        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        sessionPaused = false
        publishRecordingState(active = false, paused = false)
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(overlayControlReceiver) }
        recording?.stop()
        stopLocationUpdates()
        runCatching { cameraProvider?.unbindAll() }
        runCatching { watermarkEffect?.close() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun VideoQuality.toQualitySelector(): QualitySelector {
        val ordered = when (this) {
            VideoQuality.LOW -> listOf(Quality.SD)
            VideoQuality.MEDIUM -> listOf(Quality.HD, Quality.SD)
            VideoQuality.HIGH -> listOf(Quality.FHD, Quality.HD, Quality.SD)
            VideoQuality.VERY_HIGH -> listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
        }
        return QualitySelector.fromOrderedList(
            ordered,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
    }

    companion object {
        private const val TAG = "RecordingService"
        // O canal anterior já pode existir no dispositivo com prioridade baixa.
        // Um ID novo garante que a alteração para IMPORTANCE_NONE é aplicada.
        private const val CHANNEL_ID = "active_recording_hidden_v2"
        private const val NOTIFICATION_ID = 7001
        private const val BRAND_TEXT = "TVDE Insight"
        private const val FOOTER_SEPARATOR = "   •   "
        private const val MIN_FOOTER_TEXT_SIZE = 8f

        const val ACTION_START = "com.example.cameraseguranca.START"
        const val ACTION_STOP = "com.example.cameraseguranca.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.example.cameraseguranca.TOGGLE_PAUSE"
        const val ACTION_RECORDING_STATE_CHANGED =
            "com.example.cameraseguranca.RECORDING_STATE_CHANGED"
        const val EXTRA_RECORDING_ACTIVE = "extra_recording_active"
        const val EXTRA_RECORDING_PAUSED = "extra_recording_paused"
        const val RECORDING_STATE_PREFERENCES = "recording_state"
        const val KEY_RECORDING_ACTIVE = "recording_active"
        const val KEY_RECORDING_PAUSED = "recording_paused"
        const val LOCATION_UPDATE_INTERVAL_MS = 1_000L
        const val LOCATION_STALE_MS = 15_000L
        const val CIRCULAR_SEGMENT_MILLIS = 60_000L
        const val DEFAULT_CIRCULAR_WINDOW_MILLIS = 5 * 60_000L
    }
}
