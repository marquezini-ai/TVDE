package com.daniel.tvdeinsight.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.daniel.tvdeinsight.domain.model.DecisionType
import com.daniel.tvdeinsight.domain.model.EvaluationCriterion
import com.daniel.tvdeinsight.domain.model.OfferHistoryEntry
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import com.daniel.tvdeinsight.data.screenshot.OfferScreenshotStore
import com.daniel.tvdeinsight.domain.location.GoogleMapsRouteUrlBuilder
import com.daniel.tvdeinsight.domain.location.PortugueseAddressFormatter
import com.daniel.tvdeinsight.R
import com.daniel.tvdeinsight.ui.theme.decisionColors
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.io.File
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    paddingValues: PaddingValues,
    resetToListToken: Int,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val history by viewModel.history.collectAsState()
    var selectedEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedEntry = history.firstOrNull { it.id == selectedEntryId }

    LaunchedEffect(resetToListToken) {
        selectedEntryId = null
    }

    BackHandler(enabled = selectedEntry != null) {
        selectedEntryId = null
    }

    if (selectedEntry != null) {
        HistoryDetailScreen(
            paddingValues = paddingValues,
            entry = selectedEntry,
            screenshotStore = viewModel.screenshotStore,
            onBack = { selectedEntryId = null }
        )
        return
    }

    val entriesByDay = history.groupBy { entry -> entry.dayKey() }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "Histórico",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = if (history.isEmpty()) {
                        "As ofertas avaliadas aparecerão aqui."
                    } else {
                        "${history.size} oferta(s) analisada(s)"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }

        if (history.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Text(
                        text = "Ainda não há viagens para mostrar.",
                        modifier = Modifier.padding(22.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            entriesByDay.forEach { (day, entries) ->
                stickyHeader(key = "day-$day") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Text(
                            text = entries.first().dayLabel(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp, bottom = 1.dp)
                        )
                    }
                }
                items(entries, key = { entry -> entry.id }) { entry ->
                    HistorySummaryCard(entry = entry, onClick = { selectedEntryId = entry.id })
                }
            }
        }
    }
}

@Composable
private fun HistorySummaryCard(entry: OfferHistoryEntry, onClick: () -> Unit) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val style = decisionColors(entry.decisionType, darkTheme)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = style.background,
        contentColor = style.content,
        tonalElevation = 3.dp,
        shadowElevation = 7.dp,
        border = BorderStroke(1.dp, style.border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlatformSummaryMetric(entry.platform, Modifier.weight(0.72f))
            SummaryMetric(entry.tripValue.asCurrency(), "Valor", Modifier.weight(1f))
            SummaryMetric(entry.valorPorKm.asCurrency(), "Km", Modifier.weight(0.92f))
            SummaryMetric(entry.valorPorHora.asCurrency(), "Hora", Modifier.weight(0.96f))
        }
    }
}

@Composable
private fun PlatformSummaryMetric(platform: OfferPlatform, modifier: Modifier = Modifier) {
    val contentColor = LocalContentColor.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Plataforma",
            color = contentColor.copy(alpha = 0.7f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(1.dp))
        PlatformIcon(platform = platform)
    }
}

@Composable
private fun PlatformIcon(platform: OfferPlatform, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(28.dp),
        shape = CircleShape,
        color = Color.Transparent
    ) {
        Image(
            painter = painterResource(if (platform == OfferPlatform.BOLT) R.drawable.ic_bolt else R.drawable.ic_uber),
            contentDescription = platform.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SummaryMetric(value: String, title: String, modifier: Modifier = Modifier) {
    val contentColor = LocalContentColor.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            color = contentColor.copy(alpha = 0.7f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = value,
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HistoryDetailScreen(
    paddingValues: PaddingValues,
    entry: OfferHistoryEntry,
    screenshotStore: OfferScreenshotStore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val screenshotFile = remember(entry.screenshotFileName) {
        screenshotStore.fileFor(entry.screenshotFileName)
    }
    var screenshotOpen by rememberSaveable(entry.id, entry.screenshotFileName) { mutableStateOf(false) }
    if (screenshotOpen && screenshotFile != null) {
        OfferScreenshotScreen(
            paddingValues = paddingValues,
            screenshotFile = screenshotFile,
            onBack = { screenshotOpen = false }
        )
        return
    }
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val style = decisionColors(entry.decisionType, darkTheme)
    val routeUrl = GoogleMapsRouteUrlBuilder.build(
        originAtOffer = entry.routeOriginAtOffer(),
        pickup = entry.pickupAddress,
        destination = entry.destinationAddress
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBack,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text("← Voltar", color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(modifier = Modifier.weight(1f))
            if (screenshotFile != null) {
                IconButton(onClick = { screenshotOpen = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_screenshot),
                        contentDescription = "Abrir captura de tela",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = entry.decisionTitle(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = style.background,
                contentColor = style.content,
                border = BorderStroke(1.dp, style.border),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DetailHeaderValue("Plataforma", entry.platform.label)
                    DetailHeaderValue("Data", entry.dateTimeLabel())
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DetailHeaderValue(
                            "Categoria",
                            entry.category.orUnavailable(),
                            Modifier.weight(1f)
                        )
                        DetailHeaderValue(
                            "Valor hora",
                            entry.valorPorHora.asCurrency(),
                            Modifier.weight(1f)
                        )
                    }
                    val showToll = entry.platform == OfferPlatform.BOLT && entry.tollAmount > 0.0
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DetailHeaderValue("Valor", entry.tripValue.asCurrency(), Modifier.weight(1f))
                        DetailHeaderValue(
                            "Líquido",
                            (entry.netTripValue ?: entry.tripValue).asCurrency(),
                            Modifier.weight(1f)
                        )
                        DetailHeaderValue("Km", entry.valorPorKmBruto.asCurrency(), Modifier.weight(1f))
                        DetailHeaderValue("Km livre", entry.valorPorKm.asCurrency(), Modifier.weight(1f))
                        if (showToll) {
                            DetailHeaderValue("Portagem", entry.tollAmount.asCurrency(), Modifier.weight(1f))
                        }
                    }

                    DetailSectionTitle("Motivo")
                    Text(
                        text = entry.decisionReason(),
                        color = style.content,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    entry.currentLocationDisplay()?.let { location ->
                        DetailSectionTitle("Localização no momento da oferta")
                        DetailAddress(
                            address = location,
                            onClick = {
                                val latitude = entry.currentLocationLatitude
                                val longitude = entry.currentLocationLongitude
                                if (latitude != null && longitude != null) {
                                    context.openCoordinates(latitude, longitude)
                                } else {
                                    context.openAddress(location)
                                }
                            }
                        )
                    }

                    DetailSectionTitle("Recolha")
                    DetailAddress(
                        address = entry.pickupAddress,
                        onClick = context::openAddress
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DetailHeaderValue("Km", entry.pickupDistanceKm.asDistance(), Modifier.weight(1f))
                        DetailHeaderValue("Tempo", entry.pickupDurationMinutes.asDuration(), Modifier.weight(1f))
                    }

                    DetailSectionTitle("Destino")
                    DetailAddress(
                        address = entry.destinationAddress,
                        onClick = context::openAddress
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DetailHeaderValue("Km", entry.destinationDistanceKm.asDistance(), Modifier.weight(1f))
                        DetailHeaderValue("Tempo", entry.destinationDurationMinutes.asDuration(), Modifier.weight(1f))
                    }

                    RouteMapButton(
                        enabled = routeUrl != null,
                        onClick = { routeUrl?.let(context::openGoogleMapsUrl) },
                    )
                }
            }
        }
    }
}

/** Pré-visualização interna: a imagem não sai da aplicação até o utilizador escolher Baixar. */
@Composable
private fun OfferScreenshotScreen(
    paddingValues: PaddingValues,
    screenshotFile: File,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val image = remember(screenshotFile.absolutePath, screenshotFile.lastModified()) {
        BitmapFactory.decodeFile(screenshotFile.absolutePath)?.asImageBitmap()
    }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    screenshotFile.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Não foi possível criar o ficheiro")
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Captura baixada.", Toast.LENGTH_LONG).show()
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Não foi possível baixar a captura: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(paddingValues)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBack,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) { Text("← Voltar", color = MaterialTheme.colorScheme.onSurface) }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { downloadLauncher.launch("captura-oferta-${System.currentTimeMillis()}.jpg") }) {
                Text("Baixar")
            }
        }
        if (image == null) {
            Text(
                text = "A captura já não está disponível neste dispositivo.",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Image(
                bitmap = image,
                contentDescription = "Captura da oferta",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun DetailSectionTitle(title: String) {
    val contentColor = LocalContentColor.current
    Text(
        text = title,
        color = contentColor.copy(alpha = 0.78f),
        fontSize = 14.sp,
        fontWeight = FontWeight.ExtraBold
    )
}

@Composable
private fun DetailHeaderValue(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val contentColor = LocalContentColor.current
    Column(modifier = modifier) {
        Text(
            title,
            color = contentColor.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            value,
            color = contentColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DetailAddress(address: String?, onClick: (String) -> Unit) {
    val contentColor = LocalContentColor.current
    val cleanAddress = PortugueseAddressFormatter.withoutCountry(address)
    val readableAddress = cleanAddress.orUnavailable()
    Text(
        readableAddress,
        modifier = if (cleanAddress == null) Modifier else Modifier.clickable { onClick(cleanAddress) },
        color = contentColor,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun RouteMapButton(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .size(46.dp)
                .clickable(enabled = enabled, onClick = onClick),
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent
        ) {
            Image(
                painter = painterResource(R.drawable.ic_google_maps),
                contentDescription = "Abrir rota no Google Maps",
                alpha = if (enabled) 1f else 0.35f,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun OfferHistoryEntry.decisionTitle(): String = when (decisionType) {
    DecisionType.ACEITAR -> "ACEITA"
    DecisionType.REJEITAR -> "REJEITADA"
    DecisionType.ANALISAR -> "ANALISADA"
}

private fun OfferHistoryEntry.decisionReason(): String {
    if (isStopRejection) return "Rejeitada por paradas intermediárias."

    val prefix = when (decisionType) {
        DecisionType.ACEITAR -> "Aceita"
        DecisionType.REJEITAR -> "Rejeitada"
        DecisionType.ANALISAR -> "Analisada"
    }
    // O valor mínimo é binário e só deve ser exposto no histórico quando rejeita a oferta.
    val visibleActiveCriteria = activeCriteria.filter { criterion ->
        criterion != EvaluationCriterion.VALOR_MINIMO || decisionType == DecisionType.REJEITAR
    }
    val responsibleCriteria = visibleActiveCriteria.filter { criterionDecisions[it] == decisionType }
        .ifEmpty { visibleActiveCriteria }
    if (responsibleCriteria.isEmpty()) return "$prefix pelos critérios ativos."
    return "$prefix ${responsibleCriteria.reasonPhrase()}."
}

private fun List<EvaluationCriterion>.reasonPhrase(): String {
    val phrases = sortedBy(EvaluationCriterion::ordinal).map(EvaluationCriterion::reasonLabel)
    return when (phrases.size) {
        0 -> "pelos critérios ativos"
        1 -> phrases.single()
        2 -> "${phrases[0]} e ${phrases[1]}"
        else -> phrases.dropLast(1).joinToString(", ") + " e ${phrases.last()}"
    }
}

private fun EvaluationCriterion.reasonLabel(): String = when (this) {
    EvaluationCriterion.RECOLHA -> "pela distância de recolha"
    EvaluationCriterion.KM -> "pelo valor por quilómetro"
    EvaluationCriterion.HORA -> "pelo valor por hora"
    EvaluationCriterion.VIAGEM_LONGA -> "pela distância do destino"
    EvaluationCriterion.VALOR_MINIMO -> "pelo valor mínimo"
}

private fun OfferHistoryEntry.dayKey(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(recordedAtMillis))

private fun OfferHistoryEntry.dayLabel(): String =
    DateFormat.getDateInstance(DateFormat.FULL, Locale("pt", "PT")).format(Date(recordedAtMillis))

private fun OfferHistoryEntry.dateTimeLabel(): String =
    DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT, Locale("pt", "PT"))
        .format(Date(recordedAtMillis))

private fun Double.asCurrency(): String = String.format(PORTUGUESE_LOCALE, "€ %.2f", this)

private fun Double?.asDistance(): String = this?.let {
    String.format(PORTUGUESE_LOCALE, "%.1f km", it)
} ?: "Não disponível"

private fun Double?.asDuration(): String = this?.let {
    String.format(PORTUGUESE_LOCALE, "%.0f min", it)
} ?: "Não disponível"

private fun OfferHistoryEntry.currentLocationDisplay(): String? =
    PortugueseAddressFormatter.withoutCountry(currentLocationAddress)
    ?: if (currentLocationLatitude != null && currentLocationLongitude != null) {
        String.format(PORTUGUESE_LOCALE, "%.5f, %.5f", currentLocationLatitude, currentLocationLongitude)
    } else {
        null
    }

private fun String?.orUnavailable(): String = this?.takeIf(String::isNotBlank) ?: "Não disponível"

private fun OfferHistoryEntry.routeOriginAtOffer(): String? =
    if (currentLocationLatitude != null && currentLocationLongitude != null) {
        String.format(Locale.US, "%.6f,%.6f", currentLocationLatitude, currentLocationLongitude)
    } else {
        PortugueseAddressFormatter.withoutCountry(currentLocationAddress)
    }

private fun Context.openGoogleMapsUrl(url: String) = startGoogleMapsIntent(Uri.parse(url))

private fun Context.openAddress(address: String) {
    val uri = Uri.parse("geo:0,0").buildUpon().appendQueryParameter("q", address).build()
    startGoogleMapsIntent(uri)
}

private fun Context.openCoordinates(latitude: Double, longitude: Double) {
    val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
    startGoogleMapsIntent(uri)
}

private fun Context.startGoogleMapsIntent(uri: Uri) {
    val preferredIntent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
    val intent = if (preferredIntent.resolveActivity(packageManager) != null) {
        preferredIntent
    } else {
        Intent(Intent.ACTION_VIEW, uri)
    }
    startActivity(intent)
}

private val PORTUGUESE_LOCALE = Locale("pt", "PT")
