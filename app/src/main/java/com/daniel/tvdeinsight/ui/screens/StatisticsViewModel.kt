package com.daniel.tvdeinsight.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daniel.tvdeinsight.data.repository.OfferAnalysisStore
import com.daniel.tvdeinsight.domain.location.PortugueseMunicipalityCatalog
import com.daniel.tvdeinsight.domain.location.PortugueseMunicipalityMatcher
import com.daniel.tvdeinsight.domain.model.DecisionType
import com.daniel.tvdeinsight.domain.model.EvaluationCriterion
import com.daniel.tvdeinsight.domain.model.OfferHistoryEntry
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class StatisticsMetric(val label: String) {
    VALUE_PER_KM("Quilómetros"),
    VALUE_PER_HOUR("Hora"),
    TRIP_VALUE("Viagem"),
    NET_TRIP_VALUE("Viagem líquida")
}

enum class StatisticsValueMode(val label: String) {
    FREE("Livre"), GROSS("Bruto")
}

enum class StatisticsShift(val label: String, val shortLabel: String) {
    ALL("Todos", "Todos"),
    MORNING("Dia · 06:00–11:59", "Dia"),
    AFTERNOON("Tarde · 12:00–17:59", "Tarde"),
    NIGHT("Noite · 18:00–23:59", "Noite"),
    DAWN("Madrugada · 00:00–05:59", "Madr.")
}

enum class StatisticsCardColor(val label: String, val decisionType: DecisionType?) {
    ALL("Todas", null),
    GREEN("Verdes", DecisionType.ACEITAR),
    YELLOW("Amarelas", DecisionType.ANALISAR),
    RED("Vermelhas", DecisionType.REJEITAR)
}

data class StatisticsCategoryOption(val platform: OfferPlatform, val name: String)

data class StatisticsFilters(
    val platforms: Set<OfferPlatform> = setOf(OfferPlatform.UBER, OfferPlatform.BOLT),
    val metric: StatisticsMetric = StatisticsMetric.VALUE_PER_KM,
    val valueMode: StatisticsValueMode = StatisticsValueMode.FREE,
    val shift: StatisticsShift = StatisticsShift.ALL,
    val cardColor: StatisticsCardColor = StatisticsCardColor.ALL,
    val category: StatisticsCategoryOption? = null,
    val startDateMillis: Long? = null,
    val endDateMillis: Long? = null
)

data class PlatformStatistic(
    val platform: OfferPlatform,
    val average: Double,
    val median: Double,
    val tripCount: Int,
    val averageTripValue: Double,
    val averageNetTripValue: Double?,
    val medianPerKm: Double,
    val medianPerHour: Double,
    val averagePickupDistance: Double?,
    val greenPercentage: Double
)

data class StatisticsSummary(
    val totalOffers: Int = 0,
    val averageTripValue: Double = 0.0,
    val medianPerKm: Double = 0.0,
    val medianPerHour: Double = 0.0,
    val averageNetTripValue: Double? = null,
    val averagePickupDistance: Double? = null,
    val greenPercentage: Double = 0.0,
    val bestPlatform: OfferPlatform? = null,
    val bestPlatformAdvantagePercent: Double? = null
)

data class DecisionStatistic(val decisionType: DecisionType, val count: Int, val percentage: Double)
data class RejectionReasonStatistic(val label: String, val count: Int)
data class TrendPoint(val date: LocalDate, val platform: OfferPlatform, val value: Double, val tripCount: Int)
data class HeatmapCell(
    val dayOfWeek: DayOfWeek,
    val shift: StatisticsShift,
    val value: Double?,
    val tripCount: Int
)

/** Agrupamento das ofertas pelo município validado na morada de recolha. */
data class PickupMunicipalityStatistic(
    val municipality: String,
    val median: Double,
    val tripCount: Int
)

data class DailyMetricStatistic(
    val date: LocalDate,
    val average: Double?,
    val tripCount: Int
)

data class StatisticsUiState(
    val filters: StatisticsFilters = StatisticsFilters(),
    val results: List<PlatformStatistic> = emptyList(),
    val summary: StatisticsSummary = StatisticsSummary(),
    val decisionDistribution: List<DecisionStatistic> = emptyList(),
    val rejectionReasons: List<RejectionReasonStatistic> = emptyList(),
    val trend: List<TrendPoint> = emptyList(),
    val heatmap: List<HeatmapCell> = emptyList(),
    val pickupMunicipalities: List<PickupMunicipalityStatistic> = emptyList(),
    val dailyCalendar: List<DailyMetricStatistic> = emptyList(),
    val matchingTripCount: Int = 0,
    val matchingEntries: List<OfferHistoryEntry> = emptyList(),
    val periodDescription: String = "",
    val availableCategories: List<StatisticsCategoryOption> = emptyList(),
    val recordedDates: Set<LocalDate> = emptySet()
)

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    analysisStore: OfferAnalysisStore
) : ViewModel() {
    private val filters = MutableStateFlow(StatisticsFilters())

    val uiState: StateFlow<StatisticsUiState> = combine(
        analysisStore.history,
        analysisStore.globalHistory,
        filters
    ) { ownHistory, globalHistory, selected ->
        StatisticsCalculator.calculate(ownHistory, globalHistory, selected)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatisticsUiState())

    fun togglePlatform(platform: OfferPlatform) = updateFilters {
        val updated = if (platform in platforms) platforms - platform else platforms + platform
        if (updated.isEmpty()) this else copy(
            platforms = updated,
            category = category?.takeIf { it.platform in updated }
        )
    }

    fun selectMetric(value: StatisticsMetric) = updateFilters { copy(metric = value) }
    fun selectValueMode(value: StatisticsValueMode) = updateFilters { copy(valueMode = value) }
    fun selectShift(value: StatisticsShift) = updateFilters { copy(shift = value) }
    fun selectCardColor(value: StatisticsCardColor) = updateFilters { copy(cardColor = value) }
    fun selectCategory(value: StatisticsCategoryOption?) = updateFilters { copy(category = value) }
    fun selectDateRange(startDateMillis: Long, endDateMillis: Long) = updateFilters {
        copy(startDateMillis = startDateMillis, endDateMillis = endDateMillis)
    }

    private fun updateFilters(transform: StatisticsFilters.() -> StatisticsFilters) {
        filters.value = filters.value.transform()
    }
}

internal object StatisticsCalculator {
    /** Mantém a variante de uma fonte para os testes e usos pontuais. */
    fun calculate(
        history: List<OfferHistoryEntry>,
        filters: StatisticsFilters,
        nowMillis: Long = System.currentTimeMillis(),
        municipalityMatcher: PortugueseMunicipalityMatcher = PortugueseMunicipalityCatalog.matcher
    ): StatisticsUiState = calculateForHistory(history, filters, nowMillis, municipalityMatcher)

    /**
     * Os indicadores pessoais são sempre calculados de [ownHistory]. Os três indicadores
     * coletivos são substituídos pelos dados completos recebidos da Google Sheet.
     */
    fun calculate(
        ownHistory: List<OfferHistoryEntry>,
        globalHistory: List<OfferHistoryEntry>,
        filters: StatisticsFilters,
        nowMillis: Long = System.currentTimeMillis(),
        municipalityMatcher: PortugueseMunicipalityMatcher = PortugueseMunicipalityCatalog.matcher
    ): StatisticsUiState {
        val personal = calculateForHistory(ownHistory, filters, nowMillis, municipalityMatcher)
        val global = calculateForHistory(globalHistory, filters, nowMillis, municipalityMatcher)
        return personal.copy(
            pickupMunicipalities = global.pickupMunicipalities,
            heatmap = global.heatmap,
            dailyCalendar = global.dailyCalendar,
            recordedDates = global.recordedDates
        )
    }

    private fun calculateForHistory(
        history: List<OfferHistoryEntry>,
        filters: StatisticsFilters,
        nowMillis: Long,
        municipalityMatcher: PortugueseMunicipalityMatcher
    ): StatisticsUiState {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val oldestAllowed = filters.startDateMillis
            ?.asLocalDate()
            ?.atStartOfDay(zone)
            ?.toInstant()
            ?.toEpochMilli()
            ?: now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val newestExclusive = (filters.endDateMillis ?: filters.startDateMillis)
            ?.asLocalDate()
            ?.plusDays(1)
            ?.atStartOfDay(zone)
            ?.toInstant()
            ?.toEpochMilli()
        val wantedPlatforms = filters.platforms
            .filter { it == OfferPlatform.UBER || it == OfferPlatform.BOLT }
            .sortedBy(OfferPlatform::ordinal)
        val filtered = history.filter { entry ->
            entry.platform in wantedPlatforms &&
                entry.recordedAtMillis >= oldestAllowed &&
                (newestExclusive == null || entry.recordedAtMillis < newestExclusive) &&
                entry.matchesShift(filters.shift, zone) &&
                entry.matchesCardColor(filters.cardColor) &&
                entry.matchesCategory(filters.category)
        }
        val calendarEndDate = (filters.endDateMillis ?: filters.startDateMillis)
            ?.asLocalDate()
            ?: now.toLocalDate()
        val calendarStartDate = calendarEndDate.minusDays((CALENDAR_DAYS - 1).toLong())
        val selectedStartDate = filters.startDateMillis?.asLocalDate()
        val selectedEndDate = (filters.endDateMillis ?: filters.startDateMillis)?.asLocalDate()
        val calendarEntries = history.filter { entry ->
            val date = Instant.ofEpochMilli(entry.recordedAtMillis).atZone(zone).toLocalDate()
            entry.platform in wantedPlatforms &&
                date >= calendarStartDate && date <= calendarEndDate &&
                (selectedStartDate == null || date >= selectedStartDate) &&
                (selectedEndDate == null || date <= selectedEndDate) &&
                entry.matchesShift(filters.shift, zone) &&
                entry.matchesCardColor(filters.cardColor) &&
                entry.matchesCategory(filters.category)
        }
        val dailyCalendar = (0 until CALENDAR_DAYS).map { offset ->
            val date = calendarStartDate.plusDays(offset.toLong())
            val entries = calendarEntries.filter { entry ->
                Instant.ofEpochMilli(entry.recordedAtMillis).atZone(zone).toLocalDate() == date
            }
            DailyMetricStatistic(
                date = date,
                average = entries.mapNotNull { it.metricValue(filters) }.averageOrNull(),
                tripCount = entries.size
            )
        }
        val results = wantedPlatforms.mapNotNull { platform ->
            val entries = filtered.filter { it.platform == platform }
            val selectedValues = entries.mapNotNull { it.metricValue(filters) }
            if (entries.isEmpty() || selectedValues.isEmpty()) return@mapNotNull null
            PlatformStatistic(
                platform = platform,
                average = selectedValues.average(),
                median = selectedValues.median(),
                tripCount = entries.size,
                averageTripValue = entries.map(OfferHistoryEntry::tripValue).average(),
                averageNetTripValue = entries.mapNotNull(OfferHistoryEntry::netTripValue).averageOrNull(),
                medianPerKm = entries.map { it.perKm(filters.valueMode) }.median(),
                medianPerHour = entries.map(OfferHistoryEntry::valorPorHora).median(),
                averagePickupDistance = entries.mapNotNull(OfferHistoryEntry::pickupDistanceKm).averageOrNull(),
                greenPercentage = entries.percentageOf { it.decisionType == DecisionType.ACEITAR }
            )
        }
        val bestResult = results.maxByOrNull(PlatformStatistic::median)
        val comparisonResult = results.filterNot { it.platform == bestResult?.platform }
            .maxByOrNull(PlatformStatistic::median)
        val bestAdvantage = if (bestResult != null && comparisonResult != null && comparisonResult.median > 0.0) {
            ((bestResult.median - comparisonResult.median) / comparisonResult.median) * 100.0
        } else null
        val summary = StatisticsSummary(
            totalOffers = filtered.size,
            averageTripValue = filtered.map(OfferHistoryEntry::tripValue).averageOrZero(),
            medianPerKm = filtered.map { it.perKm(filters.valueMode) }.medianOrZero(),
            medianPerHour = filtered.map(OfferHistoryEntry::valorPorHora).medianOrZero(),
            averageNetTripValue = filtered.mapNotNull(OfferHistoryEntry::netTripValue).averageOrNull(),
            averagePickupDistance = filtered.mapNotNull(OfferHistoryEntry::pickupDistanceKm).averageOrNull(),
            greenPercentage = filtered.percentageOf { it.decisionType == DecisionType.ACEITAR },
            bestPlatform = bestResult?.platform,
            bestPlatformAdvantagePercent = bestAdvantage
        )
        val decisionDistribution = DecisionType.entries.map { decision ->
            val count = filtered.count { it.decisionType == decision }
            DecisionStatistic(decision, count, if (filtered.isEmpty()) 0.0 else count * 100.0 / filtered.size)
        }
        val trend = filtered.groupBy { entry ->
            Instant.ofEpochMilli(entry.recordedAtMillis).atZone(zone).toLocalDate() to entry.platform
        }.mapNotNull { (key, entries) ->
            val values = entries.mapNotNull { it.metricValue(filters) }
            if (values.isEmpty()) null else TrendPoint(key.first, key.second, values.median(), entries.size)
        }.sortedWith(compareBy(TrendPoint::date, TrendPoint::platform))
        val heatmapShifts = listOf(
            StatisticsShift.DAWN,
            StatisticsShift.MORNING,
            StatisticsShift.AFTERNOON,
            StatisticsShift.NIGHT
        )
        val heatmap = heatmapShifts.flatMap { shift ->
            DayOfWeek.entries.map { day ->
                val entries = filtered.filter {
                    val instant = Instant.ofEpochMilli(it.recordedAtMillis).atZone(zone)
                    instant.dayOfWeek == day && it.matchesShift(shift, zone)
                }
                val values = entries.mapNotNull { it.metricValue(filters) }
                HeatmapCell(day, shift, values.takeIf { it.isNotEmpty() }?.median(), entries.size)
            }
        }
        val pickupMunicipalities = filtered
            .mapNotNull { entry ->
                val municipality = municipalityMatcher.findInPickupAddress(entry.pickupAddress)
                    ?: return@mapNotNull null
                val metricValue = entry.metricValue(filters) ?: return@mapNotNull null
                municipality to metricValue
            }
            .groupBy({ (municipality, _) -> municipality.lowercase() }, { it })
            .map { (_, values) ->
                val municipalityName = values.first().first
                PickupMunicipalityStatistic(
                    municipality = municipalityName,
                    median = values.map { it.second }.median(),
                    tripCount = values.size
                )
            }
            .sortedWith(
                compareByDescending(PickupMunicipalityStatistic::median)
                    .thenBy(PickupMunicipalityStatistic::municipality)
            )
            .take(TOP_PICKUP_MUNICIPALITIES)
        val rejectionReasonCounts = linkedMapOf<String, Int>()
        filtered.filter { it.decisionType == DecisionType.REJEITAR }.forEach { entry ->
            if (entry.isStopRejection) rejectionReasonCounts.increment("Paradas")
            val rejectedCriteria = entry.criterionDecisions.filterValues { it == DecisionType.REJEITAR }.keys
                .ifEmpty { entry.activeCriteria }
            rejectedCriteria.forEach { rejectionReasonCounts.increment(it.statisticsLabel()) }
        }
        val rejectionReasons = rejectionReasonCounts.map { RejectionReasonStatistic(it.key, it.value) }
            .sortedByDescending(RejectionReasonStatistic::count)
        val recordedDates = history.map { entry ->
            Instant.ofEpochMilli(entry.recordedAtMillis).atZone(zone).toLocalDate()
        }.toSet()
        val availableCategories = history.asSequence()
            .filter { it.platform in wantedPlatforms }
            .mapNotNull { entry ->
                entry.category?.trim()?.takeIf(String::isNotEmpty)?.let {
                    StatisticsCategoryOption(entry.platform, it)
                }
            }
            .distinctBy { it.platform to it.name.lowercase() }
            .sortedWith(compareBy(StatisticsCategoryOption::platform, StatisticsCategoryOption::name))
            .toList()
        return StatisticsUiState(
            filters = filters,
            results = results,
            summary = summary,
            decisionDistribution = decisionDistribution,
            rejectionReasons = rejectionReasons,
            trend = trend,
            heatmap = heatmap,
            pickupMunicipalities = pickupMunicipalities,
            dailyCalendar = dailyCalendar,
            matchingTripCount = filtered.size,
            matchingEntries = filtered,
            periodDescription = dateDescription(nowMillis, filters.startDateMillis, filters.endDateMillis),
            availableCategories = availableCategories,
            recordedDates = recordedDates
        )
    }

    private fun OfferHistoryEntry.metricValue(filters: StatisticsFilters): Double? = when (filters.metric) {
        StatisticsMetric.VALUE_PER_KM -> perKm(filters.valueMode)
        StatisticsMetric.VALUE_PER_HOUR -> valorPorHora
        StatisticsMetric.TRIP_VALUE -> tripValue
        StatisticsMetric.NET_TRIP_VALUE -> netTripValue
    }

    private fun OfferHistoryEntry.perKm(mode: StatisticsValueMode): Double = when (mode) {
        StatisticsValueMode.FREE -> valorPorKm
        StatisticsValueMode.GROSS -> valorPorKmBruto
    }

    private fun OfferHistoryEntry.matchesShift(shift: StatisticsShift, zone: ZoneId): Boolean {
        if (shift == StatisticsShift.ALL) return true
        val hour = Instant.ofEpochMilli(recordedAtMillis).atZone(zone).hour
        return when (shift) {
            StatisticsShift.ALL -> true
            StatisticsShift.MORNING -> hour in 6..11
            StatisticsShift.AFTERNOON -> hour in 12..17
            StatisticsShift.NIGHT -> hour in 18..23
            StatisticsShift.DAWN -> hour in 0..5
        }
    }

    private fun OfferHistoryEntry.matchesCardColor(color: StatisticsCardColor): Boolean =
        color.decisionType == null || decisionType == color.decisionType

    private fun OfferHistoryEntry.matchesCategory(category: StatisticsCategoryOption?): Boolean =
        category == null || (
            platform == category.platform && this.category?.trim()?.equals(category.name, ignoreCase = true) == true
        )

    private fun dateDescription(nowMillis: Long, startDateMillis: Long?, endDateMillis: Long?): String {
        val formatter = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, java.util.Locale("pt", "PT"))
        if (startDateMillis == null) return formatter.format(java.util.Date(nowMillis))
        val start = formatter.format(java.util.Date(startDateMillis))
        val end = formatter.format(java.util.Date(endDateMillis ?: startDateMillis))
        return if (start == end) start else "$start – $end"
    }

    private fun Long.asLocalDate(): LocalDate =
        Instant.ofEpochMilli(this).atZone(java.time.ZoneOffset.UTC).toLocalDate()

    private fun List<Double>.median(): Double {
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun List<Double>.medianOrZero(): Double = if (isEmpty()) 0.0 else median()
    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
    private fun <T> List<T>.percentageOf(predicate: (T) -> Boolean): Double =
        if (isEmpty()) 0.0 else count(predicate) * 100.0 / size

    private fun MutableMap<String, Int>.increment(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }

    private const val TOP_PICKUP_MUNICIPALITIES = 5
    private const val CALENDAR_DAYS = 30

    private fun EvaluationCriterion.statisticsLabel(): String = when (this) {
        EvaluationCriterion.RECOLHA -> "Recolha"
        EvaluationCriterion.KM -> "Quilómetros"
        EvaluationCriterion.HORA -> "Hora"
        EvaluationCriterion.VIAGEM_LONGA -> "Viagem longa"
        EvaluationCriterion.VALOR_MINIMO -> "Valor mínimo"
    }
}
