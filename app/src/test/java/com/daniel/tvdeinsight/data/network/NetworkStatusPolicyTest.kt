package com.daniel.tvdeinsight.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStatusPolicyTest {
    @Test fun `internet is available only when network is validated`() {
        assertFalse(NetworkStatusPolicy.isOnline(hasInternetCapability = false, isValidated = false))
        assertFalse(NetworkStatusPolicy.isOnline(hasInternetCapability = true, isValidated = false))
        assertTrue(NetworkStatusPolicy.isOnline(hasInternetCapability = true, isValidated = true))
    }

    @Test fun `pending sync is enqueued only on offline to online transition`() {
        assertTrue(NetworkStatusPolicy.shouldEnqueuePendingSync(wasOnline = false, isOnline = true))
        assertFalse(NetworkStatusPolicy.shouldEnqueuePendingSync(wasOnline = false, isOnline = false))
        assertFalse(NetworkStatusPolicy.shouldEnqueuePendingSync(wasOnline = true, isOnline = true))
        assertFalse(NetworkStatusPolicy.shouldEnqueuePendingSync(wasOnline = true, isOnline = false))
    }
}
