package com.daniel.tvdeinsight

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.daniel.tvdeinsight.domain.model.RuleSettings
import com.daniel.tvdeinsight.license.LicenseManager
import com.daniel.tvdeinsight.logging.AppLogger
import com.daniel.tvdeinsight.service.accessibility.UberOfferAccessibilityService
import com.daniel.tvdeinsight.service.notification.BoltOfferNotificationListener
import com.daniel.tvdeinsight.ui.TvdeInsightApp
import com.daniel.tvdeinsight.ui.screens.MainViewModel
import com.daniel.tvdeinsight.worker.BackendSyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var licenseManager: LicenseManager
    private lateinit var serviceManager: MainServiceManager
    private var startupPermissionsSettled = false
    private var overlayPermissionRequestInFlight = false
    private var boltNotificationAccessRequestInFlight = false
    private val requestForegroundLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        AppLogger.info("Permissões de primeiro uso: localização=$granted, câmera=$cameraGranted")
        if (granted) requestBackgroundLocationPermissionIfNeeded()
        else requestBatteryOptimizationPermissionIfNeeded()
    }
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AppLogger.info("Permissão de notificações: concedida=$granted")
        requestLocationPermissionForHistoryIfNeeded()
    }
    private val requestBackgroundLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AppLogger.info("Permissão de localização em segundo plano: concedida=$granted")
        requestBatteryOptimizationPermissionIfNeeded()
    }
    private val requestBatteryOptimizationSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val powerManager = getSystemService(PowerManager::class.java)
        val granted = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        AppLogger.info("Resultado da otimização de bateria: desativada=$granted")
        requestBoltNotificationAccessIfNeeded()
    }
    private val createLogDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) {
            AppLogger.info("Exportação do log cancelada pelo utilizador")
        } else {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val exported = AppLogger.exportTo(uri)
                AppLogger.info("Exportação do log concluída: sucesso=$exported")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.info("MainActivity criada")
        serviceManager = MainServiceManager(this, ::syncTripsWhenMonitoringStarts)
        startFirstUsePermissionFlowIfNeeded()
        restoreBackendSync()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.settings, licenseManager.state) { settings, license ->
                    settings to license
                }.collect { (settings, license) ->
                    if (settings.isAppRunning && (BuildConfig.IS_ADMIN_APP || license.isValid)) {
                        serviceManager.iniciarMonitorizacao(settings)
                    } else {
                        serviceManager.pararMonitorizacao()
                    }
                }
            }
        }

        setContent {
            TvdeInsightApp()
        }
    }

    override fun onResume() {
        super.onResume()
        if (boltNotificationAccessRequestInFlight) {
            boltNotificationAccessRequestInFlight = false
            val enabled = isBoltNotificationListenerEnabled()
            AppLogger.info("Resultado do acesso às notificações Bolt: autorizado=$enabled")
            if (!enabled) {
                Toast.makeText(
                    this,
                    "Sem acesso às notificações, as ofertas Bolt não serão acionadas.",
                    Toast.LENGTH_LONG
                ).show()
            }
            markStartupPermissionsSettled()
        }
        if (overlayPermissionRequestInFlight) {
            overlayPermissionRequestInFlight = false
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permissão de sobreposição concedida.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Sem esta permissão, os cards não podem aparecer sobre Uber e Bolt.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        requestOverlayPermissionOnFirstInstallIfNeeded()
    }

    fun startLogDownload() {
        AppLogger.info("Utilizador solicitou baixar o log")
        createLogDocument.launch("tvde-insight-${System.currentTimeMillis()}.log")
    }

    private fun syncTripsWhenMonitoringStarts() {
        AppLogger.info("Monitorização iniciada: sincronização backend colocada na fila")
        BackendSyncScheduler.enqueueImmediate(this)
    }

    /**
     * Mantém o histórico já existente sincronizado, mesmo quando a monitorização
     * de ofertas ainda não foi iniciada nesta abertura da aplicação.
     */
    private fun restoreBackendSync() {
        BackendSyncScheduler.schedule(this)
        AppLogger.info("Backend: sincronização de recuperação colocada na fila com requisito de Internet")
        BackendSyncScheduler.enqueueImmediate(this)
    }

    /**
     * As ofertas são detetadas enquanto Uber/Bolt estão visíveis. Por isso a
     * localização precisa de autorização contínua para ser guardada no histórico.
     * A funcionalidade continua a operar caso o utilizador não a autorize.
     */
    /**
     * O Android só mostra um pedido runtime de cada vez.  A sequência evita
     * que POST_NOTIFICATIONS fique pendente até à próxima abertura da app.
     */
    private fun startFirstUsePermissionFlowIfNeeded() {
        val preferences = getPreferences(MODE_PRIVATE)
        if (preferences.getBoolean(KEY_FIRST_USE_PERMISSION_FLOW_FINISHED, false)) {
            // O acesso ao NotificationListenerService pode ser revogado pelo
            // Android/Samsung após uma atualização ou otimização de bateria.
            // Nesse caso, voltar a abrir a definição é necessário para que a
            // Bolt continue a ter o seu gatilho único por notificação.
            if (!isBoltNotificationListenerEnabled()) {
                requestBoltNotificationAccessIfNeeded()
                return
            }
            requestOverlayPermissionOnFirstInstallIfNeeded()
            return
        }
        requestNotificationPermissionIfNeeded()
    }

    private fun requestLocationPermissionForHistoryIfNeeded() {
        val missing = buildList {
            if (!hasForegroundLocationPermission()) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.CAMERA)
        }
        if (missing.isNotEmpty()) {
            requestForegroundLocationPermission.launch(
                missing.toTypedArray()
            )
        } else {
            requestBackgroundLocationPermissionIfNeeded()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestLocationPermissionForHistoryIfNeeded()
        }
    }

    private fun requestBackgroundLocationPermissionIfNeeded() {
        if (hasBackgroundLocationPermission()) {
            requestBatteryOptimizationPermissionIfNeeded()
            return
        }
        requestBackgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun hasForegroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requestBatteryOptimizationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            requestBoltNotificationAccessIfNeeded()
            return
        }
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager?.isIgnoringBatteryOptimizations(packageName) == true) {
            AppLogger.info("Otimização de bateria já desativada para a aplicação")
            requestBoltNotificationAccessIfNeeded()
            return
        }

        AppLogger.info("Solicitando ao utilizador a desativação da otimização de bateria")
        try {
            requestBatteryOptimizationSettings.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: ActivityNotFoundException) {
            AppLogger.warn("Pedido direto de bateria indisponível; abrindo definições gerais de bateria")
            try {
                requestBatteryOptimizationSettings.launch(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            } catch (error: ActivityNotFoundException) {
                AppLogger.error("Não foi possível abrir as definições de otimização de bateria", error)
                requestBoltNotificationAccessIfNeeded()
            }
        } catch (error: SecurityException) {
            AppLogger.error("O sistema recusou o pedido de otimização de bateria", error)
            requestBoltNotificationAccessIfNeeded()
        }
    }

    /**
     * O gatilho Bolt depende do acesso especial às notificações. A permissão
     * POST_NOTIFICATIONS apenas controla notificações próprias da aplicação e
     * não ativa um NotificationListenerService.
     */
    private fun requestBoltNotificationAccessIfNeeded() {
        if (isBoltNotificationListenerEnabled()) {
            AppLogger.info("Listener de notificações Bolt já autorizado")
            markStartupPermissionsSettled()
            return
        }

        if (boltNotificationAccessRequestInFlight) return
        boltNotificationAccessRequestInFlight = true
        AppLogger.info("Primeiro uso: a solicitar acesso às notificações para acionar ofertas Bolt")
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (error: Exception) {
            boltNotificationAccessRequestInFlight = false
            AppLogger.warn("Não foi possível abrir o acesso às notificações Bolt", error)
            markStartupPermissionsSettled()
        }
    }

    private fun isBoltNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        val expected = ComponentName(this, BoltOfferNotificationListener::class.java).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /**
     * A autorização de sobreposição é uma definição especial do Android (não
     * é um diálogo runtime). É pedida uma vez na primeira instalação, depois
     * da sequência inicial de permissões, para que os cards nunca fiquem
     * silenciosamente invisíveis.
     */
    private fun requestOverlayPermissionOnFirstInstallIfNeeded() {
        if (!startupPermissionsSettled || overlayPermissionRequestInFlight) return
        val preferences = getPreferences(MODE_PRIVATE)
        if (Settings.canDrawOverlays(this)) {
            requestAccessibilityPermissionOnFirstUseIfNeeded()
            return
        }
        if (preferences.getBoolean(KEY_OVERLAY_PERMISSION_PROMPTED, false)) {
            requestAccessibilityPermissionOnFirstUseIfNeeded()
            return
        }

        preferences.edit().putBoolean(KEY_OVERLAY_PERMISSION_PROMPTED, true).apply()
        overlayPermissionRequestInFlight = true
        AppLogger.info("Primeira instalação: a solicitar permissão para mostrar cards sobre outras aplicações")
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (error: Exception) {
            overlayPermissionRequestInFlight = false
            AppLogger.warn("Não foi possível abrir a permissão de sobreposição", error)
            Toast.makeText(
                this,
                "Ative manualmente 'Aparecer sobre outras aplicações' para ver os cards.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun markStartupPermissionsSettled() {
        startupPermissionsSettled = true
        getPreferences(MODE_PRIVATE).edit()
            .putBoolean(KEY_FIRST_USE_PERMISSION_FLOW_FINISHED, true)
            .apply()
        requestOverlayPermissionOnFirstInstallIfNeeded()
    }

    /** A acessibilidade é uma definição especial: abre-se uma única vez na configuração inicial. */
    private fun requestAccessibilityPermissionOnFirstUseIfNeeded() {
        val preferences = getPreferences(MODE_PRIVATE)
        if (preferences.getBoolean(KEY_ACCESSIBILITY_PERMISSION_PROMPTED, false)) return
        if (MainServiceManager.isAccessibilityServiceEnabled(this, UberOfferAccessibilityService::class.java)) {
            preferences.edit().putBoolean(KEY_ACCESSIBILITY_PERMISSION_PROMPTED, true).apply()
            return
        }
        preferences.edit().putBoolean(KEY_ACCESSIBILITY_PERMISSION_PROMPTED, true).apply()
        AppLogger.info("Primeiro uso: a solicitar ativação do serviço de acessibilidade")
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { AppLogger.warn("Não foi possível abrir as definições de acessibilidade", it) }
    }

    fun shareCompleteLogViaWhatsApp() {
        AppLogger.info("Utilizador solicitou envio do log completo pelo WhatsApp")
        lifecycleScope.launch(Dispatchers.IO) {
            val shareFile = AppLogger.createShareFile()
            withContext(Dispatchers.Main) {
                if (shareFile == null) {
                    Toast.makeText(this@MainActivity, "Não foi possível preparar o log.", Toast.LENGTH_LONG).show()
                    return@withContext
                }
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "$packageName.fileprovider",
                    shareFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    `package` = WHATSAPP_PACKAGE
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "Log completo da aplicação TVDE Insight")
                    putExtra("jid", "$SUPPORT_WHATSAPP_NUMBER@s.whatsapp.net")
                    clipData = ClipData.newRawUri("Log TVDE Insight", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(shareIntent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "WhatsApp não está instalado.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    class MainServiceManager(
        private val activity: ComponentActivity,
        private val onMonitoringStarted: () -> Unit
    ) {
        private var monitoringActive = false

        fun iniciarMonitorizacao(settings: RuleSettings) {
            if (!monitoringActive) {
                monitoringActive = true
                onMonitoringStarted()
            }
            AppLogger.info("Monitorização solicitada: ativo=${settings.isAppRunning}, uber=${settings.isUberEnabled}, bolt=${settings.isBoltEnabled}")
            if ((settings.isUberEnabled || settings.isBoltEnabled) &&
                !isAccessibilityServiceEnabled(activity, UberOfferAccessibilityService::class.java)
            ) {
                AppLogger.warn("Serviço de acessibilidade ausente; abrindo definições do Android")
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        fun pararMonitorizacao() {
            if (monitoringActive) {
                monitoringActive = false
                AppLogger.info("Monitorização parada")
            }
        }

        companion object {
        fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
            val expectedServiceName = "${context.packageName}/${service.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                if (splitter.next().equals(expectedServiceName, ignoreCase = true)) return true
            }
            return false
        }
        }
    }

    private companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        const val SUPPORT_WHATSAPP_NUMBER = "351912521498"
        const val KEY_OVERLAY_PERMISSION_PROMPTED = "overlay_permission_prompted"
        const val KEY_ACCESSIBILITY_PERMISSION_PROMPTED = "accessibility_permission_prompted"
        const val KEY_FIRST_USE_PERMISSION_FLOW_FINISHED = "first_use_permission_flow_finished"
    }
}
