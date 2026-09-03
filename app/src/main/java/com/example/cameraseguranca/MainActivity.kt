package com.example.cameraseguranca

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cameraseguranca.service.RecordingOverlayController
import com.example.cameraseguranca.ui.SettingsScreen
import com.example.cameraseguranca.ui.SettingsViewModel
import com.example.cameraseguranca.ui.theme.CameraSafetyTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels()
    private var overlayAllowed by mutableStateOf(false)
    private var sdCardAvailable by mutableStateOf(false)
    private var waitingForOverlayPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (hasPermission(Manifest.permission.CAMERA)) {
            requestOverlayPermissionOrStart()
        } else {
            toast("A permissão de câmera é necessária para ativar o controlo de gravação.")
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setAudioEnabled(granted)
        if (!granted) toast("O áudio continuará desativado sem a permissão de microfone.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayAllowed = Settings.canDrawOverlays(this)
        sdCardAvailable = hasSdCard()

        setContent {
            CameraSafetyTheme {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                // Ao voltar ao app, restaura o único controlo circular se ele tiver sido
                // deixado ativado e o Android tiver encerrado o serviço entretanto.
                LaunchedEffect(settings.floatingControlEnabled, overlayAllowed) {
                    if (settings.floatingControlEnabled && overlayAllowed) startOverlayService()
                }
                SettingsScreen(
                    settings = settings,
                    overlayPermissionGranted = overlayAllowed,
                    sdCardAvailable = sdCardAvailable,
                    onOpenRecordings = ::openRecordings,
                    onFloatingControlChanged = ::setFloatingControlEnabled,
                    onLensChanged = viewModel::setLens,
                    onQualityChanged = viewModel::setQuality,
                    onFpsChanged = viewModel::setFps,
                    onSegmentChanged = viewModel::setSegment,
                    onStorageChanged = viewModel::setStorageLocation,
                    onTriggerChanged = viewModel::setTriggerMode,
                    onAudioChanged = ::setAudioEnabled,
                    onAutoDeleteIntervalChanged = viewModel::setAutoDeleteInterval
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayAllowed = Settings.canDrawOverlays(this)
        sdCardAvailable = hasSdCard()
        if (waitingForOverlayPermission && overlayAllowed) {
            waitingForOverlayPermission = false
            startOverlayService()
            toast("Controlo flutuante ativado.")
        }
    }

    private fun setFloatingControlEnabled(enabled: Boolean) {
        if (enabled) {
            enableFloatingControl()
        } else {
            waitingForOverlayPermission = false
            viewModel.setFloatingControlEnabled(false)
            RecordingOverlayController.sync(this, shouldShow = false)
            toast("Controlo flutuante oculto.")
        }
    }

    private fun enableFloatingControl() {
        val permissions = buildList {
            if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            requestOverlayPermissionOrStart()
        }
    }

    private fun requestOverlayPermissionOrStart() {
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
            toast("Controlo flutuante ativado.")
        } else {
            waitingForOverlayPermission = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun startOverlayService() {
        viewModel.setFloatingControlEnabled(true)
        RecordingOverlayController.sync(this, shouldShow = true)
    }

    private fun setAudioEnabled(enabled: Boolean) {
        if (!enabled) {
            viewModel.setAudioEnabled(false)
        } else if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            viewModel.setAudioEnabled(true)
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun openRecordings() {
        startActivity(Intent(this, RecordingsActivity::class.java))
    }

    private fun hasSdCard(): Boolean = getExternalFilesDirs(null)
        .filterNotNull()
        .any { directory ->
            Environment.getExternalStorageState(directory) == Environment.MEDIA_MOUNTED &&
                Environment.isExternalStorageRemovable(directory)
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
