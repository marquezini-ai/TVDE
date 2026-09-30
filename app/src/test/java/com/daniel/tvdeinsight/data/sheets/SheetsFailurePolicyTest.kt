package com.daniel.tvdeinsight.data.sheets

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetsFailurePolicyTest {
    @Test fun `DNS and timeout failures are retryable`() {
        assertTrue(SheetsFailurePolicy.shouldRetry(UnknownHostException("offline")))
        assertTrue(SheetsFailurePolicy.shouldRetry(SocketTimeoutException("timeout")))
    }

    @Test fun `only temporary HTTP failures are retried`() {
        assertTrue(SheetsFailurePolicy.shouldRetry(SheetsHttpException(408, "timeout")))
        assertTrue(SheetsFailurePolicy.shouldRetry(SheetsHttpException(429, "rate limited")))
        assertTrue(SheetsFailurePolicy.shouldRetry(SheetsHttpException(503, "unavailable")))
        assertFalse(SheetsFailurePolicy.shouldRetry(SheetsHttpException(403, "forbidden")))
        assertFalse(SheetsFailurePolicy.shouldRetry(IllegalStateException("invalid local state")))
    }
}
