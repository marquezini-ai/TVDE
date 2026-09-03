package com.example.cameraseguranca

import android.os.Bundle
import android.widget.Toast
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.cameraseguranca.data.RecordingStorage
import com.example.cameraseguranca.ui.theme.CameraSafetyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Reproduz um arquivo privado sem expor sua localização a um app de galeria externo. */
class VideoPlayerActivity : ComponentActivity() {
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isDownloading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val candidate = intent.getStringExtra(EXTRA_RECORDING_PATH)?.let(::File)
        val recording = candidate?.takeIf { it.isFile && RecordingStorage.isManagedRecording(this, it) }

        setContent {
            CameraSafetyTheme {
                VideoPlayerScreen(
                    recording = recording,
                    canDownload = recording?.let(RecordingStorage::canDownload) == true,
                    isDownloading = isDownloading,
                    onDownload = ::downloadRecording,
                    onBack = ::finish
                )
            }
        }
    }

    private fun downloadRecording(file: File) {
        if (isDownloading || !RecordingStorage.canDownload(file)) return
        isDownloading = true
        downloadScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { RecordingStorage.downloadWatermarkedCopy(this@VideoPlayerActivity, file) }
            }.onSuccess {
                Toast.makeText(
                    this@VideoPlayerActivity,
                    "Cópia com marca d’água salva em Downloads/TVDE Insight.",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure {
                Toast.makeText(
                    this@VideoPlayerActivity,
                    "Não foi possível baixar esta gravação.",
                    Toast.LENGTH_LONG
                ).show()
            }
            isDownloading = false
        }
    }

    override fun onDestroy() {
        downloadScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RECORDING_PATH = "recording_path"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoPlayerScreen(
    recording: File?,
    canDownload: Boolean,
    isDownloading: Boolean,
    onDownload: (File) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Reprodução") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        if (recording == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Esta gravação não está mais disponível.")
            }
        } else {
            val context = androidx.compose.ui.platform.LocalContext.current
            val videoUri = remember(recording) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", recording)
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { viewContext ->
                        VideoView(viewContext).apply {
                            setMediaController(MediaController(viewContext).also { controller ->
                                controller.setAnchorView(this)
                            })
                            setVideoURI(videoUri)
                            setOnPreparedListener { start() }
                        }
                    }
                )
                if (canDownload) {
                    Button(
                        onClick = { onDownload(recording) },
                        enabled = !isDownloading,
                        modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 20.dp)
                    ) {
                        Text(if (isDownloading) "A BAIXAR…" else "BAIXAR CÓPIA COM MARCA D’ÁGUA")
                    }
                } else {
                    Text(
                        text = "Este vídeo anterior não pode ser baixado porque não há garantia de marca d’água incorporada.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
