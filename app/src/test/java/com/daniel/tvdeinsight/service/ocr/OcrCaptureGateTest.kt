package com.daniel.tvdeinsight.service.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCaptureGateTest {
    @Test
    fun `only one capture can be active`() {
        val gate = OcrCaptureGate(minimumIntervalMs = 750)

        assertEquals(OcrCaptureGate.Admission.Allowed, gate.tryAcquire(1_000))
        assertEquals(OcrCaptureGate.Admission.Busy, gate.tryAcquire(2_000))
        assertTrue(gate.isActive())
    }

    @Test
    fun `a completed capture still respects Android rate limit`() {
        val gate = OcrCaptureGate(minimumIntervalMs = 750)
        gate.tryAcquire(1_000)
        gate.release()

        assertEquals(OcrCaptureGate.Admission.TooSoon(500), gate.tryAcquire(1_250))
        assertEquals(OcrCaptureGate.Admission.Allowed, gate.tryAcquire(1_750))
    }

    @Test
    fun `release makes gate available without losing last start time`() {
        val gate = OcrCaptureGate(minimumIntervalMs = 750)
        gate.tryAcquire(1_000)
        gate.release()

        assertTrue(!gate.isActive())
        assertEquals(OcrCaptureGate.Admission.TooSoon(1), gate.tryAcquire(1_749))
    }
}
