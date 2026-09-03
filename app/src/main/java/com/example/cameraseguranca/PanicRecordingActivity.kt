package com.example.cameraseguranca

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.cameraseguranca.service.RecordingService
import com.example.cameraseguranca.ui.theme.CameraSafetyTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Activity propositalmente visível. Ela garante uma ação explícita do usuário antes
 * de iniciar uma foreground service com acesso à câmera no Android moderno.
 */
class PanicRecordingActivity : ComponentActivity() {
    private var audioRequestedForThisSession = false

    private val recordingPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val hasCamera = grants[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
        val hasLocation = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasAudio = !audioRequestedForThisSession ||
            grants[Manifest.permission.RECORD_AUDIO] == true ||
            hasPermission(Manifest.permission.RECORD_AUDIO)
        val hasNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            grants[Manifest.permission.POST_NOTIFICATIONS] == true ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        if (hasCamera && hasLocation && hasAudio && hasNotifications) {
            startRecording()
        } else {
            toast("Conceda câmera, localização, notificações e, quando ativado, microfone para gravar.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissionOnly = intent.getBooleanExtra(EXTRA_PERMISSION_ONLY, false)
        setContent {
            CameraSafetyTheme {
                if (permissionOnly) {
                    RecordingPermissionsScreen()
                } else {
                    RecordingConfirmationScreen(
                        onStart = ::ensureCameraPermissionThenStart,
                        onCancel = ::finish
                    )
                }
            }
        }
        if (permissionOnly) ensureCameraPermissionThenStart()
    }

    private fun ensureCameraPermissionThenStart() {
        lifecycleScope.launch {
            audioRequestedForThisSession =
                CameraSafetyDependencies.settingsRepository(applicationContext).settings.first().audioEnabled
            val missingPermissions = buildList {
                if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
                if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (audioRequestedForThisSession && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    add(Manifest.permission.RECORD_AUDIO)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (missingPermissions.isEmpty()) {
                startRecording()
            } else {
                recordingPermissionLauncher.launch(missingPermissions.toTypedArray())
            }
        }
    }

    private fun startRecording() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
            )
            toast("Gravação iniciada com marca d’água GPS. O Android exibirá os indicadores do sistema.")
            finishAndRemoveTask()
        } catch (error: SecurityException) {
            toast("O Android bloqueou a câmera: verifique as permissões e tente novamente.")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_PERMISSION_ONLY = "permission_only"
    }
}

@Composable
private fun RecordingPermissionsScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("A solicitar permissões de gravação…", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Conceda as permissões solicitadas pelo Android para continuar.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun RecordingConfirmationScreen(onStart: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Iniciar gravação?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "O vídeo ficará no armazenamento privado do app e receberá uma marca d’água móvel com GPS, velocidade e data/hora. Se o áudio estiver ativado, o microfone também será usado. Durante a gravação, o Android mostra os indicadores do sistema.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("INICIAR GRAVAÇÃO", fontWeight = FontWeight.Black)
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Cancelar")
                }
            }
        }
    }
}
