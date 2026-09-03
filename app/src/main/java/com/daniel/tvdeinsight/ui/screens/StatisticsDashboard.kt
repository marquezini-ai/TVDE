package com.daniel.tvdeinsight.ui.screens

import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daniel.tvdeinsight.domain.model.DecisionType
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val UberBlue = Color(0xFF4C89FF)
private val BoltGreen = Color(0xFF35C98A)
private val AcceptGreen = Color(0xFF31C86A)
private val AnalyzeYellow = Color(0xFFFFBE36)
private val RejectRed = Color(0xFFF05252)

@Composable
internal fun StatisticsDashboard(state: StatisticsUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatisticsSummarySection(state)
        PlatformComparisonSection(state)
        TrendChartCard(state)
        DecisionDistributionCard(state)
        RejectionReasonsCard(state)
        // Indicadores coletivos: sempre no fim e nesta sequência.
        PickupMunicipalitiesCard(state)
        BestTimesHeatmapCard(state)
        BestDaysCalendarCard(state)
    }
}

@Composable
private fun StatisticsSummarySection(state: StatisticsUiState) {
    val summary = state.summary
    val kmLabel = if (state.filters.valueMode == StatisticsValueMode.FREE) "Km livre mediano" else "Km bruto mediano"
    val cards = buildList {
        add(SummaryItem(summary.totalOffers.toString(), "Ofertas"))
        add(SummaryItem(summary.averageTripValue.currency(), "Valor médio"))
        add(SummaryItem(summary.medianPerKm.perKm(), kmLabel))
        add(SummaryItem(summary.medianPerHour.perHour(), "Hora mediana"))
        summary.averageNetTripValue?.let { add(SummaryItem(it.currency(), "Líquido médio")) }
        add(SummaryItem(summary.greenPercentage.percentage(), "Ofertas verdes"))
        summary.averagePickupDistance?.let { add(SummaryItem(it.distance(), "Recolha média")) }
        summary.bestPlatform?.let { platform ->
            val detail = summary.bestPlatformAdvantagePercent
                ?.takeIf { it > 0.0 }
                ?.let { "Melhor plataforma · +${it.rounded()}%" }
                ?: "Melhor plataforma"
            add(SummaryItem(platform.label, detail))
        }
    }
    SectionTitle("Resumo do período", "Indicadores das ofertas selecionadas")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.chunked(2).forEach { rowCards ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowCards.forEach { item -> SummaryCard(item, Modifier.weight(1f)) }
                if (rowCards.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class SummaryItem(val value: String, val label: String)

@Composable
private fun SummaryCard(item: SummaryItem, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(86.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = item.value,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PlatformComparisonSection(state: StatisticsUiState) {
    SectionTitle("Uber × Bolt", "Comparação baseada na mediana da métrica selecionada")
    if (state.results.isEmpty()) {
        EmptyStatisticsCard("Sem ofertas para comparar.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.results.forEach { result ->
            val color = result.platform.platformColor()
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(color, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(result.platform.label, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${result.tripCount} oferta(s)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        result.median.statisticValue(state.filters),
                        color = color,
                        fontWeight = FontWeight.Black,
                        fontSize = 25.sp
                    )
                    Text("Mediana de ${state.filters.metric.label.lowercase()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        CompactMetric(result.averageTripValue.currency(), "Valor médio")
                        CompactMetric(result.medianPerKm.perKm(), if (state.filters.valueMode == StatisticsValueMode.FREE) "Km livre" else "Km bruto")
                        CompactMetric(result.medianPerHour.perHour(), "Hora")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        CompactMetric(result.averagePickupDistance?.distance() ?: "—", "Recolha")
                        CompactMetric(result.greenPercentage.percentage(), "Verdes")
                        CompactMetric(result.averageNetTripValue?.currency() ?: "—", "Líquido")
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun PickupMunicipalitiesCard(state: StatisticsUiState) {
    SectionCard {
        SectionTitle(
            "Melhores municípios",
            "Top 5 pela mediana de ${state.filters.metric.label.lowercase()}"
        )
        if (state.pickupMunicipalities.isEmpty()) {
            EmptyChartMessage("São necessárias moradas de recolha com município reconhecido para esta análise.")
            return@SectionCard
        }
        val lastRank = (state.pickupMunicipalities.size - 1).coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            state.pickupMunicipalities.forEachIndexed { index, municipality ->
                val strength = 1f - index.toFloat() / lastRank
                val color = lerp(RejectRed, AcceptGreen, strength)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(28.dp),
                        shape = CircleShape,
                        color = color
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = (index + 1).toString(),
                                color = Color(0xFF171717),
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            municipality.municipality,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${municipality.tripCount} oferta(s)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                    Text(
                        municipality.median.statisticValue(state.filters),
                        color = color,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendChartCard(state: StatisticsUiState) {
    SectionCard {
        SectionTitle("Evolução", "Mediana diária de ${state.filters.metric.label.lowercase()}")
        if (state.trend.isEmpty()) {
            EmptyChartMessage("Sem dados para construir a evolução.")
            return@SectionCard
        }
        val dates = state.trend.map(TrendPoint::date).distinct().sorted()
        val maximum = state.trend.maxOf(TrendPoint::value).coerceAtLeast(0.01)
        var startAnimation by remember(state.filters, state.trend) { mutableStateOf(false) }
        LaunchedEffect(state.filters, state.trend) { startAnimation = true }
        val progress by animateFloatAsState(
            targetValue = if (startAnimation) 1f else 0f,
            animationSpec = tween(800),
            label = "evolução das ofertas"
        )
        val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
        val dateLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
        val chartSurfaceColor = MaterialTheme.colorScheme.surface
        val dateLabelFontSize = when {
            dates.size <= 6 -> 9.sp
            dates.size <= 10 -> 8.sp
            else -> 7.sp
        }
        Column(modifier = Modifier.fillMaxWidth()) {
                Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                    val chartTop = 20.dp.toPx()
                    val chartBottom = size.height - 20.dp.toPx()
                    val chartHeight = chartBottom - chartTop
                    repeat(4) { index ->
                        val y = chartTop + chartHeight * (index + 1) / 5f
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    dates.forEachIndexed { index, _ ->
                        val x = size.width * (index + 0.5f) / dates.size
                        drawLine(
                            color = gridColor,
                            start = Offset(x, chartTop),
                            end = Offset(x, chartBottom),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    val pointPositions = mutableMapOf<TrendPoint, Offset>()
                    listOf(OfferPlatform.UBER, OfferPlatform.BOLT).forEach { platform ->
                        val points = state.trend.filter { it.platform == platform }.sortedBy(TrendPoint::date)
                        var previous: Offset? = null
                        points.forEach { point ->
                            val dateIndex = dates.indexOf(point.date)
                            val x = size.width * (dateIndex + 0.5f) / dates.size
                            val targetY = chartBottom - (point.value / maximum).toFloat() * chartHeight
                            val y = chartBottom - (chartBottom - targetY) * progress
                            val current = Offset(x, y)
                            previous?.let {
                                drawLine(platform.platformColor(), it, current, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                            }
                            drawCircle(platform.platformColor(), radius = 4.dp.toPx(), center = current)
                            pointPositions[point] = current
                            previous = current
                        }
                    }
                    val labelAlpha = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    if (labelAlpha > 0f) {
                        val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                            textSize = dateLabelFontSize.toPx()
                            textAlign = AndroidPaint.Align.CENTER
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            alpha = (255 * labelAlpha).toInt()
                        }
                        val backgroundPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                            color = chartSurfaceColor.copy(alpha = 0.94f * labelAlpha).toArgb()
                        }
                        val horizontalPadding = 4.dp.toPx()
                        val verticalPadding = 2.dp.toPx()
                        val labelCorner = 5.dp.toPx()
                        val horizontalShift = if (dates.size <= 6) 18.dp.toPx() else 8.dp.toPx()
                        val pointGap = 7.dp.toPx()
                        val fontMetrics = textPaint.fontMetrics
                        val minimumBaseline = verticalPadding - fontMetrics.ascent
                        val maximumBaseline = size.height - verticalPadding - fontMetrics.descent
                        val laneStep = textPaint.textSize + 5.dp.toPx()
                        val safeLanes = buildList {
                            var baseline = minimumBaseline
                            while (baseline <= maximumBaseline) {
                                add(baseline)
                                baseline += laneStep
                            }
                        }
                        val placedBounds = mutableListOf<RectF>()
                        val pointsToLabel = state.trend
                            .filter(pointPositions::containsKey)
                            .sortedWith(compareBy<TrendPoint>({ it.date }, { it.platform.ordinal }))
                        pointsToLabel.forEach { point ->
                            val position = pointPositions.getValue(point)
                            val label = point.value.trendPointLabel(state.filters.metric)
                            val textWidth = textPaint.measureText(label)
                            val minX = textWidth / 2f + horizontalPadding
                            val maxX = size.width - textWidth / 2f - horizontalPadding
                            val pointsOnDate = pointsToLabel.count { it.date == point.date }
                            val hasBothPlatforms = pointsOnDate > 1
                            val requestedX = position.x + when {
                                !hasBothPlatforms -> 0f
                                point.platform == OfferPlatform.UBER -> -horizontalShift
                                else -> horizontalShift
                            }
                            val candidateXs = listOf(
                                requestedX,
                                requestedX - horizontalShift,
                                requestedX + horizontalShift,
                                position.x
                            ).map { candidate ->
                                if (minX <= maxX) candidate.coerceIn(minX, maxX) else size.width / 2f
                            }.distinct()
                            val aboveBaseline = position.y - pointGap - fontMetrics.descent
                            val belowBaseline = position.y + pointGap - fontMetrics.ascent
                            val preferredBaseline = if (point.platform == OfferPlatform.BOLT && hasBothPlatforms) {
                                belowBaseline
                            } else {
                                aboveBaseline
                            }
                            val candidateBaselines = buildList {
                                add(preferredBaseline)
                                add(aboveBaseline)
                                add(belowBaseline)
                                addAll(listOf(-48f, -32f, -16f, 16f, 32f, 48f).map { preferredBaseline + it.dp.toPx() })
                                addAll(safeLanes)
                                addAll(safeLanes.asReversed())
                            }.map { it.coerceIn(minimumBaseline, maximumBaseline) }.distinct()
                            var selectedX: Float? = null
                            var selectedBaseline: Float? = null
                            var selectedBounds: RectF? = null
                            candidateBaselines.forEach { baseline ->
                                if (selectedBounds != null) return@forEach
                                candidateXs.forEach { labelX ->
                                    if (selectedBounds != null) return@forEach
                                    val bounds = RectF(
                                        labelX - textWidth / 2f - horizontalPadding,
                                        baseline + fontMetrics.ascent - verticalPadding,
                                        labelX + textWidth / 2f + horizontalPadding,
                                        baseline + fontMetrics.descent + verticalPadding
                                    )
                                    val overlaps = placedBounds.any { placed ->
                                        bounds.left < placed.right &&
                                            bounds.right > placed.left &&
                                            bounds.top < placed.bottom &&
                                            bounds.bottom > placed.top
                                    }
                                    if (!overlaps) {
                                        selectedX = labelX
                                        selectedBaseline = baseline
                                        selectedBounds = bounds
                                    }
                                }
                            }
                            selectedBounds?.let { bounds ->
                                placedBounds += bounds
                                drawContext.canvas.nativeCanvas.drawRoundRect(
                                    bounds,
                                    labelCorner,
                                    labelCorner,
                                    backgroundPaint
                                )
                                textPaint.color = point.platform.platformColor().toArgb()
                                drawContext.canvas.nativeCanvas.drawText(
                                    label,
                                    selectedX ?: position.x,
                                    selectedBaseline ?: position.y,
                                    textPaint
                                )
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    dates.forEach { date ->
                        Text(
                            date.chartDateLabel(),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            fontSize = dateLabelFontSize,
                            lineHeight = (dateLabelFontSize.value + 1f).sp,
                            color = dateLabelColor,
                            maxLines = 2
                        )
                    }
                }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChartLegend("Uber", UberBlue)
            ChartLegend("Bolt", BoltGreen)
        }
    }
}

@Composable
private fun DecisionDistributionCard(state: StatisticsUiState) {
    SectionCard {
        SectionTitle("Qualidade das ofertas", "Distribuição pela cor apresentada no card")
        if (state.summary.totalOffers == 0) {
            EmptyChartMessage("Sem ofertas no período selecionado.")
            return@SectionCard
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(18.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            state.decisionDistribution.filter { it.count > 0 }.forEach { item ->
                Box(
                    Modifier
                        .weight(item.percentage.toFloat().coerceAtLeast(1f))
                        .fillMaxHeight()
                        .background(item.decisionType.decisionColor(), RoundedCornerShape(8.dp))
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.decisionDistribution.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(item.decisionType.decisionColor(), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(item.decisionType.statisticsLabel(), modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text("${item.count} · ${item.percentage.percentage()}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun RejectionReasonsCard(state: StatisticsUiState) {
    SectionCard {
        SectionTitle("Motivos de rejeição", "Critérios responsáveis pelos cards vermelhos")
        if (state.rejectionReasons.isEmpty()) {
            EmptyChartMessage("Nenhuma rejeição para os filtros selecionados.")
            return@SectionCard
        }
        val maximum = state.rejectionReasons.maxOf(RejectionReasonStatistic::count).coerceAtLeast(1)
        var startAnimation by remember(state.filters, state.rejectionReasons) { mutableStateOf(false) }
        LaunchedEffect(state.filters, state.rejectionReasons) { startAnimation = true }
        val progress by animateFloatAsState(
            targetValue = if (startAnimation) 1f else 0f,
            animationSpec = tween(700),
            label = "motivos de rejeição"
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.rejectionReasons.forEach { reason ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(reason.label, modifier = Modifier.weight(1f), fontSize = 12.sp)
                        Text(reason.count.toString(), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                        Box(
                            Modifier
                                .fillMaxWidth((reason.count.toFloat() / maximum * progress).coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(RejectRed, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BestTimesHeatmapCard(state: StatisticsUiState) {
    SectionCard {
        SectionTitle("Melhores horários")
        val values = state.heatmap.mapNotNull(HeatmapCell::value)
        if (values.isEmpty()) {
            EmptyChartMessage("São necessárias ofertas em mais horários para esta análise.")
            return@SectionCard
        }
        val minimum = values.minOrNull() ?: 0.0
        val maximum = values.maxOrNull() ?: minimum
        val colorRange = maximum - minimum
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(52.dp))
            DAY_LABELS.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HEATMAP_SHIFTS.forEach { shift ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(shift.shortLabel, modifier = Modifier.width(52.dp), fontSize = 9.sp)
                DayOfWeek.entries.forEach { day ->
                    val cell = state.heatmap.first { it.shift == shift && it.dayOfWeek == day }
                    val strength = cell.value?.let { value ->
                        if (colorRange <= 0.0) 0.5f else ((value - minimum) / colorRange).toFloat().coerceIn(0f, 1f)
                    } ?: 0f
                    val color = if (cell.value == null) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    } else {
                        heatmapColor(strength)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(2.dp)
                            .height(34.dp)
                            .background(color, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (cell.value == null) {
                            Text("—", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            val contentColor = if (strength in 0.30f..0.70f) Color(0xFF171717) else Color.White
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    cell.value.compactEuroValue(state.filters),
                                    color = contentColor,
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    state.filters.metric.compactContext(),
                                    color = contentColor.copy(alpha = 0.84f),
                                    fontSize = 6.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Baseado nas ofertas guardadas; horários com poucos registos podem variar.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun BestDaysCalendarCard(state: StatisticsUiState) {
    SectionCard {
        SectionTitle(
            "Melhores dias",
            "Média diária de ${state.filters.metric.label.lowercase()}"
        )
        val days = state.dailyCalendar
        if (days.isEmpty()) {
            EmptyChartMessage("Sem dados para construir o calendário.")
            return@SectionCard
        }

        val firstDate = days.first().date
        val lastDate = days.last().date
        Text(
            text = "${firstDate.format(SHORT_DATE)} – ${lastDate.format(SHORT_DATE)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            DAY_LABELS.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        val leadingEmptyCells = firstDate.dayOfWeek.value - DayOfWeek.MONDAY.value
        val gridCells = buildList<DailyMetricStatistic?> {
            repeat(leadingEmptyCells) { add(null) }
            addAll(days)
            repeat((7 - size % 7) % 7) { add(null) }
        }
        val values = days.mapNotNull(DailyMetricStatistic::average)
        val minimum = values.minOrNull() ?: 0.0
        val maximum = values.maxOrNull() ?: minimum
        val range = maximum - minimum

        gridCells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    if (day == null) {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .padding(2.dp)
                                .height(52.dp)
                        )
                    } else {
                        val strength = day.average?.let { value ->
                            if (range <= 0.0) 0.5f else ((value - minimum) / range).toFloat().coerceIn(0f, 1f)
                        }
                        val cellColor = day.average?.let { heatmapColor(strength ?: 0.5f) }
                            ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                        val contentColor = if (strength != null && strength in 0.30f..0.70f) {
                            Color(0xFF171717)
                        } else {
                            Color.White
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(2.dp)
                                .height(52.dp)
                                .background(cellColor, RoundedCornerShape(8.dp))
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = day.date.dayOfMonth.toString(),
                                color = contentColor.copy(alpha = 0.82f),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = day.average?.compactEuroValue(state.filters) ?: "—",
                                color = contentColor,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (day.tripCount > 0) {
                                Text(
                                    text = "${day.tripCount} oferta${if (day.tripCount == 1) "" else "s"}",
                                    color = contentColor.copy(alpha = 0.78f),
                                    fontSize = 6.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            text = if (values.isEmpty()) {
                "Sem ofertas no intervalo apresentado."
            } else {
                "A cor compara os dias com registos; verde indica a média mais alta."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun EmptyStatisticsCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun EmptyChartMessage(message: String) {
    Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 12.sp)
    }
}

@Composable
private fun ChartLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 10.sp)
    }
}

private fun OfferPlatform.platformColor(): Color = if (this == OfferPlatform.UBER) UberBlue else BoltGreen

private fun DecisionType.decisionColor(): Color = when (this) {
    DecisionType.ACEITAR -> AcceptGreen
    DecisionType.ANALISAR -> AnalyzeYellow
    DecisionType.REJEITAR -> RejectRed
}

private fun DecisionType.statisticsLabel(): String = when (this) {
    DecisionType.ACEITAR -> "Aceitar"
    DecisionType.ANALISAR -> "Analisar"
    DecisionType.REJEITAR -> "Rejeitar"
}

private fun Double.statisticValue(filters: StatisticsFilters): String = when (filters.metric) {
    StatisticsMetric.VALUE_PER_KM -> perKm()
    StatisticsMetric.VALUE_PER_HOUR -> perHour()
    StatisticsMetric.TRIP_VALUE, StatisticsMetric.NET_TRIP_VALUE -> currency()
}

private fun Double.compactEuroValue(filters: StatisticsFilters): String = when (filters.metric) {
    StatisticsMetric.VALUE_PER_KM -> String.format(PORTUGUESE_LOCALE, "%.2f €", this)
    StatisticsMetric.VALUE_PER_HOUR,
    StatisticsMetric.TRIP_VALUE,
    StatisticsMetric.NET_TRIP_VALUE -> String.format(PORTUGUESE_LOCALE, "%.1f €", this)
}

private fun StatisticsMetric.compactContext(): String = when (this) {
    StatisticsMetric.VALUE_PER_KM -> "por km"
    StatisticsMetric.VALUE_PER_HOUR -> "por hora"
    StatisticsMetric.TRIP_VALUE -> "viagem"
    StatisticsMetric.NET_TRIP_VALUE -> "líquido"
}

private fun Double.trendPointLabel(metric: StatisticsMetric): String = when (metric) {
    StatisticsMetric.VALUE_PER_KM -> String.format(PORTUGUESE_LOCALE, "%.2f €", this)
    StatisticsMetric.VALUE_PER_HOUR,
    StatisticsMetric.TRIP_VALUE,
    StatisticsMetric.NET_TRIP_VALUE -> String.format(PORTUGUESE_LOCALE, "%.1f €", this)
}

private fun heatmapColor(position: Float): Color = if (position <= 0.5f) {
    lerp(RejectRed, AnalyzeYellow, position * 2f)
} else {
    lerp(AnalyzeYellow, AcceptGreen, (position - 0.5f) * 2f)
}

private fun java.time.LocalDate.chartDateLabel(): String {
    val day = dayOfWeek.getDisplayName(TextStyle.FULL, PORTUGUESE_LOCALE)
        .replace("-feira", "", ignoreCase = true)
        .replaceFirstChar { it.titlecase(PORTUGUESE_LOCALE) }
    return "${format(SHORT_DATE)}\n$day"
}

private fun Double.currency(): String = String.format(PORTUGUESE_LOCALE, "€ %.2f", this)
private fun Double.perKm(): String = String.format(PORTUGUESE_LOCALE, "%.2f €/km", this)
private fun Double.perHour(): String = String.format(PORTUGUESE_LOCALE, "%.1f €/h", this)
private fun Double.distance(): String = String.format(PORTUGUESE_LOCALE, "%.1f km", this)
private fun Double.percentage(): String = String.format(PORTUGUESE_LOCALE, "%.0f%%", this)
private fun Double.rounded(): String = String.format(PORTUGUESE_LOCALE, "%.0f", this)

private val PORTUGUESE_LOCALE = Locale("pt", "PT")
private val SHORT_DATE = DateTimeFormatter.ofPattern("dd/MM", PORTUGUESE_LOCALE)
private val DAY_LABELS = listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")
private val HEATMAP_SHIFTS = listOf(
    StatisticsShift.DAWN,
    StatisticsShift.MORNING,
    StatisticsShift.AFTERNOON,
    StatisticsShift.NIGHT
)
