package com.example.cameraseguranca.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cameraseguranca.data.CameraLens
import com.example.cameraseguranca.data.AutoDeleteInterval
import com.example.cameraseguranca.data.RecordingSettings
import com.example.cameraseguranca.data.SegmentDuration
import com.example.cameraseguranca.data.StorageLocation
import com.example.cameraseguranca.data.TriggerMode
import com.example.cameraseguranca.data.VideoQuality
import com.example.cameraseguranca.data.RecordingFps

private enum class PreferenceSelector {
    LENS,
    QUALITY,
    FPS,
    SEGMENT,
    AUTO_DELETE,
    STORAGE,
    TRIGGER
}

/**
 * Preferências de Gravação com a mesma hierarquia visual das Configurações da
 * TVDE Insight: cabeçalho editorial, margens de 20 dp e grupos de cartões.
 */
@Composable
fun SettingsScreen(
    settings: RecordingSettings,
    overlayPermissionGranted: Boolean,
    sdCardAvailable: Boolean,
    onOpenRecordings: () -> Unit,
    onFloatingControlChanged: (Boolean) -> Unit,
    onLensChanged: (CameraLens) -> Unit,
    onQualityChanged: (VideoQuality) -> Unit,
    onFpsChanged: (RecordingFps) -> Unit,
    onSegmentChanged: (SegmentDuration) -> Unit,
    onStorageChanged: (StorageLocation) -> Unit,
    onTriggerChanged: (TriggerMode) -> Unit,
    onAudioChanged: (Boolean) -> Unit,
    onAutoDeleteIntervalChanged: (AutoDeleteInterval) -> Unit
) {
    var openSelector by remember { mutableStateOf<PreferenceSelector?>(null) }
    val circularWindow = settings.segment.takeUnless { it == SegmentDuration.NONE }
        ?: SegmentDuration.MINUTES_5

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Gravação de proteção",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Configure o controlo flutuante, o acionamento e os vídeos gravados.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }

            PreferenceGroup(title = "Gravação") {
                RecordingControlRow(
                    enabled = settings.floatingControlEnabled,
                    overlayPermissionGranted = overlayPermissionGranted,
                    onEnabledChanged = onFloatingControlChanged
                )
            }

            PreferenceGroup(title = "Vídeos") {
                PreferenceRow(
                    icon = Icons.Outlined.PlayCircle,
                    title = "Vídeos gravados",
                    description = "Ver, reproduzir, baixar ou apagar as gravações.",
                    value = null,
                    onClick = onOpenRecordings
                )
            }

            PreferenceGroup(title = "Câmara e vídeo") {
                PreferenceRow(
                    icon = Icons.Outlined.PhotoCamera,
                    title = "Lente",
                    description = "Defina a lente usada na próxima gravação.",
                    value = settings.lens.label,
                    onClick = { openSelector = PreferenceSelector.LENS }
                )
                PreferenceDivider()
                PreferenceRow(
                    icon = Icons.Outlined.HighQuality,
                    title = "Qualidade de gravação",
                    description = "Quanto maior a qualidade, maior o tamanho do arquivo.",
                    value = settings.quality.label,
                    onClick = { openSelector = PreferenceSelector.QUALITY }
                )
                PreferenceDivider()
                PreferenceRow(
                    icon = Icons.Outlined.Timer,
                    title = "Taxa de quadros",
                    description = "Menos fps reduz o tamanho dos vídeos e o uso de armazenamento.",
                    value = settings.fps.label,
                    onClick = { openSelector = PreferenceSelector.FPS }
                )
                PreferenceDivider()
                PreferenceRow(
                    icon = Icons.Outlined.Movie,
                    title = "Looping",
                    description = "Grava continuamente e mantém apenas os últimos minutos escolhidos.",
                    value = circularWindow.label,
                    onClick = { openSelector = PreferenceSelector.SEGMENT }
                )
                PreferenceDivider()
                PreferenceRow(
                    icon = Icons.Outlined.Mic,
                    title = "Gravar áudio",
                    description = "Inclui o som captado pelo microfone. O Android solicitará esta permissão ao ativar.",
                    value = if (settings.audioEnabled) "Ativado" else "Desativado",
                    onClick = { onAudioChanged(!settings.audioEnabled) }
                )
                PreferenceDivider()
                PreferenceRow(
                    icon = Icons.Outlined.Storage,
                    title = "Armazenamento",
                    description = if (sdCardAvailable) {
                        "Escolha o diretório privado Local ou no Cartão SD."
                    } else {
                        "Vídeos isolados no diretório privado Local do app."
                    },
                    value = if (settings.storageLocation == StorageLocation.SD_CARD && !sdCardAvailable) {
                        StorageLocation.LOCAL.label
                    } else {
                        settings.storageLocation.label
                    },
                    onClick = if (sdCardAvailable) {
                        { openSelector = PreferenceSelector.STORAGE }
                    } else {
                        null
                    }
                )
                PreferenceDivider()
                PreferenceRow(
                    icon = Icons.Outlined.Timer,
                    title = "Apagar automaticamente",
                    description = "Remove vídeos privados antigos para ajudar a não encher a memória.",
                    value = settings.autoDeleteInterval.label,
                    onClick = { openSelector = PreferenceSelector.AUTO_DELETE }
                )
            }

            PreferenceGroup(title = "Atalho flutuante") {
                PreferenceRow(
                    icon = Icons.Outlined.TouchApp,
                    title = "Acionamento",
                    description = "Escolha o gesto que inicia ou para uma sessão pelo botão flutuante.",
                    value = settings.triggerMode.label,
                    onClick = { openSelector = PreferenceSelector.TRIGGER }
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    when (openSelector) {
        PreferenceSelector.LENS -> ChoiceDialog(
            title = "Lente",
            description = "Defina a lente padrão para a próxima gravação.",
            options = CameraLens.entries.toList(),
            selected = settings.lens,
            label = CameraLens::label,
            onSelected = {
                onLensChanged(it)
                openSelector = null
            },
            onDismiss = { openSelector = null }
        )

        PreferenceSelector.QUALITY -> ChoiceDialog(
            title = "Qualidade de gravação",
            description = "Quanto maior a qualidade do vídeo, maior o tamanho do arquivo.",
            options = VideoQuality.entries.toList(),
            selected = settings.quality,
            label = VideoQuality::label,
            onSelected = {
                onQualityChanged(it)
                openSelector = null
            },
            onDismiss = { openSelector = null }
        )

        PreferenceSelector.FPS -> ChoiceDialog(
            title = "Taxa de quadros",
            description = "Escolha a taxa usada na gravação: 15, 20 ou 30 fps.",
            options = RecordingFps.entries.toList(),
            selected = settings.fps,
            label = RecordingFps::label,
            onSelected = {
                onFpsChanged(it)
                openSelector = null
            },
            onDismiss = { openSelector = null }
        )

        PreferenceSelector.SEGMENT -> ChoiceDialog(
            title = "Looping",
            description = "A gravação não termina sozinha; os segmentos mais antigos são substituídos continuamente.",
            options = SegmentDuration.entries.filter { it != SegmentDuration.NONE },
            selected = circularWindow,
            label = SegmentDuration::label,
            onSelected = {
                onSegmentChanged(it)
                openSelector = null
            },
            onDismiss = { openSelector = null }
        )

        PreferenceSelector.AUTO_DELETE -> ChoiceDialog(
            title = "Apagar automaticamente",
            description = "Ao fim do período escolhido, os vídeos privados serão removidos. Cópias baixadas em Downloads não são apagadas.",
            options = AutoDeleteInterval.entries.toList(),
            selected = settings.autoDeleteInterval,
            label = AutoDeleteInterval::label,
            onSelected = {
                onAutoDeleteIntervalChanged(it)
                openSelector = null
            },
            onDismiss = { openSelector = null }
        )

        PreferenceSelector.STORAGE -> ChoiceDialog(
            title = "Armazenamento",
            description = "Os vídeos permanecem em diretórios privados do app e não aparecem na galeria.",
            options = if (sdCardAvailable) {
                StorageLocation.entries.toList()
            } else {
                listOf(StorageLocation.LOCAL)
            },
            selected = if (settings.storageLocation == StorageLocation.SD_CARD && !sdCardAvailable) {
                StorageLocation.LOCAL
            } else {
                settings.storageLocation
            },
            label = StorageLocation::label,
            onSelected = {
                onStorageChanged(it)
                openSelector = null
            },
            onDismiss = { openSelector = null }
        )

        PreferenceSelector.TRIGGER -> ChoiceDialog(
            title = "Acionamento",
            description = "Quando não há gravação ativa, o gesto inicia uma nova sessão. Durante uma sessão, ele para e finaliza o vídeo.",
            options = TriggerMode.entries.toList(),
            selected = settings.triggerMode,
            label = TriggerMode::label,
            detail = TriggerMode::description,
            onSelected = {
                onTriggerChanged(it)
                openSelector = null
            },
            onDismiss = { openSelector = null }
        )

        null -> Unit
    }
}

@Composable
private fun PreferenceGroup(
    title: String? = null,
    content: @Composable () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(180))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            title?.let {
                Text(
                    text = it,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Spacer(Modifier.height(14.dp))
            }
            content()
        }
    }
}

@Composable
private fun RecordingControlRow(
    enabled: Boolean,
    overlayPermissionGranted: Boolean,
    onEnabledChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // A chave tem a mesma apresentação simples dos demais interruptores
        // das Configurações TVDE; não repete explicações técnicas na tela.
        Text("Gravação", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChanged
        )
    }
}

@Composable
private fun PreferenceRow(
    icon: ImageVector,
    title: String,
    description: String,
    value: String?,
    onClick: (() -> Unit)?
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
        if (value != null) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
                if (onClick != null) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowForwardIos,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        } else if (onClick != null) {
            Icon(
                imageVector = Icons.Outlined.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun PreferenceDivider() {
    HorizontalDivider(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    description: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    detail: ((T) -> String)? = null,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(4.dp))
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { onSelected(option) }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = { onSelected(option) }
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label(option), fontSize = 14.sp)
                            detail?.invoke(option)?.let { optionDetail ->
                                Text(
                                    text = optionDetail,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
