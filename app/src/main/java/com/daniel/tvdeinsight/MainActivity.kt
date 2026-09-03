package com.daniel.tvdeinsight

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.ClipData
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
import com.daniel.tvdeinsight.data.repository.TripSyncRepository
import com.daniel.tvdeinsight.license.LicenseManager
import com.daniel.tvdeinsight.logging.AppLogger
import com.daniel.tvdeinsight.service.accessibility.UberOfferAccessibilityService
import com.daniel.tvdeinsight.ui.TvdeInsightApp
import com.daniel.tvdeinsight.ui.screens.MainViewModel
import com.daniel.tvdeinsight.worker.SheetsSyncScheduler
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
    @Inject lateinit var tripSyncRepository: TripSyncRepository
    private lateinit var serviceManager: MainServiceManager
    private var startupPermissionsSettled = false
    private var overlayPermissionRequestInFlight = false
    private val requestForegroundLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        AppLogger.info("Permissão de localização em primeiro plano: concedida=$granted")
        if (granted) requestBackgroundLocationPermissionIfNeeded()
        else requestBatteryOptimizationPermissionIfNeeded()
    }
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> AppLogger.info("Permissão de notificações: concedida=$granted") }
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
        requestExactAlarmPermissionIfNeeded()
    }
    private val requestExactAlarmSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = SheetsSyncScheduler.canScheduleExactAlarms(this)
        AppLogger.info("Resultado da permissão de alarmes exatos: concedida=$granted")
        SheetsSyncScheduler.scheduleHourly(this)
        markStartupPermissionsSettled()
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
        serviceManager = MainServiceManager(this, ::uploadTripsWhenMonitoringStarts)
        requestLocationPermissionForHistoryIfNeeded()
        requestNotificationPermissionIfNeeded()
        restoreSheetsSync()

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

    private fun uploadTripsWhenMonitoringStarts() {
        lifecycleScope.launch(Dispatchers.IO) {
            AppLogger.info("Monitorização iniciada: upload imediato para a Google Sheet solicitado")
            val completed = tripSyncRepository.uploadPending()
            AppLogger.info("Upload imediato ao iniciar monitorização concluído: sucesso=$completed")
        }
    }

    /**
     * Mantém o histórico já existente sincronizado, mesmo quando a monitorização
     * de ofertas ainda não foi iniciada nesta abertura da aplicação.
     */
    private fun restoreSheetsSync() {
        SheetsSyncScheduler.scheduleHourly(this)
        lifecycleScope.launch(Dispatchers.IO) {
            AppLogger.info("Sheets: sincronização de recuperação solicitada ao abrir a aplicação")
            val completed = tripSyncRepository.sync()
            AppLogger.info("Sheets: sincronização de recuperação concluída: sucesso=$completed")
        }
    }

    /**
     * As ofertas são detetadas enquanto Uber/Bolt estão visíveis. Por isso a
     * localização precisa de autorização contínua para ser guardada no histórico.
     * A funcionalidade continua a operar caso o utilizador não a autorize.
     */
    private fun requestLocationPermissionForHistoryIfNeeded() {
        if (hasForegroundLocationPermission()) {
            requestBackgroundLocationPermissionIfNeeded()
        } else {
            requestForegroundLocationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            requestExactAlarmPermissionIfNeeded()
            return
        }
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager?.isIgnoringBatteryOptimizations(packageName) == true) {
            AppLogger.info("Otimização de bateria já desativada para a aplicação")
            requestExactAlarmPermissionIfNeeded()
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
                requestExactAlarmPermissionIfNeeded()
            }
        } catch (error: SecurityException) {
            AppLogger.error("O sistema recusou o pedido de otimização de bateria", error)
            requestExactAlarmPermissionIfNeeded()
        }
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            SheetsSyncScheduler.scheduleHourly(this)
            markStartupPermissionsSettled()
            return
        }
        if (SheetsSyncScheduler.canScheduleExactAlarms(this)) {
            AppLogger.info("Permissão de alarmes exatos já concedida")
            SheetsSyncScheduler.scheduleHourly(this)
            markStartupPermissionsSettled()
            return
        }

        AppLogger.info("Solicitando ao utilizador a permissão de alarmes exatos")
        try {
            requestExactAlarmSettings.launch(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (error: ActivityNotFoundException) {
            AppLogger.warn("Definições de alarmes exatos indisponíveis; mantendo modo inexacto", error)
            SheetsSyncScheduler.scheduleHourly(this)
            markStartupPermissionsSettled()
        } catch (error: SecurityException) {
            AppLogger.warn("Sistema recusou abrir a permissão de alarmes exatos", error)
            SheetsSyncScheduler.scheduleHourly(this)
            markStartupPermissionsSettled()
        }
    }

    /**
     * A autorização de sobreposição é uma definição especial do Android (não
     * é um diálogo runtime). É pedida uma vez na primeira instalação, depois
     * da sequência inicial de permissões, para que os cards nunca fiquem
     * silenciosamente invisíveis.
     */
    private fun requestOverlayPermissionOnFirstInstallIfNeeded() {
        if (!startupPermissionsSettled || overlayPermissionRequestInFlight) return
        if (Settings.canDrawOverlays(this)) return
        val preferences = getPreferences(MODE_PRIVATE)
        if (preferences.getBoolean(KEY_OVERLAY_PERMISSION_PROMPTED, false)) return

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
        requestOverlayPermissionOnFirstInstallIfNeeded()
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

        private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
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

    private companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        const val SUPPORT_WHATSAPP_NUMBER = "351912521498"
        const val KEY_OVERLAY_PERMISSION_PROMPTED = "overlay_permission_prompted"
    }
}
