package com.santimattius.resilient

import com.santimattius.resilient.retry.DefaultRetryPolicy
import com.santimattius.resilient.retry.ExponentialBackoff
import com.santimattius.resilient.retry.RetryPolicyConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds

class RetryPolicyTest {

    @Test
    fun retriesUntilSuccess() = runTest {
        val cfg = RetryPolicyConfig().apply {
            maxAttempts = 3
            backoffStrategy = ExponentialBackoff(initialDelay = 10.milliseconds, factor = 1.0, jitter = false)
        }
        val policy = DefaultRetryPolicy(cfg)
        var attempts = 0
        val result = policy.execute {
            attempts++
            if (attempts < 3) error("fail") else "ok"
        }
        assertEquals(3, attempts)
        assertEquals("ok", result)
    }

    @Test
    fun stopsWhenMaxAttemptsReached() = runTest {
        val cfg = RetryPolicyConfig().apply {
            maxAttempts = 2
            backoffStrategy = ExponentialBackoff(initialDelay = 1.milliseconds, factor = 1.0, jitter = false)
        }
        val policy = DefaultRetryPolicy(cfg)
        var attempts = 0
        assertFailsWith<Throwable> {
            policy.execute {
                attempts++
                error("always")
            }
        }
        assertEquals(2, attempts)
    }
}
