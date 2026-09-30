package com.daniel.tvdeinsight.reservations

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyAvailabilityTest {
    private val overnight = mapOf(1 to DailyAvailability(18 * 60, 4 * 60))
    private val fallback = DailyAvailability(18 * 60, 4 * 60)

    @Test
    fun `previous enabled overnight window remains available after midnight`() {
        // Tuesday is disabled; Monday's 18:00–04:00 must still cover 03:30 Tuesday.
        assertTrue(
            WeeklyAvailability.contains(
                schedules = overnight,
                date = LocalDate.of(2026, 9, 8),
                timeMinutes = 3 * 60 + 30,
                fallback = fallback,
                enabledDays = setOf(1)
            )
        )
    }

    @Test
    fun `disabled day does not expose its own availability window`() {
        assertFalse(
            WeeklyAvailability.contains(
                schedules = mapOf(2 to DailyAvailability(8 * 60, 17 * 60)),
                date = LocalDate.of(2026, 9, 8),
                timeMinutes = 10 * 60,
                fallback = fallback,
                enabledDays = setOf(1)
            )
        )
    }
}
