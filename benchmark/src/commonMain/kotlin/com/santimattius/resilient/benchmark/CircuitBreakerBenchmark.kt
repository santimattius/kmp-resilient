package com.santimattius.resilient.benchmark

import com.santimattius.resilient.circuitbreaker.CircuitBreaker
import com.santimattius.resilient.circuitbreaker.CircuitBreakerConfig
import com.santimattius.resilient.circuitbreaker.CircuitBreakerOpenException
import com.santimattius.resilient.circuitbreaker.DefaultCircuitBreaker
import kotlin.time.Duration

internal class BreakerFixture {
    private lateinit var closed: CircuitBreaker
    private lateinit var open: CircuitBreaker

    private class Trip : Exception()

    fun setup() {
        closed = DefaultCircuitBreaker(CircuitBreakerConfig().apply { failureThreshold = Int.MAX_VALUE })
        open =
            DefaultCircuitBreaker(
                CircuitBreakerConfig().apply {
                    failureThreshold = 1
                    timeout = Duration.INFINITE
                },
            )
    }

    suspend fun prepareOpen() {
        try {
            open.execute { throw Trip() }
            error("Trip payload unexpectedly succeeded")
        } catch (_: Trip) {
            // Only the intentional failure may be ignored.
        }
    }

    suspend fun closedExecute(): Int = closed.execute { unit() }

    suspend fun openReject(payload: suspend () -> Int = { unit() }) {
        try {
            open.execute { payload() }
            error("Open breaker unexpectedly executed its payload")
        } catch (_: CircuitBreakerOpenException) {
            // Rejection is the measured outcome.
        }
    }
}
