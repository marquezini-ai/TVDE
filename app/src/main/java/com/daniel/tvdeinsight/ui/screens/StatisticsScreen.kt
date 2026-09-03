package com.daniel.tvdeinsight.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.daniel.tvdeinsight.BuildConfig
import com.daniel.tvdeinsight.data.export.StatisticsExcelExporter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StatisticsScreen(
    paddingValues: PaddingValues,
    viewModel: StatisticsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val licenseViewModel: LicenseViewModel = hiltViewModel()
    val licenseState by licenseViewModel.licenseState.collectAsState()
    val isInteractive = BuildConfig.IS_ADMIN_APP || licenseState.isValid
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentEntries by rememberUpdatedState(state.matchingEntries)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(StatisticsExcelExporter.MIME_TYPE)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val error = runCatching {
                val output = requireNotNull(context.contentResolver.openOutputStream(uri))
                output.use { StatisticsExcelExporter.write(it, currentEntries) }
            }.exceptionOrNull()
            withContext(Dispatchers.Main) {
                val message = if (error == null) {
                    "Excel exportado com ${currentEntries.size} viagem(ns)."
                } else {
                    "Não foi possível exportar o Excel."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Estatísticas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Text(
                "Qualidade e potencial das ofertas recebidas",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        StatisticsFiltersPanel(
            state = state,
            enabled = isInteractive,
            onPlatformToggled = viewModel::togglePlatform,
            onMetricSelected = viewModel::selectMetric,
            onValueModeSelected = viewModel::selectValueMode,
            onShiftSelected = viewModel::selectShift,
            onCardColorSelected = viewModel::selectCardColor,
            onCategorySelected = viewModel::selectCategory,
            onDateRangeSelected = viewModel::selectDateRange
        )
        Spacer(modifier = Modifier.height(16.dp))

        StatisticsDashboard(
            state = state,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { exportLauncher.launch(state.filters.exportFileName()) },
            enabled = isInteractive && state.matchingEntries.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Text("Exportar Excel (${state.matchingTripCount})")
        }

    }
}

@Composable
private fun StatisticsFiltersPanel(
    state: StatisticsUiState,
    enabled: Boolean,
    onPlatformToggled: (com.daniel.tvdeinsight.domain.model.OfferPlatform) -> Unit,
    onMetricSelected: (StatisticsMetric) -> Unit,
    onValueModeSelected: (StatisticsValueMode) -> Unit,
    onShiftSelected: (StatisticsShift) -> Unit,
    onCardColorSelected: (StatisticsCardColor) -> Unit,
    onCategorySelected: (StatisticsCategoryOption?) -> Unit,
    onDateRangeSelected: (Long, Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Filtros",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            )
        }
        PlatformSegmentedControl(
            selectedPlatforms = state.filters.platforms,
            enabled = enabled,
            onPlatformToggled = onPlatformToggled
        )
        StatisticsDateRangeFilter(
            startDateMillis = state.filters.startDateMillis,
            endDateMillis = state.filters.endDateMillis,
            recordedDates = state.recordedDates,
            enabled = enabled,
            onDateRangeSelected = onDateRangeSelected
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatisticsCategoryMenu(
                selected = state.filters.category,
                selectedPlatforms = state.filters.platforms,
                enabled = enabled,
                values = state.availableCategories,
                modifier = Modifier.weight(0.9f),
                onSelected = onCategorySelected
            )
            StatisticsFilterMenu(
                title = "Métrica de valor",
                selected = state.filters.metric,
                enabled = enabled,
                values = StatisticsMetric.entries,
                label = StatisticsMetric::label,
                modifier = Modifier.weight(1.1f),
                onSelected = onMetricSelected
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.filters.metric == StatisticsMetric.VALUE_PER_KM) {
                StatisticsFilterMenu(
                    title = "Km",
                    selected = state.filters.valueMode,
                    enabled = enabled,
                    values = StatisticsValueMode.entries,
                    label = StatisticsValueMode::label,
                    modifier = Modifier.weight(0.82f),
                    onSelected = onValueModeSelected
                )
            }
            StatisticsFilterMenu(
                title = "Turno",
                selected = state.filters.shift,
                enabled = enabled,
                values = StatisticsShift.entries,
                label = StatisticsShift::label,
                modifier = Modifier.weight(1.08f),
                onSelected = onShiftSelected
            )
            StatisticsFilterMenu(
                title = "Tipo de card",
                selected = state.filters.cardColor,
                enabled = enabled,
                values = StatisticsCardColor.entries,
                label = StatisticsCardColor::label,
                modifier = Modifier.weight(1.1f),
                onSelected = onCardColorSelected
            )
        }
    }
}

@Composable
private fun PlatformSegmentedControl(
    selectedPlatforms: Set<com.daniel.tvdeinsight.domain.model.OfferPlatform>,
    enabled: Boolean,
    onPlatformToggled: (com.daniel.tvdeinsight.domain.model.OfferPlatform) -> Unit
) {
    val uber = com.daniel.tvdeinsight.domain.model.OfferPlatform.UBER
    val bolt = com.daniel.tvdeinsight.domain.model.OfferPlatform.BOLT
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.46f)
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            PlatformSegment(
                label = "Uber",
                selected = uber in selectedPlatforms,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onPlatformToggled(uber) }
            )
            PlatformSegment(
                label = "Bolt",
                selected = bolt in selectedPlatforms,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onPlatformToggled(bolt) }
            )
        }
    }
}

@Composable
private fun PlatformSegment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (selected) {
                    Color.White
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun <T> StatisticsFilterMenu(
    title: String,
    selected: T,
    enabled: Boolean,
    values: List<T>,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        CompactFilterAnchor(
            title = title,
            value = label(selected),
            expanded = expanded,
            enabled = enabled,
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(label(value)) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun CompactFilterAnchor(
    title: String,
    value: String,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (expanded) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)
        },
        border = BorderStroke(
            1.dp,
            if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "⌄",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StatisticsCategoryMenu(
    selected: StatisticsCategoryOption?,
    selectedPlatforms: Set<com.daniel.tvdeinsight.domain.model.OfferPlatform>,
    enabled: Boolean,
    values: List<StatisticsCategoryOption>,
    modifier: Modifier = Modifier,
    onSelected: (StatisticsCategoryOption?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = selected?.let {
        if (selectedPlatforms.size > 1) "${it.platform.label} · ${it.name}" else it.name
    } ?: "Todas"
    Box(modifier = modifier.fillMaxWidth()) {
        CompactFilterAnchor(
            title = "Categoria",
            value = selectedLabel,
            expanded = expanded,
            enabled = enabled,
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Todas") },
                onClick = {
                    onSelected(null)
                    expanded = false
                }
            )
            selectedPlatforms.sortedBy { it.ordinal }.forEach { platform ->
                val platformValues = values.filter { it.platform == platform }
                if (selectedPlatforms.size > 1 && platformValues.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(platform.label.uppercase(), fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                        onClick = {},
                        enabled = false
                    )
                }
                platformValues.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatisticsDateRangeFilter(
    startDateMillis: Long?,
    endDateMillis: Long?,
    recordedDates: Set<LocalDate>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onDateRangeSelected: (Long, Long) -> Unit
) {
    var showCalendar by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = { showCalendar = true }),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DateRange,
                contentDescription = "Selecionar data",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = startDateMillis.asDateRangeLabel(endDateMillis),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Abrir calendário",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
    if (showCalendar) {
        val initialStart = startDateMillis.toCalendarDate()
        val initialEnd = endDateMillis.toCalendarDate()
        val today = LocalDate.now()
        var selectedStart by remember(startDateMillis, endDateMillis) { mutableStateOf(initialStart) }
        var selectedEnd by remember(startDateMillis, endDateMillis) { mutableStateOf(initialEnd) }
        var displayedMonth by remember(initialStart) {
            mutableStateOf(YearMonth.from(initialStart ?: today))
        }
        Dialog(
            onDismissRequest = { showCalendar = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Selecionar datas",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = selectedStart.asSelectionLabel(selectedEnd),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        CompactMonthCalendar(
                            month = displayedMonth,
                            today = today,
                            enabled = enabled,
                            selectedStart = selectedStart,
                            selectedEnd = selectedEnd,
                            recordedDates = recordedDates,
                            onMonthChanged = { displayedMonth = it },
                            onDateSelected = { date ->
                                when {
                                    selectedStart == null || selectedEnd != null -> {
                                        selectedStart = date
                                        selectedEnd = null
                                    }
                                    date.isBefore(selectedStart) -> {
                                        selectedEnd = selectedStart
                                        selectedStart = date
                                    }
                                    else -> selectedEnd = date
                                }
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showCalendar = false }) { Text("Cancelar") }
                            TextButton(
                                enabled = selectedStart != null,
                                onClick = {
                                    onDateRangeSelected(
                                        selectedStart!!.toUtcDateMillis(),
                                        (selectedEnd ?: selectedStart!!).toUtcDateMillis()
                                    )
                                    showCalendar = false
                                }
                            ) { Text("OK") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactMonthCalendar(
    month: YearMonth,
    today: LocalDate,
    enabled: Boolean,
    selectedStart: LocalDate?,
    selectedEnd: LocalDate?,
    recordedDates: Set<LocalDate>,
    onMonthChanged: (YearMonth) -> Unit,
    onDateSelected: (LocalDate) -> Unit
) {
    var horizontalDrag by remember(month) { mutableFloatStateOf(0f) }
    val canMoveForward = month.isBefore(YearMonth.from(today))
    val firstDayOffset = month.atDay(1).dayOfWeek.value % DAYS_IN_WEEK
    val totalCells = firstDayOffset + month.lengthOfMonth()
    val calendarRows = (totalCells + DAYS_IN_WEEK - 1) / DAYS_IN_WEEK

    Column(
        modifier = Modifier.pointerInput(month, canMoveForward) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { _, dragAmount -> horizontalDrag += dragAmount },
                onDragCancel = { horizontalDrag = 0f },
                onDragEnd = {
                    when {
                        enabled && horizontalDrag > MONTH_SWIPE_THRESHOLD -> onMonthChanged(month.minusMonths(1))
                        enabled && horizontalDrag < -MONTH_SWIPE_THRESHOLD && canMoveForward -> onMonthChanged(month.plusMonths(1))
                    }
                    horizontalDrag = 0f
                }
            )
        },
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { onMonthChanged(month.minusMonths(1)) },
                enabled = enabled,
                modifier = Modifier.size(40.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("‹", fontSize = 24.sp) }
            Text(
                text = month.format(MONTH_FORMATTER),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(
                onClick = { onMonthChanged(month.plusMonths(1)) },
                enabled = enabled && canMoveForward,
                modifier = Modifier.size(40.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("›", fontSize = 24.sp) }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            WEEKDAY_INITIALS.forEach { day ->
                Text(
                    text = day,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        repeat(calendarRows) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(DAYS_IN_WEEK) { column ->
                    val dayOfMonth = row * DAYS_IN_WEEK + column - firstDayOffset + 1
                    val date = dayOfMonth.takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay)
                    CompactCalendarDay(
                        modifier = Modifier.weight(1f),
                        date = date,
                        enabled = enabled && date != null && !date.isAfter(today),
                        selected = date != null && (date == selectedStart || date == selectedEnd),
                        inSelectedRange = date != null && selectedStart != null && selectedEnd != null &&
                            !date.isBefore(selectedStart) && !date.isAfter(selectedEnd),
                        hasRecord = date != null && date in recordedDates,
                        onClick = { date?.let(onDateSelected) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactCalendarDay(
    modifier: Modifier,
    date: LocalDate?,
    enabled: Boolean,
    selected: Boolean,
    inSelectedRange: Boolean,
    hasRecord: Boolean,
    onClick: () -> Unit
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.primary
        inSelectedRange -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        selected -> MaterialTheme.colorScheme.onPrimary
        inSelectedRange -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .height(38.dp)
            .padding(2.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        date?.let {
            Text(
                text = it.dayOfMonth.toString(),
                color = contentColor,
                fontSize = 14.sp,
                fontWeight = if (hasRecord) FontWeight.Bold else FontWeight.Normal
            )
        }
        if (hasRecord) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
            )
        }
    }
}

private fun Long?.toCalendarDate(): LocalDate? = this?.let {
    Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
}

private fun LocalDate.toUtcDateMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun LocalDate?.asSelectionLabel(end: LocalDate?): String = when {
    this == null -> "Selecione uma data ou um intervalo"
    end == null || end == this -> format(SELECTION_DATE_FORMATTER)
    else -> "${format(SELECTION_DATE_FORMATTER)} – ${end.format(SELECTION_DATE_FORMATTER)}"
}

private val MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale("pt", "PT"))
private val SELECTION_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale("pt", "PT"))
private val WEEKDAY_INITIALS = listOf("D", "S", "T", "Q", "Q", "S", "S")
private const val DAYS_IN_WEEK = 7
private const val MONTH_SWIPE_THRESHOLD = 56f

@Composable
private fun ComparativeBarChart(state: StatisticsUiState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedContent(targetState = state.filters.metric.label, label = "métrica do gráfico") { metric ->
                Text(
                    "Média de ${metric.lowercase()}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
            AnimatedContent(
                targetState = "${state.periodDescription} · ${state.filters.shift.label} · Cards: ${state.filters.cardColor.label.lowercase()} · ${state.matchingTripCount} viagem(ns)",
                label = "resumo do gráfico"
            ) { summary ->
                Text(
                    summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (state.results.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Sem viagens para os filtros selecionados.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val maximum = state.results.maxOf { it.average }.coerceAtLeast(0.01)
                Box(modifier = Modifier.fillMaxWidth().height(250.dp)) {
                    ChartGrid(modifier = Modifier.fillMaxSize())
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 12.dp, start = 8.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        state.results.forEach { result ->
                            StatisticBar(
                                result = result,
                                maximum = maximum,
                                metric = state.filters.metric,
                                animationKey = "${state.filters}-${result.platform}-${result.average}-${result.tripCount}",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartGrid(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        repeat(4) { index ->
            val y = size.height * (index + 1) / 5f
            drawLine(
                color = Color.White.copy(alpha = 0.08f),
                start = androidx.compose.ui.geometry.Offset(0f, y),
                end = androidx.compose.ui.geometry.Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun StatisticBar(
    result: PlatformStatistic,
    maximum: Double,
    metric: StatisticsMetric,
    animationKey: String,
    modifier: Modifier = Modifier
) {
    val color = if (result.platform.name == "UBER") Color(0xFF4C89FF) else Color(0xFF43C78A)
    val fraction = (result.average / maximum).toFloat().coerceIn(0.08f, 1f)
    var shouldAnimate by remember(animationKey) { mutableStateOf(false) }
    LaunchedEffect(animationKey) { shouldAnimate = true }
    val animatedFraction by animateFloatAsState(
        targetValue = if (shouldAnimate) fraction else 0.02f,
        animationSpec = tween(850),
        label = "altura da barra ${result.platform.label}"
    )
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Text(
            "${result.platform.label}: ${result.average.asStatisticValue(metric)}",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(7.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier
                    .width(52.dp)
                    .fillMaxHeight(animatedFraction),
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                color = color.copy(alpha = 0.88f)
            ) {}
        }
        Spacer(Modifier.height(8.dp))
        Text(result.platform.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(
            "${result.tripCount} viagem(ns)",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun Double.asStatisticValue(metric: StatisticsMetric): String = when (metric) {
    StatisticsMetric.VALUE_PER_KM -> String.format(PORTUGUESE_LOCALE, "%.2f €/km", this)
    StatisticsMetric.VALUE_PER_HOUR -> String.format(PORTUGUESE_LOCALE, "%.2f €/h", this)
    StatisticsMetric.TRIP_VALUE, StatisticsMetric.NET_TRIP_VALUE -> String.format(PORTUGUESE_LOCALE, "€ %.2f", this)
}

private val PORTUGUESE_LOCALE = Locale("pt", "PT")

private fun StatisticsFilters.exportFileName(): String {
    val dateFormat = java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT)
    val start = startDateMillis ?: System.currentTimeMillis()
    val end = endDateMillis ?: start
    val periodPart = if (start != end) {
        "${dateFormat.format(java.util.Date(start))}-${dateFormat.format(java.util.Date(end))}"
    } else {
        dateFormat.format(java.util.Date(start))
    }
    val platformPart = platforms.sortedBy { it.ordinal }.joinToString("-") { it.name.lowercase(Locale.ROOT) }
    return "TVDE-Insight-$platformPart-${cardColor.name.lowercase(Locale.ROOT)}-$periodPart.xls"
}

private fun Long?.asDateRangeLabel(endDateMillis: Long?): String {
    if (this == null) return "Selecionar"
    val formatter = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, Locale("pt", "PT"))
    val start = formatter.format(java.util.Date(this))
    val end = formatter.format(java.util.Date(endDateMillis ?: this))
    return if (start == end) start else "$start – $end"
}
