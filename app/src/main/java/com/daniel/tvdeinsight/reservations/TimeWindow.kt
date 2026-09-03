package com.daniel.tvdeinsight.reservations

/**
 * Janela inclusiva de disponibilidade em minutos desde a meia-noite.
 * Se o início for posterior ao fim, a janela atravessa a meia-noite:
 * 18:00-05:00 aceita 18:00..23:59 e 00:00..05:00.
 */
data class TimeWindow(val startMinutes: Int, val endMinutes: Int) {
    fun contains(timeMinutes: Int): Boolean {
        val time = timeMinutes.coerceIn(0, 23 * 60 + 59)
        if (startMinutes == endMinutes) return true
        return if (startMinutes < endMinutes) {
            time in startMinutes..endMinutes
        } else {
            time >= startMinutes || time <= endMinutes
        }
    }
}
