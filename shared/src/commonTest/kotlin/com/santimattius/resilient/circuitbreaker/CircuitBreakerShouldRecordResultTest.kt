package com.santimattius.resilient.circuitbreaker

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [CircuitBreakerConfig.shouldRecordResult]: a predicate that allows
 * result-based failure recording without throwing an exception.
 *
 * Spec scenarios:
 * - S1: matching result triggers failure recording → circuit eventually opens
 * - S2: non-matching result counts as success / resets consecutive counter
 * - S3: null predicate → no additional failure recorded, result returned normally
 * - S4: exception path still governed by shouldRecordFailure, not shouldRecordResult
 */
class CircuitBreakerShouldRecordResultTest {

    // ---------------------------------------------------------------------------
    // Scenario 1 — S1: matching result triggers failure recording; circuit opens
    // ---------------------------------------------------------------------------

    @Test
    fun `S1 - given shouldRecordResult matching all results and failureThreshold 3 when 3 calls return matching value then circuit opens`() =
        runTest {
            val cfg = CircuitBreakerConfig().apply {
                failureThreshold = 3
                successThreshold = 2
                timeout = 60.seconds
                halfOpenMaxCalls = 1
                shouldRecordResult = { it is String && it == "FAIL" }
            }
            val cb = DefaultCircuitBreaker(cfg)

            // All three calls return a matching value — each should record a failure
            val r1 = cb.execute { "FAIL" }
            assertEquals("FAIL", r1, "Result must be returned even when counted as failure")
            assertEquals(CircuitState.CLOSED, cb.state.value)

            val r2 = cb.execute { "FAIL" }
            assertEquals("FAIL", r2)
            assertEquals(CircuitState.CLOSED, cb.state.value)

            val r3 = cb.execute { "FAIL" }
            assertEquals("FAIL", r3)

            // After 3 recorded failures the circuit must be OPEN
            assertEquals(CircuitState.OPEN, cb.state.value)
        }

    @Test
    fun `S1 - given shouldRecordResult when circuit opens then subsequent calls are rejected`() =
        runTest {
            val cfg = CircuitBreakerConfig().apply {
                failureThreshold = 2
                shouldRecordResult = { true }
            }
            val cb = DefaultCircuitBreaker(cfg)

            cb.execute { "any" }
            cb.execute { "any" } // second call opens the circuit

            assertEquals(CircuitState.OPEN, cb.state.value)

            assertFailsWith<CircuitBreakerOpenException> {
                cb.execute { "any" }
            }
        }

    // ---------------------------------------------------------------------------
    // Scenario 2 — S2: non-matching result counts as success / resets counter
    // ---------------------------------------------------------------------------

    @Test
    fun `S2 - given shouldRecordResult when non-matching result returned then failure counter resets and circuit stays CLOSED`() =
        runTest {
            val cfg = CircuitBreakerConfig().apply {
                failureThreshold = 3
                shouldRecordResult = { it is String && it == "503" }
            }
            val cb = DefaultCircuitBreaker(cfg)

            // Build up two failures
            cb.execute { "503" }
            cb.execute { "503" }
            assertEquals(2, cb.snapshot().failureCount)

            // Non-matching result → should be treated as success → counter resets
            cb.execute { "200" }
            assertEquals(0, cb.snapshot().failureCount)
            assertEquals(CircuitState.CLOSED, cb.state.value)
        }

    @Test
    fun `S2 - given non-matching result when failure threshold not reached then circuit remains CLOSED`() =
        runTest {
            val cfg = CircuitBreakerConfig().apply {
                failureThreshold = 3
                shouldRecordResult = { it is Int && it >= 500 }
            }
            val cb = DefaultCircuitBreaker(cfg)

            repeat(10) {
                val result = cb.execute { 200 }
                assertEquals(200, result)
            }

            assertEquals(CircuitState.CLOSED, cb.state.value)
            assertEquals(0, cb.snapshot().failureCount)
        }

    // ---------------------------------------------------------------------------
    // Scenario 3 — S3: null predicate → no failure recorded, result returned
    // ---------------------------------------------------------------------------

    @Test
    fun `S3 - given null shouldRecordResult when call returns any value then no failure is recorded`() =
        runTest {
            val cfg = CircuitBreakerConfig().apply {
                failureThreshold = 2
                shouldRecordResult = null // explicit null — default
            }
            val cb = DefaultCircuitBreaker(cfg)

            repeat(5) {
                val result = cb.execute { "ok" }
                assertEquals("ok", result)
            }

            assertEquals(CircuitState.CLOSED, cb.state.value)
            assertEquals(0, cb.snapshot().failureCount)
        }

    @Test
    fun `S3 - given default config no shouldRecordResult set when calls succeed then circuit stays CLOSED`() =
        runTest {
            // Default config has shouldRecordResult = null
            val cfg = CircuitBreakerConfig().apply {
                failureThreshold = 3
            }
            val cb = DefaultCircuitBreaker(cfg)

            repeat(10) { cb.execute { 42 } }

            assertEquals(CircuitState.CLOSED, cb.state.value)
        }

    // ---------------------------------------------------------------------------
    // Scenario 4 — S4: exception path governed by shouldRecordFailure, not shouldRecordResult
    // ---------------------------------------------------------------------------

    @Test
    fun `S4 - given shouldRecordResult true and shouldRecordFailure rejecting exception when call throws then failure is NOT recorded`() =
        runTest {
            val cfg = CircuitBreakerConfig().apply {
                failureThreshold = 2
                shouldRecordResult = { true }
                // Only IOExceptions should be recorded — IllegalStateException should NOT
                shouldRecordFailure = { it is RuntimeException && it.message?.startsWith("io:") == true }
            }
            val cb = DefaultCircuitBreaker(cfg)

            // Throw IllegalStateException — shouldRecordFailure returns false → not recorded
            repeat(5) {
                assertFailsWith<IllegalStateException> {
                    cb.execute { throw IllegalStateException("not an IO error") }
                }
            }

            // Circuit must stay CLOSED because shouldRecordFailure filtered out the exception
            assertEquals(CircuitState.CLOSED, cb.state.value)
            assertEquals(0, cb.snapshot().failureCount)
        }

    @Test
    fun `S4 - given shouldRecordFailure allowing IOException when IO exception thrown then failure IS recorded`() =
        runTest {
            val cfg = CircuitBreakerConfig().apply {
                failureThreshold = 2
                shouldRecordResult = { false } // result predicate does not interfere
                shouldRecordFailure = { it is RuntimeException && it.message?.startsWith("io:") == true }
            }
            val cb = DefaultCircuitBreaker(cfg)

            assertFailsWith<RuntimeException> {
                cb.execute { throw RuntimeException("io: network error") }
            }
            assertFailsWith<RuntimeException> {
                cb.execute { throw RuntimeException("io: network error") }
            }

            // shouldRecordFailure allowed both → circuit opens
            assertEquals(CircuitState.OPEN, cb.state.value)
        }
}
