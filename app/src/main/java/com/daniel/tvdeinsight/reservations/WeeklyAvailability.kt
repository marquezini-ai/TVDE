package com.daniel.tvdeinsight.reservations

import java.time.LocalDate

/** Janela configurada para um dia âncora da semana. */
data class DailyAvailability(
    val startMinutes: Int,
    val endMinutes: Int
)

/**
 * Avalia a disponibilidade semanal sem perder a relação entre dias.
 * DayOfWeek.value segue o padrão Java: segunda=1 ... domingo=7.
 */
object WeeklyAvailability {
    const val MINUTES_PER_DAY = 24 * 60
    const val LAST_SELECTABLE_MINUTE = 23 * 60 + 30

    fun defaultSchedules(startMinutes: Int, endMinutes: Int): Map<Int, DailyAvailability> =
        (1..7).associateWith { DailyAvailability(startMinutes, endMinutes) }

    fun scheduleFor(
        schedules: Map<Int, DailyAvailability>,
        dayOfWeek: Int,
        fallback: DailyAvailability
    ): DailyAvailability = schedules[dayOfWeek] ?: fallback

    /**
     * Uma janela 18:00 -> 04:00 pertence à segunda como âncora, mas também
     * cobre a terça até às 04:00. A própria janela da terça só começa às 18:00.
     */
    fun contains(
        schedules: Map<Int, DailyAvailability>,
        date: LocalDate,
        timeMinutes: Int,
        fallback: DailyAvailability,
        enabledDays: Set<Int> = (1..7).toSet()
    ): Boolean {
        if (date.dayOfWeek.value !in enabledDays) return false
        val normalizedTime = timeMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        val current = scheduleFor(schedules, date.dayOfWeek.value, fallback)
        if (containsOnAnchorDay(current, normalizedTime)) return true

        val previousDay = if (date.dayOfWeek.value == 1) 7 else date.dayOfWeek.value - 1
        val previous = scheduleFor(schedules, previousDay, fallback)
        return previousDay in enabledDays && previous.startMinutes > previous.endMinutes && normalizedTime <= previous.endMinutes
    }

    /** Faixa pertencente ao próprio dia âncora; a parte após meia-noite é excluída. */
    fun containsOnAnchorDay(schedule: DailyAvailability, timeMinutes: Int): Boolean {
        val start = schedule.startMinutes.coerceIn(0, LAST_SELECTABLE_MINUTE)
        val end = schedule.endMinutes.coerceIn(0, LAST_SELECTABLE_MINUTE)
        return when {
            start == end -> true
            start < end -> timeMinutes in start..end
            else -> timeMinutes >= start
        }
    }

    /** Usado pela UI para pintar cada meia hora da disponibilidade efetiva do dia. */
    fun effectiveSlots(
        schedules: Map<Int, DailyAvailability>,
        date: LocalDate,
        fallback: DailyAvailability,
        enabledDays: Set<Int> = (1..7).toSet()
    ): List<Boolean> = (0..47).map { slot ->
        contains(schedules, date, slot * 30, fallback, enabledDays)
    }
}
