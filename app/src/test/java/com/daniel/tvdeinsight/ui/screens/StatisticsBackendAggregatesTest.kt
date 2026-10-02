package com.daniel.tvdeinsight.ui.screens

import com.daniel.tvdeinsight.data.sync.backend.DailyAggregatePayload
import com.daniel.tvdeinsight.data.sync.backend.GlobalAggregatesPayload
import com.daniel.tvdeinsight.data.sync.backend.HeatmapAggregatePayload
import com.daniel.tvdeinsight.data.sync.backend.PickupMunicipalityAggregatePayload
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsBackendAggregatesTest {
    @Test
    fun matchingAggregateResponse_replacesOnlyCollectiveStatistics() {
        val now = LocalDate.of(2027, 1, 30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val filters = StatisticsFilters()
        val query = StatisticsAggregateQueryMapper.from(filters, now)
        val aggregates = GlobalAggregatesPayload(
            query = query,
            pickupMunicipalities = listOf(PickupMunicipalityAggregatePayload("Porto", 123, 4)),
            heatmap = listOf(HeatmapAggregatePayload("MONDAY", "MORNING", 234, 5)),
            dailyCalendar = listOf(DailyAggregatePayload("2027-01-30", 345, 6)),
            recordedDates = listOf("2027-01-30"),
            generatedAt = 1_800_000_000
        )

        val result = StatisticsCalculator.calculate(emptyList(), emptyList(), aggregates, filters, now)

        assertEquals(1.23, result.pickupMunicipalities.single().median, 0.001)
        assertEquals(2.34, result.heatmap.single().value!!, 0.001)
        assertEquals(3.45, result.dailyCalendar.single().average!!, 0.001)
        assertEquals(setOf(LocalDate.of(2027, 1, 30)), result.recordedDates)
        assertEquals(0, result.summary.totalOffers)
    }

    @Test
    fun queryMappingPreservesAllSelectedFilters() {
        val now = LocalDate.of(2027, 1, 30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val query = StatisticsAggregateQueryMapper.from(
            StatisticsFilters(
                platforms = setOf(OfferPlatform.BOLT),
                metric = StatisticsMetric.NET_TRIP_VALUE,
                valueMode = StatisticsValueMode.GROSS,
                shift = StatisticsShift.NIGHT,
                cardColor = StatisticsCardColor.RED,
                category = StatisticsCategoryOption(OfferPlatform.BOLT, "Green")
            ),
            now
        )

        assertEquals(listOf("BOLT"), query.platforms)
        assertEquals("NET_TRIP_VALUE", query.metric)
        assertEquals("GROSS", query.valueMode)
        assertEquals("NIGHT", query.shift)
        assertEquals("RED", query.cardColor)
        assertEquals("Green", query.category?.name)
        assertEquals("2027-01-01", query.startDate)
        assertEquals("2027-01-30", query.endDate)
    }
}
