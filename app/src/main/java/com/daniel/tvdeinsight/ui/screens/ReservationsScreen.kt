package com.daniel.tvdeinsight.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daniel.tvdeinsight.R
import com.daniel.tvdeinsight.BuildConfig
import com.daniel.tvdeinsight.reservations.*
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.round
import kotlin.math.roundToInt

/** Interface das Reservas Bolt preservada do aplicativo original, sem segundo botão iniciar. */
@Composable
fun ReservationsScreen(paddingValues: PaddingValues) {
    val context = LocalContext.current
    var showHistory by rememberSaveable { mutableStateOf(false) }
    if (showHistory) {
        FullReservationHistory(paddingValues, onBack = { showHistory = false })
        return
    }
    var settings by remember { mutableStateOf(AppPreferences.loadSettings(context)) }
    fun save(next: ReservationSettings) { settings = next; AppPreferences.saveSettings(context, next) }
    var diagnosticTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000L); diagnosticTick++ } }

    Column(
        Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Reserva Bolt", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Defina os critérios e a disponibilidade das suas reservas.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            FilledTonalButton(onClick = { showHistory = true }) { Text("Histórico") }
        }
        ReservationFloatingControl(diagnosticTick)
        ReservationCard("Categorias") {
            listOf("Bolt", "Green", "Comfort", "Premium", "XL", "Pet").forEach { category ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(category, fontWeight = FontWeight.SemiBold)
                    Switch(checked = category in settings.categories, onCheckedChange = { checked ->
                        val next = settings.categories.toMutableSet().apply { if (checked) add(category) else remove(category) }
                        save(settings.copy(categories = next))
                    })
                }
            }
        }
        ReservationCard("Critérios de valor", settings.criteriaLocked, { save(settings.copy(criteriaLocked = !settings.criteriaLocked)) }) {
            DecimalReservationSlider("Preço mínimo da viagem", settings.minimumTripValue, 100.0, .5, !settings.criteriaLocked) { save(settings.copy(minimumTripValue = it)) }
            DecimalReservationSlider("Valor mínimo por km", settings.minimumPerKm, 5.0, .05, !settings.criteriaLocked) { save(settings.copy(minimumPerKm = it)) }
            MaxTripDistanceReservationSlider(settings.maxTripDistanceKm, !settings.criteriaLocked) { save(settings.copy(maxTripDistanceKm = it)) }
            OutlinedTextField(settings.homeAddress, { save(settings.copy(homeAddress = it)) }, enabled = !settings.criteriaLocked, modifier = Modifier.fillMaxWidth(), label = { Text("Morada de referência") }, placeholder = { Text("Ex.: Rua..., Porto") }, singleLine = true)
            ReservationAddressMap(settings.homeAddress, settings.maxPickupDistanceKm)
            RadiusReservationSlider(settings.maxPickupDistanceKm, !settings.criteriaLocked) { save(settings.copy(maxPickupDistanceKm = it)) }
        }
        AvailabilityCard(settings, ::save)
        if (BuildConfig.IS_ADMIN_APP) {
            ReservationCard("Ritmo de procura", settings.refreshLocked, { save(settings.copy(refreshLocked = !settings.refreshLocked)) }) {
                WholeReservationSlider("Intervalo entre tentativas", settings.searchWaitMillis.toInt(), 100, 10_000, 100, "ms", !settings.refreshLocked) { save(settings.copy(searchWaitMillis = it.toLong())) }
                WholeReservationSlider("Tempo para atualizar a lista", settings.refreshDelayMillis.toInt(), 50, 5_000, 50, "ms", !settings.refreshLocked) { save(settings.copy(refreshDelayMillis = it.toLong())) }
            }
        }
        ReservationCard(null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Limite diário", fontWeight = FontWeight.SemiBold)
                Switch(checked = settings.dailyLimitEnabled, onCheckedChange = { save(settings.copy(dailyLimitEnabled = it)) })
            }
            DailyReservationLimitSlider(settings.maxDailyReservations, settings.dailyLimitEnabled) { save(settings.copy(maxDailyReservations = it)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun ReservationFloatingControl(tick: Int) {
    @Suppress("UNUSED_VARIABLE") val refresh = tick
    val context = LocalContext.current
    val enabled = AppPreferences.isOverlayVisible(context)
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .2f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(180))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Ativar", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Switch(
                checked = enabled,
                onCheckedChange = { shouldEnable ->
                    if (shouldEnable && !ReservationOverlayController.setEnabled(context, true)) {
                        context.startActivity(ReservationOverlayController.overlayPermissionIntent(context))
                    } else if (!shouldEnable) {
                        ReservationOverlayController.setEnabled(context, false)
                    }
                }
            )
        }
    }
}

/** Histórico transplantado da aba original das Reservas Bolt. */
@Composable private fun FullReservationHistory(paddingValues: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshToken by remember { mutableIntStateOf(0) }
    val entries = remember(refreshToken) { RideHistoryStore.list(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Todas") }
    var filterMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.ms-excel")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                output.write(buildReservationHistoryXls(RideHistoryStore.list(context)).toByteArray(Charsets.UTF_8))
            } ?: error("Não foi possível abrir o destino")
        }.onFailure { DiagnosticLogger.log("Falha ao exportar histórico XLS", it) }
    }
    val filteredEntries = entries.filter { ride ->
        val matchesFilter = when (filter) {
            "Aceites" -> ride.accepted
            "Recusadas" -> !ride.accepted
            else -> true
        }
        val search = query.trim().lowercase(Locale.ROOT)
        matchesFilter && (search.isBlank() || listOf(ride.date, ride.time, ride.category, ride.origin, ride.destination)
            .any { it.lowercase(Locale.ROOT).contains(search) })
    }
    Column(Modifier.fillMaxSize().padding(paddingValues).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Histórico", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onBack) { Text("Voltar") }
        }
        Text("Ofertas apresentadas, incluindo as que não foram aceites.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Pesquisar histórico") },
            singleLine = true
        )
        Box {
            OutlinedButton(onClick = { filterMenuExpanded = true }) { Text("Filtro: $filter") }
            DropdownMenu(expanded = filterMenuExpanded, onDismissRequest = { filterMenuExpanded = false }) {
                listOf("Todas", "Aceites", "Recusadas").forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { filter = option; filterMenuExpanded = false })
                }
            }
        }
        if (entries.isEmpty()) {
            Text("Ainda não foram apresentadas viagens.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (filteredEntries.isEmpty()) {
            Text("Nenhuma viagem corresponde ao filtro.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filteredEntries, key = { it.id }) { ride -> OriginalReservationHistoryCard(ride) }
                item {
                    OutlinedButton(
                        onClick = { exportLauncher.launch("historico-viagens-${System.currentTimeMillis()}.xls") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Exportar histórico (.xls)") }
                }
                item {
                    OutlinedButton(onClick = { RideHistoryStore.clear(context); refreshToken++ }, modifier = Modifier.fillMaxWidth()) {
                        Text("Limpar histórico")
                    }
                }
            }
        }
    }
}

@Composable private fun OriginalReservationHistoryCard(ride: PresentedRide) {
    val colors = MaterialTheme.colorScheme
    val statusColor = if (ride.accepted) Color(0xFF2E7D32) else Color(0xFFC62828)
    val cardColor = statusColor.copy(alpha = .18f).compositeOver(colors.surface)
    val contentColor = colors.onSurface
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp), color = cardColor, tonalElevation = 3.dp, shadowElevation = 7.dp,
        border = BorderStroke(1.dp, statusColor), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(if (ride.accepted) "ACEITE" else "RECUSADA", color = statusColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("${ride.date} · ${ride.time}", color = contentColor, fontWeight = FontWeight.Bold)
            OriginalCriterionLine("Categoria", ride.category, ride.categoryPassed)
            OriginalCriterionLine("Valor da viagem", "${MoneyParser.format(ride.payout)} €", ride.tripValuePassed)
            OriginalCriterionLine("Valor Km", "${MoneyParser.format(ride.payout / ride.distanceKm.coerceAtLeast(0.01))} €", ride.perKmPassed)
            OriginalCriterionLine("Distância da viagem", "${formatHistoryDistance(ride.distanceKm)} km", ride.tripDistancePassed)
            OriginalCriterionLine("Disponibilidade", ride.time, ride.availabilityPassed)
            if (ride.pickupDistanceKm != null || ride.pickupDistancePassed != null) {
                OriginalCriterionLine("Distância de recolha", ride.pickupDistanceKm?.let { "${formatHistoryDistance(it)} km" } ?: "não calculada", ride.pickupDistancePassed == true)
            }
            HorizontalDivider(color = contentColor.copy(alpha = 0.18f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Origem: ${ride.origin.ifBlank { "não identificada" }}", color = contentColor, fontSize = 13.sp)
                    Text("Destino: ${ride.destination.ifBlank { "não identificado" }}", color = contentColor, fontSize = 13.sp)
                }
                IconButton(
                    onClick = {
                        val routeUrl = "https://www.google.com/maps/dir/?api=1&origin=${Uri.encode(ride.origin)}&destination=${Uri.encode(ride.destination)}"
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(routeUrl))) }
                            .onFailure { DiagnosticLogger.log("Falha ao abrir rota no Google Maps", it) }
                    },
                    enabled = ride.origin.isNotBlank() && ride.destination.isNotBlank()
                ) {
                    Icon(painterResource(R.drawable.ic_google_maps), "Abrir rota no Google Maps", tint = Color.Unspecified, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

@Composable private fun OriginalCriterionLine(label: String, value: String, passed: Boolean) {
    val colors = MaterialTheme.colorScheme
    val color = if (passed) Color(0xFF2E7D32) else Color(0xFFC62828)
    val background = color.copy(alpha = .14f).compositeOver(colors.surface)
    val contentColor = colors.onSurface
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(8.dp), color = background, border = BorderStroke(1.dp, color.copy(alpha = 0.75f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = contentColor, fontSize = 13.sp)
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

private fun formatHistoryDistance(value: Double): String =
    if (value % 1.0 == 0.0) String.format(Locale("pt", "PT"), "%.0f", value)
    else String.format(Locale("pt", "PT"), "%.1f", value)

private fun buildReservationHistoryXls(entries: List<PresentedRide>): String = buildString {
    append("<html><head><meta charset=\"UTF-8\"><style>td,th{border:1px solid #999;padding:5px;}th{background:#222;color:#fff;}td.num{mso-number-format:'0,00';}</style></head><body><table><thead><tr>")
    listOf("ID", "Estado", "Data", "Horário", "Categoria", "Valor da viagem (€)", "Valor/km (€)", "Distância do trajeto (km)", "Distância de recolha (km)", "Origem", "Destino").forEach { append("<th>${escapeHistoryXls(it)}</th>") }
    append("</tr></thead><tbody>")
    entries.forEach { ride ->
        val valuePerKm = ride.payout / ride.distanceKm.coerceAtLeast(0.01)
        append("<tr><td>${escapeHistoryXls(ride.id)}</td><td>${if (ride.accepted) "ACEITE" else "RECUSADA"}</td><td>${escapeHistoryXls(ride.date)}</td><td>${escapeHistoryXls(ride.time)}</td><td>${escapeHistoryXls(ride.category)}</td>")
        append("<td class=\"num\">${formatHistoryExportNumber(ride.payout)}</td><td class=\"num\">${formatHistoryExportNumber(valuePerKm)}</td><td class=\"num\">${formatHistoryExportNumber(ride.distanceKm)}</td><td class=\"num\">${ride.pickupDistanceKm?.let(::formatHistoryExportNumber).orEmpty()}</td><td>${escapeHistoryXls(ride.origin)}</td><td>${escapeHistoryXls(ride.destination)}</td></tr>")
    }
    append("</tbody></table></body></html>")
}

private fun formatHistoryExportNumber(value: Double): String = String.format(Locale("pt", "PT"), "%.2f", value)
private fun escapeHistoryXls(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

@Composable private fun AvailabilityCard(settings: ReservationSettings, onSave: (ReservationSettings) -> Unit) {
    var selectedDay by rememberSaveable { mutableIntStateOf(LocalDate.now().dayOfWeek.value) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val fallback = DailyAvailability(settings.startMinutes, settings.endMinutes)
    val schedules = settings.weeklyAvailability.ifEmpty { WeeklyAvailability.defaultSchedules(settings.startMinutes, settings.endMinutes) }
    val activeDays = settings.enabledDays.filter { it in 1..7 }.sorted()
    LaunchedEffect(activeDays) {
        if (selectedDay !in activeDays) selectedDay = activeDays.firstOrNull() ?: ALL_DAYS
    }
    val selected = WeeklyAvailability.scheduleFor(schedules, selectedDay, fallback)
    val previousDay = if (selectedDay == 1) 7 else selectedDay - 1
    val previous = WeeklyAvailability.scheduleFor(schedules, previousDay, fallback)
    ReservationCard("Disponibilidade", settings.availabilityLocked, { onSave(settings.copy(availabilityLocked = !settings.availabilityLocked)) }) {
        Text("Dias ativos", fontWeight = FontWeight.SemiBold)
        (1..7).forEach { day ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(weekday(day), fontWeight = FontWeight.SemiBold)
                Switch(
                    checked = day in settings.enabledDays,
                    enabled = !settings.availabilityLocked,
                    onCheckedChange = { enabled ->
                        val updated = settings.enabledDays.toMutableSet().apply { if (enabled) add(day) else remove(day) }
                        onSave(settings.copy(enabledDays = updated))
                    }
                )
            }
        }
        if (activeDays.isEmpty()) {
            Text("Ative pelo menos um dia para definir o horário.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@ReservationCard
        }
        Text("Dia da semana", fontWeight = FontWeight.SemiBold)
        Box { OutlinedButton({ expanded = true }, enabled = !settings.availabilityLocked) { Text(weekday(selectedDay)) }; DropdownMenu(expanded, { expanded = false }) { activeDays.forEach { day -> DropdownMenuItem({ Text(weekday(day)) }, { selectedDay = day; expanded = false }) } } }
        AvailabilityBar(selectedDay, schedules, fallback, settings.enabledDays)
        if (previousDay in settings.enabledDays && previous.startMinutes > previous.endMinutes) {
            Text("Herdada de ${weekday(previousDay)}: 00:00 → ${clock(previous.endMinutes)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        TimeReservationSlider("Início", selected.startMinutes, !settings.availabilityLocked, true) { value ->
            val changed = selected.copy(startMinutes = value)
            val updated = schedules.toMutableMap().apply { put(selectedDay, changed) }
            onSave(settings.copy(weeklyAvailability = updated, startMinutes = value))
        }
        TimeReservationSlider("Fim", selected.endMinutes, !settings.availabilityLocked, false) { value ->
            val changed = selected.copy(endMinutes = value)
            val updated = schedules.toMutableMap().apply { put(selectedDay, changed) }
            onSave(settings.copy(weeklyAvailability = updated, endMinutes = value))
        }
        Text("Janela de ${weekday(selectedDay)}: ${clock(selected.startMinutes)} → ${clock(selected.endMinutes)}" + if (selected.startMinutes > selected.endMinutes) " (termina no dia seguinte)" else "", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun AvailabilityBar(day: Int, schedules: Map<Int, DailyAvailability>, fallback: DailyAvailability, enabledDays: Set<Int>) {
    val monday = LocalDate.of(2024, 1, 1); val date = monday.plusDays((if (day == ALL_DAYS) 0 else day - 1).toLong()); val slots = WeeklyAvailability.effectiveSlots(schedules, date, fallback, enabledDays)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Disponibilidade efetiva em ${weekday(day)}", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) { slots.forEach { enabled -> Box(Modifier.weight(1f).fillMaxSize().background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(.12f))) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("00:00", color = Color.Gray, fontSize = 11.sp); Text("12:00", color = Color.Gray, fontSize = 11.sp); Text("23:30", color = Color.Gray, fontSize = 11.sp) }
    }
}

@Composable private fun ReservationCard(title: String?, locked: Boolean? = null, onLock: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .2f)),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(180))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!title.isNullOrBlank()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp); if (locked != null && onLock != null) IconButton(onLock) { Icon(painterResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open), if (locked) "Desbloquear barras" else "Bloquear barras", tint = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
            content()
        }
    }
}

@Composable private fun DecimalReservationSlider(label: String, value: Double, max: Double, step: Double, enabled: Boolean, onFinish: (Double) -> Unit) {
    var local by remember(value) { mutableStateOf(value.coerceIn(0.0, max).toFloat()) }
    Column { Text("$label: ${MoneyParser.format(local.toDouble())} €", fontWeight = FontWeight.SemiBold); Slider(local, { local = snap(it.toDouble(), step, max).toFloat() }, valueRange = 0f..max.toFloat(), steps = (max / step).toInt() - 1, enabled = enabled, colors = reservationSliderColors(), onValueChangeFinished = { onFinish(local.toDouble()) }) }
}

@Composable private fun WholeReservationSlider(label: String, value: Int, min: Int, max: Int, step: Int, suffix: String, enabled: Boolean, onFinish: (Int) -> Unit) {
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Column { Text("$label: ${local.toInt()} $suffix", fontWeight = FontWeight.SemiBold); Slider(local, { local = snap(it.toDouble(), step.toDouble(), max.toDouble(), min.toDouble()).toFloat() }, valueRange = min.toFloat()..max.toFloat(), steps = ((max - min) / step) - 1, enabled = enabled, colors = reservationSliderColors(), onValueChangeFinished = { onFinish(local.toInt()) }) }
}

@Composable private fun RadiusReservationSlider(value: Int, enabled: Boolean, onChanged: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.coerceIn(0, 10).toFloat()) }
    Column {
        Text("Distância de recolha (raio): ${local.toInt()} km", fontWeight = FontWeight.SemiBold)
        Slider(
            value = local,
            onValueChange = {
                local = snap(it.toDouble(), 1.0, 10.0).toFloat()
                onChanged(local.toInt())
            },
            valueRange = 0f..10f,
            steps = 9,
            enabled = enabled,
            colors = reservationSliderColors()
        )
    }
}

@Composable private fun MaxTripDistanceReservationSlider(value: Double, enabled: Boolean, onChanged: (Double) -> Unit) {
    val values = remember { TripDistanceScale.values }
    var localIndex by remember(value) { mutableFloatStateOf(values.indices.minBy { kotlin.math.abs(values[it] - value) }.toFloat()) }
    Column {
        Text("Distância máxima da viagem: ${TripDistanceScale.format(values[localIndex.roundToInt()])} km", fontWeight = FontWeight.SemiBold)
        Slider(
            value = localIndex,
            onValueChange = { next ->
                localIndex = next.roundToInt().coerceIn(values.indices).toFloat()
                onChanged(values[localIndex.roundToInt()])
            },
            valueRange = values.indices.first.toFloat()..values.indices.last.toFloat(),
            steps = values.size - 2,
            enabled = enabled,
            colors = reservationSliderColors()
        )
    }
}

@Composable private fun DailyReservationLimitSlider(value: Int, enabled: Boolean, onChanged: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.coerceIn(1, 30).toFloat()) }
    Column {
        Text("Reservas (${local.toInt()}) reserva(s)", fontWeight = FontWeight.SemiBold)
        Slider(
            value = local,
            onValueChange = {
                local = it.roundToInt().coerceIn(1, 30).toFloat()
                onChanged(local.toInt())
            },
            valueRange = 1f..30f,
            steps = 28,
            enabled = enabled,
            colors = reservationSliderColors()
        )
    }
}

@Composable private fun TimeReservationSlider(label: String, value: Int, enabled: Boolean, start: Boolean, onFinish: (Int) -> Unit) {
    var local by remember(value) { mutableIntStateOf(value.coerceIn(0, 23 * 60 + 30)) }
    Column {
        Text("$label: ${clock(local)}", fontWeight = FontWeight.SemiBold)
        Slider(
            value = local.toFloat(),
            onValueChange = { local = snap(it.toDouble(), 30.0, (23 * 60 + 30).toDouble()).toInt() },
            valueRange = 0f..(23 * 60 + 30).toFloat(),
            steps = 46,
            enabled = enabled,
            colors = availabilityTimeSliderColors(start),
            onValueChangeFinished = { onFinish(local) }
        )
    }
}

/** Mantém todas as barras das Reservas no mesmo padrão visual da TVDE Insight. */
@Composable private fun reservationSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent
)

/** Mantém o marcador e altera somente o sentido visual das horas de disponibilidade. */
@Composable private fun availabilityTimeSliderColors(isStart: Boolean) = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = if (isStart) MaterialTheme.colorScheme.onSurface.copy(alpha = .12f) else MaterialTheme.colorScheme.primary,
    inactiveTrackColor = if (isStart) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .12f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent
)

private fun snap(value: Double, step: Double, max: Double, min: Double = 0.0) = (round(value / step) * step).coerceIn(min, max)
private fun clock(minutes: Int) = String.format(Locale("pt", "PT"), "%02d:%02d", minutes / 60, minutes % 60)
private fun weekday(day: Int) = when (day) { ALL_DAYS -> "Todos"; 1 -> "Segunda-feira"; 2 -> "Terça-feira"; 3 -> "Quarta-feira"; 4 -> "Quinta-feira"; 5 -> "Sexta-feira"; 6 -> "Sábado"; else -> "Domingo" }
private const val ALL_DAYS = 0
