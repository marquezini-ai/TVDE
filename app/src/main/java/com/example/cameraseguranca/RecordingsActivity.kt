package com.example.cameraseguranca

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cameraseguranca.data.RecordingStorage
import com.example.cameraseguranca.data.StoredRecording
import com.example.cameraseguranca.ui.theme.CameraSafetyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Lista somente os MP4s presentes nos diretórios privados controlados por RecordingStorage. */
class RecordingsActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recordings by mutableStateOf<List<StoredRecording>>(emptyList())
    private var isLoading by mutableStateOf(true)
    private var recordingPendingDeletion by mutableStateOf<StoredRecording?>(null)
    private var isDeleting by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CameraSafetyTheme {
                RecordingsScreen(
                    recordings = recordings,
                    isLoading = isLoading,
                    recordingPendingDeletion = recordingPendingDeletion,
                    isDeleting = isDeleting,
                    onBack = ::finish,
                    onRefresh = ::refresh,
                    onOpenRecording = ::openRecording,
                    onDeleteRequested = { recordingPendingDeletion = it },
                    onDismissDelete = { if (!isDeleting) recordingPendingDeletion = null },
                    onConfirmDelete = ::deleteRecording
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        activityScope.launch {
            isLoading = true
            recordings = withContext(Dispatchers.IO) { RecordingStorage.list(this@RecordingsActivity) }
            isLoading = false
        }
    }

    private fun openRecording(file: File) {
        if (!RecordingStorage.isManagedRecording(this, file)) return
        startActivity(
            Intent(this, VideoPlayerActivity::class.java)
                .putExtra(VideoPlayerActivity.EXTRA_RECORDING_PATH, file.absolutePath)
        )
    }

    private fun deleteRecording(recording: StoredRecording) {
        if (isDeleting) return
        isDeleting = true
        activityScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching { RecordingStorage.delete(this@RecordingsActivity, recording.file) }
                    .getOrDefault(false)
            }
            recordingPendingDeletion = null
            isDeleting = false
            if (deleted) {
                Toast.makeText(this@RecordingsActivity, "Gravação excluída.", Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                Toast.makeText(
                    this@RecordingsActivity,
                    "Não foi possível excluir esta gravação.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingsScreen(
    recordings: List<StoredRecording>,
    isLoading: Boolean,
    recordingPendingDeletion: StoredRecording?,
    isDeleting: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenRecording: (File) -> Unit,
    onDeleteRequested: (StoredRecording) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: (StoredRecording) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Gravações", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Atualizar lista")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { paddingValues ->
        when {
            isLoading -> Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }

            recordings.isEmpty() -> EmptyRecordings(
                modifier = Modifier.fillMaxSize().padding(paddingValues)
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp,
                    vertical = 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(recordings, key = { it.file.absolutePath }) { recording ->
                    RecordingRow(
                        recording = recording,
                        onClick = { onOpenRecording(recording.file) },
                        onDelete = { onDeleteRequested(recording) }
                    )
                }
            }
        }
    }

    recordingPendingDeletion?.let { recording ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Excluir gravação?") },
            text = {
                Text(
                    "Este vídeo será removido apenas do armazenamento privado do app e não poderá ser recuperado."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = { onConfirmDelete(recording) }
                ) {
                    Text(if (isDeleting) "EXCLUINDO…" else "EXCLUIR")
                }
            },
            dismissButton = {
                TextButton(enabled = !isDeleting, onClick = onDismissDelete) {
                    Text("CANCELAR")
                }
            }
        )
    }
}

@Composable
private fun EmptyRecordings(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.size(14.dp))
        Text("Nenhuma gravação encontrada", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.size(6.dp))
        Text(
            text = "Os próximos vídeos aparecerão aqui e ficam armazenados somente nos diretórios privados do app.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun RecordingRow(recording: StoredRecording, onClick: () -> Unit, onDelete: () -> Unit) {
    val modifiedAt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(recording.file.lastModified()))
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(17.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(29.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = recording.file.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$modifiedAt • ${Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, recording.file.length())}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text(
                    text = recording.storageLabel,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Excluir gravação",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
