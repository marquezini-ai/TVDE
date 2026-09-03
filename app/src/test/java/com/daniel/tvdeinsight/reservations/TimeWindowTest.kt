package com.daniel.tvdeinsight.reservations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeWindowTest {
    @Test
    fun `turno noturno inclui noite e madrugada mas exclui manha`() {
        val window = TimeWindow(startMinutes = 18 * 60, endMinutes = 5 * 60)

        assertTrue(window.contains(23 * 60))
        assertTrue(window.contains(2 * 60))
        assertFalse(window.contains(10 * 60))
    }

    @Test
    fun `turno normal inclui apenas intervalo configurado`() {
        val window = TimeWindow(startMinutes = 8 * 60, endMinutes = 18 * 60)

        assertFalse(window.contains(7 * 60 + 59))
        assertTrue(window.contains(12 * 60))
        assertFalse(window.contains(18 * 60 + 1))
    }
}
