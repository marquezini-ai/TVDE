package com.daniel.tvdeinsight.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.daniel.tvdeinsight.BuildConfig
import com.daniel.tvdeinsight.MainActivity
import com.daniel.tvdeinsight.R
import com.daniel.tvdeinsight.backup.AppDataBackup
import com.daniel.tvdeinsight.domain.model.RuleSettings
import com.daniel.tvdeinsight.domain.model.VehicleType
import com.daniel.tvdeinsight.reservations.AppPreferences
import com.daniel.tvdeinsight.reservations.RideHistoryStore
import com.daniel.tvdeinsight.ui.theme.ThemeMode
import com.example.cameraseguranca.CameraSafetyDependencies
import com.example.cameraseguranca.service.RecordingOverlayController
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

@Composable
fun SettingsScreen(paddingValues: PaddingValues, viewModel: MainViewModel = hiltViewModel()) {
    val savedSettings by viewModel.settings.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState(initial = ThemeMode.AUTOMATIC)
    val licenseViewModel: LicenseViewModel = hiltViewModel()
    val licenseState by licenseViewModel.licenseState.collectAsState()
    var draft by remember(savedSettings) { mutableStateOf(savedSettings) }
    var appearanceMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var screenshotRetentionMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val isEditable = BuildConfig.IS_ADMIN_APP || licenseState.isValid
    val isOffline = !savedSettings.isAppRunning && isEditable
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                val backup = AppDataBackup.create(
                    ruleSettings = savedSettings,
                    themeMode = themeMode,
                    reservationSettings = AppPreferences.loadSettings(context),
                    reservationHistory = RideHistoryStore.list(context),
                    recordingSettings = CameraSafetyDependencies.settingsRepository(context).settings.first()
                )
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(backup) }
                    ?: error("Não foi possível abrir o ficheiro")
            }.onSuccess {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Backup criado.", Toast.LENGTH_LONG).show() }
            }.onFailure { error ->
                withContext(Dispatchers.Main) { Toast.makeText(context, "Não foi possível criar o backup: ${error.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                val restored = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    AppDataBackup.parse(reader.readText())
                } ?: error("Não foi possível abrir o ficheiro")
                RideHistoryStore.replaceAll(context, restored.reservationHistory)
                AppPreferences.saveSettings(context, restored.reservationSettings)
                CameraSafetyDependencies.settingsRepository(context).restore(restored.recordingSettings)
                RecordingOverlayController.sync(context, restored.recordingSettings.floatingControlEnabled)
                restored
            }.onSuccess { restored ->
                withContext(Dispatchers.Main) {
                    viewModel.saveSettings(restored.ruleSettings)
                    viewModel.saveThemeMode(restored.themeMode)
                    Toast.makeText(context, "Backup carregado com sucesso.", Toast.LENGTH_LONG).show()
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) { Toast.makeText(context, "Não foi possível carregar o backup: ${error.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

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
                "Configurações",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                "Personalize a análise ao seu modo de trabalhar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }

        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(180))
                .clickable {
                    context.startActivity(
                        android.content.Intent(
                            context,
                            com.example.cameraseguranca.MainActivity::class.java
                        )
                    )
                },
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Text("Gravação de proteção", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = "Abrir gravação de proteção",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Aparência", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "Escolha o tema da aplicação",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                                shape = MaterialTheme.shapes.medium
                            )
                            .clickable(enabled = isEditable) { appearanceMenuExpanded = true }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Tema",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                            Text(
                                themeMode.label,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "⌄",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 20.sp
                        )
                    }

                    DropdownMenu(
                        expanded = appearanceMenuExpanded,
                        onDismissRequest = { appearanceMenuExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.92f)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        mode.label,
                                        fontWeight = if (themeMode == mode) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                trailingIcon = {
                                    if (themeMode == mode) Text("✓", color = MaterialTheme.colorScheme.primary)
                                },
                                onClick = {
                                    viewModel.saveThemeMode(mode)
                                    appearanceMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        if (BuildConfig.IS_ADMIN_APP) {
            AdminActivationCard()
        }

        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Captura de Tela", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                PlatformSwitch(
                    title = "Ativar",
                    checked = draft.isOfferScreenshotCaptureEnabled,
                    // Esta opção pode ser alterada durante a monitorização:
                    // o serviço recebe a alteração imediatamente pelo DataStore.
                    enabled = isEditable,
                    onCheckedChange = { enabled ->
                        val updated = draft.copy(isOfferScreenshotCaptureEnabled = enabled)
                        draft = updated
                        viewModel.saveSettings(updated)
                    }
                )
                Text("Apagar automaticamente", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                                shape = MaterialTheme.shapes.medium
                            )
                            .clickable(enabled = isEditable) { screenshotRetentionMenuExpanded = true }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(screenshotRetentionLabel(draft.screenshotRetentionHours), fontWeight = FontWeight.SemiBold)
                        Text("⌄", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
                    }
                    DropdownMenu(
                        expanded = screenshotRetentionMenuExpanded,
                        onDismissRequest = { screenshotRetentionMenuExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.92f)
                    ) {
                        listOf(24, 7 * 24).forEach { hours ->
                            DropdownMenuItem(
                                text = { Text(screenshotRetentionLabel(hours)) },
                                trailingIcon = {
                                    if (draft.screenshotRetentionHours == hours) {
                                        Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    val updated = draft.copy(screenshotRetentionHours = hours)
                                    draft = updated
                                    viewModel.saveSettings(updated)
                                    screenshotRetentionMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Aplicações de plataforma", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                PlatformSwitch(
                    title = "Uber Driver",
                    checked = draft.isUberEnabled,
                    enabled = isOffline,
                    onCheckedChange = { enabled ->
                        draft = draft.copy(isUberEnabled = enabled)
                        viewModel.saveSettings(draft)
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                PlatformSwitch(
                    title = "Bolt Driver",
                    checked = draft.isBoltEnabled,
                    enabled = isOffline,
                    onCheckedChange = { enabled ->
                        draft = draft.copy(isBoltEnabled = enabled)
                        viewModel.saveSettings(draft)
                    }
                )
            }
        }

        CriteriaCard(title = "Critérios de avaliação") {
            EvaluationCriteriaLockButton(
                locked = draft.areEvaluationCriteriaLocked,
                enabled = isEditable,
                onToggle = {
                    val updated = draft.copy(areEvaluationCriteriaLocked = !draft.areEvaluationCriteriaLocked)
                    draft = updated
                    viewModel.saveSettings(updated)
                }
            )

            EvaluationCriterionSwitch(
                title = "Quilómetros",
                checked = draft.isKmCriterionEnabled,
                enabled = isEditable
            ) { enabled ->
                val updated = draft.copy(isKmCriterionEnabled = enabled)
                draft = updated
                viewModel.saveSettings(updated)
            }
            ThresholdRangeSelector(
                title = "Zonas de valor por quilómetro",
                valueRange = 0f..2f,
                lowerValue = draft.minEurPerKm,
                upperValue = draft.goodEurPerKm,
                isHigherBetter = true,
                stepSize = 0.05f,
                enabled = isEditable && !draft.areEvaluationCriteriaLocked,
                valueFormatter = { String.format(PORTUGUESE_LOCALE, "€ %.2f", it) },
                onValueChangeFinished = { minimum, good ->
                    val updated = draft.copy(minEurPerKm = minimum, goodEurPerKm = good)
                    draft = updated
                    viewModel.saveSettings(updated)
                }
            )

            CriteriaDivider()

            EvaluationCriterionSwitch(
                title = "Hora",
                checked = draft.isHourCriterionEnabled,
                enabled = isEditable
            ) { enabled ->
                val updated = draft.copy(isHourCriterionEnabled = enabled)
                draft = updated
                viewModel.saveSettings(updated)
            }
            ThresholdRangeSelector(
                title = "Zonas de valor por hora",
                valueRange = 0f..40f,
                lowerValue = draft.minEurPerHour,
                upperValue = draft.goodEurPerHour,
                isHigherBetter = true,
                stepSize = 0.5f,
                enabled = isEditable && !draft.areEvaluationCriteriaLocked,
                valueFormatter = { String.format(PORTUGUESE_LOCALE, "€ %.2f", it) },
                onValueChangeFinished = { minimum, good ->
                    val updated = draft.copy(minEurPerHour = minimum, goodEurPerHour = good)
                    draft = updated
                    viewModel.saveSettings(updated)
                }
            )

            CriteriaDivider()

            EvaluationCriterionSwitch(
                title = "Recolha",
                checked = draft.isPickupCriterionEnabled,
                enabled = isEditable
            ) { enabled ->
                val updated = draft.copy(isPickupCriterionEnabled = enabled)
                draft = updated
                viewModel.saveSettings(updated)
            }
            ThresholdRangeSelector(
                title = "Zonas de distância de recolha",
                valueRange = 0f..10f,
                lowerValue = draft.idealPickupDistanceKm,
                upperValue = draft.acceptablePickupDistanceKm,
                isHigherBetter = false,
                stepSize = 0.5f,
                enabled = isEditable && !draft.areEvaluationCriteriaLocked,
                valueFormatter = { String.format(PORTUGUESE_LOCALE, "%.1f km", it) },
                onValueChangeFinished = { ideal, acceptable ->
                    val updated = draft.copy(idealPickupDistanceKm = ideal, acceptablePickupDistanceKm = acceptable)
                    draft = updated
                    viewModel.saveSettings(updated)
                }
            )

            CriteriaDivider()

            EvaluationCriterionSwitch(
                title = "Viagens longas",
                checked = draft.isLongTripCriterionEnabled,
                enabled = isEditable
            ) { enabled ->
                val updated = draft.copy(isLongTripCriterionEnabled = enabled)
                draft = updated
                viewModel.saveSettings(updated)
            }
            LongTripThresholdSelector(
                value = draft.longTripMinimumKm,
                enabled = isEditable && !draft.areEvaluationCriteriaLocked,
                onValueChangeFinished = { minimum ->
                    val updated = draft.copy(longTripMinimumKm = minimum)
                    draft = updated
                    viewModel.saveSettings(updated)
                }
            )

            CriteriaDivider()

            EvaluationCriterionSwitch(
                title = "Valor mínimo",
                checked = draft.isMinimumTripValueCriterionEnabled,
                enabled = isEditable
            ) { enabled ->
                val updated = draft.copy(isMinimumTripValueCriterionEnabled = enabled)
                draft = updated
                viewModel.saveSettings(updated)
            }
            MinimumTripValueThresholdSelector(
                value = draft.minimumTripValue,
                enabled = isEditable && !draft.areEvaluationCriteriaLocked,
                onValueChangeFinished = { minimum ->
                    val updated = draft.copy(minimumTripValue = minimum)
                    draft = updated
                    viewModel.saveSettings(updated)
                }
            )

            CriteriaDivider()

            EvaluationCriterionSwitch(
                title = "Viagens com paradas",
                checked = draft.rejectTripsWithStops,
                enabled = isEditable
            ) { enabled ->
                val updated = draft.copy(rejectTripsWithStops = enabled)
                draft = updated
                viewModel.saveSettings(updated)
            }
        }

        VehicleRegistrationCard(
            settings = draft,
            enabled = isEditable,
            calculateCostPerKm = viewModel::calculateVehicleCostPerKm,
            onSettingsCommitted = { updated ->
                draft = updated
                viewModel.saveSettings(updated)
            }
        )

        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Dados da aplicação", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "Guarde ou recupere as configurações, Reservas, histórico e Gravação. Os vídeos permanecem protegidos no aparelho.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { backupLauncher.launch("TVDE-Insight-backup-${System.currentTimeMillis()}.json") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Fazer backup") }
                    Button(
                        onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Carregar dados") }
                }
            }
        }

        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Diagnóstico", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Button(
                    onClick = {
                        (context as? MainActivity)?.let { activity ->
                            if (BuildConfig.IS_ADMIN_APP) activity.startLogDownload()
                            else activity.shareCompleteLogViaWhatsApp()
                        }
                    },
                    enabled = true,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(if (BuildConfig.IS_ADMIN_APP) "Baixar log completo" else "Enviar log pelo WhatsApp")
                }
            }
        }

    }
}

@Composable
private fun VehicleRegistrationCard(
    settings: RuleSettings,
    enabled: Boolean,
    calculateCostPerKm: (consumptionPer100Km: Double, pricePerUnit: Double) -> Double,
    onSettingsCommitted: (RuleSettings) -> Unit
) {
    var consumptionText by rememberSaveable(settings.vehicleType, settings.vehicleConsumptionPer100Km) {
        mutableStateOf(settings.vehicleConsumptionPer100Km.toVehicleConsumptionInput())
    }
    var priceText by rememberSaveable(settings.vehicleType, settings.vehiclePricePerUnit) {
        mutableStateOf(settings.vehiclePricePerUnit.toVehiclePriceInput())
    }
    val consumption = consumptionText.toVehicleNumberOrZero()
    val price = priceText.toVehicleNumberOrZero()
    val costPerKm = calculateCostPerKm(consumption, price)
    val latestSettings by rememberUpdatedState(settings)
    val latestConsumption by rememberUpdatedState(consumption)
    val latestPrice by rememberUpdatedState(price)
    val latestOnSettingsCommitted by rememberUpdatedState(onSettingsCommitted)

    DisposableEffect(Unit) {
        onDispose {
            val currentSettings = latestSettings
            val hasCompleteVehicleData = latestConsumption > 0.0 && latestPrice > 0.0
            val updatedSettings = currentSettings.copy(
                vehicleConsumptionPer100Km = latestConsumption,
                vehiclePricePerUnit = latestPrice,
                isVehicleCostPerKmEnabled = currentSettings.isVehicleCostPerKmEnabled && hasCompleteVehicleData
            )
            if (enabled && updatedSettings != currentSettings) latestOnSettingsCommitted(updatedSettings)
        }
    }

    fun saveConsumption() {
        consumptionText.toVehicleNumberOrNull()?.let { value ->
            onSettingsCommitted(settings.copy(vehicleConsumptionPer100Km = value))
        }
    }
    fun savePrice() {
        priceText.toVehicleNumberOrNull()?.let { value ->
            onSettingsCommitted(settings.copy(vehiclePricePerUnit = value))
        }
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Cadastro do veículo", fontWeight = FontWeight.Bold, fontSize = 17.sp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Considerar custo por km:",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Switch(
                    checked = settings.isVehicleCostPerKmEnabled,
                    enabled = enabled,
                    onCheckedChange = { enabled ->
                        onSettingsCommitted(settings.copy(isVehicleCostPerKmEnabled = enabled))
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                VehicleType.entries.forEach { type ->
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.vehicleType == type,
                            enabled = enabled && settings.isVehicleCostPerKmEnabled,
                            onClick = {
                                if (settings.vehicleType != type) {
                                    onSettingsCommitted(settings.copy(vehicleType = type))
                                }
                            }
                        )
                        Text(
                            text = type.label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (enabled && settings.isVehicleCostPerKmEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }
                }
            }

            VehicleNumericField(
                value = consumptionText,
                title = "Média de consumo",
                suffix = settings.vehicleType.consumptionUnit,
                maxFractionDigits = 1,
                enabled = enabled && settings.isVehicleCostPerKmEnabled,
                onValueChange = { consumptionText = it },
                onCommit = ::saveConsumption
            )
            VehicleNumericField(
                value = priceText,
                title = settings.vehicleType.priceLabel,
                suffix = "€",
                maxFractionDigits = 2,
                enabled = enabled && settings.isVehicleCostPerKmEnabled,
                onValueChange = { priceText = it },
                onCommit = ::savePrice
            )

            Text(
                text = "Custo por km: ${costPerKm.asVehicleCost()} €/km",
                color = if (enabled && settings.isVehicleCostPerKmEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun VehicleNumericField(
    value: String,
    title: String,
    suffix: String,
    maxFractionDigits: Int,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit
) {
    val validation = if (maxFractionDigits == 1) {
        Regex("^\\d{0,2}([,.]\\d{0,1})?$")
    } else {
        Regex("^\\d{0,3}([,.]\\d{0,2})?$")
    }
    OutlinedTextField(
        value = value,
        onValueChange = { input -> if (validation.matches(input)) onValueChange(input) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) onCommit() },
        label = { Text(title) },
        suffix = { Text(suffix, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit() })
    )
}

private fun String.toVehicleNumberOrNull(): Double? = replace(',', '.').toDoubleOrNull()

private fun String.toVehicleNumberOrZero(): Double = toVehicleNumberOrNull() ?: 0.0

private fun Double.toVehicleConsumptionInput(): String =
    if (this > 0.0) String.format(PORTUGUESE_LOCALE, "%.1f", this) else ""

private fun Double.toVehiclePriceInput(): String =
    if (this > 0.0) String.format(PORTUGUESE_LOCALE, "%.2f", this) else ""

private fun Double.asVehicleCost(): String = String.format(PORTUGUESE_LOCALE, "%.2f", this)

private fun screenshotRetentionLabel(hours: Int): String =
    if (hours >= 7 * 24) "7 dias" else "24 horas"

private val PORTUGUESE_LOCALE = Locale("pt", "PT")

@Composable
private fun PlatformSwitch(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun EvaluationCriterionSwitch(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun CriteriaCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            content()
        }
    }
}

@Composable
private fun CriteriaDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
}

@Composable
private fun EvaluationCriteriaLockButton(locked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (locked) "Barras bloqueadas" else "Barras desbloqueadas",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        IconButton(onClick = onToggle, enabled = enabled) {
            Icon(
                painter = painterResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open),
                contentDescription = if (locked) "Desbloquear barras dos critérios" else "Bloquear barras dos critérios",
                tint = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Marcador estreito: mantém duas posições próximas visíveis e fáceis de arrastar. */
@Composable
private fun CriterionSliderThumb(enabled: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 18.dp, height = 28.dp)
            .background(
                if (enabled) Color(0xFFE7EDF4) else Color(0xFFD6DCE3),
                RoundedCornerShape(8.dp)
            )
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (enabled) Color.White else Color(0xFFF1F3F5), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFFD6DDE5), RoundedCornerShape(6.dp))
        )
    }
}

private fun Color.withHalfSaturation(): Color {
    val gray = red * 0.2126f + green * 0.7152f + blue * 0.0722f
    return Color(
        red = gray + (red - gray) * 0.5f,
        green = gray + (green - gray) * 0.5f,
        blue = gray + (blue - gray) * 0.5f,
        alpha = alpha
    )
}

/**
 * Barra de três zonas com dois marcadores: o primeiro separa vermelho/amarelo
 * (ou verde/amarelo na recolha) e o segundo separa amarelo/verde (ou vermelho).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThresholdRangeSelector(
    title: String,
    valueRange: ClosedFloatingPointRange<Float>,
    lowerValue: Double,
    upperValue: Double,
    isHigherBetter: Boolean,
    stepSize: Float,
    enabled: Boolean,
    valueFormatter: (Double) -> String,
    onValueChangeFinished: (Double, Double) -> Unit
) {
    val rangeStart = valueRange.start
    val rangeEnd = valueRange.endInclusive
    fun snapToStep(value: Float): Float {
        val snapped = kotlin.math.round((value.coerceIn(rangeStart, rangeEnd) - rangeStart) / stepSize) * stepSize + rangeStart
        return snapped.coerceIn(rangeStart, rangeEnd)
    }
    val firstValue = snapToStep(lowerValue.toFloat())
    val secondValue = snapToStep(upperValue.toFloat())
    var sliderValues by remember(lowerValue, upperValue, valueRange) {
        mutableStateOf(minOf(firstValue, secondValue)..maxOf(firstValue, secondValue))
    }
    val firstZoneColor = if (isHigherBetter) Color(0xFFF44336) else Color(0xFF4CAF50)
    val thirdZoneColor = if (isHigherBetter) Color(0xFF4CAF50) else Color(0xFFF44336)
    val middleZoneColor = Color(0xFFFFC107)
    val displayedFirstZoneColor = if (enabled) firstZoneColor else firstZoneColor.withHalfSaturation()
    val displayedMiddleZoneColor = if (enabled) middleZoneColor else middleZoneColor.withHalfSaturation()
    val displayedThirdZoneColor = if (enabled) thirdZoneColor else thirdZoneColor.withHalfSaturation()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            val startFraction = (sliderValues.start - rangeStart) / (rangeEnd - rangeStart)
            val endFraction = (sliderValues.endInclusive - rangeStart) / (rangeEnd - rangeStart)
            RangeSlider(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
                value = sliderValues,
                onValueChange = { values ->
                    sliderValues = values.start.coerceIn(rangeStart, rangeEnd)..
                        values.endInclusive.coerceIn(rangeStart, rangeEnd)
                },
                onValueChangeFinished = {
                    val snappedValues = minOf(snapToStep(sliderValues.start), snapToStep(sliderValues.endInclusive))..
                        maxOf(snapToStep(sliderValues.start), snapToStep(sliderValues.endInclusive))
                    sliderValues = snappedValues
                    onValueChangeFinished(snappedValues.start.toDouble(), snappedValues.endInclusive.toDouble())
                },
                valueRange = valueRange,
                steps = ((rangeEnd - rangeStart) / stepSize).toInt() - 1,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                startThumb = { CriterionSliderThumb(enabled) },
                endThumb = { CriterionSliderThumb(enabled) },
                track = { sliderState ->
                    val firstFraction = (sliderState.activeRangeStart - rangeStart) / (rangeEnd - rangeStart)
                    val secondFraction = (sliderState.activeRangeEnd - rangeStart) / (rangeEnd - rangeStart)
                    Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
                        val y = center.y
                        val stroke = 14.dp.toPx()
                        drawLine(
                            color = displayedFirstZoneColor,
                            start = Offset(0f, y),
                            end = Offset(size.width * firstFraction, y),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = displayedMiddleZoneColor,
                            start = Offset(size.width * firstFraction, y),
                            end = Offset(size.width * secondFraction, y),
                            strokeWidth = stroke
                        )
                        drawLine(
                            color = displayedThirdZoneColor,
                            start = Offset(size.width * secondFraction, y),
                            end = Offset(size.width, y),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                    }
                }
            )
            SliderValueCallout(
                value = valueFormatter(sliderValues.start.toDouble()),
                color = displayedFirstZoneColor,
                position = if (isHigherBetter) CalloutPosition.BOTTOM else CalloutPosition.TOP,
                handleFraction = startFraction,
                availableWidth = maxWidth,
                availableHeight = maxHeight
            )
            SliderValueCallout(
                value = valueFormatter(sliderValues.endInclusive.toDouble()),
                color = displayedThirdZoneColor,
                position = if (isHigherBetter) CalloutPosition.TOP else CalloutPosition.BOTTOM,
                handleFraction = endFraction,
                availableWidth = maxWidth,
                availableHeight = maxHeight
            )
        }
    }
}

/** Barra de duas cores: até ao limite a viagem é aceite; acima dele é rejeitada. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LongTripThresholdSelector(
    value: Double,
    enabled: Boolean,
    onValueChangeFinished: (Double) -> Unit
) {
    val rangeStart = 5f
    val rangeEnd = 100f
    val stepSize = 5f
    fun snapToStep(candidate: Float): Float {
        val snapped = kotlin.math.round((candidate.coerceIn(rangeStart, rangeEnd) - rangeStart) / stepSize) * stepSize + rangeStart
        return snapped.coerceIn(rangeStart, rangeEnd)
    }
    var selectedValue by remember(value) { mutableFloatStateOf(snapToStep(value.toFloat())) }
    val red = Color(0xFFF44336)
    val green = Color(0xFF4CAF50)
    val displayedRed = if (enabled) red else red.withHalfSaturation()
    val displayedGreen = if (enabled) green else green.withHalfSaturation()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Distância máxima até o destino", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(92.dp)) {
            val fraction = (selectedValue - rangeStart) / (rangeEnd - rangeStart)
            Slider(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                value = selectedValue,
                onValueChange = { selectedValue = it.coerceIn(rangeStart, rangeEnd) },
                onValueChangeFinished = {
                    val snappedValue = snapToStep(selectedValue)
                    selectedValue = snappedValue
                    onValueChangeFinished(snappedValue.toDouble())
                },
                valueRange = rangeStart..rangeEnd,
                steps = ((rangeEnd - rangeStart) / stepSize).toInt() - 1,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                thumb = { CriterionSliderThumb(enabled) },
                track = { sliderState ->
                    val fraction = (sliderState.value - rangeStart) / (rangeEnd - rangeStart)
                    Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
                        val y = center.y
                        val stroke = 14.dp.toPx()
                        drawLine(displayedGreen, Offset(0f, y), Offset(size.width * fraction, y), stroke, StrokeCap.Round)
                        drawLine(displayedRed, Offset(size.width * fraction, y), Offset(size.width, y), stroke, StrokeCap.Round)
                    }
                }
            )
            SliderValueCallout(
                value = "${selectedValue.toInt()} km",
                color = displayedRed,
                position = CalloutPosition.TOP,
                handleFraction = fraction,
                availableWidth = maxWidth,
                availableHeight = maxHeight
            )
        }
    }
}

/** Barra binária: valor abaixo do limite rejeita; no limite ou acima aceita. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinimumTripValueThresholdSelector(
    value: Double,
    enabled: Boolean,
    onValueChangeFinished: (Double) -> Unit
) {
    val rangeStart = 2.5f
    val rangeEnd = 5f
    val stepSize = 0.25f
    fun snapToStep(candidate: Float): Float {
        val snapped = kotlin.math.round((candidate.coerceIn(rangeStart, rangeEnd) - rangeStart) / stepSize) * stepSize + rangeStart
        return snapped.coerceIn(rangeStart, rangeEnd)
    }
    var selectedValue by remember(value) { mutableFloatStateOf(snapToStep(value.toFloat())) }
    val red = Color(0xFFF44336)
    val green = Color(0xFF4CAF50)
    val displayedRed = if (enabled) red else red.withHalfSaturation()
    val displayedGreen = if (enabled) green else green.withHalfSaturation()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Valor mínimo da viagem", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(92.dp)) {
            val fraction = (selectedValue - rangeStart) / (rangeEnd - rangeStart)
            Slider(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                value = selectedValue,
                onValueChange = { selectedValue = it.coerceIn(rangeStart, rangeEnd) },
                onValueChangeFinished = {
                    val snappedValue = snapToStep(selectedValue)
                    selectedValue = snappedValue
                    onValueChangeFinished(snappedValue.toDouble())
                },
                valueRange = rangeStart..rangeEnd,
                steps = ((rangeEnd - rangeStart) / stepSize).toInt() - 1,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                thumb = { CriterionSliderThumb(enabled) },
                track = { sliderState ->
                    val fraction = (sliderState.value - rangeStart) / (rangeEnd - rangeStart)
                    Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
                        val y = center.y
                        val stroke = 14.dp.toPx()
                        drawLine(displayedRed, Offset(0f, y), Offset(size.width * fraction, y), stroke, StrokeCap.Round)
                        drawLine(displayedGreen, Offset(size.width * fraction, y), Offset(size.width, y), stroke, StrokeCap.Round)
                    }
                }
            )
            SliderValueCallout(
                value = String.format(PORTUGUESE_LOCALE, "€ %.2f", selectedValue),
                color = displayedGreen,
                position = CalloutPosition.TOP,
                handleFraction = fraction,
                availableWidth = maxWidth,
                availableHeight = maxHeight
            )
        }
    }
}

private enum class CalloutPosition { TOP, BOTTOM }

@Composable
private fun BoxScope.SliderValueCallout(
    value: String,
    color: Color,
    position: CalloutPosition,
    handleFraction: Float,
    availableWidth: androidx.compose.ui.unit.Dp,
    availableHeight: androidx.compose.ui.unit.Dp
) {
    val density = LocalDensity.current
    var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
    val bubbleWidth = with(density) { bubbleSize.width.toDp() }
    val bubbleHeight = with(density) { bubbleSize.height.toDp() }
    val pointerWidth = 18.dp
    val pointerHeight = 10.dp
    // O marcador tem 18 dp: o seu centro percorre a faixa entre as duas margens de 9 dp.
    val thumbRadius = 9.dp
    val usableTrackWidth = maxOf(availableWidth - thumbRadius * 2f, 0.dp)
    val handleX = (thumbRadius + usableTrackWidth * handleFraction).coerceIn(0.dp, availableWidth)
    val bubbleLeft = if (bubbleWidth > 0.dp) {
        (handleX - bubbleWidth / 2f).coerceIn(0.dp, maxOf(availableWidth - bubbleWidth, 0.dp))
    } else {
        handleX
    }
    val bubbleTop = when (position) {
        CalloutPosition.TOP -> 0.dp
        CalloutPosition.BOTTOM -> maxOf(availableHeight - bubbleHeight, 0.dp)
    }
    val pointerLeft = if (bubbleWidth > 0.dp) {
        val minimumPointerLeft = bubbleLeft
        val maximumPointerLeft = bubbleLeft + maxOf(bubbleWidth - pointerWidth, 0.dp)
        (handleX - pointerWidth / 2f).coerceIn(minimumPointerLeft, maximumPointerLeft)
    } else {
        handleX - pointerWidth / 2f
    }
    val pointerTop = when (position) {
        CalloutPosition.TOP -> bubbleTop + bubbleHeight
        CalloutPosition.BOTTOM -> bubbleTop - pointerHeight
    }

    SliderValueBubble(
        value = value,
        color = color,
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = bubbleLeft, y = bubbleTop)
            .onSizeChanged { bubbleSize = it }
    )
    CalloutPointer(
        color = color,
        pointsUp = position == CalloutPosition.BOTTOM,
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = pointerLeft, y = pointerTop)
    )
}

@Composable
private fun SliderValueBubble(value: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color, RoundedCornerShape(16.dp))
            .border(1.dp, color.copy(alpha = 0.86f), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CalloutPointer(color: Color, pointsUp: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 18.dp, height = 10.dp)) {
        val path = Path().apply {
            if (pointsUp) {
                moveTo(size.width / 2f, 0f)
                lineTo(0f, size.height)
                lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
            }
            close()
        }
        drawPath(path, color)
    }
}
