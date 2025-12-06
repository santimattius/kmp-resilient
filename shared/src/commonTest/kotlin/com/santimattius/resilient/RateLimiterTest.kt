package com.santimattius.resilient

import com.santimattius.resilient.ratelimiter.DefaultRateLimiter
import com.santimattius.resilient.ratelimiter.RateLimitExceededException
import com.santimattius.resilient.ratelimiter.RateLimiterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds

class RateLimiterTest {

    @Test
    fun allowsWithinRateAndThenWaitsOrThrows() = runTest {
        val cfg = RateLimiterConfig().apply {
            maxCalls = 1
            period = 100.milliseconds
            timeoutWhenLimited = 10.milliseconds
        }
        val rl = DefaultRateLimiter(cfg)

        val first = rl.execute { "ok1" }
        assertEquals("ok1", first)

        // Second call within same period should exceed timeout and throw
        assertFailsWith<RateLimitExceededException> {
            rl.execute { "ok2" }
        }
    }
}
