package com.santimattius.resilient

import com.santimattius.resilient.circuitbreaker.CircuitBreakerConfig
import com.santimattius.resilient.circuitbreaker.CircuitBreakerOpenException
import com.santimattius.resilient.circuitbreaker.DefaultCircuitBreaker
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds

class CircuitBreakerTest {

    @Test
    fun opensAfterFailureThreshold() = runTest {
        val cfg = CircuitBreakerConfig().apply {
            failureThreshold = 2
            successThreshold = 1
            timeout = 200.milliseconds
            halfOpenMaxCalls = 1
        }
        val cb = DefaultCircuitBreaker(cfg)

        repeat(2) {
            assertFailsWith<Throwable> { cb.execute { error("boom") } }
        }

        assertFailsWith<CircuitBreakerOpenException> {
            cb.execute { "never" }
        }
    }
}
