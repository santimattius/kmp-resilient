package com.santimattius.resilient.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Integration tests demonstrating how to use [FaultInjector] with resilience policies.
 */
class IntegrationTest {

    @Test
    fun `given retry policy with fault injector when intermittent failures then eventually succeeds`() = runTest {
        val scope = TestResilientScope()
        val policy = PolicyBuilders.retryPolicy(
            scope,
            maxAttempts = 5,
            initialDelay = 10.milliseconds
        )

        val injector = FaultInjector.builder()
            .failureRate(0.6) // 60% failure rate
            .build()

        var attempts = 0
        val result = policy.execute {
            attempts++
            injector.execute {
                "success after retries"
            }
        }

        assertEquals("success after retries", result)
        assertTrue(attempts >= 1, "Expected at least 1 attempt")
    }

    @Test
    fun `given circuit breaker with fault injector when high failure rate then opens circuit`() = runTest {
        val scope = TestResilientScope()
        val policy = PolicyBuilders.circuitBreakerPolicy(
            scope,
            failureThreshold = 3,
            timeout = 1000.milliseconds
        )

        val injector = FaultInjector.builder()
            .failureRate(1.0) // Always fails
            .exception { RuntimeException("injected failure") }
            .build()

        // Fail 3 times to open circuit
        var failures = 0
        repeat(3) {
            try {
                policy.execute {
                    injector.execute { "should fail" }
                }
            } catch (e: RuntimeException) {
                failures++
            }
        }

        assertEquals(3, failures, "Expected 3 failures to open circuit")

        // Fourth call should be rejected by open circuit (not by injector)
        val fourthResult = runCatching {
            policy.execute {
                injector.execute { "should be rejected by circuit" }
            }
        }

        // Circuit breaker should reject the call
        assertTrue(fourthResult.isFailure, "Expected circuit breaker to reject the call")
    }

    @Test
    fun `given timeout policy with slow fault injector when delay exceeds timeout then times out`() = runTest {
        val scope = TestResilientScope()
        val policy = PolicyBuilders.timeoutPolicy(scope, timeout = 50.milliseconds)

        val injector = FaultInjector.builder()
            .delay(100.milliseconds)
            .build()

        try {
            policy.execute {
                injector.execute { "should timeout" }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Expected timeout
        }
    }

    @Test
    fun `given bulkhead with fault injector when slow operations then limits concurrency`() = runTest {
        val scope = TestResilientScope()
        val policy = PolicyBuilders.bulkheadPolicy(
            scope,
            maxConcurrentCalls = 2,
            maxWaitingCalls = 1
        )

        val injector = FaultInjector.builder()
            .delay(100.milliseconds)
            .build()

        val gate = CompletableDeferred<Unit>()
        val results = mutableListOf<Result<String>>()

        // Launch 4 concurrent calls
        val jobs = List(4) { index ->
            launch {
                results.add(
                    runCatching {
                        policy.execute {
                            injector.execute {
                                gate.await()
                                "result-$index"
                            }
                        }
                    }
                )
            }
        }

        testScheduler.advanceUntilIdle()

        // Release gate
        gate.complete(Unit)
        jobs.forEach { it.join() }

        // At least one call should have been rejected by bulkhead
        val rejections = results.count { it.isFailure }
        assertTrue(rejections >= 1, "Expected at least 1 rejection, got $rejections")
    }
}
